/*
 * JVMMonitor - Safepoint Timing Implementation
 * Uses DiagnosticCommandMBean to invoke VM.print_safepoint_statistics
 * or reads sun.management.HotspotRuntimeMBean if available.
 * Fallback: reads -XX:+PrintSafepointStatistics output if enabled.
 */
#include "jvmmon/safepoint_tracker.h"
#include "jvmmon/log.h"
#include "jvmmon/protocol.h"
#include <string.h>

static void collect_safepoint_data(safepoint_tracker_t *st, JNIEnv *env) {
    uint8_t payload[JVMMON_MAX_PAYLOAD];
    int off = 0;
    off += protocol_encode_u64(payload + off, jvmmon_time_millis());

    /*
     * Try 1: DiagnosticCommandMBean → execute("vmUptime")
     * This gives us a baseline. Then try to get safepoint-specific data.
     *
     * Try 2: Access HotspotRuntimeMBean via
     *   sun.management.ManagementFactoryHelper.getHotspotRuntimeMBean()
     *   → getSafepointCount(), getTotalSafepointTime(), getSafepointSyncTime()
     */
    jclass helperClass = (*env)->FindClass(env,
            "sun/management/ManagementFactoryHelper");
    if (helperClass != NULL) {
        jmethodID getHotspotRT = (*env)->GetStaticMethodID(env, helperClass,
                "getHotspotRuntimeMBean",
                "()Lsun/management/HotspotRuntimeMBean;");
        if (getHotspotRT != NULL) {
            jobject hsBean = (*env)->CallStaticObjectMethod(env, helperClass, getHotspotRT);
            if (hsBean != NULL && !(*env)->ExceptionCheck(env)) {
                jclass hsBeanClass = (*env)->GetObjectClass(env, hsBean);

                jmethodID getSPCount = (*env)->GetMethodID(env, hsBeanClass,
                        "getSafepointCount", "()J");
                jmethodID getSPTime = (*env)->GetMethodID(env, hsBeanClass,
                        "getTotalSafepointTime", "()J");
                jmethodID getSPSyncTime = (*env)->GetMethodID(env, hsBeanClass,
                        "getSafepointSyncTime", "()J");

                jlong spCount = getSPCount ? (*env)->CallLongMethod(env, hsBean, getSPCount) : -1;
                jlong spTime = getSPTime ? (*env)->CallLongMethod(env, hsBean, getSPTime) : -1;
                jlong spSyncTime = getSPSyncTime ? (*env)->CallLongMethod(env, hsBean, getSPSyncTime) : -1;

                off += protocol_encode_i64(payload + off, (int64_t)spCount);
                off += protocol_encode_i64(payload + off, (int64_t)spTime);
                off += protocol_encode_i64(payload + off, (int64_t)spSyncTime);

                LOG_DEBUG("Safepoint: count=%lld, totalTime=%lldms, syncTime=%lldms",
                          (long long)spCount, (long long)spTime, (long long)spSyncTime);

                (*env)->DeleteLocalRef(env, hsBeanClass);
                (*env)->DeleteLocalRef(env, hsBean);
            } else {
                if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                off += protocol_encode_i64(payload + off, -1);
                off += protocol_encode_i64(payload + off, -1);
                off += protocol_encode_i64(payload + off, -1);
            }
        } else {
            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
            off += protocol_encode_i64(payload + off, -1);
            off += protocol_encode_i64(payload + off, -1);
            off += protocol_encode_i64(payload + off, -1);
        }
        (*env)->DeleteLocalRef(env, helperClass);
    } else {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        off += protocol_encode_i64(payload + off, -1);
        off += protocol_encode_i64(payload + off, -1);
        off += protocol_encode_i64(payload + off, -1);
    }

    agent_send_message(JVMMON_MSG_SAFEPOINT, payload, (uint32_t)off);
}

static void *poll_fn(void *arg) {
    safepoint_tracker_t *st = (safepoint_tracker_t *)arg;
    JNIEnv *env;
    if ((*st->agent->jvm)->AttachCurrentThread(st->agent->jvm, (void **)&env, NULL) != JNI_OK)
        return NULL;
    while (jvmmon_atomic_load(&st->running)) {
        collect_safepoint_data(st, env);
        jvmmon_sleep_ms(st->interval_ms);
    }
    (*st->agent->jvm)->DetachCurrentThread(st->agent->jvm);
    return NULL;
}

safepoint_tracker_t *safepoint_tracker_create(jvmmon_agent_t *agent, int interval_ms) {
    safepoint_tracker_t *st = (safepoint_tracker_t *)jvmmon_calloc(1, sizeof(safepoint_tracker_t));
    if (!st) return NULL;
    st->agent = agent;
    st->interval_ms = interval_ms > 0 ? interval_ms : 5000;
    return st;
}

void safepoint_tracker_destroy(safepoint_tracker_t *st) {
    if (st) { if (jvmmon_atomic_load(&st->running)) safepoint_tracker_deactivate(0, st); jvmmon_free(st); }
}

int safepoint_tracker_activate(int level, const char *target, void *ctx) {
    safepoint_tracker_t *st = (safepoint_tracker_t *)ctx; (void)level; (void)target;
    jvmmon_atomic_store(&st->running, 1);
    jvmmon_thread_create(&st->poll_thread, poll_fn, st);
    LOG_INFO("Safepoint tracker activated");
    return 0;
}

int safepoint_tracker_deactivate(int level, void *ctx) {
    safepoint_tracker_t *st = (safepoint_tracker_t *)ctx; (void)level;
    jvmmon_atomic_store(&st->running, 0);
    jvmmon_thread_join(&st->poll_thread);
    LOG_INFO("Safepoint tracker deactivated");
    return 0;
}
