/*
 * JVMMonitor - Binary Wire Protocol Implementation
 */
#include "jvmmon/protocol.h"
#include <string.h>

/* ── Encoding ───────────────────────────────────────── */

int protocol_encode_header(uint8_t *buf, uint8_t msg_type, uint32_t payload_len) {
    int off = 0;
    off += protocol_encode_u32(buf + off, JVMMON_PROTOCOL_MAGIC);
    off += protocol_encode_u8(buf + off, JVMMON_PROTOCOL_VERSION);
    off += protocol_encode_u8(buf + off, msg_type);
    off += protocol_encode_u32(buf + off, payload_len);
    return off; /* always JVMMON_HEADER_SIZE = 10 */
}

int protocol_encode_u8(uint8_t *buf, uint8_t val) {
    buf[0] = val;
    return 1;
}

int protocol_encode_u16(uint8_t *buf, uint16_t val) {
    buf[0] = (uint8_t)((val >> 8) & 0xFF);
    buf[1] = (uint8_t)(val & 0xFF);
    return 2;
}

int protocol_encode_u32(uint8_t *buf, uint32_t val) {
    buf[0] = (uint8_t)((val >> 24) & 0xFF);
    buf[1] = (uint8_t)((val >> 16) & 0xFF);
    buf[2] = (uint8_t)((val >> 8) & 0xFF);
    buf[3] = (uint8_t)(val & 0xFF);
    return 4;
}

int protocol_encode_u64(uint8_t *buf, uint64_t val) {
    buf[0] = (uint8_t)((val >> 56) & 0xFF);
    buf[1] = (uint8_t)((val >> 48) & 0xFF);
    buf[2] = (uint8_t)((val >> 40) & 0xFF);
    buf[3] = (uint8_t)((val >> 32) & 0xFF);
    buf[4] = (uint8_t)((val >> 24) & 0xFF);
    buf[5] = (uint8_t)((val >> 16) & 0xFF);
    buf[6] = (uint8_t)((val >> 8) & 0xFF);
    buf[7] = (uint8_t)(val & 0xFF);
    return 8;
}

int protocol_encode_i32(uint8_t *buf, int32_t val) {
    return protocol_encode_u32(buf, (uint32_t)val);
}

int protocol_encode_i64(uint8_t *buf, int64_t val) {
    return protocol_encode_u64(buf, (uint64_t)val);
}

int protocol_encode_string(uint8_t *buf, const char *str, uint16_t len) {
    int off = 0;
    off += protocol_encode_u16(buf + off, len);
    if (len > 0 && str != NULL) {
        memcpy(buf + off, str, len);
        off += len;
    }
    return off;
}

int protocol_encode_bytes(uint8_t *buf, const uint8_t *data, uint32_t len) {
    int off = 0;
    off += protocol_encode_u32(buf + off, len);
    if (len > 0 && data != NULL) {
        memcpy(buf + off, data, len);
        off += len;
    }
    return off;
}

/* ── Decoding ───────────────────────────────────────── */

uint8_t protocol_decode_u8(const uint8_t *buf) {
    return buf[0];
}

uint16_t protocol_decode_u16(const uint8_t *buf) {
    return (uint16_t)(((uint16_t)buf[0] << 8) | (uint16_t)buf[1]);
}

uint32_t protocol_decode_u32(const uint8_t *buf) {
    return ((uint32_t)buf[0] << 24) |
           ((uint32_t)buf[1] << 16) |
           ((uint32_t)buf[2] << 8) |
           (uint32_t)buf[3];
}

uint64_t protocol_decode_u64(const uint8_t *buf) {
    return ((uint64_t)buf[0] << 56) |
           ((uint64_t)buf[1] << 48) |
           ((uint64_t)buf[2] << 40) |
           ((uint64_t)buf[3] << 32) |
           ((uint64_t)buf[4] << 24) |
           ((uint64_t)buf[5] << 16) |
           ((uint64_t)buf[6] << 8) |
           (uint64_t)buf[7];
}

int32_t protocol_decode_i32(const uint8_t *buf) {
    return (int32_t)protocol_decode_u32(buf);
}

int64_t protocol_decode_i64(const uint8_t *buf) {
    return (int64_t)protocol_decode_u64(buf);
}

int protocol_validate_header(const uint8_t *buf, uint32_t *out_payload_len) {
    uint32_t magic = protocol_decode_u32(buf);
    uint8_t version = protocol_decode_u8(buf + 4);
    uint8_t msg_type = protocol_decode_u8(buf + 5);
    uint32_t payload_len = protocol_decode_u32(buf + 6);

    if (magic != JVMMON_PROTOCOL_MAGIC) return -1;
    if (version != JVMMON_PROTOCOL_VERSION) return -1;
    if (payload_len > JVMMON_MAX_PAYLOAD) return -1;

    if (out_payload_len != NULL) {
        *out_payload_len = payload_len;
    }

    return (int)msg_type;
}
