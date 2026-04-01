/*
 * JVMMonitor - Crash Handler
 * Automatically saves diagnostic data when the JVM crashes or shuts down abnormally.
 * Uses JVMTI VMDeath callback + signal handlers for SIGSEGV/SIGABRT.
 * Saves: thread dump, heap summary, last events, GC state, system info.
 */
#ifndef JVMMON_CRASH_HANDLER_H
#define JVMMON_CRASH_HANDLER_H

#include "agent.h"

#define CRASH_DUMP_MAX_SIZE  (1024 * 1024)  /* 1 MB max dump */

typedef struct crash_handler {
    jvmmon_agent_t  *agent;
    char             dump_path[512];
    volatile int32_t active;
} crash_handler_t;

crash_handler_t *crash_handler_create(jvmmon_agent_t *agent, const char *dump_dir);
void crash_handler_destroy(crash_handler_t *ch);

/* Called by JVMTI VMDeath callback */
void crash_handler_on_vm_death(crash_handler_t *ch, JNIEnv *jni);

/* Called by signal handler (SIGSEGV, SIGABRT) */
void crash_handler_on_signal(crash_handler_t *ch, int signum);

/* Manual trigger */
void crash_handler_save_dump(crash_handler_t *ch, const char *reason);

#endif /* JVMMON_CRASH_HANDLER_H */
