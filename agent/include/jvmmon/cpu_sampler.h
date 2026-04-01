/*
 * JVMMonitor - CPU Sampler (AsyncGetCallTrace)
 * Level 0 CORE: always-on sampling via timer thread + SIGPROF/SuspendThread.
 */
#ifndef JVMMON_CPU_SAMPLER_H
#define JVMMON_CPU_SAMPLER_H

#include "agent.h"

#define CPU_SAMPLER_MAX_FRAMES 128

struct cpu_sampler {
    jvmmon_agent_t  *agent;
    jvmmon_thread_t  timer_thread;
    volatile int32_t running;
    int              interval_ms;
};

cpu_sampler_t *cpu_sampler_create(jvmmon_agent_t *agent, int interval_ms);
int  cpu_sampler_start(cpu_sampler_t *cs);
void cpu_sampler_stop(cpu_sampler_t *cs);
void cpu_sampler_destroy(cpu_sampler_t *cs);

#endif /* JVMMON_CPU_SAMPLER_H */
