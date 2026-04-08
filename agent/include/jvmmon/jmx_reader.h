/*
 * JVMMonitor - JMX MBean Reader
 * Reads MBean data via JNI calls to MBeanServer.
 * Runs periodically and sends MBean snapshots to client.
 */
#ifndef JVMMON_JMX_READER_H
#define JVMMON_JMX_READER_H

#include "agent.h"

/* Message subtypes within JVMMON_MSG_JMX_DATA */
#define JMX_SUBTYPE_MBEAN_LIST    0x01   /* list of all MBean ObjectNames */
#define JMX_SUBTYPE_MBEAN_ATTRS   0x02   /* attributes for a specific MBean */
#define JMX_SUBTYPE_PLATFORM_INFO 0x03   /* standard platform MBeans summary */

typedef struct jmx_reader {
    jvmmon_agent_t  *agent;
    jvmmon_thread_t  poll_thread;
    volatile int32_t running;
    int              interval_ms;   /* polling interval for subscribed MBeans */
    /* Subscribed MBean names (set by client commands) */
    char             subscribed[64][256];
    int              subscribed_count;
    jvmmon_mutex_t   lock;
    /* JNI cache for CPU usage (resolved once, reused every poll) */
    jclass     mf_global;         /* ManagementFactory global ref */
    jmethodID  getOS;
    jclass     os_class_global;   /* OperatingSystemMXBean class global ref */
    jmethodID  getProcs;
    jmethodID  getSystemCpu;      /* NULL if not available (non-Sun JVM) */
    jmethodID  getProcessCpu;
    jmethodID  getProcessCpuTime;
    int        cpu_cached;
} jmx_reader_t;

jmx_reader_t *jmx_reader_create(jvmmon_agent_t *agent, int interval_ms);
int  jmx_reader_start(jmx_reader_t *jr);
void jmx_reader_stop(jmx_reader_t *jr);
void jmx_reader_destroy(jmx_reader_t *jr);

/* Send full MBean list to client */
void jmx_reader_send_mbean_list(jmx_reader_t *jr);

/* Send platform MBeans summary (OS, Runtime, Memory pools, GC, Compilation) */
void jmx_reader_send_platform_info(jmx_reader_t *jr);

/* Subscribe to receive periodic updates for a specific MBean */
void jmx_reader_subscribe(jmx_reader_t *jr, const char *mbean_name);

/* Unsubscribe */
void jmx_reader_unsubscribe(jmx_reader_t *jr, const char *mbean_name);

#endif /* JVMMON_JMX_READER_H */
