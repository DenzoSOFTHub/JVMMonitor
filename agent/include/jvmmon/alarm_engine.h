/*
 * JVMMonitor - Alarm Engine
 * Level 0 CORE: threshold-based alarms on metrics.
 */
#ifndef JVMMON_ALARM_ENGINE_H
#define JVMMON_ALARM_ENGINE_H

#include "agent.h"

#define ALARM_TYPE_GC_FREQUENCY   1
#define ALARM_TYPE_GC_PAUSE       2
#define ALARM_TYPE_HEAP_USAGE     3
#define ALARM_TYPE_HEAP_GROWTH    4
#define ALARM_TYPE_THREAD_BLOCKED 5
#define ALARM_TYPE_CPU_HIGH       6

#define MAX_ALARM_RULES 32

typedef struct {
    int      alarm_type;
    int      severity;      /* JVMMON_ALARM_INFO/WARNING/CRITICAL */
    double   threshold;
    double   current_value;
    int      active;        /* is this alarm currently firing? */
    uint64_t last_fired;
    char     message[256];
} alarm_rule_t;

struct alarm_engine {
    jvmmon_agent_t *agent;
    alarm_rule_t    rules[MAX_ALARM_RULES];
    int             rule_count;
    jvmmon_mutex_t  lock;
};

alarm_engine_t *alarm_engine_create(jvmmon_agent_t *agent);
void alarm_engine_destroy(alarm_engine_t *ae);

/* Add default rules */
void alarm_engine_add_defaults(alarm_engine_t *ae);

/* Update a metric value, fires alarm if threshold crossed */
void alarm_engine_update(alarm_engine_t *ae, int alarm_type, double value);

#endif /* JVMMON_ALARM_ENGINE_H */
