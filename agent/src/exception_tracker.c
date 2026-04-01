/*
 * JVMMonitor - Exception Tracker Implementation
 * Rate-limited: max 100 full exception events per second sent to client.
 * Beyond that, only increments counters (total thrown/caught always accurate).
 * Periodically sends a summary with total counts + rate.
 */
#include "jvmmon/exception_tracker.h"
#include "jvmmon/log.h"
#include "jvmmon/protocol.h"
#include <string.h>

exception_tracker_t *exception_tracker_create(jvmmon_agent_t *agent) {
    exception_tracker_t *et = (exception_tracker_t *)jvmmon_calloc(1, sizeof(exception_tracker_t));
    if (et == NULL) return NULL;
    et->agent = agent;
    return et;
}

void exception_tracker_destroy(exception_tracker_t *et) {
    if (et != NULL) jvmmon_free(et);
}

int exception_tracker_activate(int level, const char *target, void *ctx) {
    exception_tracker_t *et = (exception_tracker_t *)ctx;
    jvmtiEnv *jvmti = et->agent->jvmti;
    (void)level; (void)target;

    et->exception_count = 0;
    et->caught_count = 0;
    et->sent_count = 0;
    et->dropped_count = 0;
    et->window_start_ms = jvmmon_time_millis();
    et->window_sent = 0;

    (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE,
            JVMTI_EVENT_EXCEPTION, NULL);
    (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE,
            JVMTI_EVENT_EXCEPTION_CATCH, NULL);

    jvmmon_atomic_store(&et->active, 1);
    LOG_INFO("Exception tracker activated (rate limit: %d/s)", EXCEPTION_MAX_SEND_PER_SEC);
    return 0;
}

int exception_tracker_deactivate(int level, void *ctx) {
    exception_tracker_t *et = (exception_tracker_t *)ctx;
    jvmtiEnv *jvmti = et->agent->jvmti;
    (void)level;

    jvmmon_atomic_store(&et->active, 0);

    (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_DISABLE,
            JVMTI_EVENT_EXCEPTION, NULL);
    (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_DISABLE,
            JVMTI_EVENT_EXCEPTION_CATCH, NULL);

    LOG_INFO("Exception tracker deactivated (thrown=%d, caught=%d, sent=%d, dropped=%d)",
             jvmmon_atomic_load(&et->exception_count),
             jvmmon_atomic_load(&et->caught_count),
             jvmmon_atomic_load(&et->sent_count),
             jvmmon_atomic_load(&et->dropped_count));
    return 0;
}

/* Check rate limit. Returns 1 if allowed, 0 if throttled.
 * Thread-safe: uses atomic CAS for window reset and atomic increment for counter. */
static int rate_check(exception_tracker_t *et) {
    uint64_t now = jvmmon_time_millis();
    /* Reset window every second — use CAS to prevent double-reset */
    uint64_t ws = et->window_start_ms;
    if (now - ws >= 1000) {
        if (jvmmon_atomic_cas((volatile int32_t *)&et->window_start_ms,
                (int32_t)ws, (int32_t)now)) {
            jvmmon_atomic_store(&et->window_sent, 0);
        }
    }
    /* Atomic increment + check */
    int32_t current = jvmmon_atomic_add(&et->window_sent, 1);
    if (current > EXCEPTION_MAX_SEND_PER_SEC) {
        return 0;
    }
    return 1;
}

void exception_tracker_on_exception(exception_tracker_t *et, JNIEnv *jni,
        jthread thread, jmethodID method, jlocation location,
        jobject exception, jmethodID catch_method, jlocation catch_location) {
    (void)catch_location;
    if (!jvmmon_atomic_load(&et->active)) return;

    /* Always count */
    jvmmon_atomic_add(&et->exception_count, 1);

    /* Rate limit: drop if over budget */
    if (!rate_check(et)) {
        jvmmon_atomic_add(&et->dropped_count, 1);
        return;
    }

    jvmmon_atomic_add(&et->sent_count, 1);

    jvmtiEnv *jvmti = et->agent->jvmti;
    uint8_t payload[JVMMON_MAX_PAYLOAD];
    int off = 0;

    off += protocol_encode_u64(payload + off, jvmmon_time_millis());

    /* Total counters (always accurate even when rate-limited) */
    off += protocol_encode_i32(payload + off, jvmmon_atomic_load(&et->exception_count));
    off += protocol_encode_i32(payload + off, jvmmon_atomic_load(&et->caught_count));
    off += protocol_encode_i32(payload + off, jvmmon_atomic_load(&et->dropped_count));

    /* Exception class name */
    jclass exc_class = (*jni)->GetObjectClass(jni, exception);
    char *class_sig = NULL;
    (*jvmti)->GetClassSignature(jvmti, exc_class, &class_sig, NULL);
    uint16_t cslen = class_sig ? (uint16_t)strlen(class_sig) : 0;
    if (cslen > 255) cslen = 255;
    off += protocol_encode_string(payload + off, class_sig, cslen);

    /* Method where thrown */
    char mname[256] = {0}, cname[256] = {0};
    agent_resolve_method(method, cname, 256, mname, 256, NULL, 0);
    off += protocol_encode_string(payload + off, cname, (uint16_t)strlen(cname));
    off += protocol_encode_string(payload + off, mname, (uint16_t)strlen(mname));
    off += protocol_encode_i64(payload + off, (int64_t)location);

    /* Caught? */
    off += protocol_encode_u8(payload + off, catch_method != NULL ? 1 : 0);
    if (catch_method != NULL) {
        char cm[256] = {0}, cc[256] = {0};
        agent_resolve_method(catch_method, cc, 256, cm, 256, NULL, 0);
        off += protocol_encode_string(payload + off, cc, (uint16_t)strlen(cc));
        off += protocol_encode_string(payload + off, cm, (uint16_t)strlen(cm));
    }

    /* Stack trace (top 16 frames) */
    jvmtiFrameInfo frames[16];
    jint frame_count = 0;
    (*jvmti)->GetStackTrace(jvmti, thread, 0, 16, frames, &frame_count);
    off += protocol_encode_u16(payload + off, (uint16_t)frame_count);
    int i;
    for (i = 0; i < frame_count && off < (int)(JVMMON_MAX_PAYLOAD - 20); i++) {
        off += protocol_encode_u64(payload + off, (uint64_t)(uintptr_t)frames[i].method);
        off += protocol_encode_i32(payload + off, (int32_t)frames[i].location);
    }

    agent_send_message(JVMMON_MSG_EXCEPTION, payload, (uint32_t)off);

    if (class_sig) (*jvmti)->Deallocate(jvmti, (unsigned char *)class_sig);
    (*jni)->DeleteLocalRef(jni, exc_class);
}

void exception_tracker_on_exception_catch(exception_tracker_t *et, JNIEnv *jni,
        jthread thread, jmethodID method, jlocation location, jobject exception) {
    (void)jni; (void)thread; (void)method; (void)location; (void)exception;
    if (!jvmmon_atomic_load(&et->active)) return;
    jvmmon_atomic_add(&et->caught_count, 1);
}
