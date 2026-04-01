/*
 * JVMMonitor - Native Agent Unit Tests
 * Minimal test framework: no dependencies, just assert + counters.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

/* Simple test framework */
static int tests_run = 0;
static int tests_passed = 0;
static int tests_failed = 0;

#define TEST(name) static void name(void)
#define RUN_TEST(name) do { \
    printf("  %-50s", #name); \
    tests_run++; \
    name(); \
    tests_passed++; \
    printf(" PASS\n"); \
} while(0)

#define ASSERT(cond) do { \
    if (!(cond)) { \
        printf(" FAIL\n    Assertion failed: %s (%s:%d)\n", #cond, __FILE__, __LINE__); \
        tests_failed++; \
        tests_passed--; \
        return; \
    } \
} while(0)

#define ASSERT_EQ(a, b) do { \
    if ((a) != (b)) { \
        printf(" FAIL\n    Expected %lld == %lld (%s:%d)\n", \
               (long long)(a), (long long)(b), __FILE__, __LINE__); \
        tests_failed++; \
        tests_passed--; \
        return; \
    } \
} while(0)

#define ASSERT_STR_EQ(a, b) do { \
    if (strcmp((a), (b)) != 0) { \
        printf(" FAIL\n    Expected \"%s\" == \"%s\" (%s:%d)\n", (a), (b), __FILE__, __LINE__); \
        tests_failed++; \
        tests_passed--; \
        return; \
    } \
} while(0)

/* ================================================================
 * Include the code under test
 * ================================================================ */

#include "jvmmon/protocol.h"
#include "jvmmon/platform.h"
#include "jvmmon/ring_buffer.h"

/* ================================================================
 * Protocol Tests
 * ================================================================ */

TEST(test_protocol_encode_decode_u8) {
    uint8_t buf[1];
    protocol_encode_u8(buf, 0xAB);
    ASSERT_EQ(protocol_decode_u8(buf), 0xAB);
}

TEST(test_protocol_encode_decode_u16) {
    uint8_t buf[2];
    protocol_encode_u16(buf, 0x1234);
    ASSERT_EQ(protocol_decode_u16(buf), 0x1234);
    /* Verify big-endian: high byte first */
    ASSERT_EQ(buf[0], 0x12);
    ASSERT_EQ(buf[1], 0x34);
}

TEST(test_protocol_encode_decode_u32) {
    uint8_t buf[4];
    protocol_encode_u32(buf, 0xDEADBEEF);
    ASSERT_EQ(protocol_decode_u32(buf), 0xDEADBEEF);
    ASSERT_EQ(buf[0], 0xDE);
    ASSERT_EQ(buf[1], 0xAD);
    ASSERT_EQ(buf[2], 0xBE);
    ASSERT_EQ(buf[3], 0xEF);
}

TEST(test_protocol_encode_decode_u64) {
    uint8_t buf[8];
    uint64_t val = 0x0102030405060708ULL;
    protocol_encode_u64(buf, val);
    ASSERT_EQ(protocol_decode_u64(buf), val);
    ASSERT_EQ(buf[0], 0x01);
    ASSERT_EQ(buf[7], 0x08);
}

TEST(test_protocol_encode_decode_i32_negative) {
    uint8_t buf[4];
    protocol_encode_i32(buf, -12345);
    ASSERT_EQ(protocol_decode_i32(buf), -12345);
}

TEST(test_protocol_encode_decode_i64_negative) {
    uint8_t buf[8];
    protocol_encode_i64(buf, -9876543210LL);
    ASSERT_EQ(protocol_decode_i64(buf), -9876543210LL);
}

TEST(test_protocol_encode_string) {
    uint8_t buf[64];
    const char *str = "Hello";
    int len = protocol_encode_string(buf, str, 5);
    ASSERT_EQ(len, 7); /* 2 (length prefix) + 5 (data) */
    ASSERT_EQ(protocol_decode_u16(buf), 5);
    ASSERT(memcmp(buf + 2, "Hello", 5) == 0);
}

TEST(test_protocol_encode_string_empty) {
    uint8_t buf[16];
    int len = protocol_encode_string(buf, NULL, 0);
    ASSERT_EQ(len, 2); /* just the length prefix */
    ASSERT_EQ(protocol_decode_u16(buf), 0);
}

TEST(test_protocol_encode_header) {
    uint8_t buf[JVMMON_HEADER_SIZE];
    int len = protocol_encode_header(buf, JVMMON_MSG_CPU_SAMPLE, 256);
    ASSERT_EQ(len, JVMMON_HEADER_SIZE);
    ASSERT_EQ(protocol_decode_u32(buf), JVMMON_PROTOCOL_MAGIC);
    ASSERT_EQ(buf[4], JVMMON_PROTOCOL_VERSION);
    ASSERT_EQ(buf[5], JVMMON_MSG_CPU_SAMPLE);
    ASSERT_EQ(protocol_decode_u32(buf + 6), 256);
}

TEST(test_protocol_validate_header_valid) {
    uint8_t buf[JVMMON_HEADER_SIZE];
    protocol_encode_header(buf, JVMMON_MSG_GC_EVENT, 100);
    uint32_t payload_len = 0;
    int type = protocol_validate_header(buf, &payload_len);
    ASSERT_EQ(type, JVMMON_MSG_GC_EVENT);
    ASSERT_EQ(payload_len, 100);
}

TEST(test_protocol_validate_header_bad_magic) {
    uint8_t buf[JVMMON_HEADER_SIZE];
    protocol_encode_header(buf, JVMMON_MSG_GC_EVENT, 100);
    buf[0] = 0xFF; /* corrupt magic */
    uint32_t payload_len = 0;
    int type = protocol_validate_header(buf, &payload_len);
    ASSERT_EQ(type, -1);
}

TEST(test_protocol_validate_header_bad_version) {
    uint8_t buf[JVMMON_HEADER_SIZE];
    protocol_encode_header(buf, JVMMON_MSG_GC_EVENT, 100);
    buf[4] = 99; /* wrong version */
    uint32_t payload_len = 0;
    int type = protocol_validate_header(buf, &payload_len);
    ASSERT_EQ(type, -1);
}

TEST(test_protocol_validate_header_payload_too_large) {
    uint8_t buf[JVMMON_HEADER_SIZE];
    protocol_encode_header(buf, JVMMON_MSG_GC_EVENT, JVMMON_MAX_PAYLOAD + 1);
    uint32_t payload_len = 0;
    int type = protocol_validate_header(buf, &payload_len);
    ASSERT_EQ(type, -1);
}

TEST(test_protocol_encode_bytes) {
    uint8_t buf[64];
    uint8_t data[] = {0xAA, 0xBB, 0xCC};
    int len = protocol_encode_bytes(buf, data, 3);
    ASSERT_EQ(len, 7); /* 4 (length) + 3 (data) */
    ASSERT_EQ(protocol_decode_u32(buf), 3);
    ASSERT_EQ(buf[4], 0xAA);
    ASSERT_EQ(buf[5], 0xBB);
    ASSERT_EQ(buf[6], 0xCC);
}

TEST(test_protocol_all_message_types_distinct) {
    /* Ensure no two message type constants have the same value */
    int types[] = {
        JVMMON_MSG_HANDSHAKE, JVMMON_MSG_HEARTBEAT, JVMMON_MSG_COMMAND,
        JVMMON_MSG_COMMAND_RESP, JVMMON_MSG_CPU_SAMPLE, JVMMON_MSG_GC_EVENT,
        JVMMON_MSG_THREAD_SNAPSHOT, JVMMON_MSG_THREAD_EVENT,
        JVMMON_MSG_MEMORY_SNAPSHOT, JVMMON_MSG_CLASS_INFO, JVMMON_MSG_ALARM,
        JVMMON_MSG_MODULE_EVENT, JVMMON_MSG_ALLOC_SAMPLE,
        JVMMON_MSG_MONITOR_EVENT, JVMMON_MSG_METHOD_INFO
    };
    int n = sizeof(types) / sizeof(types[0]);
    int i, j;
    for (i = 0; i < n; i++) {
        for (j = i + 1; j < n; j++) {
            ASSERT(types[i] != types[j]);
        }
    }
}

/* ================================================================
 * Ring Buffer Tests
 * ================================================================ */

TEST(test_ring_buffer_init_destroy) {
    ring_buffer_t rb;
    ASSERT_EQ(ring_buffer_init(&rb), 0);
    ASSERT(ring_buffer_is_empty(&rb));
    ASSERT_EQ(ring_buffer_size(&rb), 0);
    ASSERT_EQ(ring_buffer_dropped(&rb), 0);
    ring_buffer_destroy(&rb);
}

TEST(test_ring_buffer_push_pop_single) {
    ring_buffer_t rb;
    ring_buffer_init(&rb);

    uint8_t data[] = {1, 2, 3, 4, 5};
    ASSERT_EQ(ring_buffer_push(&rb, data, 5), 0);
    ASSERT(!ring_buffer_is_empty(&rb));
    ASSERT_EQ(ring_buffer_size(&rb), 1);

    uint8_t out[RING_BUFFER_SLOT_SIZE];
    int len = ring_buffer_pop(&rb, out);
    ASSERT_EQ(len, 5);
    ASSERT(memcmp(out, data, 5) == 0);
    ASSERT(ring_buffer_is_empty(&rb));

    ring_buffer_destroy(&rb);
}

TEST(test_ring_buffer_push_pop_multiple) {
    ring_buffer_t rb;
    ring_buffer_init(&rb);

    uint8_t d1[] = {0xAA};
    uint8_t d2[] = {0xBB, 0xCC};
    uint8_t d3[] = {0xDD, 0xEE, 0xFF};
    ring_buffer_push(&rb, d1, 1);
    ring_buffer_push(&rb, d2, 2);
    ring_buffer_push(&rb, d3, 3);
    ASSERT_EQ(ring_buffer_size(&rb), 3);

    uint8_t out[RING_BUFFER_SLOT_SIZE];
    int len;

    len = ring_buffer_pop(&rb, out);
    ASSERT_EQ(len, 1);
    ASSERT_EQ(out[0], 0xAA);

    len = ring_buffer_pop(&rb, out);
    ASSERT_EQ(len, 2);
    ASSERT_EQ(out[0], 0xBB);
    ASSERT_EQ(out[1], 0xCC);

    len = ring_buffer_pop(&rb, out);
    ASSERT_EQ(len, 3);
    ASSERT_EQ(out[0], 0xDD);

    ASSERT(ring_buffer_is_empty(&rb));
    ring_buffer_destroy(&rb);
}

TEST(test_ring_buffer_pop_empty_returns_zero) {
    ring_buffer_t rb;
    ring_buffer_init(&rb);

    uint8_t out[RING_BUFFER_SLOT_SIZE];
    ASSERT_EQ(ring_buffer_pop(&rb, out), 0);

    ring_buffer_destroy(&rb);
}

TEST(test_ring_buffer_full_drops) {
    ring_buffer_t rb;
    ring_buffer_init(&rb);

    uint8_t data[] = {0x42};
    int i;
    /* Fill to capacity */
    for (i = 0; i < RING_BUFFER_CAPACITY; i++) {
        ASSERT_EQ(ring_buffer_push(&rb, data, 1), 0);
    }
    ASSERT_EQ(ring_buffer_size(&rb), RING_BUFFER_CAPACITY);

    /* Next push should fail */
    ASSERT_EQ(ring_buffer_push(&rb, data, 1), -1);
    ASSERT_EQ(ring_buffer_dropped(&rb), 1);

    /* Pop one, then push succeeds again */
    uint8_t out[RING_BUFFER_SLOT_SIZE];
    ring_buffer_pop(&rb, out);
    ASSERT_EQ(ring_buffer_push(&rb, data, 1), 0);

    ring_buffer_destroy(&rb);
}

TEST(test_ring_buffer_fifo_order) {
    ring_buffer_t rb;
    ring_buffer_init(&rb);

    int i;
    for (i = 0; i < 100; i++) {
        uint8_t data[4];
        protocol_encode_u32(data, (uint32_t)i);
        ring_buffer_push(&rb, data, 4);
    }

    for (i = 0; i < 100; i++) {
        uint8_t out[RING_BUFFER_SLOT_SIZE];
        int len = ring_buffer_pop(&rb, out);
        ASSERT_EQ(len, 4);
        ASSERT_EQ(protocol_decode_u32(out), (uint32_t)i);
    }

    ring_buffer_destroy(&rb);
}

TEST(test_ring_buffer_wrap_around) {
    ring_buffer_t rb;
    ring_buffer_init(&rb);

    uint8_t data[] = {0x01};
    uint8_t out[RING_BUFFER_SLOT_SIZE];
    int i;

    /* Push and pop more than capacity to test wrap-around */
    for (i = 0; i < RING_BUFFER_CAPACITY * 3; i++) {
        data[0] = (uint8_t)(i & 0xFF);
        ASSERT_EQ(ring_buffer_push(&rb, data, 1), 0);
        int len = ring_buffer_pop(&rb, out);
        ASSERT_EQ(len, 1);
        ASSERT_EQ(out[0], (uint8_t)(i & 0xFF));
    }

    ASSERT(ring_buffer_is_empty(&rb));
    ASSERT_EQ(ring_buffer_dropped(&rb), 0);

    ring_buffer_destroy(&rb);
}

/* ================================================================
 * Platform Tests
 * ================================================================ */

TEST(test_platform_time_nanos_monotonic) {
    uint64_t t1 = jvmmon_time_nanos();
    uint64_t t2 = jvmmon_time_nanos();
    ASSERT(t2 >= t1);
}

TEST(test_platform_time_millis_reasonable) {
    uint64_t ms = jvmmon_time_millis();
    /* Should be after 2020-01-01 in millis */
    ASSERT(ms > 1577836800000ULL);
}

TEST(test_platform_atomic_load_store) {
    volatile int32_t val = 0;
    jvmmon_atomic_store(&val, 42);
    ASSERT_EQ(jvmmon_atomic_load(&val), 42);
    jvmmon_atomic_store(&val, -1);
    ASSERT_EQ(jvmmon_atomic_load(&val), -1);
}

TEST(test_platform_atomic_add) {
    volatile int32_t val = 10;
    int32_t result = jvmmon_atomic_add(&val, 5);
    ASSERT_EQ(result, 15);
    ASSERT_EQ(jvmmon_atomic_load(&val), 15);
    result = jvmmon_atomic_add(&val, -3);
    ASSERT_EQ(result, 12);
}

TEST(test_platform_atomic_cas_success) {
    volatile int32_t val = 100;
    int32_t old = jvmmon_atomic_cas(&val, 100, 200);
    ASSERT_EQ(old, 100);
    ASSERT_EQ(jvmmon_atomic_load(&val), 200);
}

TEST(test_platform_atomic_cas_failure) {
    volatile int32_t val = 100;
    int32_t old = jvmmon_atomic_cas(&val, 999, 200);
    ASSERT_EQ(old, 100); /* returns old value, no swap */
    ASSERT_EQ(jvmmon_atomic_load(&val), 100); /* unchanged */
}

TEST(test_platform_alloc_free) {
    void *p = jvmmon_alloc(1024);
    ASSERT(p != NULL);
    memset(p, 0xAA, 1024);
    jvmmon_free(p);
}

TEST(test_platform_calloc_zeroed) {
    uint8_t *p = (uint8_t *)jvmmon_calloc(256, 1);
    ASSERT(p != NULL);
    int i;
    for (i = 0; i < 256; i++) {
        ASSERT_EQ(p[i], 0);
    }
    jvmmon_free(p);
}

TEST(test_platform_getpid) {
    int pid = jvmmon_getpid();
    ASSERT(pid > 0);
}

TEST(test_platform_gethostname) {
    char buf[256] = {0};
    int ret = jvmmon_gethostname(buf, sizeof(buf));
    ASSERT_EQ(ret, 0);
    ASSERT(strlen(buf) > 0);
}

TEST(test_platform_sleep) {
    uint64_t t1 = jvmmon_time_nanos();
    jvmmon_sleep_ms(50);
    uint64_t t2 = jvmmon_time_nanos();
    uint64_t elapsed_ms = (t2 - t1) / 1000000;
    ASSERT(elapsed_ms >= 40); /* allow some slack */
    ASSERT(elapsed_ms < 200);
}

/* ================================================================
 * Protocol Full Message Roundtrip Tests
 * ================================================================ */

TEST(test_protocol_gc_event_roundtrip) {
    uint8_t buf[JVMMON_HEADER_SIZE + 64];
    uint8_t *payload = buf + JVMMON_HEADER_SIZE;
    int off = 0;

    uint64_t ts = 1711700000000ULL;
    uint8_t gc_type = 3;
    uint64_t duration = 150000000ULL; /* 150ms in nanos */
    int32_t gc_count = 42;
    int32_t full_gc_count = 5;

    off += protocol_encode_u64(payload + off, ts);
    off += protocol_encode_u8(payload + off, gc_type);
    off += protocol_encode_u64(payload + off, duration);
    off += protocol_encode_i32(payload + off, gc_count);
    off += protocol_encode_i32(payload + off, full_gc_count);

    protocol_encode_header(buf, JVMMON_MSG_GC_EVENT, (uint32_t)off);

    /* Decode */
    uint32_t plen;
    int type = protocol_validate_header(buf, &plen);
    ASSERT_EQ(type, JVMMON_MSG_GC_EVENT);
    ASSERT_EQ(plen, (uint32_t)off);

    int roff = 0;
    ASSERT_EQ(protocol_decode_u64(payload + roff), ts); roff += 8;
    ASSERT_EQ(protocol_decode_u8(payload + roff), gc_type); roff += 1;
    ASSERT_EQ(protocol_decode_u64(payload + roff), duration); roff += 8;
    ASSERT_EQ(protocol_decode_i32(payload + roff), gc_count); roff += 4;
    ASSERT_EQ(protocol_decode_i32(payload + roff), full_gc_count);
}

TEST(test_protocol_memory_snapshot_roundtrip) {
    uint8_t payload[64];
    int off = 0;

    int64_t heap_used = 536870912LL;  /* 512MB */
    int64_t heap_max = 1073741824LL;  /* 1GB */
    int64_t nonheap_used = 67108864LL; /* 64MB */
    int64_t nonheap_max = 268435456LL; /* 256MB */

    off += protocol_encode_u64(payload + off, 1711700000000ULL);
    off += protocol_encode_i64(payload + off, heap_used);
    off += protocol_encode_i64(payload + off, heap_max);
    off += protocol_encode_i64(payload + off, nonheap_used);
    off += protocol_encode_i64(payload + off, nonheap_max);

    int roff = 8; /* skip timestamp */
    ASSERT_EQ(protocol_decode_i64(payload + roff), heap_used); roff += 8;
    ASSERT_EQ(protocol_decode_i64(payload + roff), heap_max); roff += 8;
    ASSERT_EQ(protocol_decode_i64(payload + roff), nonheap_used); roff += 8;
    ASSERT_EQ(protocol_decode_i64(payload + roff), nonheap_max);
}

TEST(test_protocol_alarm_roundtrip) {
    uint8_t payload[256];
    int off = 0;
    const char *msg = "GC pause > 200ms";
    uint16_t msg_len = (uint16_t)strlen(msg);

    off += protocol_encode_u64(payload + off, jvmmon_time_millis());
    off += protocol_encode_u8(payload + off, 2);  /* alarm type */
    off += protocol_encode_u8(payload + off, 1);  /* severity WARNING */
    off += protocol_encode_u64(payload + off, 250000ULL); /* value * 1000 */
    off += protocol_encode_u64(payload + off, 200000ULL); /* threshold * 1000 */
    off += protocol_encode_string(payload + off, msg, msg_len);

    int roff = 8; /* skip ts */
    ASSERT_EQ(protocol_decode_u8(payload + roff), 2); roff++;
    ASSERT_EQ(protocol_decode_u8(payload + roff), 1); roff++;
    ASSERT_EQ(protocol_decode_u64(payload + roff), 250000ULL); roff += 8;
    ASSERT_EQ(protocol_decode_u64(payload + roff), 200000ULL); roff += 8;
    uint16_t slen = protocol_decode_u16(payload + roff); roff += 2;
    ASSERT_EQ(slen, msg_len);
    ASSERT(memcmp(payload + roff, msg, msg_len) == 0);
}

/* ================================================================
 * Main
 * ================================================================ */

int main(void) {
    jvmmon_socket_init();

    printf("\n=== Protocol Tests ===\n");
    RUN_TEST(test_protocol_encode_decode_u8);
    RUN_TEST(test_protocol_encode_decode_u16);
    RUN_TEST(test_protocol_encode_decode_u32);
    RUN_TEST(test_protocol_encode_decode_u64);
    RUN_TEST(test_protocol_encode_decode_i32_negative);
    RUN_TEST(test_protocol_encode_decode_i64_negative);
    RUN_TEST(test_protocol_encode_string);
    RUN_TEST(test_protocol_encode_string_empty);
    RUN_TEST(test_protocol_encode_header);
    RUN_TEST(test_protocol_validate_header_valid);
    RUN_TEST(test_protocol_validate_header_bad_magic);
    RUN_TEST(test_protocol_validate_header_bad_version);
    RUN_TEST(test_protocol_validate_header_payload_too_large);
    RUN_TEST(test_protocol_encode_bytes);
    RUN_TEST(test_protocol_all_message_types_distinct);

    printf("\n=== Ring Buffer Tests ===\n");
    RUN_TEST(test_ring_buffer_init_destroy);
    RUN_TEST(test_ring_buffer_push_pop_single);
    RUN_TEST(test_ring_buffer_push_pop_multiple);
    RUN_TEST(test_ring_buffer_pop_empty_returns_zero);
    RUN_TEST(test_ring_buffer_full_drops);
    RUN_TEST(test_ring_buffer_fifo_order);
    RUN_TEST(test_ring_buffer_wrap_around);

    printf("\n=== Platform Tests ===\n");
    RUN_TEST(test_platform_time_nanos_monotonic);
    RUN_TEST(test_platform_time_millis_reasonable);
    RUN_TEST(test_platform_atomic_load_store);
    RUN_TEST(test_platform_atomic_add);
    RUN_TEST(test_platform_atomic_cas_success);
    RUN_TEST(test_platform_atomic_cas_failure);
    RUN_TEST(test_platform_alloc_free);
    RUN_TEST(test_platform_calloc_zeroed);
    RUN_TEST(test_platform_getpid);
    RUN_TEST(test_platform_gethostname);
    RUN_TEST(test_platform_sleep);

    printf("\n=== Protocol Roundtrip Tests ===\n");
    RUN_TEST(test_protocol_gc_event_roundtrip);
    RUN_TEST(test_protocol_memory_snapshot_roundtrip);
    RUN_TEST(test_protocol_alarm_roundtrip);

    jvmmon_socket_cleanup();

    printf("\n========================================\n");
    printf("Results: %d/%d passed, %d failed\n", tests_passed, tests_run, tests_failed);
    printf("========================================\n\n");

    return tests_failed > 0 ? 1 : 0;
}
