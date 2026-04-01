/*
 * JVMMonitor - Lock Monitor Implementation
 * Uses JVMTI MonitorContendedEnter/Exit events to track lock contention.
 * Sends per-event details: which thread waited, on which lock (class),
 * who owned it, and how long the wait was.
 */
#include "jvmmon/lock_monitor.h"
#include "jvmmon/log.h"
#include "jvmmon/protocol.h"
#include <string.h>

lock_monitor_t *lock_monitor_create(jvmmon_agent_t *agent) {
    lock_monitor_t *lm = (lock_monitor_t *)jvmmon_calloc(1, sizeof(lock_monitor_t));
    if (lm == NULL) return NULL;
    lm->agent = agent;
    return lm;
}

void lock_monitor_destroy(lock_monitor_t *lm) {
    if (lm != NULL) jvmmon_free(lm);
}

int lock_monitor_activate(int level, const char *target, void *ctx) {
    lock_monitor_t *lm = (lock_monitor_t *)ctx;
    jvmtiEnv *jvmti = lm->agent->jvmti;
    (void)level; (void)target;

    lm->contention_count = 0;
    lm->deadlock_count = 0;

    (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE,
            JVMTI_EVENT_MONITOR_CONTENDED_ENTER, NULL);
    (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE,
            JVMTI_EVENT_MONITOR_CONTENDED_ENTERED, NULL);

    jvmmon_atomic_store(&lm->active, 1);
    LOG_INFO("Lock monitor activated");
    return 0;
}

int lock_monitor_deactivate(int level, void *ctx) {
    lock_monitor_t *lm = (lock_monitor_t *)ctx;
    jvmtiEnv *jvmti = lm->agent->jvmti;
    (void)level;

    jvmmon_atomic_store(&lm->active, 0);

    (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_DISABLE,
            JVMTI_EVENT_MONITOR_CONTENDED_ENTER, NULL);
    (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_DISABLE,
            JVMTI_EVENT_MONITOR_CONTENDED_ENTERED, NULL);

    LOG_INFO("Lock monitor deactivated (contentions=%d)",
             jvmmon_atomic_load(&lm->contention_count));
    return 0;
}

static void send_lock_event(lock_monitor_t *lm, JNIEnv *jni, jvmtiEnv *jvmti,
                             jthread thread, jobject object, uint8_t event_type) {
    uint8_t payload[JVMMON_MAX_PAYLOAD];
    int off = 0;

    off += protocol_encode_u64(payload + off, jvmmon_time_millis());
    off += protocol_encode_u8(payload + off, event_type);

    /* Thread name */
    jvmtiThreadInfo tinfo;
    memset(&tinfo, 0, sizeof(tinfo));
    (*jvmti)->GetThreadInfo(jvmti, thread, &tinfo);
    uint16_t tnlen = tinfo.name ? (uint16_t)strlen(tinfo.name) : 0;
    if (tnlen > 200) tnlen = 200;
    off += protocol_encode_string(payload + off, tinfo.name ? tinfo.name : "?", tnlen);

    /* Lock object class name */
    jclass lockClass = (*jni)->GetObjectClass(jni, object);
    char *class_sig = NULL;
    (*jvmti)->GetClassSignature(jvmti, lockClass, &class_sig, NULL);
    uint16_t cslen = class_sig ? (uint16_t)strlen(class_sig) : 0;
    if (cslen > 200) cslen = 200;
    off += protocol_encode_string(payload + off, class_sig ? class_sig : "?", cslen);

    /* Lock identity hash (to distinguish lock instances) */
    jint hash = 0;
    (*jvmti)->GetObjectHashCode(jvmti, object, &hash);
    off += protocol_encode_i32(payload + off, hash);

    /* Contention count */
    off += protocol_encode_i32(payload + off, jvmmon_atomic_load(&lm->contention_count));

    /* Owner thread (for contended enter) */
    jvmtiMonitorUsage usage;
    memset(&usage, 0, sizeof(usage));
    jvmtiError err = (*jvmti)->GetObjectMonitorUsage(jvmti, object, &usage);
    if (err == JVMTI_ERROR_NONE && usage.owner != NULL) {
        jvmtiThreadInfo owner_info;
        memset(&owner_info, 0, sizeof(owner_info));
        (*jvmti)->GetThreadInfo(jvmti, usage.owner, &owner_info);
        uint16_t onlen = owner_info.name ? (uint16_t)strlen(owner_info.name) : 0;
        if (onlen > 200) onlen = 200;
        off += protocol_encode_string(payload + off, owner_info.name ? owner_info.name : "?", onlen);
        off += protocol_encode_i32(payload + off, usage.entry_count);
        off += protocol_encode_i32(payload + off, usage.waiter_count);

        if (owner_info.name) (*jvmti)->Deallocate(jvmti, (unsigned char *)owner_info.name);
    } else {
        off += protocol_encode_string(payload + off, "(none)", 6);
        off += protocol_encode_i32(payload + off, 0);
        off += protocol_encode_i32(payload + off, 0);
    }

    /* Stack trace of the waiting thread (top 8 frames) */
    jvmtiFrameInfo frames[8];
    jint frame_count = 0;
    (*jvmti)->GetStackTrace(jvmti, thread, 0, 8, frames, &frame_count);
    off += protocol_encode_u16(payload + off, (uint16_t)frame_count);
    int i;
    for (i = 0; i < frame_count && off < (int)(JVMMON_MAX_PAYLOAD - 20); i++) {
        char cname[256] = {0}, mname[256] = {0};
        agent_resolve_method(frames[i].method, cname, 256, mname, 256, NULL, 0);
        off += protocol_encode_string(payload + off, cname, (uint16_t)strlen(cname));
        off += protocol_encode_string(payload + off, mname, (uint16_t)strlen(mname));
    }

    agent_send_message(JVMMON_MSG_LOCK_EVENT, payload, (uint32_t)off);

    if (tinfo.name) (*jvmti)->Deallocate(jvmti, (unsigned char *)tinfo.name);
    if (class_sig) (*jvmti)->Deallocate(jvmti, (unsigned char *)class_sig);
    (*jni)->DeleteLocalRef(jni, lockClass);
}

void lock_monitor_on_contended_enter(lock_monitor_t *lm, JNIEnv *jni,
        jthread thread, jobject object) {
    if (!jvmmon_atomic_load(&lm->active)) return;
    jvmmon_atomic_add(&lm->contention_count, 1);
    send_lock_event(lm, jni, lm->agent->jvmti, thread, object, LOCK_EVENT_CONTENDED_ENTER);
}

void lock_monitor_on_contended_exit(lock_monitor_t *lm, JNIEnv *jni,
        jthread thread, jobject object) {
    if (!jvmmon_atomic_load(&lm->active)) return;
    send_lock_event(lm, jni, lm->agent->jvmti, thread, object, LOCK_EVENT_CONTENDED_EXIT);
}
