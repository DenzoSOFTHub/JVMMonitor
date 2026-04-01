/*
 * JVMMonitor - Thread Monitor Implementation
 */
#include "jvmmon/thread_monitor.h"
#include "jvmmon/alarm_engine.h"
#include "jvmmon/protocol.h"
#include <string.h>

static void send_thread_event(thread_monitor_t *tm, int event_type,
                               jlong thread_id, const char *name, int state, int daemon) {
    uint8_t payload[512];
    int off = 0;
    uint16_t name_len = (name != NULL) ? (uint16_t)strlen(name) : 0;
    if (name_len > 255) name_len = 255;

    off += protocol_encode_u64(payload + off, jvmmon_time_millis());
    off += protocol_encode_u8(payload + off, (uint8_t)event_type);
    off += protocol_encode_u64(payload + off, (uint64_t)thread_id);
    off += protocol_encode_string(payload + off, name, name_len);
    off += protocol_encode_i32(payload + off, state);
    off += protocol_encode_u8(payload + off, (uint8_t)(daemon ? 1 : 0));

    uint8_t msg_type = (event_type == THREAD_EVENT_SNAPSHOT)
        ? JVMMON_MSG_THREAD_SNAPSHOT : JVMMON_MSG_THREAD_EVENT;

    agent_send_message(msg_type, payload, (uint32_t)off);
}

static void collect_thread_snapshot(thread_monitor_t *tm) {
    jvmmon_agent_t *agent = tm->agent;
    jvmtiEnv *jvmti = agent->jvmti;
    jthread *threads = NULL;
    jint count = 0;
    jvmtiError err;
    int blocked_count = 0;
    int i;

    err = (*jvmti)->GetAllThreads(jvmti, &count, &threads);
    if (err != JVMTI_ERROR_NONE || threads == NULL) return;

    for (i = 0; i < count; i++) {
        jvmtiThreadInfo tinfo;
        jint state = 0;

        memset(&tinfo, 0, sizeof(tinfo));
        err = (*jvmti)->GetThreadInfo(jvmti, threads[i], &tinfo);
        if (err != JVMTI_ERROR_NONE) continue;

        (*jvmti)->GetThreadState(jvmti, threads[i], &state);

        /* Map JVMTI state to simplified state */
        int simple_state = 0;
        if (state & JVMTI_THREAD_STATE_TERMINATED) simple_state = 5;
        else if (state & JVMTI_THREAD_STATE_BLOCKED_ON_MONITOR_ENTER) { simple_state = 2; blocked_count++; }
        else if (state & JVMTI_THREAD_STATE_WAITING) {
            if (state & JVMTI_THREAD_STATE_WAITING_WITH_TIMEOUT) simple_state = 4;
            else simple_state = 3;
        }
        else if (state & JVMTI_THREAD_STATE_RUNNABLE) simple_state = 1;

        send_thread_event(tm, THREAD_EVENT_SNAPSHOT, (jlong)(intptr_t)threads[i],
                          tinfo.name, simple_state, tinfo.is_daemon);

        if (tinfo.name != NULL) {
            (*jvmti)->Deallocate(jvmti, (unsigned char *)tinfo.name);
        }
    }

    (*jvmti)->Deallocate(jvmti, (unsigned char *)threads);

    /* Feed alarm engine with blocked thread count */
    if (agent->alarm_engine && count > 0) {
        double blocked_pct = (double)blocked_count / (double)count * 100.0;
        alarm_engine_update(agent->alarm_engine, ALARM_TYPE_THREAD_BLOCKED, blocked_pct);
    }
}

static void *poll_thread_fn(void *arg) {
    thread_monitor_t *tm = (thread_monitor_t *)arg;

    while (jvmmon_atomic_load(&tm->running)) {
        if (!jvmmon_atomic_load(&tm->agent->running)) break;
        collect_thread_snapshot(tm);
        jvmmon_sleep_ms(tm->interval_ms);
    }

    return NULL;
}

thread_monitor_t *thread_monitor_create(jvmmon_agent_t *agent, int interval_ms) {
    thread_monitor_t *tm = (thread_monitor_t *)jvmmon_calloc(1, sizeof(thread_monitor_t));
    if (tm == NULL) return NULL;
    tm->agent = agent;
    tm->interval_ms = interval_ms > 0 ? interval_ms : 1000;
    tm->running = 0;
    return tm;
}

int thread_monitor_start(thread_monitor_t *tm) {
    jvmmon_atomic_store(&tm->running, 1);
    return jvmmon_thread_create(&tm->poll_thread, poll_thread_fn, tm);
}

void thread_monitor_stop(thread_monitor_t *tm) {
    jvmmon_atomic_store(&tm->running, 0);
    jvmmon_thread_join(&tm->poll_thread);
}

void thread_monitor_destroy(thread_monitor_t *tm) {
    if (tm != NULL) jvmmon_free(tm);
}

void thread_monitor_on_thread_start(thread_monitor_t *tm, JNIEnv *jni, jthread thread) {
    jvmtiThreadInfo tinfo;
    (void)jni;
    memset(&tinfo, 0, sizeof(tinfo));
    (*tm->agent->jvmti)->GetThreadInfo(tm->agent->jvmti, thread, &tinfo);
    send_thread_event(tm, THREAD_EVENT_START, (jlong)(intptr_t)thread,
                      tinfo.name, 0, tinfo.is_daemon);
    if (tinfo.name) {
        (*tm->agent->jvmti)->Deallocate(tm->agent->jvmti, (unsigned char *)tinfo.name);
    }
}

void thread_monitor_on_thread_end(thread_monitor_t *tm, JNIEnv *jni, jthread thread) {
    jvmtiThreadInfo tinfo;
    (void)jni;
    memset(&tinfo, 0, sizeof(tinfo));
    (*tm->agent->jvmti)->GetThreadInfo(tm->agent->jvmti, thread, &tinfo);
    send_thread_event(tm, THREAD_EVENT_END, (jlong)(intptr_t)thread,
                      tinfo.name, 5, tinfo.is_daemon);
    if (tinfo.name) {
        (*tm->agent->jvmti)->Deallocate(tm->agent->jvmti, (unsigned char *)tinfo.name);
    }
}
