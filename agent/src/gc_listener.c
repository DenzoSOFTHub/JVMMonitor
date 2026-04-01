/*
 * JVMMonitor - GC Event Listener Implementation
 */
#include "jvmmon/gc_listener.h"
#include "jvmmon/alarm_engine.h"
#include "jvmmon/protocol.h"

gc_listener_t *gc_listener_create(jvmmon_agent_t *agent) {
    gc_listener_t *gl = (gc_listener_t *)jvmmon_calloc(1, sizeof(gc_listener_t));
    if (gl == NULL) return NULL;
    gl->agent = agent;
    gl->gc_count = 0;
    gl->full_gc_count = 0;
    return gl;
}

void gc_listener_destroy(gc_listener_t *gl) {
    if (gl != NULL) {
        jvmmon_free(gl);
    }
}

void gc_listener_on_gc_start(gc_listener_t *gl) {
    gl->gc_start_nanos = jvmmon_time_nanos();
}

void gc_listener_on_gc_finish(gc_listener_t *gl) {
    uint64_t end_nanos = jvmmon_time_nanos();
    uint64_t duration_nanos = end_nanos - gl->gc_start_nanos;
    uint64_t now_millis = jvmmon_time_millis();

    gl->gc_count++;
    gl->last_gc_end_nanos = end_nanos;

    /* Determine GC type heuristic: > 100ms is likely Full GC */
    int gc_type = 1; /* 1=young */
    if (duration_nanos > 100000000ULL) { /* > 100ms */
        gc_type = 3; /* 3=full */
        gl->full_gc_count++;
    }

    /* Encode GC event message
     * Payload: timestamp(8) + gc_type(1) + duration_ns(8) + gc_count(4) + full_gc_count(4)
     */
    uint8_t payload[64];
    int off = 0;

    off += protocol_encode_u64(payload + off, now_millis);
    off += protocol_encode_u8(payload + off, (uint8_t)gc_type);
    off += protocol_encode_u64(payload + off, duration_nanos);
    off += protocol_encode_i32(payload + off, gl->gc_count);
    off += protocol_encode_i32(payload + off, gl->full_gc_count);

    agent_send_message(JVMMON_MSG_GC_EVENT, payload, (uint32_t)off);

    /* Feed alarm engine */
    if (gl->agent->alarm_engine) {
        double pause_ms = (double)duration_nanos / 1000000.0;
        alarm_engine_update(gl->agent->alarm_engine, ALARM_TYPE_GC_PAUSE, pause_ms);
    }
}
