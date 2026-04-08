/*
 * JVMMonitor - Memory Monitor Implementation
 * Polls heap/non-heap usage via JNI calls to Runtime and MemoryMXBean.
 * JNI class/method IDs are cached after first resolution for efficiency.
 */
#include "jvmmon/memory_monitor.h"
#include "jvmmon/alarm_engine.h"
#include "jvmmon/protocol.h"
#include <string.h>

/* Resolve and cache JNI class refs and method IDs on first call.
 * jmethodID is stable for the lifetime of the class. jclass is kept as global ref. */
static int ensure_cached(memory_monitor_t *mm, JNIEnv *env) {
    if (mm->cached) return 1;

    jclass rc = (*env)->FindClass(env, "java/lang/Runtime");
    if (rc == NULL) { (*env)->ExceptionClear(env); return 0; }
    mm->runtime_class_g = (jclass)(*env)->NewGlobalRef(env, rc);
    (*env)->DeleteLocalRef(env, rc);

    mm->get_runtime = (*env)->GetStaticMethodID(env, mm->runtime_class_g,
        "getRuntime", "()Ljava/lang/Runtime;");
    mm->total_mem = (*env)->GetMethodID(env, mm->runtime_class_g, "totalMemory", "()J");
    mm->free_mem  = (*env)->GetMethodID(env, mm->runtime_class_g, "freeMemory", "()J");
    mm->max_mem   = (*env)->GetMethodID(env, mm->runtime_class_g, "maxMemory", "()J");
    if (!mm->get_runtime || !mm->total_mem || !mm->free_mem || !mm->max_mem) {
        (*env)->ExceptionClear(env);
        return 0;
    }

    jclass mf = (*env)->FindClass(env, "java/lang/management/ManagementFactory");
    if (mf != NULL) {
        mm->mf_class_g = (jclass)(*env)->NewGlobalRef(env, mf);
        (*env)->DeleteLocalRef(env, mf);
        mm->get_mem_bean = (*env)->GetStaticMethodID(env, mm->mf_class_g,
            "getMemoryMXBean", "()Ljava/lang/management/MemoryMXBean;");
    }
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);

    mm->cached = 1;
    return 1;
}

static void collect_memory_snapshot(memory_monitor_t *mm) {
    jvmmon_agent_t *agent = mm->agent;
    JNIEnv *env;
    jlong heap_used = 0, heap_max = 0;
    jlong non_heap_used = 0, non_heap_max = 0;

    if ((*agent->jvm)->GetEnv(agent->jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        return;
    }

    if (!ensure_cached(mm, env)) return;

    if ((*env)->PushLocalFrame(env, 16) < 0) return;

    /* Get heap info via Runtime (cached method IDs) */
    jobject runtime = (*env)->CallStaticObjectMethod(env, mm->runtime_class_g, mm->get_runtime);
    if (runtime != NULL) {
        jlong total = (*env)->CallLongMethod(env, runtime, mm->total_mem);
        jlong free_m = (*env)->CallLongMethod(env, runtime, mm->free_mem);
        heap_max = (*env)->CallLongMethod(env, runtime, mm->max_mem);
        heap_used = total - free_m;
    }

    /* Try to get non-heap via MemoryMXBean */
    if (mm->mf_class_g != NULL && mm->get_mem_bean != NULL) {
        jobject mem_bean = (*env)->CallStaticObjectMethod(env, mm->mf_class_g, mm->get_mem_bean);
        if (mem_bean != NULL) {
            jclass bean_class = (*env)->GetObjectClass(env, mem_bean);
            /* Resolve non-heap method ID on first use */
            if (mm->get_non_heap == NULL) {
                mm->get_non_heap = (*env)->GetMethodID(env, bean_class,
                    "getNonHeapMemoryUsage", "()Ljava/lang/management/MemoryUsage;");
            }
            if (mm->get_non_heap != NULL) {
                jobject usage = (*env)->CallObjectMethod(env, mem_bean, mm->get_non_heap);
                if (usage != NULL) {
                    jclass usage_class = (*env)->GetObjectClass(env, usage);
                    if (mm->get_used == NULL)
                        mm->get_used = (*env)->GetMethodID(env, usage_class, "getUsed", "()J");
                    if (mm->get_max2 == NULL)
                        mm->get_max2 = (*env)->GetMethodID(env, usage_class, "getMax", "()J");
                    if (mm->get_used) non_heap_used = (*env)->CallLongMethod(env, usage, mm->get_used);
                    if (mm->get_max2) non_heap_max = (*env)->CallLongMethod(env, usage, mm->get_max2);
                }
            }
        }
    }

    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->PopLocalFrame(env, NULL);

    /* Encode memory snapshot */
    uint8_t payload[64];
    int off = 0;
    off += protocol_encode_u64(payload + off, jvmmon_time_millis());
    off += protocol_encode_i64(payload + off, (int64_t)heap_used);
    off += protocol_encode_i64(payload + off, (int64_t)heap_max);
    off += protocol_encode_i64(payload + off, (int64_t)non_heap_used);
    off += protocol_encode_i64(payload + off, (int64_t)non_heap_max);

    agent_send_message(JVMMON_MSG_MEMORY_SNAPSHOT, payload, (uint32_t)off);

    /* Feed alarm engine */
    if (agent->alarm_engine && heap_max > 0) {
        double usage_pct = (double)heap_used / (double)heap_max * 100.0;
        alarm_engine_update(agent->alarm_engine, ALARM_TYPE_HEAP_USAGE, usage_pct);
    }
}

static void *poll_thread_fn(void *arg) {
    memory_monitor_t *mm = (memory_monitor_t *)arg;
    JNIEnv *env;

    if ((*mm->agent->jvm)->AttachCurrentThread(mm->agent->jvm, (void **)&env, NULL) != JNI_OK) {
        return NULL;
    }

    while (jvmmon_atomic_load(&mm->running)) {
        if (!jvmmon_atomic_load(&mm->agent->running)) break;
        collect_memory_snapshot(mm);
        jvmmon_sleep_ms(mm->interval_ms);
    }

    (*mm->agent->jvm)->DetachCurrentThread(mm->agent->jvm);
    return NULL;
}

memory_monitor_t *memory_monitor_create(jvmmon_agent_t *agent, int interval_ms) {
    memory_monitor_t *mm = (memory_monitor_t *)jvmmon_calloc(1, sizeof(memory_monitor_t));
    if (mm == NULL) return NULL;
    mm->agent = agent;
    mm->interval_ms = interval_ms > 0 ? interval_ms : 2000;
    mm->running = 0;
    mm->cached = 0;
    return mm;
}

int memory_monitor_start(memory_monitor_t *mm) {
    jvmmon_atomic_store(&mm->running, 1);
    return jvmmon_thread_create(&mm->poll_thread, poll_thread_fn, mm);
}

void memory_monitor_stop(memory_monitor_t *mm) {
    jvmmon_atomic_store(&mm->running, 0);
    jvmmon_thread_join(&mm->poll_thread);
}

void memory_monitor_destroy(memory_monitor_t *mm) {
    if (mm != NULL) {
        /* Note: global refs (runtime_class_g, mf_class_g) are leaked intentionally —
         * they're small and the JVM is shutting down when this runs. Attempting
         * DeleteGlobalRef here requires a JNIEnv which may not be available. */
        jvmmon_free(mm);
    }
}
