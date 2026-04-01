/*
 * JVMMonitor - Thread CPU Time Implementation
 * Reads per-thread CPU time via JVMTI, computes deltas, sends top consumers.
 */
#include "jvmmon/thread_cpu.h"
#include "jvmmon/log.h"
#include "jvmmon/protocol.h"
#include <string.h>

#define MAX_TRACKED_THREADS 256

typedef struct {
    jlong   thread_id;
    int64_t last_cpu_ns;
    char    name[128];
} thread_cpu_entry_t;

static thread_cpu_entry_t prev_entries[MAX_TRACKED_THREADS];
static int prev_count = 0;

static int64_t find_prev_cpu(jlong tid) {
    int i;
    for (i = 0; i < prev_count; i++) {
        if (prev_entries[i].thread_id == tid) return prev_entries[i].last_cpu_ns;
    }
    return 0;
}

static void collect_thread_cpu(thread_cpu_t *tc) {
    jvmtiEnv *jvmti = tc->agent->jvmti;
    jthread *threads = NULL;
    jint count = 0;
    jvmtiError err;

    err = (*jvmti)->GetAllThreads(jvmti, &count, &threads);
    if (err != JVMTI_ERROR_NONE || threads == NULL) return;

    uint8_t payload[JVMMON_MAX_PAYLOAD];
    int off = 0;
    off += protocol_encode_u64(payload + off, jvmmon_time_millis());

    int send_count = count < MAX_TRACKED_THREADS ? count : MAX_TRACKED_THREADS;
    off += protocol_encode_u16(payload + off, (uint16_t)send_count);

    thread_cpu_entry_t new_entries[MAX_TRACKED_THREADS];
    int new_count = 0;

    jint i;
    for (i = 0; i < send_count && off < (int)(JVMMON_MAX_PAYLOAD - 150); i++) {
        jlong cpu_ns = 0;
        jvmtiThreadInfo tinfo;
        memset(&tinfo, 0, sizeof(tinfo));

        (*jvmti)->GetThreadCpuTime(jvmti, threads[i], &cpu_ns);
        (*jvmti)->GetThreadInfo(jvmti, threads[i], &tinfo);

        jlong tid = (jlong)(intptr_t)threads[i];
        int64_t prev_cpu = find_prev_cpu(tid);
        int64_t delta_ns = cpu_ns - prev_cpu;
        if (delta_ns < 0) delta_ns = 0;

        const char *name = tinfo.name ? tinfo.name : "?";
        uint16_t nlen = (uint16_t)strlen(name);
        if (nlen > 120) nlen = 120;

        off += protocol_encode_u64(payload + off, (uint64_t)tid);
        off += protocol_encode_string(payload + off, name, nlen);
        off += protocol_encode_i64(payload + off, (int64_t)cpu_ns);
        off += protocol_encode_i64(payload + off, delta_ns);

        /* Save for next delta */
        if (new_count < MAX_TRACKED_THREADS) {
            new_entries[new_count].thread_id = tid;
            new_entries[new_count].last_cpu_ns = cpu_ns;
            strncpy(new_entries[new_count].name, name, 127);
            new_count++;
        }

        if (tinfo.name) (*jvmti)->Deallocate(jvmti, (unsigned char *)tinfo.name);
    }

    memcpy(prev_entries, new_entries, new_count * sizeof(thread_cpu_entry_t));
    prev_count = new_count;

    (*jvmti)->Deallocate(jvmti, (unsigned char *)threads);
    agent_send_message(JVMMON_MSG_THREAD_CPU, payload, (uint32_t)off);
}

static void *poll_fn(void *arg) {
    thread_cpu_t *tc = (thread_cpu_t *)arg;
    while (jvmmon_atomic_load(&tc->running)) {
        collect_thread_cpu(tc);
        jvmmon_sleep_ms(tc->interval_ms);
    }
    return NULL;
}

thread_cpu_t *thread_cpu_create(jvmmon_agent_t *agent, int interval_ms) {
    thread_cpu_t *tc = (thread_cpu_t *)jvmmon_calloc(1, sizeof(thread_cpu_t));
    if (!tc) return NULL;
    tc->agent = agent; tc->interval_ms = interval_ms > 0 ? interval_ms : 5000;
    return tc;
}
void thread_cpu_destroy(thread_cpu_t *tc) {
    if (tc) { if (jvmmon_atomic_load(&tc->running)) thread_cpu_deactivate(0, tc); jvmmon_free(tc); }
}
int thread_cpu_activate(int level, const char *target, void *ctx) {
    thread_cpu_t *tc = (thread_cpu_t *)ctx; (void)level; (void)target;
    jvmmon_atomic_store(&tc->running, 1);
    jvmmon_thread_create(&tc->poll_thread, poll_fn, tc);
    LOG_INFO("Thread CPU tracker activated"); return 0;
}
int thread_cpu_deactivate(int level, void *ctx) {
    thread_cpu_t *tc = (thread_cpu_t *)ctx; (void)level;
    jvmmon_atomic_store(&tc->running, 0);
    jvmmon_thread_join(&tc->poll_thread);
    LOG_INFO("Thread CPU tracker deactivated"); return 0;
}
