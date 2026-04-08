/*
 * JVMMonitor - Module Registry Implementation
 */
#include "jvmmon/module_registry.h"
#include "jvmmon/protocol.h"
#include <string.h>

module_registry_t *module_registry_create(jvmmon_agent_t *agent) {
    module_registry_t *mr = (module_registry_t *)jvmmon_calloc(1, sizeof(module_registry_t));
    if (mr == NULL) return NULL;
    mr->agent = agent;
    mr->module_count = 0;
    jvmmon_mutex_init(&mr->lock);

    /* Stub modules for future profiling (alloc, locks, etc.) */
    static const char *stub_names[] = { "alloc", "locks", "memory", "resources", "io", "method" };
    int i;
    for (i = 0; i < 6; i++) {
        module_registry_register(mr, stub_names[i], 3, NULL, NULL, NULL, NULL, NULL);
    }
    /* Real modules (exceptions, os, jit, histogram, buffers) are registered
     * in cb_vm_init after their instances are created. */

    return mr;
}

void module_registry_deactivate_all(module_registry_t *mr) {
    if (mr == NULL) return;
    int i;
    jvmmon_mutex_lock(&mr->lock);
    for (i = 0; i < mr->module_count; i++) {
        if (mr->modules[i].current_level > 0 && mr->modules[i].deactivate) {
            mr->modules[i].deactivate(mr->modules[i].current_level, mr->modules[i].ctx);
            mr->modules[i].current_level = 0;
        }
    }
    jvmmon_mutex_unlock(&mr->lock);
}

void module_registry_destroy(module_registry_t *mr) {
    if (mr == NULL) return;
    int i;
    jvmmon_mutex_lock(&mr->lock);
    for (i = 0; i < mr->module_count; i++) {
        if (mr->modules[i].destroy && mr->modules[i].ctx) {
            mr->modules[i].destroy(mr->modules[i].ctx);
        }
    }
    jvmmon_mutex_unlock(&mr->lock);
    jvmmon_mutex_destroy(&mr->lock);
    jvmmon_free(mr);
}

int module_registry_register(module_registry_t *mr, const char *name, int max_level,
                             module_activate_fn activate, module_deactivate_fn deactivate,
                             module_collect_fn collect, module_destroy_fn destroy,
                             void *ctx) {
    jvmmon_mutex_lock(&mr->lock);
    if (mr->module_count >= MAX_MODULES) {
        jvmmon_mutex_unlock(&mr->lock);
        return -1;
    }

    jvmmon_module_t *m = &mr->modules[mr->module_count++];
    memset(m, 0, sizeof(jvmmon_module_t));
    strncpy(m->name, name, MAX_MODULE_NAME - 1);
    m->max_level = max_level;
    m->current_level = 0;
    m->activate = activate;
    m->deactivate = deactivate;
    m->collect = collect;
    m->destroy = destroy;
    m->ctx = ctx;
    m->auto_disable_at = 0;

    jvmmon_mutex_unlock(&mr->lock);
    return 0;
}

void module_registry_set_overhead(module_registry_t *mr, const char *name,
                                   const double *overheads, int count) {
    jvmmon_module_t *m = module_registry_find(mr, name);
    if (m == NULL) return;
    int i;
    for (i = 0; i < count && i < MAX_MODULE_LEVELS; i++) {
        m->overhead_estimate[i] = overheads[i];
    }
}

jvmmon_module_t *module_registry_find(module_registry_t *mr, const char *name) {
    int i;
    for (i = 0; i < mr->module_count; i++) {
        if (strcmp(mr->modules[i].name, name) == 0) {
            return &mr->modules[i];
        }
    }
    return NULL;
}

int module_registry_enable(module_registry_t *mr, const char *name,
                           int level, const char *target, int duration_sec) {
    jvmmon_mutex_lock(&mr->lock);

    jvmmon_module_t *m = module_registry_find(mr, name);
    if (m == NULL) {
        jvmmon_mutex_unlock(&mr->lock);
        return -1;
    }

    if (level < 0 || level > m->max_level) {
        jvmmon_mutex_unlock(&mr->lock);
        return -1;
    }

    int old_level = m->current_level;

    if (level == 0 && old_level > 0) {
        /* Deactivate: call deactivate callback, not activate(0) */
        if (m->deactivate != NULL) {
            m->deactivate(old_level, m->ctx);
        }
    } else if (level > 0) {
        /* Deactivate old level first if changing levels */
        if (old_level > 0 && old_level != level && m->deactivate != NULL) {
            m->deactivate(old_level, m->ctx);
        }
        if (m->activate != NULL) {
            if (m->activate(level, target, m->ctx) != 0) {
                jvmmon_mutex_unlock(&mr->lock);
                return -1;
            }
        }
    }

    m->current_level = level;

    if (duration_sec > 0) {
        m->auto_disable_at = jvmmon_time_millis() + (uint64_t)duration_sec * 1000;
    } else {
        m->auto_disable_at = 0;
    }

    /* Send ack to collector */
    uint8_t payload[256];
    int off = 0;
    uint16_t nlen = (uint16_t)strlen(name);
    off += protocol_encode_u64(payload + off, jvmmon_time_millis());
    off += protocol_encode_string(payload + off, name, nlen);
    off += protocol_encode_u8(payload + off, (uint8_t)old_level);
    off += protocol_encode_u8(payload + off, (uint8_t)level);
    off += protocol_encode_u8(payload + off, (uint8_t)m->max_level);
    agent_send_message(JVMMON_MSG_MODULE_EVENT, payload, (uint32_t)off);

    jvmmon_mutex_unlock(&mr->lock);
    return 0;
}

int module_registry_disable(module_registry_t *mr, const char *name) {
    return module_registry_enable(mr, name, 0, NULL, 0);
}

void module_registry_check_timers(module_registry_t *mr) {
    int i;
    uint64_t now = jvmmon_time_millis();

    jvmmon_mutex_lock(&mr->lock);
    for (i = 0; i < mr->module_count; i++) {
        jvmmon_module_t *m = &mr->modules[i];
        if (m->auto_disable_at > 0 && now >= m->auto_disable_at) {
            if (m->deactivate != NULL) {
                m->deactivate(0, m->ctx);
            }
            m->current_level = 0;
            m->auto_disable_at = 0;

            /* Notify collector */
            uint8_t payload[256];
            int off = 0;
            uint16_t nlen = (uint16_t)strlen(m->name);
            off += protocol_encode_u64(payload + off, now);
            off += protocol_encode_string(payload + off, m->name, nlen);
            off += protocol_encode_u8(payload + off, (uint8_t)m->current_level);
            off += protocol_encode_u8(payload + off, 0);
            off += protocol_encode_u8(payload + off, (uint8_t)m->max_level);
            agent_send_message(JVMMON_MSG_MODULE_EVENT, payload, (uint32_t)off);
        }
    }
    jvmmon_mutex_unlock(&mr->lock);
}

void module_registry_handle_command(module_registry_t *mr, uint8_t cmd_type,
                                     const uint8_t *payload, uint32_t payload_len) {
    if (payload_len < 3) return;

    uint16_t name_len = protocol_decode_u16(payload);
    if (name_len + 2 > payload_len || name_len >= MAX_MODULE_NAME) return;

    char name[MAX_MODULE_NAME];
    memcpy(name, payload + 2, name_len);
    name[name_len] = '\0';

    const uint8_t *rest = payload + 2 + name_len;
    uint32_t rest_len = payload_len - 2 - name_len;

    switch (cmd_type) {
        case JVMMON_CMD_ENABLE_MODULE: {
            if (rest_len < 1) return;
            int level = protocol_decode_u8(rest);
            int duration = 300; /* default 5 minutes */
            char target[256] = {0};

            if (rest_len >= 5) {
                duration = (int)protocol_decode_u32(rest + 1);
            }
            if (rest_len >= 7) {
                uint16_t tlen = protocol_decode_u16(rest + 5);
                if (tlen > 0 && tlen < sizeof(target) && (7 + tlen) <= rest_len) {
                    memcpy(target, rest + 7, tlen);
                    target[tlen] = '\0';
                }
            }
            module_registry_enable(mr, name, level, target[0] ? target : NULL, duration);
            break;
        }
        case JVMMON_CMD_DISABLE_MODULE:
            module_registry_disable(mr, name);
            break;

        case JVMMON_CMD_SET_LEVEL: {
            if (rest_len < 1) return;
            int level = protocol_decode_u8(rest);
            module_registry_enable(mr, name, level, NULL, 0);
            break;
        }
        case JVMMON_CMD_LIST_MODULES: {
            /* Send module list */
            uint8_t resp[JVMMON_MAX_PAYLOAD];
            int off = 0;
            int i;

            off += protocol_encode_u64(resp + off, jvmmon_time_millis());
            off += protocol_encode_u16(resp + off, (uint16_t)mr->module_count);

            for (i = 0; i < mr->module_count && off < (int)(JVMMON_MAX_PAYLOAD - 64); i++) {
                jvmmon_module_t *m = &mr->modules[i];
                uint16_t nlen = (uint16_t)strlen(m->name);
                off += protocol_encode_string(resp + off, m->name, nlen);
                off += protocol_encode_u8(resp + off, (uint8_t)m->current_level);
                off += protocol_encode_u8(resp + off, (uint8_t)m->max_level);
            }
            agent_send_message(JVMMON_MSG_COMMAND_RESP, resp, (uint32_t)off);
            break;
        }
        case JVMMON_CMD_DETACH:
            jvmmon_atomic_store(&mr->agent->running, 0);
            break;
    }
}
