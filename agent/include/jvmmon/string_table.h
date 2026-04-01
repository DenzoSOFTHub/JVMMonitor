/*
 * JVMMonitor - String Table & JFR Stream Module
 * String intern table stats via DiagnosticCommand.
 * JFR event stream tapping on JDK 14+ if available.
 */
#ifndef JVMMON_STRING_TABLE_H
#define JVMMON_STRING_TABLE_H
#include "agent.h"

typedef struct string_table_tracker {
    jvmmon_agent_t  *agent;
    jvmmon_thread_t  poll_thread;
    volatile int32_t running;
    int              interval_ms;
} string_table_tracker_t;

string_table_tracker_t *string_table_tracker_create(jvmmon_agent_t *agent, int interval_ms);
void string_table_tracker_destroy(string_table_tracker_t *st);
int  string_table_tracker_activate(int level, const char *target, void *ctx);
int  string_table_tracker_deactivate(int level, void *ctx);
#endif
