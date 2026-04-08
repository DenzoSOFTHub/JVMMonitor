/*
 * JVMMonitor - GC Collector Details Implementation
 * Reads GarbageCollectorMXBeans for per-collector stats.
 */
#include "jvmmon/gc_detail.h"
#include "jvmmon/log.h"
#include "jvmmon/protocol.h"
#include <string.h>

static void collect_gc_detail(gc_detail_t *gd, JNIEnv *env) {
    uint8_t payload[JVMMON_MAX_PAYLOAD];
    int off = 0;
    off += protocol_encode_u64(payload + off, jvmmon_time_millis());

    jclass mf = (*env)->FindClass(env, "java/lang/management/ManagementFactory");
    if (mf == NULL) return;

    jmethodID getGCBeans = (*env)->GetStaticMethodID(env, mf,
            "getGarbageCollectorMXBeans", "()Ljava/util/List;");
    if (getGCBeans == NULL) { (*env)->DeleteLocalRef(env, mf); return; }

    jobject gcList = (*env)->CallStaticObjectMethod(env, mf, getGCBeans);
    if (gcList == NULL) { (*env)->DeleteLocalRef(env, mf); return; }

    jclass listClass = (*env)->GetObjectClass(env, gcList);
    jmethodID size = (*env)->GetMethodID(env, listClass, "size", "()I");
    jmethodID get = (*env)->GetMethodID(env, listClass, "get", "(I)Ljava/lang/Object;");
    if (size == NULL || get == NULL) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, listClass);
        (*env)->DeleteLocalRef(env, gcList);
        (*env)->DeleteLocalRef(env, mf);
        return;
    }
    jint count = (*env)->CallIntMethod(env, gcList, size);

    off += protocol_encode_u16(payload + off, (uint16_t)count);

    jint i;
    for (i = 0; i < count && off < JVMMON_MAX_PAYLOAD - 200; i++) {
        jobject gcBean = (*env)->CallObjectMethod(env, gcList, get, i);
        if (gcBean == NULL) continue;

        jclass gcClass = (*env)->GetObjectClass(env, gcBean);
        jmethodID getName = (*env)->GetMethodID(env, gcClass, "getName",
                "()Ljava/lang/String;");
        jmethodID getCount = (*env)->GetMethodID(env, gcClass,
                "getCollectionCount", "()J");
        jmethodID getTime = (*env)->GetMethodID(env, gcClass,
                "getCollectionTime", "()J");
        jmethodID getPools = (*env)->GetMethodID(env, gcClass,
                "getMemoryPoolNames", "()[Ljava/lang/String;");
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        if (getName == NULL || getCount == NULL || getTime == NULL) {
            (*env)->DeleteLocalRef(env, gcClass);
            (*env)->DeleteLocalRef(env, gcBean);
            continue;
        }

        jstring nameStr = (jstring)(*env)->CallObjectMethod(env, gcBean, getName);
        jlong gcCount = (*env)->CallLongMethod(env, gcBean, getCount);
        jlong gcTime = (*env)->CallLongMethod(env, gcBean, getTime);

        if (nameStr != NULL) {
            const char *name = (*env)->GetStringUTFChars(env, nameStr, NULL);
            if (name != NULL) {
                off += protocol_encode_string(payload + off, name, (uint16_t)strlen(name));
                (*env)->ReleaseStringUTFChars(env, nameStr, name);
            }
            (*env)->DeleteLocalRef(env, nameStr);
        }
        off += protocol_encode_i64(payload + off, (int64_t)gcCount);
        off += protocol_encode_i64(payload + off, (int64_t)gcTime);

        /* Memory pool names associated with this collector */
        if (getPools != NULL) {
            jobjectArray pools = (jobjectArray)(*env)->CallObjectMethod(env, gcBean, getPools);
            if (pools != NULL) {
                jint pcount = (*env)->GetArrayLength(env, pools);
                off += protocol_encode_u16(payload + off, (uint16_t)pcount);
                jint p;
                for (p = 0; p < pcount && off < JVMMON_MAX_PAYLOAD - 50; p++) {
                    jstring pname = (jstring)(*env)->GetObjectArrayElement(env, pools, p);
                    if (pname != NULL) {
                        const char *ps = (*env)->GetStringUTFChars(env, pname, NULL);
                        if (ps) {
                            off += protocol_encode_string(payload + off, ps, (uint16_t)strlen(ps));
                            (*env)->ReleaseStringUTFChars(env, pname, ps);
                        }
                        (*env)->DeleteLocalRef(env, pname);
                    }
                }
                (*env)->DeleteLocalRef(env, pools);
            } else {
                off += protocol_encode_u16(payload + off, 0);
            }
        } else {
            off += protocol_encode_u16(payload + off, 0);
        }

        (*env)->DeleteLocalRef(env, gcClass);
        (*env)->DeleteLocalRef(env, gcBean);
    }

    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, listClass);
    (*env)->DeleteLocalRef(env, gcList);
    (*env)->DeleteLocalRef(env, mf);

    agent_send_message(JVMMON_MSG_GC_DETAIL, payload, (uint32_t)off);
}

static void *poll_fn(void *arg) {
    gc_detail_t *gd = (gc_detail_t *)arg;
    JNIEnv *env;
    if ((*gd->agent->jvm)->AttachCurrentThread(gd->agent->jvm, (void **)&env, NULL) != JNI_OK)
        return NULL;
    while (jvmmon_atomic_load(&gd->running)) {
        collect_gc_detail(gd, env);
        jvmmon_sleep_ms(gd->interval_ms);
    }
    (*gd->agent->jvm)->DetachCurrentThread(gd->agent->jvm);
    return NULL;
}

gc_detail_t *gc_detail_create(jvmmon_agent_t *agent, int interval_ms) {
    gc_detail_t *gd = (gc_detail_t *)jvmmon_calloc(1, sizeof(gc_detail_t));
    if (!gd) return NULL;
    gd->agent = agent; gd->interval_ms = interval_ms > 0 ? interval_ms : 5000;
    return gd;
}
void gc_detail_destroy(gc_detail_t *gd) {
    if (gd) { if (jvmmon_atomic_load(&gd->running)) gc_detail_deactivate(0, gd); jvmmon_free(gd); }
}
int gc_detail_activate(int level, const char *target, void *ctx) {
    gc_detail_t *gd = (gc_detail_t *)ctx; (void)level; (void)target;
    jvmmon_atomic_store(&gd->running, 1);
    jvmmon_thread_create(&gd->poll_thread, poll_fn, gd);
    LOG_INFO("GC detail tracker activated"); return 0;
}
int gc_detail_deactivate(int level, void *ctx) {
    gc_detail_t *gd = (gc_detail_t *)ctx; (void)level;
    jvmmon_atomic_store(&gd->running, 0);
    jvmmon_thread_join(&gd->poll_thread);
    LOG_INFO("GC detail tracker deactivated"); return 0;
}
