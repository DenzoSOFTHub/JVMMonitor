/*
 * JVMMonitor - AsyncGetCallTrace (unofficial HotSpot API)
 * Resolved at runtime via dlsym. Available since JDK 6.
 */
#ifndef JVMMON_ASGCT_H
#define JVMMON_ASGCT_H

#include <jvmti.h>

typedef struct {
    jint      lineno;
    jmethodID method_id;
} ASGCT_CallFrame;

typedef struct {
    JNIEnv         *env_id;
    jint            num_frames;
    ASGCT_CallFrame *frames;
} ASGCT_CallTrace;

/* Error codes returned in num_frames */
#define ASGCT_ERR_THREAD_EXIT      -1
#define ASGCT_ERR_GC_ACTIVE        -2
#define ASGCT_ERR_UNKNOWN_NOT_JAVA -3
#define ASGCT_ERR_NOT_WALKABLE     -4
#define ASGCT_ERR_UNKNOWN_JAVA     -5
#define ASGCT_ERR_NO_CLASS_LOAD    -6
#define ASGCT_ERR_DEOPT            -7
#define ASGCT_ERR_SAFEPOINT        -8

typedef void (*AsyncGetCallTrace_fn)(ASGCT_CallTrace *trace, jint depth, void *ucontext);

#endif /* JVMMON_ASGCT_H */
