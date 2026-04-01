/*
 * JVMMonitor - Agent Logging
 *
 * Levels:
 *   ERROR   - Errors that affect agent functionality
 *   INFO    - Normal operational messages (default)
 *   SUPPORT - Detailed info for remote troubleshooting
 *   DEBUG   - Verbose detail for development
 *
 * On load, agent prints version + port to stdout.
 * All log messages go to a log file (jvmmonitor-agent.log)
 * and optionally to stderr.
 */
#ifndef JVMMON_LOG_H
#define JVMMON_LOG_H

#include <stdint.h>

#define JVMMON_LOG_ERROR   0
#define JVMMON_LOG_INFO    1
#define JVMMON_LOG_SUPPORT 2
#define JVMMON_LOG_DEBUG   3

/* Initialize logging. Call once at agent start.
 * log_level: JVMMON_LOG_ERROR..JVMMON_LOG_DEBUG
 * log_file:  path to log file (NULL = "jvmmonitor-agent.log" in working dir)
 */
int  jvmmon_log_init(int log_level, const char *log_file);
void jvmmon_log_close(void);

/* Set log level at runtime (e.g., from client command) */
void jvmmon_log_set_level(int level);
int  jvmmon_log_get_level(void);

/* Parse log level from string: "error", "info", "support", "debug" */
int  jvmmon_log_level_from_string(const char *str);

/* Log functions */
void jvmmon_log(int level, const char *fmt, ...);

/* Convenience macros */
#define LOG_ERROR(fmt, ...)   jvmmon_log(JVMMON_LOG_ERROR,   fmt, ##__VA_ARGS__)
#define LOG_INFO(fmt, ...)    jvmmon_log(JVMMON_LOG_INFO,    fmt, ##__VA_ARGS__)
#define LOG_SUPPORT(fmt, ...) jvmmon_log(JVMMON_LOG_SUPPORT, fmt, ##__VA_ARGS__)
#define LOG_DEBUG(fmt, ...)   jvmmon_log(JVMMON_LOG_DEBUG,   fmt, ##__VA_ARGS__)

#endif /* JVMMON_LOG_H */
