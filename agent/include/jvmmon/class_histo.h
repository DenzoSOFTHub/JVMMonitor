/*
 * JVMMonitor - Class Histogram Module
 * Heap iteration to count live objects per class. Detects leak candidates.
 * WARNING: heap iteration is stop-the-world. Use sparingly.
 */
#ifndef JVMMON_CLASS_HISTO_H
#define JVMMON_CLASS_HISTO_H

#include "agent.h"

typedef struct class_histo {
    jvmmon_agent_t *agent;
} class_histo_t;

class_histo_t *class_histo_create(jvmmon_agent_t *agent);
void class_histo_destroy(class_histo_t *ch);

int  class_histo_activate(int level, const char *target, void *ctx);
int  class_histo_deactivate(int level, void *ctx);

/* Take a single snapshot and send to client (called on demand) */
void class_histo_snapshot(class_histo_t *ch);

#endif /* JVMMON_CLASS_HISTO_H */
