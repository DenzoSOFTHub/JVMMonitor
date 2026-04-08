/*
 * JVMMonitor - Network Monitor Module
 * Reads per-socket TCP details and aggregate TCP counters from /proc.
 */
#ifndef JVMMON_NETWORK_MONITOR_H
#define JVMMON_NETWORK_MONITOR_H

#include "agent.h"

#define MAX_SOCKETS 512

/* TCP states (from kernel) */
#define TCP_ESTABLISHED  1
#define TCP_SYN_SENT     2
#define TCP_SYN_RECV     3
#define TCP_FIN_WAIT1    4
#define TCP_FIN_WAIT2    5
#define TCP_TIME_WAIT    6
#define TCP_CLOSE        7
#define TCP_CLOSE_WAIT   8
#define TCP_LAST_ACK     9
#define TCP_LISTEN      10
#define TCP_CLOSING     11

typedef struct network_monitor {
    jvmmon_agent_t  *agent;
    volatile int32_t running;
    int              interval_ms;
    jvmmon_thread_t  poll_thread;
} network_monitor_t;

network_monitor_t *network_monitor_create(jvmmon_agent_t *agent, int interval_ms);
void network_monitor_destroy(network_monitor_t *nm);

int  network_monitor_activate(int level, const char *target, void *ctx);
int  network_monitor_deactivate(int level, void *ctx);

#endif /* JVMMON_NETWORK_MONITOR_H */
