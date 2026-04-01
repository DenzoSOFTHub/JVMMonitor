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
};

memory_monitor_t *memory_monitor_create(jvmmon_agent_t *agent, int interval_ms);
int  memory_monitor_start(memory_monitor_t *mm);
void memory_monitor_stop(memory_monitor_t *mm);
void memory_monitor_destroy(memory_monitor_t *mm);

#endif /* JVMMON_MEMORY_MONITOR_H */
