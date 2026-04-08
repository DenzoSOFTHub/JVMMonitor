/*
 * JVMMonitor - CPU Sampler Implementation
 * Uses AsyncGetCallTrace for safepoint-bias-free CPU profiling.
 * On Linux: SIGPROF timer. On Windows: periodic GetThreadContext.
 */
#include "jvmmon/cpu_sampler.h"
#include "jvmmon/protocol.h"
#include <string.h>

#ifndef _WIN32
#include <sys/time.h>
#include <signal.h>
#include <ucontext.h>
#endif

/* ── SIGPROF handler (Linux) ────────────────────────── */

#ifndef _WIN32

/* Spinlock to prevent SPSC ring buffer corruption when SIGPROF
 * interrupts a thread that is already inside ring_buffer_push. */
static volatile int32_t sampler_lock = 0;

static void sigprof_handler(int sig, siginfo_t *info, void *ucontext) {
    jvmmon_agent_t *agent = agent_get();
    (void)sig;
    (void)info;

    if (agent->asgct == NULL) return;
    if (!jvmmon_atomic_load(&agent->running)) return;

    /* Acquire spinlock — if re-entrant (interrupted during push), skip sample */
    if (jvmmon_atomic_cas(&sampler_lock, 0, 1) != 0) return;

    JNIEnv *env;
    if ((*agent->jvm)->GetEnv(agent->jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        jvmmon_atomic_store(&sampler_lock, 0);
        return;
    }

    ASGCT_CallFrame frames[CPU_SAMPLER_MAX_FRAMES];
    ASGCT_CallTrace trace;
    trace.env_id = env;
    trace.num_frames = CPU_SAMPLER_MAX_FRAMES;
    trace.frames = frames;

    agent->asgct(&trace, CPU_SAMPLER_MAX_FRAMES, ucontext);

    if (trace.num_frames <= 0) {
        jvmmon_atomic_store(&sampler_lock, 0);
        return;
    }

    /* Encode CPU sample message */
    uint8_t payload[JVMMON_MAX_PAYLOAD];
    int off = 0;
    int i;

    off += protocol_encode_u64(payload + off, jvmmon_time_millis());
    off += protocol_encode_u64(payload + off, jvmmon_thread_id());
    off += protocol_encode_u16(payload + off, (uint16_t)trace.num_frames);

    for (i = 0; i < trace.num_frames && off < (int)(JVMMON_MAX_PAYLOAD - 12); i++) {
        off += protocol_encode_u64(payload + off, (uint64_t)(uintptr_t)frames[i].method_id);
        off += protocol_encode_i32(payload + off, frames[i].lineno);
    }

    agent_send_message(JVMMON_MSG_CPU_SAMPLE, payload, (uint32_t)off);
    jvmmon_atomic_store(&sampler_lock, 0);
}

#endif /* !_WIN32 */

/* ── Timer thread ───────────────────────────────────── */

static void *sampler_thread_fn(void *arg) {
    cpu_sampler_t *cs = (cpu_sampler_t *)arg;
    jvmmon_agent_t *agent = cs->agent;
    JNIEnv *env;

    /* Attach this native thread to the JVM (required for JVMTI calls) */
    if ((*agent->jvm)->AttachCurrentThread(agent->jvm, (void **)&env, NULL) != JNI_OK) {
        LOG_ERROR("CPU sampler: failed to attach to JVM");
        return NULL;
    }

#ifndef _WIN32
    /* Linux: actual sampling happens in SIGPROF handler (async, no safepoint).
     * This thread only needs to stay alive to keep the JVM attachment valid.
     * No need for GetAllThreads/GetThreadInfo — that was wasted work. */
    while (jvmmon_atomic_load(&cs->running)) {
        if (!jvmmon_atomic_load(&agent->running)) break;
        jvmmon_sleep_ms(cs->interval_ms);
    }
#else
    /* Windows: use JVMTI GetStackTrace as fallback (safepoint-biased but functional) */
    while (jvmmon_atomic_load(&cs->running)) {
        if (!jvmmon_atomic_load(&agent->running)) break;

        jthread *threads = NULL;
        jint thread_count = 0;
        jvmtiError err;

        err = (*agent->jvmti)->GetAllThreads(agent->jvmti, &thread_count, &threads);
        if (err == JVMTI_ERROR_NONE && threads != NULL) {
            int i;
            for (i = 0; i < thread_count; i++) {
                jvmtiFrameInfo frame_buf[CPU_SAMPLER_MAX_FRAMES];
                jint frame_count = 0;

                err = (*agent->jvmti)->GetStackTrace(agent->jvmti, threads[i],
                          0, CPU_SAMPLER_MAX_FRAMES, frame_buf, &frame_count);
                if (err == JVMTI_ERROR_NONE && frame_count > 0) {
                    uint8_t payload[JVMMON_MAX_PAYLOAD];
                    int off = 0;
                    int j;

                    off += protocol_encode_u64(payload + off, jvmmon_time_millis());
                    off += protocol_encode_u64(payload + off, (uint64_t)i);
                    off += protocol_encode_u16(payload + off, (uint16_t)frame_count);

                    for (j = 0; j < frame_count && off < (int)(JVMMON_MAX_PAYLOAD - 12); j++) {
                        off += protocol_encode_u64(payload + off,
                                   (uint64_t)(uintptr_t)frame_buf[j].method);
                        off += protocol_encode_i32(payload + off,
                                   (int32_t)frame_buf[j].location);
                    }

                    agent_send_message(JVMMON_MSG_CPU_SAMPLE, payload, (uint32_t)off);
                }
            }
            (*agent->jvmti)->Deallocate(agent->jvmti, (unsigned char *)threads);
        }

        jvmmon_sleep_ms(cs->interval_ms);
    }
#endif

    (*agent->jvm)->DetachCurrentThread(agent->jvm);
    return NULL;
}

/* ── Public API ─────────────────────────────────────── */

cpu_sampler_t *cpu_sampler_create(jvmmon_agent_t *agent, int interval_ms) {
    cpu_sampler_t *cs = (cpu_sampler_t *)jvmmon_calloc(1, sizeof(cpu_sampler_t));
    if (cs == NULL) return NULL;
    cs->agent = agent;
    cs->interval_ms = interval_ms > 0 ? interval_ms : 10;
    cs->running = 0;
    return cs;
}

int cpu_sampler_start(cpu_sampler_t *cs) {
    jvmmon_atomic_store(&cs->running, 1);

#ifndef _WIN32
    /* Install SIGPROF handler */
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = sigprof_handler;
    sa.sa_flags = SA_RESTART | SA_SIGINFO;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGPROF, &sa, NULL);

    /* Set up interval timer for SIGPROF */
    struct itimerval timer;
    timer.it_interval.tv_sec = 0;
    timer.it_interval.tv_usec = cs->interval_ms * 1000;
    timer.it_value = timer.it_interval;
    setitimer(ITIMER_PROF, &timer, NULL);
#endif

    return jvmmon_thread_create(&cs->timer_thread, sampler_thread_fn, cs);
}

void cpu_sampler_stop(cpu_sampler_t *cs) {
    jvmmon_atomic_store(&cs->running, 0);

#ifndef _WIN32
    /* Stop SIGPROF timer */
    struct itimerval timer;
    memset(&timer, 0, sizeof(timer));
    setitimer(ITIMER_PROF, &timer, NULL);

    /* Restore default SIGPROF handler */
    signal(SIGPROF, SIG_DFL);
#endif

    jvmmon_thread_join(&cs->timer_thread);
}

void cpu_sampler_destroy(cpu_sampler_t *cs) {
    if (cs != NULL) {
        jvmmon_free(cs);
    }
}
