/*
 * JVMMonitor - Finalizer & Buffer Pool Tracker
 * Monitors: direct ByteBuffer pools, classloading stats, peak threads.
 * Deadlock detection runs at reduced frequency (every 30s).
 */
#ifndef JVMMON_FINALIZER_TRACKER_H
#define JVMMON_FINALIZER_TRACKER_H

#include "agent.h"

typedef struct finalizer_tracker {
    jvmmon_agent_t  *agent;
    jvmmon_thread_t  poll_thread;
    volatile int32_t running;
    int              interval_ms;
    int              deadlock_interval_ms;
    uint64_t         last_deadlock_check;
    /* JNI cache (resolved once, reused every poll) */
    jclass     mf_global;
    jmethodID  getPlatformBeans;
    jmethodID  getCL;
    jmethodID  getTM;
    jclass     bp_class_global;
    jmethodID  cl_getLoaded;
    jmethodID  cl_getTotal;
    jmethodID  cl_getUnloaded;
    jmethodID  tm_getPeak;
    jmethodID  tm_getDaemon;
    jmethodID  tm_findDeadlock;
    int        jni_cached;
} finalizer_tracker_t;

finalizer_tracker_t *finalizer_tracker_create(jvmmon_agent_t *agent, int interval_ms);
void finalizer_tracker_destroy(finalizer_tracker_t *ft);

int  finalizer_tracker_activate(int level, const char *target, void *ctx);
int  finalizer_tracker_deactivate(int level, void *ctx);

#endif
