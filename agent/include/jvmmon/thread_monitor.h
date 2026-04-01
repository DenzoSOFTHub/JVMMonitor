/*
 * JVMMonitor - Thread Monitor
 * Level 0 CORE: periodic thread state snapshots + thread start/end events.
 */
#ifndef JVMMON_THREAD_MONITOR_H
#define JVMMON_THREAD_MONITOR_H

#include "agent.h"

#define THREAD_EVENT_START    1
#define THREAD_EVENT_END      2
#define THREAD_EVENT_SNAPSHOT 3

struct thread_monitor {
    jvmmon_agent_t  *agent;
    jvmmon_thread_t  poll_thread;
    volatile int32_t running;
    int              interval_ms;
};

thread_monitor_t *thread_monitor_create(jvmmon_agent_t *agent, int interval_ms);
int  thread_monitor_start(thread_monitor_t *tm);
void thread_monitor_stop(thread_monitor_t *tm);
void thread_monitor_destroy(thread_monitor_t *tm);

/* Called by JVMTI callbacks */
void thread_monitor_on_thread_start(thread_monitor_t *tm, JNIEnv *jni, jthread thread);
void thread_monitor_on_thread_end(thread_monitor_t *tm, JNIEnv *jni, jthread thread);

#endif /* JVMMON_THREAD_MONITOR_H */
