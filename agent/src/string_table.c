/*
 * JVMMonitor - String Table & JFR Stream Implementation
 * Uses DiagnosticCommandMBean to get string table statistics.
 * On JDK 14+, attempts to tap JFR RecordingStream for additional events.
 */
#include "jvmmon/string_table.h"
#include "jvmmon/log.h"
#include "jvmmon/protocol.h"
#include <string.h>

static void invoke_diagnostic_command(string_table_tracker_t *st, JNIEnv *env,
                                       const char *command, uint8_t msg_type) {
    uint8_t payload[JVMMON_MAX_PAYLOAD];
    int off = 0;
    off += protocol_encode_u64(payload + off, jvmmon_time_millis());

    if ((*env)->PushLocalFrame(env, 32) < 0) return;

    jclass mf = (*env)->FindClass(env, "java/lang/management/ManagementFactory");
    if (mf == NULL) goto done;

    jmethodID getPBS = (*env)->GetStaticMethodID(env, mf,
            "getPlatformMBeanServer", "()Ljavax/management/MBeanServer;");
    if (getPBS == NULL) goto done;
    jobject server = (*env)->CallStaticObjectMethod(env, mf, getPBS);
    if (server == NULL) goto done;

    jclass onClass = (*env)->FindClass(env, "javax/management/ObjectName");
    jmethodID onCtor = (*env)->GetMethodID(env, onClass, "<init>", "(Ljava/lang/String;)V");
    jstring diagName = (*env)->NewStringUTF(env, "com.sun.management:type=DiagnosticCommand");
    jobject diagON = (*env)->NewObject(env, onClass, onCtor, diagName);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); goto done; }

    jclass serverClass = (*env)->GetObjectClass(env, server);
    jmethodID invoke = (*env)->GetMethodID(env, serverClass, "invoke",
        "(Ljavax/management/ObjectName;Ljava/lang/String;"
        "[Ljava/lang/Object;[Ljava/lang/String;)Ljava/lang/Object;");
    if (invoke == NULL) goto done;

    jstring opName = (*env)->NewStringUTF(env, command);
    jobject result = (*env)->CallObjectMethod(env, server, invoke,
            diagON, opName, NULL, NULL);

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        off += protocol_encode_u8(payload + off, 0);
        off += protocol_encode_string(payload + off, "Command not available", 21);
    } else if (result != NULL) {
        jstring resultStr = (jstring)result;
        const char *text = (*env)->GetStringUTFChars(env, resultStr, NULL);
        if (text != NULL) {
            uint16_t tlen = (uint16_t)strlen(text);
            if (tlen > JVMMON_MAX_PAYLOAD - 100) tlen = JVMMON_MAX_PAYLOAD - 100;
            off += protocol_encode_u8(payload + off, 1);
            off += protocol_encode_string(payload + off, text, tlen);
            (*env)->ReleaseStringUTFChars(env, resultStr, text);
        }
    } else {
        off += protocol_encode_u8(payload + off, 0);
        off += protocol_encode_string(payload + off, "No result", 9);
    }

done:
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->PopLocalFrame(env, NULL);
    agent_send_message(msg_type, payload, (uint32_t)off);
}

static void try_jfr_stream(string_table_tracker_t *st, JNIEnv *env) {
    /* Check if JFR is available (JDK 14+) */
    jclass jfrClass = (*env)->FindClass(env, "jdk/jfr/consumer/RecordingStream");
    if (jfrClass == NULL) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        LOG_DEBUG("JFR RecordingStream not available (requires JDK 14+)");
        return;
    }

    /* JFR is available — send a notification to client */
    uint8_t payload[128];
    int off = 0;
    off += protocol_encode_u64(payload + off, jvmmon_time_millis());
    off += protocol_encode_u8(payload + off, 1); /* JFR available */
    off += protocol_encode_string(payload + off, "JFR available on this JVM", 25);
    agent_send_message(JVMMON_MSG_JFR_EVENT, payload, (uint32_t)off);

    (*env)->DeleteLocalRef(env, jfrClass);
    /* Actual JFR streaming would require more complex setup —
     * for now we just report availability. Full JFR integration
     * can be done in a future version. */
}

static void *poll_fn(void *arg) {
    string_table_tracker_t *st = (string_table_tracker_t *)arg;
    JNIEnv *env;
    if ((*st->agent->jvm)->AttachCurrentThread(st->agent->jvm, (void **)&env, NULL) != JNI_OK)
        return NULL;

    /* One-time JFR availability check */
    try_jfr_stream(st, env);

    while (jvmmon_atomic_load(&st->running)) {
        /* String table stats via "stringTableStatistics" diagnostic command */
        invoke_diagnostic_command(st, env, "stringTableStatistics", JVMMON_MSG_STRING_TABLE);
        jvmmon_sleep_ms(st->interval_ms);
    }

    (*st->agent->jvm)->DetachCurrentThread(st->agent->jvm);
    return NULL;
}

string_table_tracker_t *string_table_tracker_create(jvmmon_agent_t *agent, int interval_ms) {
    string_table_tracker_t *st = (string_table_tracker_t *)jvmmon_calloc(1, sizeof(string_table_tracker_t));
    if (!st) return NULL;
    st->agent = agent; st->interval_ms = interval_ms > 0 ? interval_ms : 15000;
    return st;
}
void string_table_tracker_destroy(string_table_tracker_t *st) {
    if (st) { if (jvmmon_atomic_load(&st->running)) string_table_tracker_deactivate(0, st); jvmmon_free(st); }
}
int string_table_tracker_activate(int level, const char *target, void *ctx) {
    string_table_tracker_t *st = (string_table_tracker_t *)ctx; (void)level; (void)target;
    jvmmon_atomic_store(&st->running, 1);
    jvmmon_thread_create(&st->poll_thread, poll_fn, st);
    LOG_INFO("String table / JFR tracker activated"); return 0;
}
int string_table_tracker_deactivate(int level, void *ctx) {
    string_table_tracker_t *st = (string_table_tracker_t *)ctx; (void)level;
    jvmmon_atomic_store(&st->running, 0);
    jvmmon_thread_join(&st->poll_thread);
    LOG_INFO("String table / JFR tracker deactivated"); return 0;
}
