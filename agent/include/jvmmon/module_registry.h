/*
 * JVMMonitor - Module Registry
 * Manages activatable profiling modules with progressive levels.
 */
#ifndef JVMMON_MODULE_REGISTRY_H
#define JVMMON_MODULE_REGISTRY_H

#include "agent.h"

#define MAX_MODULES        32
#define MAX_MODULE_NAME    32
#define MAX_MODULE_LEVELS  4   /* 0=core, 1=statistical, 2=detailed, 3=surgical */

typedef int  (*module_activate_fn)(int level, const char *target, void *ctx);
typedef int  (*module_deactivate_fn)(int level, void *ctx);
typedef void (*module_collect_fn)(int level, void *ctx);
typedef void (*module_destroy_fn)(void *ctx);

typedef struct {
    char                name[MAX_MODULE_NAME];
    int                 current_level;
    int                 max_level;
    double              overhead_estimate[MAX_MODULE_LEVELS];
    module_activate_fn  activate;
    module_deactivate_fn deactivate;
    module_collect_fn   collect;
    module_destroy_fn   destroy;
    void               *ctx;
    /* Auto-disable timer */
    uint64_t            auto_disable_at;  /* 0 = no auto-disable */
} jvmmon_module_t;

struct module_registry {
    jvmmon_agent_t  *agent;
    jvmmon_module_t  modules[MAX_MODULES];
    int              module_count;
    jvmmon_mutex_t   lock;
};

module_registry_t *module_registry_create(jvmmon_agent_t *agent);
void module_registry_deactivate_all(module_registry_t *mr);
void module_registry_destroy(module_registry_t *mr);

/* Register a module */
int module_registry_register(module_registry_t *mr, const char *name, int max_level,
                             module_activate_fn activate, module_deactivate_fn deactivate,
                             module_collect_fn collect, module_destroy_fn destroy,
                             void *ctx);

/* Set overhead estimates for each level */
void module_registry_set_overhead(module_registry_t *mr, const char *name,
                                   const double *overheads, int count);

/* Enable a module at a specific level with optional target and duration */
int module_registry_enable(module_registry_t *mr, const char *name,
                           int level, const char *target, int duration_sec);

/* Disable a module (returns to level 0) */
int module_registry_disable(module_registry_t *mr, const char *name);

/* Find module by name */
jvmmon_module_t *module_registry_find(module_registry_t *mr, const char *name);

/* Check auto-disable timers (called periodically) */
void module_registry_check_timers(module_registry_t *mr);

/* Handle command from collector */
void module_registry_handle_command(module_registry_t *mr, uint8_t cmd_type,
                                     const uint8_t *payload, uint32_t payload_len);

#endif /* JVMMON_MODULE_REGISTRY_H */
