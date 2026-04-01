/*
 * JVMMonitor - Thread CPU Time Module
 * Per-thread CPU time via JVMTI GetThreadCpuTime. Identifies hot threads.
 */
#ifndef JVMMON_THREAD_CPU_H
#define JVMMON_THREAD_CPU_H
#include "agent.h"

typedef struct thread_cpu {
    jvmmon_agent_t  *agent;
    jvmmon_thread_t  poll_thread;
    volatile int32_t running;
    int              interval_ms;
} thread_cpu_t;

thread_cpu_t *thread_cpu_create(jvmmon_agent_t *agent, int interval_ms);
void thread_cpu_destroy(thread_cpu_t *tc);
int  thread_cpu_activate(int level, const char *target, void *ctx);
int  thread_cpu_deactivate(int level, void *ctx);
#endif
