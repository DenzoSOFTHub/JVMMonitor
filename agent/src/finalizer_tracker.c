/*
 * JVMMonitor - Finalizer & Buffer Pool Tracker Implementation
 * Reads via JNI: finalizer queue, direct/mapped buffer pools, classloader count.
 * JNI class/method IDs are cached after first resolution.
 */
#include "jvmmon/finalizer_tracker.h"
#include "jvmmon/log.h"
#include "jvmmon/protocol.h"
#include <string.h>

static int ensure_cached(finalizer_tracker_t *ft, JNIEnv *env) {
    if (ft->jni_cached) return 1;

    jclass mf = (*env)->FindClass(env, "java/lang/management/ManagementFactory");
    if (mf == NULL) { (*env)->ExceptionClear(env); return 0; }
    ft->mf_global = (jclass)(*env)->NewGlobalRef(env, mf);
    (*env)->DeleteLocalRef(env, mf);

    /* BufferPoolMXBean (JDK 7+) — may not exist */
    jclass bp = (*env)->FindClass(env, "java/lang/management/BufferPoolMXBean");
    if (bp != NULL) {
        ft->bp_class_global = (jclass)(*env)->NewGlobalRef(env, bp);
        (*env)->DeleteLocalRef(env, bp);
        ft->getPlatformBeans = (*env)->GetStaticMethodID(env, ft->mf_global,
            "getPlatformMXBeans", "(Ljava/lang/Class;)Ljava/util/List;");
    }
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); ft->getPlatformBeans = NULL; }

    ft->getCL = (*env)->GetStaticMethodID(env, ft->mf_global,
        "getClassLoadingMXBean", "()Ljava/lang/management/ClassLoadingMXBean;");
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); ft->getCL = NULL; }

    ft->getTM = (*env)->GetStaticMethodID(env, ft->mf_global,
        "getThreadMXBean", "()Ljava/lang/management/ThreadMXBean;");
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); ft->getTM = NULL; }

    ft->jni_cached = 1;
    LOG_DEBUG("Finalizer tracker JNI cache initialized");
    return 1;
}

static void collect_and_send(finalizer_tracker_t *ft, JNIEnv *env) {
    uint8_t payload[JVMMON_MAX_PAYLOAD];
    int off = 0;

    off += protocol_encode_u64(payload + off, jvmmon_time_millis());

    if (!ensure_cached(ft, env)) return;
    if ((*env)->PushLocalFrame(env, 32) < 0) return;

    /* ── Buffer Pools (direct + mapped) ──────── */
    if (ft->bp_class_global != NULL && ft->getPlatformBeans != NULL) {
        jobject beanList = (*env)->CallStaticObjectMethod(env, ft->mf_global,
            ft->getPlatformBeans, ft->bp_class_global);
        if (beanList != NULL && !(*env)->ExceptionCheck(env)) {
            jclass listClass = (*env)->GetObjectClass(env, beanList);
            jmethodID size = (*env)->GetMethodID(env, listClass, "size", "()I");
            jmethodID get = (*env)->GetMethodID(env, listClass, "get", "(I)Ljava/lang/Object;");
            jint count = (*env)->CallIntMethod(env, beanList, size);

            off += protocol_encode_u8(payload + off, (uint8_t)count);

            jint i;
            for (i = 0; i < count && off < JVMMON_MAX_PAYLOAD - 100; i++) {
                jobject bean = (*env)->CallObjectMethod(env, beanList, get, i);
                if (bean == NULL) continue;

                jclass beanClass = (*env)->GetObjectClass(env, bean);
                jmethodID getName = (*env)->GetMethodID(env, beanClass, "getName", "()Ljava/lang/String;");
                jmethodID getCount = (*env)->GetMethodID(env, beanClass, "getCount", "()J");
                jmethodID getUsed = (*env)->GetMethodID(env, beanClass, "getMemoryUsed", "()J");
                jmethodID getCap = (*env)->GetMethodID(env, beanClass, "getTotalCapacity", "()J");

                jstring nameStr = (jstring)(*env)->CallObjectMethod(env, bean, getName);
                jlong bufCount = (*env)->CallLongMethod(env, bean, getCount);
                jlong memUsed = (*env)->CallLongMethod(env, bean, getUsed);
                jlong totalCap = (*env)->CallLongMethod(env, bean, getCap);

                if (nameStr != NULL) {
                    const char *name = (*env)->GetStringUTFChars(env, nameStr, NULL);
                    if (name != NULL) {
                        off += protocol_encode_string(payload + off, name, (uint16_t)strlen(name));
                        (*env)->ReleaseStringUTFChars(env, nameStr, name);
                    }
                    (*env)->DeleteLocalRef(env, nameStr);
                }
                off += protocol_encode_i64(payload + off, (int64_t)bufCount);
                off += protocol_encode_i64(payload + off, (int64_t)memUsed);
                off += protocol_encode_i64(payload + off, (int64_t)totalCap);

                (*env)->DeleteLocalRef(env, beanClass);
                (*env)->DeleteLocalRef(env, bean);
            }
        } else {
            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
            off += protocol_encode_u8(payload + off, 0);
        }
    } else {
        off += protocol_encode_u8(payload + off, 0);
    }

    /* ── ClassLoading MXBean (cached getMethodID on first use) ──── */
    if (ft->getCL != NULL) {
        jobject clBean = (*env)->CallStaticObjectMethod(env, ft->mf_global, ft->getCL);
        if (clBean != NULL) {
            if (ft->cl_getLoaded == NULL) {
                jclass clClass = (*env)->GetObjectClass(env, clBean);
                ft->cl_getLoaded = (*env)->GetMethodID(env, clClass, "getLoadedClassCount", "()I");
                ft->cl_getTotal = (*env)->GetMethodID(env, clClass, "getTotalLoadedClassCount", "()J");
                ft->cl_getUnloaded = (*env)->GetMethodID(env, clClass, "getUnloadedClassCount", "()J");
                (*env)->DeleteLocalRef(env, clClass);
            }
            jint loaded = ft->cl_getLoaded ? (*env)->CallIntMethod(env, clBean, ft->cl_getLoaded) : 0;
            jlong total = ft->cl_getTotal ? (*env)->CallLongMethod(env, clBean, ft->cl_getTotal) : 0;
            jlong unloaded = ft->cl_getUnloaded ? (*env)->CallLongMethod(env, clBean, ft->cl_getUnloaded) : 0;
            off += protocol_encode_i32(payload + off, loaded);
            off += protocol_encode_i64(payload + off, total);
            off += protocol_encode_i64(payload + off, unloaded);
        } else {
            off += protocol_encode_i32(payload + off, 0);
            off += protocol_encode_i64(payload + off, 0);
            off += protocol_encode_i64(payload + off, 0);
        }
    } else {
        off += protocol_encode_i32(payload + off, 0);
        off += protocol_encode_i64(payload + off, 0);
        off += protocol_encode_i64(payload + off, 0);
    }

    /* ── Thread MXBean: peak, daemon, deadlock (30s throttle) ─── */
    if (ft->getTM != NULL) {
        jobject tmBean = (*env)->CallStaticObjectMethod(env, ft->mf_global, ft->getTM);
        if (tmBean != NULL) {
            if (ft->tm_getPeak == NULL) {
                jclass tmClass = (*env)->GetObjectClass(env, tmBean);
                ft->tm_getPeak = (*env)->GetMethodID(env, tmClass, "getPeakThreadCount", "()I");
                ft->tm_getDaemon = (*env)->GetMethodID(env, tmClass, "getDaemonThreadCount", "()I");
                ft->tm_findDeadlock = (*env)->GetMethodID(env, tmClass, "findDeadlockedThreads", "()[J");
                if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); ft->tm_findDeadlock = NULL; }
                (*env)->DeleteLocalRef(env, tmClass);
            }

            jint peak = ft->tm_getPeak ? (*env)->CallIntMethod(env, tmBean, ft->tm_getPeak) : 0;
            jint daemon = ft->tm_getDaemon ? (*env)->CallIntMethod(env, tmBean, ft->tm_getDaemon) : 0;

            int deadlockCount = -1;
            uint64_t now = jvmmon_time_millis();
            if (now - ft->last_deadlock_check >= (uint64_t)ft->deadlock_interval_ms) {
                ft->last_deadlock_check = now;
                deadlockCount = 0;
                if (ft->tm_findDeadlock != NULL) {
                    jlongArray dlArr = (jlongArray)(*env)->CallObjectMethod(env, tmBean, ft->tm_findDeadlock);
                    if (dlArr != NULL) {
                        deadlockCount = (*env)->GetArrayLength(env, dlArr);
                        (*env)->DeleteLocalRef(env, dlArr);
                    }
                    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                }
            }

            off += protocol_encode_i32(payload + off, peak);
            off += protocol_encode_i32(payload + off, daemon);
            off += protocol_encode_i32(payload + off, deadlockCount);
        } else {
            off += protocol_encode_i32(payload + off, 0);
            off += protocol_encode_i32(payload + off, 0);
            off += protocol_encode_i32(payload + off, -1);
        }
    } else {
        off += protocol_encode_i32(payload + off, 0);
        off += protocol_encode_i32(payload + off, 0);
        off += protocol_encode_i32(payload + off, -1);
    }

    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->PopLocalFrame(env, NULL);

    agent_send_message(JVMMON_MSG_FINALIZER, payload, (uint32_t)off);
}

static void *poll_thread_fn(void *arg) {
    finalizer_tracker_t *ft = (finalizer_tracker_t *)arg;
    JNIEnv *env;

    if ((*ft->agent->jvm)->AttachCurrentThread(ft->agent->jvm, (void **)&env, NULL) != JNI_OK) {
        return NULL;
    }

    while (jvmmon_atomic_load(&ft->running)) {
        collect_and_send(ft, env);
        jvmmon_sleep_ms(ft->interval_ms);
    }

    (*ft->agent->jvm)->DetachCurrentThread(ft->agent->jvm);
    return NULL;
}

finalizer_tracker_t *finalizer_tracker_create(jvmmon_agent_t *agent, int interval_ms) {
    finalizer_tracker_t *ft = (finalizer_tracker_t *)jvmmon_calloc(1, sizeof(finalizer_tracker_t));
    if (ft == NULL) return NULL;
    ft->agent = agent;
    ft->interval_ms = interval_ms > 0 ? interval_ms : 5000;
    ft->deadlock_interval_ms = 30000;
    ft->last_deadlock_check = 0;
    ft->running = 0;
    ft->jni_cached = 0;
    return ft;
}

void finalizer_tracker_destroy(finalizer_tracker_t *ft) {
    if (ft != NULL) {
        if (jvmmon_atomic_load(&ft->running)) {
            jvmmon_atomic_store(&ft->running, 0);
            jvmmon_thread_join(&ft->poll_thread);
        }
        jvmmon_free(ft);
    }
}

int finalizer_tracker_activate(int level, const char *target, void *ctx) {
    finalizer_tracker_t *ft = (finalizer_tracker_t *)ctx;
    (void)level; (void)target;
    jvmmon_atomic_store(&ft->running, 1);
    jvmmon_thread_create(&ft->poll_thread, poll_thread_fn, ft);
    LOG_INFO("Finalizer/Buffer tracker activated");
    return 0;
}

int finalizer_tracker_deactivate(int level, void *ctx) {
    finalizer_tracker_t *ft = (finalizer_tracker_t *)ctx;
    (void)level;
    jvmmon_atomic_store(&ft->running, 0);
    jvmmon_thread_join(&ft->poll_thread);
    LOG_INFO("Finalizer/Buffer tracker deactivated");
    return 0;
}
