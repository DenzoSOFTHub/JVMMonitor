/*
 * JVMMonitor - Native Memory Tracking Implementation
 * Invokes DiagnosticCommandMBean.execute("vmNativeMemory", ["summary"])
 * to get native memory breakdown. Requires JVM started with -XX:NativeMemoryTracking=summary.
 * If NMT is not enabled, sends the raw output string for client-side display.
 */
#include "jvmmon/native_mem.h"
#include "jvmmon/log.h"
#include "jvmmon/protocol.h"
#include <string.h>

static void collect_native_mem(native_mem_tracker_t *nm, JNIEnv *env) {
    uint8_t payload[JVMMON_MAX_PAYLOAD];
    int off = 0;
    off += protocol_encode_u64(payload + off, jvmmon_time_millis());

    /* Get DiagnosticCommandMBean via MBeanServer */
    jclass mf = (*env)->FindClass(env, "java/lang/management/ManagementFactory");
    if (mf == NULL) goto fallback;

    jmethodID getPBS = (*env)->GetStaticMethodID(env, mf,
            "getPlatformMBeanServer", "()Ljavax/management/MBeanServer;");
    if (getPBS == NULL) goto fallback;
    jobject server = (*env)->CallStaticObjectMethod(env, mf, getPBS);
    if (server == NULL) goto fallback;

    /* ObjectName for DiagnosticCommand */
    jclass onClass = (*env)->FindClass(env, "javax/management/ObjectName");
    jmethodID onCtor = (*env)->GetMethodID(env, onClass, "<init>", "(Ljava/lang/String;)V");
    jstring diagName = (*env)->NewStringUTF(env, "com.sun.management:type=DiagnosticCommand");
    jobject diagON = (*env)->NewObject(env, onClass, onCtor, diagName);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); goto fallback; }

    /* Invoke: server.invoke(diagON, "vmNativeMemory", [["summary"]], ["[Ljava.lang.String;"]) */
    jclass serverClass = (*env)->GetObjectClass(env, server);
    jmethodID invoke = (*env)->GetMethodID(env, serverClass, "invoke",
        "(Ljavax/management/ObjectName;Ljava/lang/String;"
        "[Ljava/lang/Object;[Ljava/lang/String;)Ljava/lang/Object;");
    if (invoke == NULL) goto fallback;

    jstring opName = (*env)->NewStringUTF(env, "vmNativeMemory");

    /* params: String[] {"summary"} */
    jclass strArrayClass = (*env)->FindClass(env, "[Ljava/lang/String;");
    jclass strClass = (*env)->FindClass(env, "java/lang/String");
    jobjectArray innerArr = (*env)->NewObjectArray(env, 1, strClass, NULL);
    (*env)->SetObjectArrayElement(env, innerArr, 0, (*env)->NewStringUTF(env, "summary"));
    jobjectArray params = (*env)->NewObjectArray(env, 1, (*env)->FindClass(env, "java/lang/Object"), NULL);
    (*env)->SetObjectArrayElement(env, params, 0, innerArr);

    /* signature */
    jobjectArray sig = (*env)->NewObjectArray(env, 1, strClass, NULL);
    (*env)->SetObjectArrayElement(env, sig, 0, (*env)->NewStringUTF(env, "[Ljava.lang.String;"));

    jobject result = (*env)->CallObjectMethod(env, server, invoke, diagON, opName, params, sig);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        goto fallback;
    }

    if (result != NULL) {
        jstring resultStr = (jstring)result;
        const char *text = (*env)->GetStringUTFChars(env, resultStr, NULL);
        if (text != NULL) {
            uint16_t tlen = (uint16_t)strlen(text);
            if (tlen > JVMMON_MAX_PAYLOAD - 100) tlen = JVMMON_MAX_PAYLOAD - 100;
            off += protocol_encode_u8(payload + off, 1); /* 1 = data available */
            off += protocol_encode_string(payload + off, text, tlen);
            (*env)->ReleaseStringUTFChars(env, resultStr, text);
            LOG_DEBUG("Native memory: got %d bytes of NMT data", (int)tlen);
        } else {
            goto fallback;
        }
    } else {
        goto fallback;
    }

    agent_send_message(JVMMON_MSG_NATIVE_MEM, payload, (uint32_t)off);

    (*env)->DeleteLocalRef(env, mf);
    return;

fallback:
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    /* NMT not available — send flag */
    off = 0;
    off += protocol_encode_u64(payload + off, jvmmon_time_millis());
    off += protocol_encode_u8(payload + off, 0); /* 0 = not available */
    off += protocol_encode_string(payload + off,
            "NMT not available. Start JVM with -XX:NativeMemoryTracking=summary", 66);
    agent_send_message(JVMMON_MSG_NATIVE_MEM, payload, (uint32_t)off);
}

static void *poll_fn(void *arg) {
    native_mem_tracker_t *nm = (native_mem_tracker_t *)arg;
    JNIEnv *env;
    if ((*nm->agent->jvm)->AttachCurrentThread(nm->agent->jvm, (void **)&env, NULL) != JNI_OK)
        return NULL;
    while (jvmmon_atomic_load(&nm->running)) {
        collect_native_mem(nm, env);
        jvmmon_sleep_ms(nm->interval_ms);
    }
    (*nm->agent->jvm)->DetachCurrentThread(nm->agent->jvm);
    return NULL;
}

native_mem_tracker_t *native_mem_tracker_create(jvmmon_agent_t *agent, int interval_ms) {
    native_mem_tracker_t *nm = (native_mem_tracker_t *)jvmmon_calloc(1, sizeof(native_mem_tracker_t));
    if (!nm) return NULL;
    nm->agent = agent;
    nm->interval_ms = interval_ms > 0 ? interval_ms : 10000;
    return nm;
}

void native_mem_tracker_destroy(native_mem_tracker_t *nm) {
    if (nm) { if (jvmmon_atomic_load(&nm->running)) native_mem_tracker_deactivate(0, nm); jvmmon_free(nm); }
}

int native_mem_tracker_activate(int level, const char *target, void *ctx) {
    native_mem_tracker_t *nm = (native_mem_tracker_t *)ctx; (void)level; (void)target;
    jvmmon_atomic_store(&nm->running, 1);
    jvmmon_thread_create(&nm->poll_thread, poll_fn, nm);
    LOG_INFO("Native memory tracker activated");
    return 0;
}

int native_mem_tracker_deactivate(int level, void *ctx) {
    native_mem_tracker_t *nm = (native_mem_tracker_t *)ctx; (void)level;
    jvmmon_atomic_store(&nm->running, 0);
    jvmmon_thread_join(&nm->poll_thread);
    LOG_INFO("Native memory tracker deactivated");
    return 0;
}
