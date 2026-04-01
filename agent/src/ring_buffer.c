/*
 * JVMMonitor - Lock-free SPSC Ring Buffer Implementation
 */
#include "jvmmon/ring_buffer.h"
#include <string.h>

int ring_buffer_init(ring_buffer_t *rb) {
    rb->head = 0;
    rb->tail = 0;
    rb->dropped = 0;
    rb->slots = (ring_slot_t *)jvmmon_calloc(RING_BUFFER_CAPACITY, sizeof(ring_slot_t));
    if (rb->slots == NULL) return -1;
    return 0;
}

void ring_buffer_destroy(ring_buffer_t *rb) {
    if (rb->slots != NULL) {
        jvmmon_free(rb->slots);
        rb->slots = NULL;
    }
}

int ring_buffer_push(ring_buffer_t *rb, const uint8_t *data, uint16_t len) {
    int32_t head = jvmmon_atomic_load(&rb->head);
    int32_t tail = jvmmon_atomic_load(&rb->tail);

    /* Full when (head - tail) == capacity */
    if ((head - tail) >= RING_BUFFER_CAPACITY) {
        jvmmon_atomic_add(&rb->dropped, 1);
        return -1;
    }

    ring_slot_t *slot = &rb->slots[head & RING_BUFFER_MASK];
    slot->length = len;
    memcpy(slot->data, data, len);

    /* Publish: store head after data is written */
    jvmmon_atomic_store(&rb->head, head + 1);
    return 0;
}

int ring_buffer_pop(ring_buffer_t *rb, uint8_t *out_buf) {
    int32_t tail = jvmmon_atomic_load(&rb->tail);
    int32_t head = jvmmon_atomic_load(&rb->head);

    if (tail == head) {
        return 0; /* empty */
    }

    ring_slot_t *slot = &rb->slots[tail & RING_BUFFER_MASK];
    uint16_t len = slot->length;
    memcpy(out_buf, slot->data, len);

    /* Publish: store tail after data is read */
    jvmmon_atomic_store(&rb->tail, tail + 1);
    return (int)len;
}

int ring_buffer_is_empty(ring_buffer_t *rb) {
    return jvmmon_atomic_load(&rb->head) == jvmmon_atomic_load(&rb->tail);
}

int ring_buffer_size(ring_buffer_t *rb) {
    return jvmmon_atomic_load(&rb->head) - jvmmon_atomic_load(&rb->tail);
}

int32_t ring_buffer_dropped(ring_buffer_t *rb) {
    return jvmmon_atomic_load(&rb->dropped);
}
