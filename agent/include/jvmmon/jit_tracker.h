/*
 * JVMMonitor - JIT Compiler Events Module
 * Tracks: method compilation, deoptimization, code cache usage.
 */
#ifndef JVMMON_JIT_TRACKER_H
#define JVMMON_JIT_TRACKER_H

#include "agent.h"

#define JIT_EVENT_COMPILED   1
#define JIT_EVENT_UNLOADED   2

typedef struct jit_tracker {
    jvmmon_agent_t  *agent;
    volatile int32_t active;
    volatile int32_t compiled_count;
    volatile int32_t deopt_count;
    volatile int32_t in_initial_burst; /* 1 during GenerateEvents replay */
    volatile int32_t burst_count;      /* events during burst */
} jit_tracker_t;

jit_tracker_t *jit_tracker_create(jvmmon_agent_t *agent);
void jit_tracker_destroy(jit_tracker_t *jt);

int  jit_tracker_activate(int level, const char *target, void *ctx);
int  jit_tracker_deactivate(int level, void *ctx);

/* JVMTI callbacks */
void jit_tracker_on_compiled_method_load(jit_tracker_t *jt,
        jmethodID method, jint code_size, const void *code_addr);
void jit_tracker_on_compiled_method_unload(jit_tracker_t *jt,
        jmethodID method, const void *code_addr);

#endif /* JVMMON_JIT_TRACKER_H */
