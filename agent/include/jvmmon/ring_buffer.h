/*
 * JVMMonitor - Lock-free SPSC Ring Buffer
 * Single-producer, single-consumer. Safe for use from signal handlers.
 */
#ifndef JVMMON_RING_BUFFER_H
#define JVMMON_RING_BUFFER_H

#include <stdint.h>
#include "platform.h"
#include "protocol.h"

#define RING_BUFFER_SLOT_SIZE    (JVMMON_HEADER_SIZE + JVMMON_MAX_PAYLOAD)
#define RING_BUFFER_CAPACITY     4096  /* must be power of 2 */
#define RING_BUFFER_MASK         (RING_BUFFER_CAPACITY - 1)

typedef struct {
    uint16_t length;
    uint8_t  data[RING_BUFFER_SLOT_SIZE];
} ring_slot_t;

typedef struct {
    volatile uint32_t head;     /* next write position (producer) — unsigned to wrap safely at 2^32 */
    char pad1[60];              /* cache line padding */
    volatile uint32_t tail;     /* next read position (consumer) */
    char pad2[60];              /* cache line padding */
    volatile int32_t dropped;   /* dropped message count */
    ring_slot_t *slots;         /* heap-allocated slot array */
} ring_buffer_t;

int  ring_buffer_init(ring_buffer_t *rb);
void ring_buffer_destroy(ring_buffer_t *rb);

/* Producer: returns 0 on success, -1 if full (message dropped) */
int  ring_buffer_push(ring_buffer_t *rb, const uint8_t *data, uint16_t len);

/* Consumer: returns message length (> 0), or 0 if empty */
int  ring_buffer_pop(ring_buffer_t *rb, uint8_t *out_buf);

int     ring_buffer_is_empty(ring_buffer_t *rb);
int     ring_buffer_size(ring_buffer_t *rb);
int32_t ring_buffer_dropped(ring_buffer_t *rb);

#endif /* JVMMON_RING_BUFFER_H */
