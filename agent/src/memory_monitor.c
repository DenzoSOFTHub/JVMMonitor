/*
 * JVMMonitor - Memory Monitor Implementation
 * Polls heap/non-heap usage via JNI calls to Runtime and MemoryMXBean.
 */
#include "jvmmon/memory_monitor.h"
#include "jvmmon/alarm_engine.h"
#include "jvmmon/protocol.h"
#include <string.h>

static void collect_memory_snapshot(memory_monitor_t *mm) {
    jvmmon_agent_t *agent = mm->agent;
    JNIEnv *env;
    jlong heap_used = 0, heap_max = 0;
    jlong non_heap_used = 0, non_heap_max = 0;

    if ((*agent->jvm)->GetEnv(agent->jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        return;
    }

    /* Get heap info via Runtime */
    jclass runtime_class = (*env)->FindClass(env, "java/lang/Runtime");
    if (runtime_class != NULL) {
        jmethodID get_runtime = (*env)->GetStaticMethodID(env, runtime_class,
            "getRuntime", "()Ljava/lang/Runtime;");
        if (get_runtime != NULL) {
            jobject runtime = (*env)->CallStaticObjectMethod(env, runtime_class, get_runtime);
            if (runtime != NULL) {
                jmethodID total_mem = (*env)->GetMethodID(env, runtime_class, "totalMemory", "()J");
                jmethodID free_mem = (*env)->GetMethodID(env, runtime_class, "freeMemory", "()J");
                jmethodID max_mem = (*env)->GetMethodID(env, runtime_class, "maxMemory", "()J");

                if (total_mem && free_mem && max_mem) {
                    jlong total = (*env)->CallLongMethod(env, runtime, total_mem);
                    jlong free_m = (*env)->CallLongMethod(env, runtime, free_mem);
                    heap_max = (*env)->CallLongMethod(env, runtime, max_mem);
                    heap_used = total - free_m;
                }
                (*env)->DeleteLocalRef(env, runtime);
            }
        }
        (*env)->DeleteLocalRef(env, runtime_class);
    }

    /* Try to get MemoryMXBean for non-heap */
    jclass mf_class = (*env)->FindClass(env, "java/lang/management/ManagementFactory");
    if (mf_class != NULL) {
        jmethodID get_mem_bean = (*env)->GetStaticMethodID(env, mf_class,
            "getMemoryMXBean", "()Ljava/lang/management/MemoryMXBean;");
        if (get_mem_bean != NULL) {
            jobject mem_bean = (*env)->CallStaticObjectMethod(env, mf_class, get_mem_bean);
            if (mem_bean != NULL) {
                jclass bean_class = (*env)->GetObjectClass(env, mem_bean);
                jmethodID get_non_heap = (*env)->GetMethodID(env, bean_class,
                    "getNonHeapMemoryUsage", "()Ljava/lang/management/MemoryUsage;");
                if (get_non_heap != NULL) {
                    jobject usage = (*env)->CallObjectMethod(env, mem_bean, get_non_heap);
                    if (usage != NULL) {
                        jclass usage_class = (*env)->GetObjectClass(env, usage);
                        jmethodID get_used = (*env)->GetMethodID(env, usage_class, "getUsed", "()J");
                        jmethodID get_max2 = (*env)->GetMethodID(env, usage_class, "getMax", "()J");
                        if (get_used) non_heap_used = (*env)->CallLongMethod(env, usage, get_used);
                        if (get_max2) non_heap_max = (*env)->CallLongMethod(env, usage, get_max2);
                        (*env)->DeleteLocalRef(env, usage_class);
                        (*env)->DeleteLocalRef(env, usage);
                    }
                }
                (*env)->DeleteLocalRef(env, bean_class);
                (*env)->DeleteLocalRef(env, mem_bean);
            }
        }
        (*env)->DeleteLocalRef(env, mf_class);
    }

    /* Clear any pending exceptions from JNI calls */
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }

    /* Encode memory snapshot
     * Payload: timestamp(8) + heap_used(8) + heap_max(8) + non_heap_used(8) + non_heap_max(8)
     */
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

    /* Attach this thread to the JVM */
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
    mm->interval_ms = interval_ms > 0 ? interval_ms : 1000;
    mm->running = 0;
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
    if (mm != NULL) jvmmon_free(mm);
}
