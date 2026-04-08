/*
 * JVMMonitor - Lock-free SPSC Ring Buffer Implementation
 * Uses uint32_t head/tail to wrap safely at 2^32 (~136 years at 1000 msg/s).
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
    /* Validate data fits in slot */
    if (len > RING_BUFFER_SLOT_SIZE) {
        jvmmon_atomic_add(&rb->dropped, 1);
        return -1;
    }

    uint32_t head = __sync_val_compare_and_swap(&rb->head, 0, 0); /* atomic load */
    uint32_t tail = __sync_val_compare_and_swap(&rb->tail, 0, 0);

    /* Full when (head - tail) == capacity. Unsigned subtraction wraps correctly. */
    if ((head - tail) >= RING_BUFFER_CAPACITY) {
        jvmmon_atomic_add(&rb->dropped, 1);
        return -1;
    }

    ring_slot_t *slot = &rb->slots[head & RING_BUFFER_MASK];
    slot->length = len;
    memcpy(slot->data, data, len);

    /* Publish: store head after data is written */
    __sync_synchronize();
    __sync_lock_test_and_set(&rb->head, head + 1);
    return 0;
}

int ring_buffer_pop(ring_buffer_t *rb, uint8_t *out_buf) {
    uint32_t tail = __sync_val_compare_and_swap(&rb->tail, 0, 0);
    uint32_t head = __sync_val_compare_and_swap(&rb->head, 0, 0);

    if (tail == head) {
        return 0; /* empty */
    }

    ring_slot_t *slot = &rb->slots[tail & RING_BUFFER_MASK];
    uint16_t len = slot->length;
    if (len > RING_BUFFER_SLOT_SIZE) len = RING_BUFFER_SLOT_SIZE; /* safety clamp */
    memcpy(out_buf, slot->data, len);

    /* Publish: store tail after data is read */
    __sync_synchronize();
    __sync_lock_test_and_set(&rb->tail, tail + 1);
    return (int)len;
}

int ring_buffer_is_empty(ring_buffer_t *rb) {
    return __sync_val_compare_and_swap(&rb->head, 0, 0)
        == __sync_val_compare_and_swap(&rb->tail, 0, 0);
}

int ring_buffer_size(ring_buffer_t *rb) {
    uint32_t h = __sync_val_compare_and_swap(&rb->head, 0, 0);
    uint32_t t = __sync_val_compare_and_swap(&rb->tail, 0, 0);
    return (int)(h - t);
}

int32_t ring_buffer_dropped(ring_buffer_t *rb) {
    return jvmmon_atomic_load(&rb->dropped);
}
