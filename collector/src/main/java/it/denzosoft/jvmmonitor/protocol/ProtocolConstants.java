package it.denzosoft.jvmmonitor.protocol;

public final class ProtocolConstants {

    public static final int MAGIC = 0x4A564D4D;
    public static final int VERSION = 1;
    public static final int HEADER_SIZE = 10;
    public static final int MAX_PAYLOAD = 8192;

    /* Message types */
    public static final int MSG_HANDSHAKE       = 0x01;
    public static final int MSG_HEARTBEAT       = 0x02;
    public static final int MSG_COMMAND         = 0x03;
    public static final int MSG_COMMAND_RESP    = 0x04;
    public static final int MSG_CPU_SAMPLE      = 0x10;
    public static final int MSG_GC_EVENT        = 0x20;
    public static final int MSG_THREAD_SNAPSHOT = 0x30;
    public static final int MSG_THREAD_EVENT    = 0x31;
    public static final int MSG_MEMORY_SNAPSHOT = 0x40;
    public static final int MSG_CLASS_INFO      = 0x50;
    public static final int MSG_ALARM           = 0x60;
    public static final int MSG_MODULE_EVENT    = 0x70;
    public static final int MSG_ALLOC_SAMPLE    = 0x80;
    public static final int MSG_MONITOR_EVENT   = 0x90;
    public static final int MSG_METHOD_INFO     = 0xA0;
    public static final int MSG_JMX_DATA        = 0xB0;
    public static final int MSG_EXCEPTION       = 0xB1;
    public static final int MSG_OS_METRICS      = 0xB2;
    public static final int MSG_JIT_EVENT       = 0xB3;
    public static final int MSG_CLASS_HISTO     = 0xB4;
    public static final int MSG_FINALIZER       = 0xB5;
    public static final int MSG_SAFEPOINT       = 0xB6;
    public static final int MSG_NATIVE_MEM      = 0xB7;
    public static final int MSG_GC_DETAIL       = 0xB8;
    public static final int MSG_THREAD_CPU      = 0xB9;
    public static final int MSG_CLASSLOADER     = 0xBA;
    public static final int MSG_STRING_TABLE    = 0xBB;
    public static final int MSG_JFR_EVENT       = 0xBC;
    public static final int MSG_NETWORK         = 0xBD;
    public static final int MSG_LOCK_EVENT      = 0xBE;
    public static final int MSG_CPU_USAGE       = 0xBF;
    public static final int MSG_PROCESS_LIST    = 0xC0;
    public static final int MSG_INSTR_EVENT     = 0xC1;

    /* Instrumentation command subtypes */
    public static final int INSTR_CMD_CONFIGURE  = 0x10;
    public static final int INSTR_CMD_START      = 0x11;
    public static final int INSTR_CMD_STOP       = 0x12;

    /* Debugger messages */
    public static final int MSG_DEBUG_BP_HIT     = 0xD0;
    public static final int MSG_DEBUG_CLASS_BYTES = 0xD1;

    /* Debugger command subtypes */
    public static final int DEBUG_CMD_SET_BP     = 0x20;
    public static final int DEBUG_CMD_REMOVE_BP  = 0x21;
    public static final int DEBUG_CMD_RESUME     = 0x22;
    public static final int DEBUG_CMD_STEP_OVER  = 0x23;
    public static final int DEBUG_CMD_STEP_INTO  = 0x24;
    public static final int DEBUG_CMD_STEP_OUT   = 0x25;
    public static final int DEBUG_CMD_GET_CLASS   = 0x26;
    public static final int DEBUG_CMD_LIST_CLASSES = 0x27;

    /* Diagnostic messages */
    public static final int MSG_FIELD_WATCH      = 0xD2;
    public static final int MSG_JVM_CONFIG       = 0xD3;
    public static final int MSG_JMX_BROWSE       = 0xD4;
    public static final int MSG_THREAD_DUMP      = 0xD5;
    public static final int MSG_DEADLOCK         = 0xD6;
    public static final int MSG_GC_ROOT          = 0xD7;

    /* Diagnostic command subtypes */
    public static final int DIAG_CMD_SET_FIELD_WATCH   = 0x30;
    public static final int DIAG_CMD_REMOVE_FIELD_WATCH = 0x31;
    public static final int DIAG_CMD_GET_JVM_CONFIG    = 0x32;
    public static final int DIAG_CMD_JMX_LIST_MBEANS   = 0x33;
    public static final int DIAG_CMD_JMX_GET_ATTRS     = 0x34;
    public static final int DIAG_CMD_THREAD_DUMP       = 0x35;
    public static final int DIAG_CMD_HEAP_DUMP         = 0x36;
    public static final int DIAG_CMD_DETECT_DEADLOCKS  = 0x37;
    public static final int DIAG_CMD_GC_ROOTS          = 0x38;
    public static final int DIAG_CMD_FORCE_GC          = 0x39;

    /* Advanced JVMTI commands */
    public static final int DIAG_CMD_SUSPEND_THREAD    = 0x40;
    public static final int DIAG_CMD_RESUME_THREAD     = 0x41;
    public static final int DIAG_CMD_STOP_THREAD       = 0x42;
    public static final int DIAG_CMD_HOT_SWAP          = 0x43;
    public static final int DIAG_CMD_START_OBJ_TRACKING = 0x44;
    public static final int DIAG_CMD_STOP_OBJ_TRACKING  = 0x45;
    public static final int DIAG_CMD_GET_MONITOR_MAP    = 0x46;

    /* Advanced JVMTI messages */
    public static final int MSG_OBJ_LIFETIME    = 0xD8;
    public static final int MSG_MONITOR_MAP     = 0xD9;
    public static final int MSG_HOT_SWAP_RESULT = 0xDA;
    public static final int MSG_QUEUE_STATS     = 0xDB;

    /* JMX subtypes */
    public static final int JMX_MBEAN_LIST      = 0x01;
    public static final int JMX_MBEAN_ATTRS     = 0x02;
    public static final int JMX_PLATFORM_INFO   = 0x03;

    /* Command subtypes */
    public static final int CMD_ENABLE_MODULE  = 0x01;
    public static final int CMD_DISABLE_MODULE = 0x02;
    public static final int CMD_SET_LEVEL      = 0x03;
    public static final int CMD_LIST_MODULES   = 0x04;
    public static final int CMD_DETACH         = 0xFF;

    /* Alarm severities */
    public static final int ALARM_INFO     = 0;
    public static final int ALARM_WARNING  = 1;
    public static final int ALARM_CRITICAL = 2;

    private ProtocolConstants() {}
}
