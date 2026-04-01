/*
 * JVMMonitor - Exception Tracker Module
 * Tracks exceptions thrown/caught via JVMTI events.
 * Rate-limited: max MAX_SEND_PER_SEC full events/s, samples beyond that.
 */
#ifndef JVMMON_EXCEPTION_TRACKER_H
#define JVMMON_EXCEPTION_TRACKER_H

#include "agent.h"

#define EXCEPTION_MAX_SEND_PER_SEC 100

typedef struct exception_tracker {
    jvmmon_agent_t  *agent;
    volatile int32_t active;
    volatile int32_t exception_count;    /* total thrown (always accurate) */
    volatile int32_t caught_count;       /* total caught */
    volatile int32_t sent_count;         /* sent to client (rate limited) */
    volatile int32_t dropped_count;      /* skipped due to rate limit */
    /* Rate limiter state */
    uint64_t         window_start_ms;
    int32_t          window_sent;
} exception_tracker_t;

exception_tracker_t *exception_tracker_create(jvmmon_agent_t *agent);
void exception_tracker_destroy(exception_tracker_t *et);

int  exception_tracker_activate(int level, const char *target, void *ctx);
int  exception_tracker_deactivate(int level, void *ctx);

void exception_tracker_on_exception(exception_tracker_t *et, JNIEnv *jni,
        jthread thread, jmethodID method, jlocation location,
        jobject exception, jmethodID catch_method, jlocation catch_location);
void exception_tracker_on_exception_catch(exception_tracker_t *et, JNIEnv *jni,
        jthread thread, jmethodID method, jlocation location, jobject exception);

#endif
