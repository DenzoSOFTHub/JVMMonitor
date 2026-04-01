/*
 * JVMMonitor - Native Memory Tracking Module
 * Uses DiagnosticCommandMBean to invoke VM.native_memory (requires -XX:NativeMemoryTracking=summary).
 */
#ifndef JVMMON_NATIVE_MEM_H
#define JVMMON_NATIVE_MEM_H

#include "agent.h"

typedef struct native_mem_tracker {
    jvmmon_agent_t  *agent;
    jvmmon_thread_t  poll_thread;
    volatile int32_t running;
    int              interval_ms;
} native_mem_tracker_t;

native_mem_tracker_t *native_mem_tracker_create(jvmmon_agent_t *agent, int interval_ms);
void native_mem_tracker_destroy(native_mem_tracker_t *nm);
int  native_mem_tracker_activate(int level, const char *target, void *ctx);
int  native_mem_tracker_deactivate(int level, void *ctx);

#endif
