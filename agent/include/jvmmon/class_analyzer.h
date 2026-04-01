/*
 * JVMMonitor - Class Analyzer
 * Level 0 CORE: class loading events + bytecode structure analysis.
 */
#ifndef JVMMON_CLASS_ANALYZER_H
#define JVMMON_CLASS_ANALYZER_H

#include "agent.h"

struct class_analyzer {
    jvmmon_agent_t *agent;
    int32_t         total_classes;
    int64_t         total_bytecode_bytes;
};

class_analyzer_t *class_analyzer_create(jvmmon_agent_t *agent);
void class_analyzer_destroy(class_analyzer_t *ca);

/* Called by JVMTI ClassFileLoadHook */
void class_analyzer_on_class_load(class_analyzer_t *ca,
                                   const char *class_name,
                                   const unsigned char *class_data,
                                   jint class_data_len);

/* Send periodic summary */
void class_analyzer_send_summary(class_analyzer_t *ca);

#endif /* JVMMON_CLASS_ANALYZER_H */
