/*
 * JVMMonitor - Agent Logging Implementation
 */
#include "jvmmon/log.h"
#include "jvmmon/platform.h"
#include <stdio.h>
#include <stdarg.h>
#include <string.h>
#include <time.h>

static int g_log_level = JVMMON_LOG_INFO;
static FILE *g_log_file = NULL;
static jvmmon_mutex_t g_log_mutex;
static int g_initialized = 0;

static const char *level_names[] = { "ERROR", "INFO", "SUPPORT", "DEBUG" };

int jvmmon_log_init(int log_level, const char *log_file) {
    g_log_level = log_level;
    jvmmon_mutex_init(&g_log_mutex);
    g_initialized = 1;

    const char *path = (log_file != NULL) ? log_file : "jvmmonitor-agent.log";
    g_log_file = fopen(path, "a");
    if (g_log_file == NULL) {
        fprintf(stderr, "[JVMMonitor] WARNING: cannot open log file '%s', logging to stderr only\n", path);
        return -1;
    }
    return 0;
}

void jvmmon_log_close(void) {
    if (g_log_file != NULL) {
        fclose(g_log_file);
        g_log_file = NULL;
    }
    if (g_initialized) {
        jvmmon_mutex_destroy(&g_log_mutex);
        g_initialized = 0;
    }
}

void jvmmon_log_set_level(int level) {
    if (level >= JVMMON_LOG_ERROR && level <= JVMMON_LOG_DEBUG) {
        g_log_level = level;
    }
}

int jvmmon_log_get_level(void) {
    return g_log_level;
}

int jvmmon_log_level_from_string(const char *str) {
    if (str == NULL) return JVMMON_LOG_INFO;
    if (strcasecmp(str, "error") == 0) return JVMMON_LOG_ERROR;
    if (strcasecmp(str, "info") == 0) return JVMMON_LOG_INFO;
    if (strcasecmp(str, "support") == 0) return JVMMON_LOG_SUPPORT;
    if (strcasecmp(str, "debug") == 0) return JVMMON_LOG_DEBUG;
    return JVMMON_LOG_INFO;
}

void jvmmon_log(int level, const char *fmt, ...) {
    if (level > g_log_level) return;
    if (!g_initialized) return;

    /* Build timestamp */
    time_t now;
    struct tm tm_buf;
    char ts_buf[32];
    time(&now);
#ifdef _WIN32
    localtime_s(&tm_buf, &now);
#else
    localtime_r(&now, &tm_buf);
#endif
    strftime(ts_buf, sizeof(ts_buf), "%Y-%m-%d %H:%M:%S", &tm_buf);

    const char *lvl_str = (level >= 0 && level <= 3) ? level_names[level] : "???";

    va_list args;

    jvmmon_mutex_lock(&g_log_mutex);

    /* Write to log file */
    if (g_log_file != NULL) {
        fprintf(g_log_file, "%s [%-7s] ", ts_buf, lvl_str);
        va_start(args, fmt);
        vfprintf(g_log_file, fmt, args);
        va_end(args);
        fprintf(g_log_file, "\n");
        fflush(g_log_file);
    }

    /* ERROR always goes to stderr too */
    if (level == JVMMON_LOG_ERROR) {
        fprintf(stderr, "[JVMMonitor] %s: ", lvl_str);
        va_start(args, fmt);
        vfprintf(stderr, fmt, args);
        va_end(args);
        fprintf(stderr, "\n");
    }

    jvmmon_mutex_unlock(&g_log_mutex);
}
