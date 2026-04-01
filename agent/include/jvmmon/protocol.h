/*
 * JVMMonitor - Binary Wire Protocol
 * Big-endian encoding, matches Java DataInputStream byte order.
 */
#ifndef JVMMON_PROTOCOL_H
#define JVMMON_PROTOCOL_H

#include <stdint.h>
#include <stddef.h>

/* Protocol constants */
#define JVMMON_PROTOCOL_MAGIC    0x4A564D4D  /* "JVMM" */
#define JVMMON_PROTOCOL_VERSION  1
#define JVMMON_HEADER_SIZE       10  /* magic(4) + version(1) + type(1) + payload_len(4) */
#define JVMMON_MAX_PAYLOAD       8192

/* Message types */
#define JVMMON_MSG_HANDSHAKE       0x01
#define JVMMON_MSG_HEARTBEAT       0x02
#define JVMMON_MSG_COMMAND         0x03
#define JVMMON_MSG_COMMAND_RESP    0x04
#define JVMMON_MSG_CPU_SAMPLE      0x10
#define JVMMON_MSG_GC_EVENT        0x20
#define JVMMON_MSG_THREAD_SNAPSHOT 0x30
#define JVMMON_MSG_THREAD_EVENT    0x31
#define JVMMON_MSG_MEMORY_SNAPSHOT 0x40
#define JVMMON_MSG_CLASS_INFO      0x50
#define JVMMON_MSG_ALARM           0x60
#define JVMMON_MSG_MODULE_EVENT    0x70
#define JVMMON_MSG_ALLOC_SAMPLE    0x80
#define JVMMON_MSG_MONITOR_EVENT   0x90
#define JVMMON_MSG_METHOD_INFO     0xA0
#define JVMMON_MSG_JMX_DATA        0xB0
#define JVMMON_MSG_EXCEPTION       0xB1
#define JVMMON_MSG_OS_METRICS      0xB2
#define JVMMON_MSG_JIT_EVENT       0xB3
#define JVMMON_MSG_CLASS_HISTO     0xB4
#define JVMMON_MSG_FINALIZER       0xB5
#define JVMMON_MSG_SAFEPOINT       0xB6
#define JVMMON_MSG_NATIVE_MEM      0xB7
#define JVMMON_MSG_GC_DETAIL       0xB8
#define JVMMON_MSG_THREAD_CPU      0xB9
#define JVMMON_MSG_CLASSLOADER     0xBA
#define JVMMON_MSG_STRING_TABLE    0xBB
#define JVMMON_MSG_JFR_EVENT       0xBC
#define JVMMON_MSG_NETWORK         0xBD
#define JVMMON_MSG_LOCK_EVENT      0xBE
#define JVMMON_MSG_CPU_USAGE       0xBF
#define JVMMON_MSG_PROCESS_LIST    0xC0
#define JVMMON_MSG_INSTR_EVENT     0xC1

/* Command subtypes */
#define JVMMON_CMD_ENABLE_MODULE   0x01
#define JVMMON_CMD_DISABLE_MODULE  0x02
#define JVMMON_CMD_SET_LEVEL       0x03
#define JVMMON_CMD_LIST_MODULES    0x04
#define JVMMON_CMD_DETACH          0xFF

/* Alarm severities */
#define JVMMON_ALARM_INFO     0
#define JVMMON_ALARM_WARNING  1
#define JVMMON_ALARM_CRITICAL 2

/* ── Encoding (big-endian) ──────────────────────────── */

int protocol_encode_header(uint8_t *buf, uint8_t msg_type, uint32_t payload_len);
int protocol_encode_u8(uint8_t *buf, uint8_t val);
int protocol_encode_u16(uint8_t *buf, uint16_t val);
int protocol_encode_u32(uint8_t *buf, uint32_t val);
int protocol_encode_u64(uint8_t *buf, uint64_t val);
int protocol_encode_i32(uint8_t *buf, int32_t val);
int protocol_encode_i64(uint8_t *buf, int64_t val);
int protocol_encode_string(uint8_t *buf, const char *str, uint16_t len);
int protocol_encode_bytes(uint8_t *buf, const uint8_t *data, uint32_t len);

/* ── Decoding (big-endian) ──────────────────────────── */

uint8_t  protocol_decode_u8(const uint8_t *buf);
uint16_t protocol_decode_u16(const uint8_t *buf);
uint32_t protocol_decode_u32(const uint8_t *buf);
uint64_t protocol_decode_u64(const uint8_t *buf);
int32_t  protocol_decode_i32(const uint8_t *buf);
int64_t  protocol_decode_i64(const uint8_t *buf);

/* Validate header, returns msg_type (>= 0) or -1 on error */
int protocol_validate_header(const uint8_t *buf, uint32_t *out_payload_len);

#endif /* JVMMON_PROTOCOL_H */
