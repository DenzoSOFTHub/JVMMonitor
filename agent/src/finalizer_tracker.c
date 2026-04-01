/*
 * JVMMonitor - Finalizer & Buffer Pool Tracker Implementation
 * Reads via JNI: finalizer queue, direct/mapped buffer pools, classloader count.
 */
#include "jvmmon/finalizer_tracker.h"
#include "jvmmon/log.h"
#include "jvmmon/protocol.h"
#include <string.h>

static void collect_and_send(finalizer_tracker_t *ft, JNIEnv *env) {
    uint8_t payload[JVMMON_MAX_PAYLOAD];
    int off = 0;

    off += protocol_encode_u64(payload + off, jvmmon_time_millis());

    /* ── Buffer Pools (direct + mapped) ──────── */
    jclass mf = (*env)->FindClass(env, "java/lang/management/ManagementFactory");
    if (mf != NULL) {
        /* ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class) — JDK 7+ */
        /* Fallback: use reflection to find BufferPoolMXBean */
        jclass bpClass = (*env)->FindClass(env, "java/lang/management/BufferPoolMXBean");
        if (bpClass != NULL) {
            jmethodID getPlatformBeans = (*env)->GetStaticMethodID(env, mf,
                "getPlatformMXBeans", "(Ljava/lang/Class;)Ljava/util/List;");
            if (getPlatformBeans != NULL) {
                jobject beanList = (*env)->CallStaticObjectMethod(env, mf,
                    getPlatformBeans, bpClass);
                if (beanList != NULL && !(*env)->ExceptionCheck(env)) {
                    jclass listClass = (*env)->GetObjectClass(env, beanList);
                    jmethodID size = (*env)->GetMethodID(env, listClass, "size", "()I");
                    jmethodID get = (*env)->GetMethodID(env, listClass, "get",
                        "(I)Ljava/lang/Object;");
                    jint count = (*env)->CallIntMethod(env, beanList, size);

                    off += protocol_encode_u8(payload + off, (uint8_t)count);

                    jint i;
                    for (i = 0; i < count && off < JVMMON_MAX_PAYLOAD - 100; i++) {
                        jobject bean = (*env)->CallObjectMethod(env, beanList, get, i);
                        if (bean == NULL) continue;

                        jclass beanClass = (*env)->GetObjectClass(env, bean);
                        jmethodID getName = (*env)->GetMethodID(env, beanClass,
                            "getName", "()Ljava/lang/String;");
                        jmethodID getCount = (*env)->GetMethodID(env, beanClass,
                            "getCount", "()J");
                        jmethodID getUsed = (*env)->GetMethodID(env, beanClass,
                            "getMemoryUsed", "()J");
                        jmethodID getCap = (*env)->GetMethodID(env, beanClass,
                            "getTotalCapacity", "()J");

                        jstring nameStr = (jstring)(*env)->CallObjectMethod(env, bean, getName);
                        jlong bufCount = (*env)->CallLongMethod(env, bean, getCount);
                        jlong memUsed = (*env)->CallLongMethod(env, bean, getUsed);
                        jlong totalCap = (*env)->CallLongMethod(env, bean, getCap);

                        if (nameStr != NULL) {
                            const char *name = (*env)->GetStringUTFChars(env, nameStr, NULL);
                            if (name != NULL) {
                                off += protocol_encode_string(payload + off, name,
                                    (uint16_t)strlen(name));
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

                    (*env)->DeleteLocalRef(env, listClass);
                    (*env)->DeleteLocalRef(env, beanList);
                } else {
                    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                    off += protocol_encode_u8(payload + off, 0);
                }
            } else {
                off += protocol_encode_u8(payload + off, 0);
            }
            (*env)->DeleteLocalRef(env, bpClass);
        } else {
            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
            off += protocol_encode_u8(payload + off, 0);
        }

        /* ── ClassLoading MXBean ──────────────── */
        jmethodID getCL = (*env)->GetStaticMethodID(env, mf,
            "getClassLoadingMXBean", "()Ljava/lang/management/ClassLoadingMXBean;");
        if (getCL != NULL) {
            jobject clBean = (*env)->CallStaticObjectMethod(env, mf, getCL);
            if (clBean != NULL) {
                jclass clClass = (*env)->GetObjectClass(env, clBean);
                jmethodID getLoaded = (*env)->GetMethodID(env, clClass,
                    "getLoadedClassCount", "()I");
                jmethodID getTotal = (*env)->GetMethodID(env, clClass,
                    "getTotalLoadedClassCount", "()J");
                jmethodID getUnloaded = (*env)->GetMethodID(env, clClass,
                    "getUnloadedClassCount", "()J");

                jint loaded = getLoaded ? (*env)->CallIntMethod(env, clBean, getLoaded) : 0;
                jlong total = getTotal ? (*env)->CallLongMethod(env, clBean, getTotal) : 0;
                jlong unloaded = getUnloaded ? (*env)->CallLongMethod(env, clBean, getUnloaded) : 0;

                off += protocol_encode_i32(payload + off, loaded);
                off += protocol_encode_i64(payload + off, total);
                off += protocol_encode_i64(payload + off, unloaded);

                (*env)->DeleteLocalRef(env, clClass);
                (*env)->DeleteLocalRef(env, clBean);
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
        jmethodID getTM = (*env)->GetStaticMethodID(env, mf,
            "getThreadMXBean", "()Ljava/lang/management/ThreadMXBean;");
        if (getTM != NULL) {
            jobject tmBean = (*env)->CallStaticObjectMethod(env, mf, getTM);
            if (tmBean != NULL) {
                jclass tmClass = (*env)->GetObjectClass(env, tmBean);
                jmethodID getPeak = (*env)->GetMethodID(env, tmClass,
                    "getPeakThreadCount", "()I");
                jmethodID getDaemon = (*env)->GetMethodID(env, tmClass,
                    "getDaemonThreadCount", "()I");

                jint peak = getPeak ? (*env)->CallIntMethod(env, tmBean, getPeak) : 0;
                jint daemon = getDaemon ? (*env)->CallIntMethod(env, tmBean, getDaemon) : 0;

                /* Deadlock detection only every 30s (expensive O(n^2)) */
                int deadlockCount = -1; /* -1 = not checked this cycle */
                uint64_t now = jvmmon_time_millis();
                if (now - ft->last_deadlock_check >= (uint64_t)ft->deadlock_interval_ms) {
                    ft->last_deadlock_check = now;
                    deadlockCount = 0;
                    jmethodID findDeadlock = (*env)->GetMethodID(env, tmClass,
                        "findDeadlockedThreads", "()[J");
                    if (findDeadlock != NULL) {
                        jlongArray dlArr = (jlongArray)(*env)->CallObjectMethod(env, tmBean, findDeadlock);
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

                (*env)->DeleteLocalRef(env, tmClass);
                (*env)->DeleteLocalRef(env, tmBean);
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

        (*env)->DeleteLocalRef(env, mf);
    }

    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);

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
    ft->deadlock_interval_ms = 30000; /* deadlock check every 30s */
    ft->last_deadlock_check = 0;
    ft->running = 0;
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
    (void)level;
    (void)target;
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
