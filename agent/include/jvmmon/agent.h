/*
 * JVMMonitor - Agent Core
 * Top-level agent state and lifecycle.
 */
#ifndef JVMMON_AGENT_H
#define JVMMON_AGENT_H

#include <jvmti.h>
#include "platform.h"
#include "ring_buffer.h"
#include "transport.h"
#include "protocol.h"
#include "asgct.h"

/* Forward declarations for module headers */
typedef struct cpu_sampler    cpu_sampler_t;
typedef struct gc_listener    gc_listener_t;
typedef struct thread_monitor thread_monitor_t;
typedef struct memory_monitor memory_monitor_t;
typedef struct class_analyzer class_analyzer_t;
typedef struct alarm_engine   alarm_engine_t;
typedef struct module_registry    module_registry_t;
typedef struct jmx_reader            jmx_reader_t;
typedef struct exception_tracker     exception_tracker_t;
typedef struct os_metrics            os_metrics_t;
typedef struct jit_tracker           jit_tracker_t;
typedef struct class_histo           class_histo_t;
typedef struct finalizer_tracker     finalizer_tracker_t;
typedef struct safepoint_tracker     safepoint_tracker_t;
typedef struct native_mem_tracker    native_mem_tracker_t;
typedef struct gc_detail             gc_detail_t;
typedef struct thread_cpu            thread_cpu_t;
typedef struct classloader_tracker   classloader_tracker_t;
typedef struct string_table_tracker  string_table_tracker_t;
typedef struct network_monitor       network_monitor_t;
typedef struct lock_monitor          lock_monitor_t;
typedef struct crash_handler         crash_handler_t;

typedef struct {
    JavaVM              *jvm;
    jvmtiEnv            *jvmti;
    JNIEnv              *jni;
    jrawMonitorID        lock;
    ring_buffer_t       *ring;
    transport_ctx_t      transport;
    AsyncGetCallTrace_fn asgct;
    volatile int32_t     running;
    int                  is_onload;      /* 1 = Agent_OnLoad, 0 = Agent_OnAttach */
    int                  jvm_version;    /* e.g., 6, 7, 8, 11, 17, 21 */

    /* Core modules */
    cpu_sampler_t       *cpu_sampler;
    gc_listener_t       *gc_listener;
    thread_monitor_t    *thread_monitor;
    memory_monitor_t    *memory_monitor;
    class_analyzer_t    *class_analyzer;
    alarm_engine_t      *alarm_engine;
    module_registry_t   *module_registry;
    jmx_reader_t        *jmx_reader;
    exception_tracker_t    *exception_tracker;
    os_metrics_t           *os_metrics;
    jit_tracker_t          *jit_tracker;
    class_histo_t          *class_histo;
    finalizer_tracker_t    *finalizer_tracker;
    safepoint_tracker_t    *safepoint_tracker;
    native_mem_tracker_t   *native_mem_tracker;
    gc_detail_t            *gc_detail;
    thread_cpu_t           *thread_cpu;
    classloader_tracker_t  *classloader_tracker;
    string_table_tracker_t *string_table_tracker;
    network_monitor_t      *network_monitor;
    lock_monitor_t         *lock_monitor;
    crash_handler_t        *crash_handler;

    /* Config */
    int                  collector_port;    /* port agent listens on */
    int                  sample_interval_ms;  /* CPU sampling interval (default 10) */
    int                  monitor_interval_ms; /* polling interval for memory/thread (default 1000) */
    int                  log_level;           /* JVMMON_LOG_INFO default */
    char                 log_file[512];       /* log file path */
} jvmmon_agent_t;

/* Global agent instance */
jvmmon_agent_t *agent_get(void);

/* Send a pre-encoded message via the ring buffer */
int agent_send_message(uint8_t msg_type, const uint8_t *payload, uint32_t payload_len);

/* Resolve method info from jmethodID */
int agent_resolve_method(jmethodID method, char *class_buf, int class_len,
                         char *method_buf, int method_len, char *sig_buf, int sig_len);

#endif /* JVMMON_AGENT_H */
