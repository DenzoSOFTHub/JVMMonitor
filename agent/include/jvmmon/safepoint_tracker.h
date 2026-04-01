/*
 * JVMMonitor - Safepoint Timing Module
 * Reads safepoint stats via HotSpot internal MBean or DiagnosticCommand.
 */
#ifndef JVMMON_SAFEPOINT_TRACKER_H
#define JVMMON_SAFEPOINT_TRACKER_H

#include "agent.h"

typedef struct safepoint_tracker {
    jvmmon_agent_t  *agent;
    jvmmon_thread_t  poll_thread;
    volatile int32_t running;
    int              interval_ms;
} safepoint_tracker_t;

safepoint_tracker_t *safepoint_tracker_create(jvmmon_agent_t *agent, int interval_ms);
void safepoint_tracker_destroy(safepoint_tracker_t *st);
int  safepoint_tracker_activate(int level, const char *target, void *ctx);
int  safepoint_tracker_deactivate(int level, void *ctx);

#endif
