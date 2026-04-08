/*
 * JVMMonitor - Memory Monitor
 * Level 0 CORE: periodic memory pool snapshots.
 */
#ifndef JVMMON_MEMORY_MONITOR_H
#define JVMMON_MEMORY_MONITOR_H

#include "agent.h"

struct memory_monitor {
    jvmmon_agent_t  *agent;
    jvmmon_thread_t  poll_thread;
    volatile int32_t running;
    int              interval_ms;
    /* JNI method ID cache (resolved once, stable for class lifetime) */
    jmethodID  get_runtime;
    jmethodID  total_mem;
    jmethodID  free_mem;
    jmethodID  max_mem;
    jmethodID  get_mem_bean;
    jmethodID  get_non_heap;
    jmethodID  get_used;
    jmethodID  get_max2;
    jclass     runtime_class_g; /* global ref */
    jclass     mf_class_g;      /* global ref */
    int        cached;
};

memory_monitor_t *memory_monitor_create(jvmmon_agent_t *agent, int interval_ms);
int  memory_monitor_start(memory_monitor_t *mm);
void memory_monitor_stop(memory_monitor_t *mm);
void memory_monitor_destroy(memory_monitor_t *mm);

#endif /* JVMMON_MEMORY_MONITOR_H */
