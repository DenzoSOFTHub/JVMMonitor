/*
 * JVMMonitor - JIT Compiler Events Implementation
 * Throttled: GenerateEvents initial burst is counted but not sent individually.
 * Instead, a single summary message is sent after the burst completes.
 * Subsequent incremental events are sent normally.
 */
#include "jvmmon/jit_tracker.h"
#include "jvmmon/log.h"
#include "jvmmon/protocol.h"
#include <string.h>

jit_tracker_t *jit_tracker_create(jvmmon_agent_t *agent) {
    jit_tracker_t *jt = (jit_tracker_t *)jvmmon_calloc(1, sizeof(jit_tracker_t));
    if (jt == NULL) return NULL;
    jt->agent = agent;
    return jt;
}

void jit_tracker_destroy(jit_tracker_t *jt) {
    if (jt != NULL) jvmmon_free(jt);
}

static void send_burst_summary(jit_tracker_t *jt) {
    uint8_t payload[128];
    int off = 0;
    off += protocol_encode_u64(payload + off, jvmmon_time_millis());
    off += protocol_encode_u8(payload + off, JIT_EVENT_COMPILED);
    off += protocol_encode_string(payload + off, "_burst_summary", 14);
    off += protocol_encode_string(payload + off, "initial_replay", 14);
    off += protocol_encode_i32(payload + off, 0); /* code_size = 0 for summary */
    off += protocol_encode_u64(payload + off, 0); /* code_addr = 0 */
    off += protocol_encode_i32(payload + off, jvmmon_atomic_load(&jt->compiled_count));

    agent_send_message(JVMMON_MSG_JIT_EVENT, payload, (uint32_t)off);
    LOG_INFO("JIT initial burst: %d already-compiled methods (sent as summary)",
             jvmmon_atomic_load(&jt->burst_count));
}

int jit_tracker_activate(int level, const char *target, void *ctx) {
    jit_tracker_t *jt = (jit_tracker_t *)ctx;
    jvmtiEnv *jvmti = jt->agent->jvmti;
    (void)level; (void)target;

    jt->compiled_count = 0;
    jt->deopt_count = 0;
    jt->burst_count = 0;

    (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE,
            JVMTI_EVENT_COMPILED_METHOD_LOAD, NULL);
    (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE,
            JVMTI_EVENT_COMPILED_METHOD_UNLOAD, NULL);

    jvmmon_atomic_store(&jt->active, 1);

    /* Replay already-compiled methods — mark as burst so we don't flood */
    jvmmon_atomic_store(&jt->in_initial_burst, 1);
    (*jvmti)->GenerateEvents(jvmti, JVMTI_EVENT_COMPILED_METHOD_LOAD);
    jvmmon_atomic_store(&jt->in_initial_burst, 0);

    /* Send single summary for the burst */
    send_burst_summary(jt);

    LOG_INFO("JIT tracker activated");
    return 0;
}

int jit_tracker_deactivate(int level, void *ctx) {
    jit_tracker_t *jt = (jit_tracker_t *)ctx;
    jvmtiEnv *jvmti = jt->agent->jvmti;
    (void)level;

    jvmmon_atomic_store(&jt->active, 0);

    (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_DISABLE,
            JVMTI_EVENT_COMPILED_METHOD_LOAD, NULL);
    (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_DISABLE,
            JVMTI_EVENT_COMPILED_METHOD_UNLOAD, NULL);

    LOG_INFO("JIT tracker deactivated (compiled=%d, deopt=%d)",
             jvmmon_atomic_load(&jt->compiled_count),
             jvmmon_atomic_load(&jt->deopt_count));
    return 0;
}

void jit_tracker_on_compiled_method_load(jit_tracker_t *jt,
        jmethodID method, jint code_size, const void *code_addr) {
    if (!jvmmon_atomic_load(&jt->active)) return;
    jvmmon_atomic_add(&jt->compiled_count, 1);

    /* During initial burst, just count — don't send individual events */
    if (jvmmon_atomic_load(&jt->in_initial_burst)) {
        jvmmon_atomic_add(&jt->burst_count, 1);
        return;
    }

    /* Incremental event — send full details */
    char class_name[256] = {0};
    char method_name[256] = {0};
    agent_resolve_method(method, class_name, 256, method_name, 256, NULL, 0);

    uint8_t payload[512];
    int off = 0;
    off += protocol_encode_u64(payload + off, jvmmon_time_millis());
    off += protocol_encode_u8(payload + off, JIT_EVENT_COMPILED);
    off += protocol_encode_string(payload + off, class_name, (uint16_t)strlen(class_name));
    off += protocol_encode_string(payload + off, method_name, (uint16_t)strlen(method_name));
    off += protocol_encode_i32(payload + off, code_size);
    off += protocol_encode_u64(payload + off, (uint64_t)(uintptr_t)code_addr);
    off += protocol_encode_i32(payload + off, jvmmon_atomic_load(&jt->compiled_count));

    agent_send_message(JVMMON_MSG_JIT_EVENT, payload, (uint32_t)off);
    LOG_DEBUG("JIT compiled: %s.%s (%d bytes)", class_name, method_name, code_size);
}

void jit_tracker_on_compiled_method_unload(jit_tracker_t *jt,
        jmethodID method, const void *code_addr) {
    if (!jvmmon_atomic_load(&jt->active)) return;
    jvmmon_atomic_add(&jt->deopt_count, 1);
    (void)method;

    uint8_t payload[64];
    int off = 0;
    off += protocol_encode_u64(payload + off, jvmmon_time_millis());
    off += protocol_encode_u8(payload + off, JIT_EVENT_UNLOADED);
    off += protocol_encode_u64(payload + off, (uint64_t)(uintptr_t)code_addr);
    off += protocol_encode_i32(payload + off, jvmmon_atomic_load(&jt->deopt_count));

    agent_send_message(JVMMON_MSG_JIT_EVENT, payload, (uint32_t)off);
}
