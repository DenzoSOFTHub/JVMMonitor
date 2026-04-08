/*
 * JVMMonitor - Alarm Engine Implementation
 */
#include "jvmmon/alarm_engine.h"
#include "jvmmon/protocol.h"
#include <string.h>
#include <stdio.h>

alarm_engine_t *alarm_engine_create(jvmmon_agent_t *agent) {
    alarm_engine_t *ae = (alarm_engine_t *)jvmmon_calloc(1, sizeof(alarm_engine_t));
    if (ae == NULL) return NULL;
    ae->agent = agent;
    ae->rule_count = 0;
    jvmmon_mutex_init(&ae->lock);
    return ae;
}

void alarm_engine_destroy(alarm_engine_t *ae) {
    if (ae != NULL) {
        jvmmon_mutex_destroy(&ae->lock);
        jvmmon_free(ae);
    }
}

static void add_rule(alarm_engine_t *ae, int type, int severity, double threshold,
                     const char *msg) {
    if (ae->rule_count >= MAX_ALARM_RULES) return;
    alarm_rule_t *r = &ae->rules[ae->rule_count++];
    r->alarm_type = type;
    r->severity = severity;
    r->threshold = threshold;
    r->current_value = 0;
    r->active = 0;
    r->last_fired = 0;
    strncpy(r->message, msg, sizeof(r->message) - 1);
    r->message[sizeof(r->message) - 1] = '\0';
}

void alarm_engine_add_defaults(alarm_engine_t *ae) {
    jvmmon_mutex_lock(&ae->lock);
    add_rule(ae, ALARM_TYPE_GC_PAUSE, JVMMON_ALARM_WARNING, 200.0,
             "GC pause > 200ms");
    add_rule(ae, ALARM_TYPE_GC_PAUSE, JVMMON_ALARM_CRITICAL, 1000.0,
             "GC pause > 1s");
    add_rule(ae, ALARM_TYPE_HEAP_USAGE, JVMMON_ALARM_WARNING, 80.0,
             "Heap usage > 80%");
    add_rule(ae, ALARM_TYPE_HEAP_USAGE, JVMMON_ALARM_CRITICAL, 95.0,
             "Heap usage > 95%");
    add_rule(ae, ALARM_TYPE_THREAD_BLOCKED, JVMMON_ALARM_WARNING, 30.0,
             "More than 30% threads BLOCKED");
    add_rule(ae, ALARM_TYPE_THREAD_BLOCKED, JVMMON_ALARM_CRITICAL, 60.0,
             "More than 60% threads BLOCKED");
    jvmmon_mutex_unlock(&ae->lock);
}

static void fire_alarm(alarm_engine_t *ae, alarm_rule_t *rule) {
    uint64_t now = jvmmon_time_millis();

    /* Debounce: don't fire same alarm more than once per 10 seconds */
    if (rule->last_fired != 0 && (now - rule->last_fired) < 10000) return;

    rule->active = 1;
    rule->last_fired = now;

    /* Encode alarm message */
    uint8_t payload[512];
    int off = 0;
    uint16_t msg_len = (uint16_t)strlen(rule->message);

    off += protocol_encode_u64(payload + off, now);
    off += protocol_encode_u8(payload + off, (uint8_t)rule->alarm_type);
    off += protocol_encode_u8(payload + off, (uint8_t)rule->severity);
    off += protocol_encode_u64(payload + off, (uint64_t)(rule->current_value * 1000.0));
    off += protocol_encode_u64(payload + off, (uint64_t)(rule->threshold * 1000.0));
    off += protocol_encode_string(payload + off, rule->message, msg_len);

    agent_send_message(JVMMON_MSG_ALARM, payload, (uint32_t)off);
}

void alarm_engine_update(alarm_engine_t *ae, int alarm_type, double value) {
    int i;
    jvmmon_mutex_lock(&ae->lock);

    for (i = 0; i < ae->rule_count; i++) {
        alarm_rule_t *r = &ae->rules[i];
        if (r->alarm_type != alarm_type) continue;

        r->current_value = value;

        if (value >= r->threshold) {
            fire_alarm(ae, r);
        } else if (r->active && value < r->threshold * 0.9) {
            /* Hysteresis: clear at 90% of threshold */
            r->active = 0;
        }
    }

    jvmmon_mutex_unlock(&ae->lock);
}
