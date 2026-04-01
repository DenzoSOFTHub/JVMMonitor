/*
 * JVMMonitor - Classloader Leak Detection Module
 * Tracks classloaders, classes per loader, detects loaders that should be GC'd.
 */
#ifndef JVMMON_CLASSLOADER_TRACKER_H
#define JVMMON_CLASSLOADER_TRACKER_H
#include "agent.h"

typedef struct classloader_tracker {
    jvmmon_agent_t  *agent;
    jvmmon_thread_t  poll_thread;
    volatile int32_t running;
    int              interval_ms;
} classloader_tracker_t;

classloader_tracker_t *classloader_tracker_create(jvmmon_agent_t *agent, int interval_ms);
void classloader_tracker_destroy(classloader_tracker_t *ct);
int  classloader_tracker_activate(int level, const char *target, void *ctx);
int  classloader_tracker_deactivate(int level, void *ctx);
#endif
