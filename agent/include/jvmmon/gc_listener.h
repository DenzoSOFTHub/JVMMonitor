/*
 * JVMMonitor - GC Event Listener
 * Level 0 CORE: GC start/finish callbacks with timing.
 */
#ifndef JVMMON_GC_LISTENER_H
#define JVMMON_GC_LISTENER_H

#include "agent.h"

struct gc_listener {
    jvmmon_agent_t *agent;
    uint64_t        gc_start_nanos;
    uint64_t        last_gc_end_nanos;
    int32_t         gc_count;
    int32_t         full_gc_count;
};

gc_listener_t *gc_listener_create(jvmmon_agent_t *agent);
void gc_listener_destroy(gc_listener_t *gl);

/* Called by JVMTI callbacks (set up in agent.c) */
void gc_listener_on_gc_start(gc_listener_t *gl);
void gc_listener_on_gc_finish(gc_listener_t *gl);

#endif /* JVMMON_GC_LISTENER_H */
