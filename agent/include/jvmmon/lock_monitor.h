/*
 * JVMMonitor - Lock / Monitor Contention Module
 * Tracks JVMTI MonitorContendedEnter/Exit events to identify
 * lock contention, wait times, and deadlock conditions.
 */
#ifndef JVMMON_LOCK_MONITOR_H
#define JVMMON_LOCK_MONITOR_H

#include "agent.h"

#define LOCK_EVENT_CONTENDED_ENTER  1  /* Thread waiting to acquire lock */
#define LOCK_EVENT_CONTENDED_EXIT   2  /* Thread acquired after waiting */
#define LOCK_EVENT_WAIT_ENTER       3  /* Thread entered Object.wait() */
#define LOCK_EVENT_WAIT_EXIT        4  /* Thread returned from Object.wait() */
#define LOCK_EVENT_DEADLOCK         5  /* Deadlock detected */

typedef struct lock_monitor {
    jvmmon_agent_t  *agent;
    volatile int32_t active;
    volatile int32_t contention_count;
    volatile int32_t deadlock_count;
} lock_monitor_t;

lock_monitor_t *lock_monitor_create(jvmmon_agent_t *agent);
void lock_monitor_destroy(lock_monitor_t *lm);

int  lock_monitor_activate(int level, const char *target, void *ctx);
int  lock_monitor_deactivate(int level, void *ctx);

/* JVMTI callbacks */
void lock_monitor_on_contended_enter(lock_monitor_t *lm, JNIEnv *jni,
        jthread thread, jobject object);
void lock_monitor_on_contended_exit(lock_monitor_t *lm, JNIEnv *jni,
        jthread thread, jobject object);

#endif /* JVMMON_LOCK_MONITOR_H */
