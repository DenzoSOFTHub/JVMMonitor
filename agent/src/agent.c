/*
 * JVMMonitor Agent v1.0.0 - Production Entry Point
 * Agent_OnLoad / Agent_OnAttach / Agent_OnUnload
 *
 * Features:
 * - JVMTI-based profiling and monitoring
 * - Lock-free ring buffer for data transport
 * - TCP server for collector connections
 * - Crash handler for automatic diagnostic dump
 * - Progressive module system (levels 0-3)
 * - Network monitoring, lock tracking, CPU profiling
 *
 * Supported JVM: Java 1.6+
 * Platforms: Linux, Windows
 */
#include "jvmmon/agent.h"
#include "jvmmon/log.h"
#include "jvmmon/cpu_sampler.h"
#include "jvmmon/gc_listener.h"
#include "jvmmon/thread_monitor.h"
#include "jvmmon/memory_monitor.h"
#include "jvmmon/class_analyzer.h"
#include "jvmmon/alarm_engine.h"
#include "jvmmon/module_registry.h"
#include "jvmmon/jmx_reader.h"
#include "jvmmon/exception_tracker.h"
#include "jvmmon/os_metrics.h"
#include "jvmmon/jit_tracker.h"
#include "jvmmon/class_histo.h"
#include "jvmmon/finalizer_tracker.h"
#include "jvmmon/safepoint_tracker.h"
#include "jvmmon/native_mem.h"
#include "jvmmon/gc_detail.h"
#include "jvmmon/thread_cpu.h"
#include "jvmmon/classloader_tracker.h"
#include "jvmmon/string_table.h"
#include "jvmmon/network_monitor.h"
#include "jvmmon/lock_monitor.h"
#include "jvmmon/crash_handler.h"
#include <string.h>

#define JVMMON_VERSION_STRING "1.0.0"

static jvmmon_agent_t g_agent;

jvmmon_agent_t *agent_get(void) {
    return &g_agent;
}

/* ── Message sending via ring buffer ────────────────── */

int agent_send_message(uint8_t msg_type, const uint8_t *payload, uint32_t payload_len) {
    uint8_t buf[JVMMON_HEADER_SIZE + JVMMON_MAX_PAYLOAD];

    if (!jvmmon_atomic_load(&g_agent.running)) return -1;
    if (payload_len > JVMMON_MAX_PAYLOAD) return -1;

    protocol_encode_header(buf, msg_type, payload_len);
    if (payload_len > 0) {
        memcpy(buf + JVMMON_HEADER_SIZE, payload, payload_len);
    }

    return ring_buffer_push(g_agent.ring, buf,
                            (uint16_t)(JVMMON_HEADER_SIZE + payload_len));
}

/* ── Method resolution ──────────────────────────────── */

int agent_resolve_method(jmethodID method, char *class_buf, int class_len,
                         char *method_buf, int method_len, char *sig_buf, int sig_len) {
    jvmtiEnv *jvmti = g_agent.jvmti;
    if (jvmti == NULL) return -1;

    jclass klass;
    char *mname = NULL, *msig = NULL, *csig = NULL;
    jvmtiError err;

    err = (*jvmti)->GetMethodName(jvmti, method, &mname, &msig, NULL);
    if (err != JVMTI_ERROR_NONE) return -1;

    err = (*jvmti)->GetMethodDeclaringClass(jvmti, method, &klass);
    if (err != JVMTI_ERROR_NONE) {
        if (mname) (*jvmti)->Deallocate(jvmti, (unsigned char *)mname);
        if (msig) (*jvmti)->Deallocate(jvmti, (unsigned char *)msig);
        return -1;
    }

    err = (*jvmti)->GetClassSignature(jvmti, klass, &csig, NULL);
    if (err != JVMTI_ERROR_NONE) {
        if (mname) (*jvmti)->Deallocate(jvmti, (unsigned char *)mname);
        if (msig) (*jvmti)->Deallocate(jvmti, (unsigned char *)msig);
        return -1;
    }

    if (class_buf) { class_buf[0] = '\0'; if (csig) strncpy(class_buf, csig, class_len - 1); class_buf[class_len - 1] = '\0'; }
    if (method_buf) { method_buf[0] = '\0'; if (mname) strncpy(method_buf, mname, method_len - 1); method_buf[method_len - 1] = '\0'; }
    if (sig_buf) { sig_buf[0] = '\0'; if (msig) strncpy(sig_buf, msig, sig_len - 1); sig_buf[sig_len - 1] = '\0'; }

    if (mname) (*jvmti)->Deallocate(jvmti, (unsigned char *)mname);
    if (msig) (*jvmti)->Deallocate(jvmti, (unsigned char *)msig);
    if (csig) (*jvmti)->Deallocate(jvmti, (unsigned char *)csig);

    return 0;
}

/* ── Option parsing ─────────────────────────────────── */

static void parse_options(jvmmon_agent_t *agent, const char *options) {
    /* Defaults */
    agent->collector_port = 9090;
    agent->sample_interval_ms = 10;
    agent->monitor_interval_ms = 1000;
    agent->log_level = JVMMON_LOG_INFO;
    strncpy(agent->log_file, "jvmmonitor-agent.log", sizeof(agent->log_file) - 1);

    if (options == NULL || options[0] == '\0') return;

    /* Parse key=value,key=value format */
    char buf[1024];
    strncpy(buf, options, sizeof(buf) - 1);
    buf[sizeof(buf) - 1] = '\0';

    char *saveptr = NULL;
    char *token = strtok_r(buf, ",", &saveptr);
    while (token != NULL) {
        char *eq = strchr(token, '=');
        if (eq != NULL) {
            *eq = '\0';
            const char *key = token;
            const char *val = eq + 1;

            if (strcmp(key, "port") == 0) {
                int p = atoi(val);
                if (p > 0 && p < 65536) agent->collector_port = p;
            } else if (strcmp(key, "loglevel") == 0) {
                agent->log_level = jvmmon_log_level_from_string(val);
            } else if (strcmp(key, "logfile") == 0) {
                strncpy(agent->log_file, val, sizeof(agent->log_file) - 1);
                agent->log_file[sizeof(agent->log_file) - 1] = '\0';
            } else if (strcmp(key, "interval") == 0) {
                int v = atoi(val);
                if (v > 0 && v < 60000) agent->sample_interval_ms = v;
            } else if (strcmp(key, "monitor_interval") == 0) {
                int v = atoi(val);
                if (v > 0 && v < 60000) agent->monitor_interval_ms = v;
            }
        }
        token = strtok_r(NULL, ",", &saveptr);
    }
}

/* ── Detect JVM version ─────────────────────────────── */

static int detect_jvm_version(JavaVM *vm) {
    JNIEnv *env;
    jint version;

    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_4) != JNI_OK) {
            return 6;
        }
    }

    version = (*env)->GetVersion(env);
    int minor = version & 0xFFFF;
    int major = (version >> 16) & 0xFFFF;

    if (major >= 9) return major;
    return minor > 0 ? minor : 6;
}

/* ── JVMTI Callbacks ────────────────────────────────── */

static void JNICALL cb_exception(jvmtiEnv *jvmti, JNIEnv *jni,
        jthread thread, jmethodID method, jlocation location,
        jobject exception, jmethodID catch_method, jlocation catch_location) {
    (void)jvmti;
    if (g_agent.exception_tracker) {
        exception_tracker_on_exception(g_agent.exception_tracker, jni,
                thread, method, location, exception, catch_method, catch_location);
    }
}

static void JNICALL cb_exception_catch(jvmtiEnv *jvmti, JNIEnv *jni,
        jthread thread, jmethodID method, jlocation location, jobject exception) {
    (void)jvmti;
    if (g_agent.exception_tracker) {
        exception_tracker_on_exception_catch(g_agent.exception_tracker, jni,
                thread, method, location, exception);
    }
}

static void JNICALL cb_compiled_method_load(jvmtiEnv *jvmti,
        jmethodID method, jint code_size, const void *code_addr,
        jint map_length, const jvmtiAddrLocationMap *map, const void *compile_info) {
    (void)jvmti; (void)map_length; (void)map; (void)compile_info;
    if (g_agent.jit_tracker) {
        jit_tracker_on_compiled_method_load(g_agent.jit_tracker, method, code_size, code_addr);
    }
}

static void JNICALL cb_compiled_method_unload(jvmtiEnv *jvmti,
        jmethodID method, const void *code_addr) {
    (void)jvmti;
    if (g_agent.jit_tracker) {
        jit_tracker_on_compiled_method_unload(g_agent.jit_tracker, method, code_addr);
    }
}

static void JNICALL cb_gc_start(jvmtiEnv *jvmti) {
    (void)jvmti;
    if (g_agent.gc_listener) gc_listener_on_gc_start(g_agent.gc_listener);
}

static void JNICALL cb_gc_finish(jvmtiEnv *jvmti) {
    (void)jvmti;
    if (g_agent.gc_listener) gc_listener_on_gc_finish(g_agent.gc_listener);
}

static void JNICALL cb_thread_start(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread) {
    (void)jvmti;
    if (g_agent.thread_monitor) thread_monitor_on_thread_start(g_agent.thread_monitor, jni, thread);
}

static void JNICALL cb_thread_end(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread) {
    (void)jvmti;
    if (g_agent.thread_monitor) thread_monitor_on_thread_end(g_agent.thread_monitor, jni, thread);
}

static void JNICALL cb_monitor_contended_enter(jvmtiEnv *jvmti, JNIEnv *jni,
        jthread thread, jobject object) {
    (void)jvmti;
    if (g_agent.lock_monitor) lock_monitor_on_contended_enter(g_agent.lock_monitor, jni, thread, object);
}

static void JNICALL cb_monitor_contended_entered(jvmtiEnv *jvmti, JNIEnv *jni,
        jthread thread, jobject object) {
    (void)jvmti;
    if (g_agent.lock_monitor) lock_monitor_on_contended_exit(g_agent.lock_monitor, jni, thread, object);
}

/* ── VM Init: create all modules ───────────────────── */

static void JNICALL cb_vm_init(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread) {
    (void)thread;
    g_agent.jni = jni;

    LOG_INFO("VM initialized (JDK %d). Starting modules...", g_agent.jvm_version);

    /* Resolve AsyncGetCallTrace */
    g_agent.asgct = (AsyncGetCallTrace_fn)jvmmon_dlsym("AsyncGetCallTrace");
    if (g_agent.asgct) {
        LOG_INFO("AsyncGetCallTrace resolved — safe CPU profiling available");
    }

    /* Install crash handler */
    g_agent.crash_handler = crash_handler_create(&g_agent, NULL);

    /* Create core modules */
    g_agent.gc_listener = gc_listener_create(&g_agent);
    g_agent.thread_monitor = thread_monitor_create(&g_agent, g_agent.monitor_interval_ms);
    g_agent.memory_monitor = memory_monitor_create(&g_agent, g_agent.monitor_interval_ms);
    g_agent.class_analyzer = class_analyzer_create(&g_agent);
    g_agent.alarm_engine = alarm_engine_create(&g_agent);
    g_agent.module_registry = module_registry_create(&g_agent);

    if (g_agent.alarm_engine) alarm_engine_add_defaults(g_agent.alarm_engine);

    /* Create all feature modules */
    g_agent.cpu_sampler = cpu_sampler_create(&g_agent, g_agent.sample_interval_ms);
    g_agent.jmx_reader = jmx_reader_create(&g_agent, 5000);
    g_agent.exception_tracker = exception_tracker_create(&g_agent);
    g_agent.os_metrics = os_metrics_create(&g_agent, 5000);
    g_agent.jit_tracker = jit_tracker_create(&g_agent);
    g_agent.class_histo = class_histo_create(&g_agent);
    g_agent.finalizer_tracker = finalizer_tracker_create(&g_agent, 5000);
    g_agent.safepoint_tracker = safepoint_tracker_create(&g_agent, 5000);
    g_agent.native_mem_tracker = native_mem_tracker_create(&g_agent, 10000);
    g_agent.gc_detail = gc_detail_create(&g_agent, 5000);
    g_agent.thread_cpu = thread_cpu_create(&g_agent, 5000);
    g_agent.classloader_tracker = classloader_tracker_create(&g_agent, 10000);
    g_agent.string_table_tracker = string_table_tracker_create(&g_agent, 15000);
    g_agent.network_monitor = network_monitor_create(&g_agent, 5000);
    g_agent.lock_monitor = lock_monitor_create(&g_agent);

    /* Register all activatable modules with the registry */
    if (g_agent.module_registry) {
        if (g_agent.exception_tracker)
            module_registry_register(g_agent.module_registry, "exceptions", 3,
                exception_tracker_activate, exception_tracker_deactivate,
                NULL, NULL, g_agent.exception_tracker);
        if (g_agent.os_metrics)
            module_registry_register(g_agent.module_registry, "os", 3,
                os_metrics_activate, os_metrics_deactivate,
                NULL, NULL, g_agent.os_metrics);
        if (g_agent.jit_tracker)
            module_registry_register(g_agent.module_registry, "jit", 3,
                jit_tracker_activate, jit_tracker_deactivate,
                NULL, NULL, g_agent.jit_tracker);
        if (g_agent.class_histo)
            module_registry_register(g_agent.module_registry, "histogram", 3,
                class_histo_activate, class_histo_deactivate,
                NULL, NULL, g_agent.class_histo);
        if (g_agent.finalizer_tracker)
            module_registry_register(g_agent.module_registry, "buffers", 3,
                finalizer_tracker_activate, finalizer_tracker_deactivate,
                NULL, NULL, g_agent.finalizer_tracker);
        if (g_agent.safepoint_tracker)
            module_registry_register(g_agent.module_registry, "safepoint", 3,
                safepoint_tracker_activate, safepoint_tracker_deactivate,
                NULL, NULL, g_agent.safepoint_tracker);
        if (g_agent.native_mem_tracker)
            module_registry_register(g_agent.module_registry, "nativemem", 3,
                native_mem_tracker_activate, native_mem_tracker_deactivate,
                NULL, NULL, g_agent.native_mem_tracker);
        if (g_agent.gc_detail)
            module_registry_register(g_agent.module_registry, "gcdetail", 3,
                gc_detail_activate, gc_detail_deactivate,
                NULL, NULL, g_agent.gc_detail);
        if (g_agent.thread_cpu)
            module_registry_register(g_agent.module_registry, "threadcpu", 3,
                thread_cpu_activate, thread_cpu_deactivate,
                NULL, NULL, g_agent.thread_cpu);
        if (g_agent.classloader_tracker)
            module_registry_register(g_agent.module_registry, "classloaders", 3,
                classloader_tracker_activate, classloader_tracker_deactivate,
                NULL, NULL, g_agent.classloader_tracker);
        if (g_agent.string_table_tracker)
            module_registry_register(g_agent.module_registry, "strings", 3,
                string_table_tracker_activate, string_table_tracker_deactivate,
                NULL, NULL, g_agent.string_table_tracker);
        if (g_agent.network_monitor)
            module_registry_register(g_agent.module_registry, "network", 3,
                network_monitor_activate, network_monitor_deactivate,
                NULL, NULL, g_agent.network_monitor);
        if (g_agent.lock_monitor)
            module_registry_register(g_agent.module_registry, "locks", 3,
                lock_monitor_activate, lock_monitor_deactivate,
                NULL, NULL, g_agent.lock_monitor);
    }

    /* Start core modules (always on) */
    if (g_agent.cpu_sampler) cpu_sampler_start(g_agent.cpu_sampler);
    if (g_agent.thread_monitor) thread_monitor_start(g_agent.thread_monitor);
    if (g_agent.memory_monitor) memory_monitor_start(g_agent.memory_monitor);
    if (g_agent.jmx_reader) jmx_reader_start(g_agent.jmx_reader);

    /* Start transport */
    g_agent.transport.pid = (uint32_t)jvmmon_getpid();
    jvmmon_gethostname(g_agent.transport.hostname, sizeof(g_agent.transport.hostname));
    snprintf(g_agent.transport.jvm_info, sizeof(g_agent.transport.jvm_info),
             "JDK %d", g_agent.jvm_version);
    transport_start(&g_agent.transport);

    LOG_INFO("All modules started. Agent ready (port %d)", g_agent.collector_port);
}

/* ── VM Death: clean shutdown ──────────────────────── */

static void JNICALL cb_vm_death(jvmtiEnv *jvmti, JNIEnv *jni) {
    (void)jvmti; (void)jni;

    LOG_INFO("VM shutting down. Stopping agent...");
    jvmmon_atomic_store(&g_agent.running, 0);

    /* Save crash dump on shutdown */
    if (g_agent.crash_handler) {
        crash_handler_on_vm_death(g_agent.crash_handler, jni);
    }

    /* Stop core modules */
    if (g_agent.cpu_sampler) cpu_sampler_stop(g_agent.cpu_sampler);
    if (g_agent.thread_monitor) thread_monitor_stop(g_agent.thread_monitor);
    if (g_agent.memory_monitor) memory_monitor_stop(g_agent.memory_monitor);
    if (g_agent.jmx_reader) jmx_reader_stop(g_agent.jmx_reader);

    /* Stop transport before destroying modules */
    transport_stop(&g_agent.transport);

    /* Destroy all modules (order: registry first to deactivate active modules) */
    if (g_agent.module_registry) module_registry_destroy(g_agent.module_registry);
    if (g_agent.cpu_sampler) cpu_sampler_destroy(g_agent.cpu_sampler);
    if (g_agent.gc_listener) gc_listener_destroy(g_agent.gc_listener);
    if (g_agent.thread_monitor) thread_monitor_destroy(g_agent.thread_monitor);
    if (g_agent.memory_monitor) memory_monitor_destroy(g_agent.memory_monitor);
    if (g_agent.class_analyzer) class_analyzer_destroy(g_agent.class_analyzer);
    if (g_agent.alarm_engine) alarm_engine_destroy(g_agent.alarm_engine);
    if (g_agent.jmx_reader) jmx_reader_destroy(g_agent.jmx_reader);
    if (g_agent.exception_tracker) exception_tracker_destroy(g_agent.exception_tracker);
    if (g_agent.os_metrics) os_metrics_destroy(g_agent.os_metrics);
    if (g_agent.jit_tracker) jit_tracker_destroy(g_agent.jit_tracker);
    if (g_agent.class_histo) class_histo_destroy(g_agent.class_histo);
    if (g_agent.finalizer_tracker) finalizer_tracker_destroy(g_agent.finalizer_tracker);
    if (g_agent.safepoint_tracker) safepoint_tracker_destroy(g_agent.safepoint_tracker);
    if (g_agent.native_mem_tracker) native_mem_tracker_destroy(g_agent.native_mem_tracker);
    if (g_agent.gc_detail) gc_detail_destroy(g_agent.gc_detail);
    if (g_agent.thread_cpu) thread_cpu_destroy(g_agent.thread_cpu);
    if (g_agent.classloader_tracker) classloader_tracker_destroy(g_agent.classloader_tracker);
    if (g_agent.string_table_tracker) string_table_tracker_destroy(g_agent.string_table_tracker);
    if (g_agent.network_monitor) network_monitor_destroy(g_agent.network_monitor);
    if (g_agent.lock_monitor) lock_monitor_destroy(g_agent.lock_monitor);
    if (g_agent.crash_handler) crash_handler_destroy(g_agent.crash_handler);

    transport_destroy(&g_agent.transport);
    if (g_agent.ring) {
        ring_buffer_destroy(g_agent.ring);
        jvmmon_free(g_agent.ring);
    }
    jvmmon_socket_cleanup();

    LOG_INFO("Agent v%s shutdown complete (pid=%d)", JVMMON_VERSION_STRING, jvmmon_getpid());
    jvmmon_log_close();
}

/* ── Command callback from collector ────────────────── */

static void on_command(uint8_t cmd_type, const uint8_t *payload,
                       uint32_t payload_len, void *user_data) {
    jvmmon_agent_t *agent = (jvmmon_agent_t *)user_data;

    if (agent->module_registry) {
        module_registry_handle_command(agent->module_registry,
                                       cmd_type, payload, payload_len);
    }
}

/* ── Agent init (shared between OnLoad and OnAttach) ── */

static jint agent_init(JavaVM *vm, char *options, jboolean is_onload) {
    jvmtiEnv *jvmti;
    jvmtiError err;
    jvmtiCapabilities caps;
    jvmtiEventCallbacks callbacks;

    memset(&g_agent, 0, sizeof(g_agent));
    g_agent.jvm = vm;
    g_agent.is_onload = is_onload;

    /* Init platform */
    jvmmon_socket_init();

    /* Parse options */
    parse_options(&g_agent, options);

    /* Init logging */
    jvmmon_log_init(g_agent.log_level,
                     g_agent.log_file[0] ? g_agent.log_file : NULL);

    /* Banner */
    fprintf(stdout, "JVMMonitor Agent v%s\n", JVMMON_VERSION_STRING);
    fprintf(stdout, "  Port: %d | Log: %s | Mode: %s | PID: %d\n",
            g_agent.collector_port,
            g_agent.log_level == JVMMON_LOG_ERROR ? "ERROR" :
            g_agent.log_level == JVMMON_LOG_INFO ? "INFO" :
            g_agent.log_level == JVMMON_LOG_SUPPORT ? "SUPPORT" : "DEBUG",
            is_onload ? "OnLoad" : "OnAttach",
            jvmmon_getpid());
    fflush(stdout);

    LOG_INFO("Agent v%s starting (port=%d, mode=%s, pid=%d)",
             JVMMON_VERSION_STRING, g_agent.collector_port,
             is_onload ? "OnLoad" : "OnAttach", jvmmon_getpid());

    /* Get JVMTI environment */
    if ((*vm)->GetEnv(vm, (void **)&jvmti, JVMTI_VERSION_1_0) != JNI_OK) {
        LOG_ERROR("Failed to get JVMTI environment");
        return JNI_ERR;
    }
    g_agent.jvmti = jvmti;

    /* Detect JVM version */
    g_agent.jvm_version = detect_jvm_version(vm);
    LOG_INFO("JVM version detected: %d", g_agent.jvm_version);

    /* Request capabilities (graceful degradation) */
    memset(&caps, 0, sizeof(caps));
    caps.can_generate_garbage_collection_events = 1;
    caps.can_get_current_thread_cpu_time = 1;
    caps.can_get_thread_cpu_time = 1;
    caps.can_tag_objects = 1;
    caps.can_get_source_file_name = 1;
    caps.can_get_line_numbers = 1;
    caps.can_generate_compiled_method_load_events = 1;
    caps.can_generate_exception_events = 1;
    caps.can_generate_monitor_events = 1;
    caps.can_access_local_variables = 1;
    caps.can_generate_breakpoint_events = 1;
    caps.can_generate_single_step_events = 1;
    caps.can_get_bytecodes = 1;
    caps.can_get_monitor_info = 1;
    caps.can_redefine_classes = 1;
    if (is_onload) {
        caps.can_generate_all_class_hook_events = 1;
        caps.can_retransform_classes = 1;
    }

    err = (*jvmti)->AddCapabilities(jvmti, &caps);
    if (err != JVMTI_ERROR_NONE) {
        LOG_INFO("Full capabilities not available (err=%d), retrying with basic set", (int)err);
        /* Retry with minimal capabilities */
        memset(&caps, 0, sizeof(caps));
        caps.can_generate_garbage_collection_events = 1;
        caps.can_get_current_thread_cpu_time = 1;
        caps.can_get_thread_cpu_time = 1;
        caps.can_tag_objects = 1;
        caps.can_get_source_file_name = 1;
        caps.can_get_line_numbers = 1;
        caps.can_generate_compiled_method_load_events = 1;
        caps.can_generate_exception_events = 1;
        err = (*jvmti)->AddCapabilities(jvmti, &caps);
        if (err != JVMTI_ERROR_NONE) {
            LOG_ERROR("Failed to add even basic JVMTI capabilities (err=%d)", (int)err);
            return JNI_ERR;
        }
        LOG_INFO("Running with basic capabilities (some features may be limited)");
    } else {
        LOG_INFO("Full JVMTI capabilities acquired (debugger, monitor, redefine available)");
    }

    /* Set callbacks */
    memset(&callbacks, 0, sizeof(callbacks));
    callbacks.GarbageCollectionStart = cb_gc_start;
    callbacks.GarbageCollectionFinish = cb_gc_finish;
    callbacks.ThreadStart = cb_thread_start;
    callbacks.ThreadEnd = cb_thread_end;
    callbacks.VMInit = cb_vm_init;
    callbacks.VMDeath = cb_vm_death;
    callbacks.Exception = cb_exception;
    callbacks.ExceptionCatch = cb_exception_catch;
    callbacks.CompiledMethodLoad = cb_compiled_method_load;
    callbacks.CompiledMethodUnload = cb_compiled_method_unload;
    callbacks.MonitorContendedEnter = cb_monitor_contended_enter;
    callbacks.MonitorContendedEntered = cb_monitor_contended_entered;

    err = (*jvmti)->SetEventCallbacks(jvmti, &callbacks, (jint)sizeof(callbacks));
    if (err != JVMTI_ERROR_NONE) {
        LOG_ERROR("Failed to set JVMTI callbacks (err=%d)", (int)err);
        return JNI_ERR;
    }

    /* Enable core events (always on) */
    (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_VM_INIT, NULL);
    (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_VM_DEATH, NULL);
    (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_GARBAGE_COLLECTION_START, NULL);
    (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_GARBAGE_COLLECTION_FINISH, NULL);
    (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_THREAD_START, NULL);
    (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_THREAD_END, NULL);

    /* Init ring buffer */
    g_agent.ring = (ring_buffer_t *)jvmmon_calloc(1, sizeof(ring_buffer_t));
    if (g_agent.ring == NULL) {
        LOG_ERROR("Failed to allocate ring buffer");
        return JNI_ERR;
    }
    if (ring_buffer_init(g_agent.ring) != 0) {
        LOG_ERROR("Failed to initialize ring buffer");
        jvmmon_free(g_agent.ring);
        return JNI_ERR;
    }

    /* Init transport */
    transport_init(&g_agent.transport, g_agent.ring, g_agent.collector_port);
    transport_set_command_callback(&g_agent.transport, on_command, &g_agent);

    jvmmon_atomic_store(&g_agent.running, 1);

    /* If late attach, VM is already initialized */
    if (!is_onload) {
        JNIEnv *env;
        if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) {
            LOG_INFO("Late attach: triggering VM init sequence");
            cb_vm_init(jvmti, env, NULL);
        }
    }

    return JNI_OK;
}

/* ── Exported entry points ──────────────────────────── */

JVMMON_EXPORT jint JNICALL Agent_OnLoad(JavaVM *vm, char *options, void *reserved) {
    (void)reserved;
    return agent_init(vm, options, JNI_TRUE);
}

JVMMON_EXPORT jint JNICALL Agent_OnAttach(JavaVM *vm, char *options, void *reserved) {
    (void)reserved;
    return agent_init(vm, options, JNI_FALSE);
}

JVMMON_EXPORT void JNICALL Agent_OnUnload(JavaVM *vm) {
    (void)vm;
    LOG_INFO("Agent_OnUnload called");
}
