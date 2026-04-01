/*
 * JVMMonitor - Class Analyzer Implementation
 * Analyzes bytecode at class load time for structural info.
 */
#include "jvmmon/class_analyzer.h"
#include "jvmmon/protocol.h"
#include <string.h>

class_analyzer_t *class_analyzer_create(jvmmon_agent_t *agent) {
    class_analyzer_t *ca = (class_analyzer_t *)jvmmon_calloc(1, sizeof(class_analyzer_t));
    if (ca == NULL) return NULL;
    ca->agent = agent;
    ca->total_classes = 0;
    ca->total_bytecode_bytes = 0;
    return ca;
}

void class_analyzer_destroy(class_analyzer_t *ca) {
    if (ca != NULL) jvmmon_free(ca);
}

void class_analyzer_on_class_load(class_analyzer_t *ca,
                                   const char *class_name,
                                   const unsigned char *class_data,
                                   jint class_data_len) {
    if (ca == NULL) return;

    jvmmon_atomic_add(&ca->total_classes, 1);
    /* Non-atomic add on 64-bit is fine for statistics */
    ca->total_bytecode_bytes += class_data_len;

    /* Send class info for non-JDK classes (application classes) */
    if (class_name != NULL &&
        strncmp(class_name, "java/", 5) != 0 &&
        strncmp(class_name, "javax/", 6) != 0 &&
        strncmp(class_name, "sun/", 4) != 0 &&
        strncmp(class_name, "com/sun/", 8) != 0 &&
        strncmp(class_name, "jdk/", 4) != 0) {

        uint8_t payload[1024];
        int off = 0;
        uint16_t name_len = (uint16_t)strlen(class_name);
        if (name_len > 500) name_len = 500;

        off += protocol_encode_u64(payload + off, jvmmon_time_millis());
        off += protocol_encode_string(payload + off, class_name, name_len);
        off += protocol_encode_i32(payload + off, class_data_len);
        off += protocol_encode_i32(payload + off, ca->total_classes);
        off += protocol_encode_i64(payload + off, ca->total_bytecode_bytes);

        agent_send_message(JVMMON_MSG_CLASS_INFO, payload, (uint32_t)off);
    }
}

void class_analyzer_send_summary(class_analyzer_t *ca) {
    if (ca == NULL) return;

    uint8_t payload[64];
    int off = 0;

    off += protocol_encode_u64(payload + off, jvmmon_time_millis());
    off += protocol_encode_string(payload + off, "_summary", 8);
    off += protocol_encode_i32(payload + off, ca->total_classes);
    off += protocol_encode_i64(payload + off, ca->total_bytecode_bytes);

    agent_send_message(JVMMON_MSG_CLASS_INFO, payload, (uint32_t)off);
}
