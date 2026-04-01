/*
 * JVMMonitor - GC Collector Details Module
 * GarbageCollectorMXBean: algorithm name, per-collector count/time, pool associations.
 */
#ifndef JVMMON_GC_DETAIL_H
#define JVMMON_GC_DETAIL_H
#include "agent.h"

typedef struct gc_detail {
    jvmmon_agent_t  *agent;
    jvmmon_thread_t  poll_thread;
    volatile int32_t running;
    int              interval_ms;
} gc_detail_t;

gc_detail_t *gc_detail_create(jvmmon_agent_t *agent, int interval_ms);
void gc_detail_destroy(gc_detail_t *gd);
int  gc_detail_activate(int level, const char *target, void *ctx);
int  gc_detail_deactivate(int level, void *ctx);
#endif
