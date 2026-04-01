/*
 * JVMMonitor - OS Metrics Module
 * Reads OS-level process metrics: FD count, RSS, TCP states, context switches.
 * Linux: /proc/self/*   Windows: WinAPI
 */
#ifndef JVMMON_OS_METRICS_H
#define JVMMON_OS_METRICS_H

#include "agent.h"

typedef struct os_metrics {
    jvmmon_agent_t  *agent;
    jvmmon_thread_t  poll_thread;
    volatile int32_t running;
    int              interval_ms;
} os_metrics_t;

os_metrics_t *os_metrics_create(jvmmon_agent_t *agent, int interval_ms);
int  os_metrics_activate(int level, const char *target, void *ctx);
int  os_metrics_deactivate(int level, void *ctx);
void os_metrics_destroy(os_metrics_t *om);

#endif /* JVMMON_OS_METRICS_H */
