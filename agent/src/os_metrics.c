/*
 * JVMMonitor - OS Metrics Implementation
 * Reads process-level metrics from /proc (Linux) or WinAPI (Windows).
 */
#include "jvmmon/os_metrics.h"
#include "jvmmon/log.h"
#include "jvmmon/protocol.h"
#include <string.h>
#include <stdio.h>

#ifndef _WIN32
#include <dirent.h>
#endif

/* ── Linux /proc readers ──────���─────────────────────── */
#ifndef _WIN32

static int count_open_fds(void) {
    int count = 0;
    DIR *dir = opendir("/proc/self/fd");
    if (dir == NULL) return -1;
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] != '.') count++;
    }
    closedir(dir);
    return count;
}

static long read_proc_status_kb(const char *field) {
    FILE *f = fopen("/proc/self/status", "r");
    if (f == NULL) return -1;
    char line[256];
    long value = -1;
    while (fgets(line, sizeof(line), f) != NULL) {
        if (strncmp(line, field, strlen(field)) == 0) {
            if (sscanf(line + strlen(field), "%ld", &value) != 1) {
                value = -1;
            }
            break;
        }
    }
    fclose(f);
    return value;
}

static void read_context_switches(long *voluntary, long *involuntary) {
    *voluntary = read_proc_status_kb("voluntary_ctxt_switches:\t");
    *involuntary = read_proc_status_kb("nonvoluntary_ctxt_switches:\t");
}

static int count_tcp_states(int *established, int *close_wait, int *time_wait) {
    FILE *f = fopen("/proc/self/net/tcp", "r");
    if (f == NULL) return -1;
    char line[512];
    *established = 0;
    *close_wait = 0;
    *time_wait = 0;
    /* Skip header */
    if (fgets(line, sizeof(line), f) == NULL) { fclose(f); return -1; }
    while (fgets(line, sizeof(line), f) != NULL) {
        /* State is field 4 (hex): 01=ESTABLISHED, 06=TIME_WAIT, 08=CLOSE_WAIT */
        unsigned int state = 0;
        /* Format: sl local_addr remote_addr st ... */
        char *p = line;
        int field = 0;
        while (*p && field < 3) {
            while (*p == ' ') p++;
            while (*p && *p != ' ') p++;
            field++;
        }
        while (*p == ' ') p++;
        if (sscanf(p, "%X", &state) != 1) continue;
        if (state == 0x01) (*established)++;
        else if (state == 0x06) (*time_wait)++;
        else if (state == 0x08) (*close_wait)++;
    }
    fclose(f);
    return 0;
}

static int count_threads_os(void) {
    return (int)read_proc_status_kb("Threads:\t");
}

#else /* Windows — limited OS metrics via WinAPI */

static int count_open_fds(void) { return -1; }
static long read_rss_kb(void) { return -1; }
static void read_context_switches(long *voluntary, long *involuntary) {
    *voluntary = -1; *involuntary = -1;
}
static int count_tcp_states(int *established, int *close_wait, int *time_wait) {
    *established = -1; *close_wait = -1; *time_wait = -1; return -1;
}
static int count_threads_os(void) { return -1; }

#endif

/* ── Collect and send ───────────────────────────────── */

static void collect_and_send(os_metrics_t *om) {
    uint8_t payload[512];
    int off = 0;

    off += protocol_encode_u64(payload + off, jvmmon_time_millis());

    /* FD count */
    int fds = count_open_fds();
    off += protocol_encode_i32(payload + off, fds);

    /* RSS (KB) */
#ifndef _WIN32
    long rss_kb = read_proc_status_kb("VmRSS:\t");
    long vm_size_kb = read_proc_status_kb("VmSize:\t");
#else
    long rss_kb = read_rss_kb();
    long vm_size_kb = -1;
#endif
    off += protocol_encode_i64(payload + off, rss_kb >= 0 ? (int64_t)(rss_kb * 1024) : -1);
    off += protocol_encode_i64(payload + off, vm_size_kb >= 0 ? (int64_t)(vm_size_kb * 1024) : -1);

    /* Context switches */
    long vol_cs, invol_cs;
    read_context_switches(&vol_cs, &invol_cs);
    off += protocol_encode_i64(payload + off, (int64_t)vol_cs);
    off += protocol_encode_i64(payload + off, (int64_t)invol_cs);

    /* TCP states */
    int tcp_est = 0, tcp_cw = 0, tcp_tw = 0;
    count_tcp_states(&tcp_est, &tcp_cw, &tcp_tw);
    off += protocol_encode_i32(payload + off, tcp_est);
    off += protocol_encode_i32(payload + off, tcp_cw);
    off += protocol_encode_i32(payload + off, tcp_tw);

    /* OS thread count */
    int os_threads = count_threads_os();
    off += protocol_encode_i32(payload + off, os_threads);

    agent_send_message(JVMMON_MSG_OS_METRICS, payload, (uint32_t)off);
}

static void *os_poll_thread_fn(void *arg) {
    os_metrics_t *om = (os_metrics_t *)arg;

    while (jvmmon_atomic_load(&om->running)) {
        collect_and_send(om);
        jvmmon_sleep_ms(om->interval_ms);
    }
    return NULL;
}

/* ── Public API ─────────────────────────────────────── */

os_metrics_t *os_metrics_create(jvmmon_agent_t *agent, int interval_ms) {
    os_metrics_t *om = (os_metrics_t *)jvmmon_calloc(1, sizeof(os_metrics_t));
    if (om == NULL) return NULL;
    om->agent = agent;
    om->interval_ms = interval_ms > 0 ? interval_ms : 5000;
    om->running = 0;
    return om;
}

int os_metrics_activate(int level, const char *target, void *ctx) {
    os_metrics_t *om = (os_metrics_t *)ctx;
    (void)level;
    (void)target;
    jvmmon_atomic_store(&om->running, 1);
    jvmmon_thread_create(&om->poll_thread, os_poll_thread_fn, om);
    LOG_INFO("OS metrics module activated");
    return 0;
}

int os_metrics_deactivate(int level, void *ctx) {
    os_metrics_t *om = (os_metrics_t *)ctx;
    (void)level;
    jvmmon_atomic_store(&om->running, 0);
    jvmmon_thread_join(&om->poll_thread);
    LOG_INFO("OS metrics module deactivated");
    return 0;
}

void os_metrics_destroy(os_metrics_t *om) {
    if (om != NULL) {
        if (jvmmon_atomic_load(&om->running)) {
            jvmmon_atomic_store(&om->running, 0);
            jvmmon_thread_join(&om->poll_thread);
        }
        jvmmon_free(om);
    }
}
