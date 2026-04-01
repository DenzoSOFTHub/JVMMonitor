/*
 * JVMMonitor - Crash Handler Implementation (Production)
 * On JVM death or crash signal, writes a diagnostic dump file.
 *
 * Signal handler uses ONLY async-signal-safe functions:
 * - write(), open(), close(), _exit()
 * NO: fprintf, fopen, fclose, malloc, localtime, snprintf
 *
 * VMDeath handler can use full stdio since it runs in normal context.
 */
#include "jvmmon/crash_handler.h"
#include "jvmmon/log.h"
#include "jvmmon/protocol.h"
#include <string.h>
#include <stdio.h>
#include <time.h>

#ifndef _WIN32
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#endif

static crash_handler_t *g_crash_handler = NULL;

/* Pre-allocated buffer for signal-safe dump */
static char g_signal_dump_path[512] = "jvmmonitor-crash.txt";
static char g_signal_buf[4096];

/* ── Async-signal-safe integer-to-string ─────────── */
static int int_to_str(char *buf, int val) {
    if (val == 0) { buf[0] = '0'; return 1; }
    char tmp[16];
    int len = 0;
    int neg = 0;
    if (val < 0) { neg = 1; val = -val; }
    while (val > 0 && len < 15) { tmp[len++] = '0' + (val % 10); val /= 10; }
    int pos = 0;
    if (neg) buf[pos++] = '-';
    for (int i = len - 1; i >= 0; i--) buf[pos++] = tmp[i];
    return pos;
}

/* ── Safe string append ──────────────────────────── */
static int safe_append(char *buf, int pos, int max, const char *str) {
    int i = 0;
    while (str[i] && pos < max - 1) { buf[pos++] = str[i++]; }
    return pos;
}

/* ── VMDeath dump (full stdio available) ─────────── */

static void write_vm_death_dump(crash_handler_t *ch, const char *reason) {
    char filename[512];
    time_t now = time(NULL);
    struct tm *tm = localtime(&now);

    if (tm != NULL) {
        snprintf(filename, sizeof(filename), "jvmmonitor-crash-%04d%02d%02d-%02d%02d%02d.txt",
                 tm->tm_year + 1900, tm->tm_mon + 1, tm->tm_mday,
                 tm->tm_hour, tm->tm_min, tm->tm_sec);
    } else {
        snprintf(filename, sizeof(filename), "jvmmonitor-crash-unknown.txt");
    }

    FILE *f = fopen(filename, "w");
    if (f == NULL) {
        LOG_ERROR("Crash handler: failed to open dump file %s", filename);
        return;
    }

    fprintf(f, "=== JVMMonitor Crash Dump v1.0.0 ===\n");
    fprintf(f, "Reason: %s\n", reason);
    fprintf(f, "PID: %d\n", (int)jvmmon_getpid());
    char hostname[256] = {0};
    jvmmon_gethostname(hostname, sizeof(hostname));
    fprintf(f, "Host: %s\n", hostname);

    /* Agent config */
    fprintf(f, "\n=== Agent Configuration ===\n");
    fprintf(f, "Port: %d\n", ch->agent->collector_port);
    fprintf(f, "Sample interval: %d ms\n", ch->agent->sample_interval_ms);
    fprintf(f, "Monitor interval: %d ms\n", ch->agent->monitor_interval_ms);
    fprintf(f, "JVM version: %d\n", ch->agent->jvm_version);
    fprintf(f, "Mode: %s\n", ch->agent->is_onload ? "Agent_OnLoad" : "Agent_OnAttach");

    /* Thread dump */
    jvmtiEnv *jvmti = ch->agent->jvmti;
    if (jvmti != NULL) {
        jthread *threads = NULL;
        jint thread_count = 0;
        jvmtiError err = (*jvmti)->GetAllThreads(jvmti, &thread_count, &threads);

        fprintf(f, "\n=== Thread Dump (%d threads) ===\n", (int)thread_count);
        if (err == JVMTI_ERROR_NONE && threads != NULL) {
            int i;
            for (i = 0; i < thread_count && i < 200; i++) {
                jvmtiThreadInfo info;
                memset(&info, 0, sizeof(info));
                (*jvmti)->GetThreadInfo(jvmti, threads[i], &info);

                jint state = 0;
                (*jvmti)->GetThreadState(jvmti, threads[i], &state);

                const char *state_str = "UNKNOWN";
                if (state & JVMTI_THREAD_STATE_RUNNABLE) state_str = "RUNNABLE";
                else if (state & JVMTI_THREAD_STATE_BLOCKED_ON_MONITOR_ENTER) state_str = "BLOCKED";
                else if (state & JVMTI_THREAD_STATE_WAITING) state_str = "WAITING";
                else if (state & JVMTI_THREAD_STATE_TERMINATED) state_str = "TERMINATED";

                fprintf(f, "\"%s\" %s%s\n",
                        info.name ? info.name : "?", state_str,
                        info.is_daemon ? " daemon" : "");

                jvmtiFrameInfo frames[32];
                jint frame_count = 0;
                (*jvmti)->GetStackTrace(jvmti, threads[i], 0, 32, frames, &frame_count);
                int j;
                for (j = 0; j < frame_count; j++) {
                    char cname[256] = {0}, mname[256] = {0};
                    agent_resolve_method(frames[j].method, cname, 256, mname, 256, NULL, 0);
                    fprintf(f, "    at %s.%s\n", cname, mname);
                }
                fprintf(f, "\n");

                if (info.name) (*jvmti)->Deallocate(jvmti, (unsigned char *)info.name);
            }
            (*jvmti)->Deallocate(jvmti, (unsigned char *)threads);
        }
    }

    /* OS info */
#ifndef _WIN32
    fprintf(f, "=== OS Info ===\n");
    FILE *status = fopen("/proc/self/status", "r");
    if (status != NULL) {
        char line[256];
        while (fgets(line, sizeof(line), status) != NULL) {
            if (strncmp(line, "VmRSS:", 6) == 0 || strncmp(line, "VmSize:", 7) == 0 ||
                strncmp(line, "Threads:", 8) == 0 || strncmp(line, "VmPeak:", 7) == 0) {
                fprintf(f, "%s", line);
            }
        }
        fclose(status);
    }
#endif

    fprintf(f, "\n=== End of Crash Dump ===\n");
    fclose(f);
    LOG_INFO("Crash dump saved to %s", filename);
}

/* ── Signal handler (async-signal-safe ONLY) ─────── */

#ifndef _WIN32
static void signal_handler(int signum) {
    /* Build minimal dump using ONLY async-signal-safe functions */
    int pos = 0;
    pos = safe_append(g_signal_buf, pos, sizeof(g_signal_buf),
            "=== JVMMonitor SIGNAL CRASH DUMP ===\nSignal: ");
    switch (signum) {
        case SIGSEGV: pos = safe_append(g_signal_buf, pos, sizeof(g_signal_buf), "SIGSEGV"); break;
        case SIGABRT: pos = safe_append(g_signal_buf, pos, sizeof(g_signal_buf), "SIGABRT"); break;
        case SIGBUS:  pos = safe_append(g_signal_buf, pos, sizeof(g_signal_buf), "SIGBUS"); break;
        default:
            pos = safe_append(g_signal_buf, pos, sizeof(g_signal_buf), "SIG#");
            pos += int_to_str(g_signal_buf + pos, signum);
            break;
    }
    pos = safe_append(g_signal_buf, pos, sizeof(g_signal_buf), "\nPID: ");
    pos += int_to_str(g_signal_buf + pos, (int)getpid());

    if (g_crash_handler != NULL) {
        pos = safe_append(g_signal_buf, pos, sizeof(g_signal_buf), "\nPort: ");
        pos += int_to_str(g_signal_buf + pos, g_crash_handler->agent->collector_port);
        pos = safe_append(g_signal_buf, pos, sizeof(g_signal_buf), "\nJVM: ");
        pos += int_to_str(g_signal_buf + pos, g_crash_handler->agent->jvm_version);
    }

    pos = safe_append(g_signal_buf, pos, sizeof(g_signal_buf),
            "\nNOTE: Thread dump skipped (JVMTI unsafe in signal context)\n"
            "=== End ===\n");
    g_signal_buf[pos] = '\0';

    /* Write using ONLY async-signal-safe open/write/close */
    int fd = open(g_signal_dump_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd >= 0) {
        write(fd, g_signal_buf, pos);
        close(fd);
    }

    /* Also write to stderr (async-signal-safe) */
    write(STDERR_FILENO, g_signal_buf, pos);

    /* Re-raise with default handler */
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = SIG_DFL;
    sigaction(signum, &sa, NULL);
    raise(signum);
}
#endif

/* ── Public API ──────────────────────────────────── */

crash_handler_t *crash_handler_create(jvmmon_agent_t *agent, const char *dump_dir) {
    crash_handler_t *ch = (crash_handler_t *)jvmmon_calloc(1, sizeof(crash_handler_t));
    if (ch == NULL) return NULL;
    ch->agent = agent;

    /* Pre-compute signal dump path (used in signal handler without malloc) */
    if (dump_dir != NULL && dump_dir[0] != '\0') {
        snprintf(g_signal_dump_path, sizeof(g_signal_dump_path),
                 "%s/jvmmonitor-signal-crash.txt", dump_dir);
    } else {
        strncpy(g_signal_dump_path, "jvmmonitor-signal-crash.txt", sizeof(g_signal_dump_path) - 1);
    }

    g_crash_handler = ch;

    /* Install signal handlers using sigaction (not signal) */
#ifndef _WIN32
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = signal_handler;
    sa.sa_flags = SA_RESETHAND; /* Auto-reset to default after first invocation */
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSEGV, &sa, NULL);
    sigaction(SIGABRT, &sa, NULL);
    sigaction(SIGBUS, &sa, NULL);
#endif

    ch->active = 1;
    LOG_INFO("Crash handler installed (signal dump: %s)", g_signal_dump_path);
    return ch;
}

void crash_handler_destroy(crash_handler_t *ch) {
    if (ch != NULL) {
        g_crash_handler = NULL;
#ifndef _WIN32
        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_handler = SIG_DFL;
        sigaction(SIGSEGV, &sa, NULL);
        sigaction(SIGABRT, &sa, NULL);
        sigaction(SIGBUS, &sa, NULL);
#endif
        jvmmon_free(ch);
    }
}

void crash_handler_on_vm_death(crash_handler_t *ch, JNIEnv *jni) {
    (void)jni;
    if (ch == NULL || !ch->active) return;
    write_vm_death_dump(ch, "VMDeath (normal or abnormal shutdown)");
}

void crash_handler_save_dump(crash_handler_t *ch, const char *reason) {
    if (ch == NULL || !ch->active) return;
    write_vm_death_dump(ch, reason);
}

void crash_handler_on_signal(crash_handler_t *ch, int signum) {
    (void)ch; (void)signum;
    /* Signal handler already called directly — this is a no-op */
}
