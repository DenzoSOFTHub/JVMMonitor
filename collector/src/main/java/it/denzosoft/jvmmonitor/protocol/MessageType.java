package it.denzosoft.jvmmonitor.protocol;

import java.util.HashMap;
import java.util.Map;

public enum MessageType {

    HANDSHAKE(ProtocolConstants.MSG_HANDSHAKE),
    HEARTBEAT(ProtocolConstants.MSG_HEARTBEAT),
    COMMAND(ProtocolConstants.MSG_COMMAND),
    COMMAND_RESP(ProtocolConstants.MSG_COMMAND_RESP),
    CPU_SAMPLE(ProtocolConstants.MSG_CPU_SAMPLE),
    GC_EVENT(ProtocolConstants.MSG_GC_EVENT),
    THREAD_SNAPSHOT(ProtocolConstants.MSG_THREAD_SNAPSHOT),
    THREAD_EVENT(ProtocolConstants.MSG_THREAD_EVENT),
    MEMORY_SNAPSHOT(ProtocolConstants.MSG_MEMORY_SNAPSHOT),
    CLASS_INFO(ProtocolConstants.MSG_CLASS_INFO),
    ALARM(ProtocolConstants.MSG_ALARM),
    MODULE_EVENT(ProtocolConstants.MSG_MODULE_EVENT),
    ALLOC_SAMPLE(ProtocolConstants.MSG_ALLOC_SAMPLE),
    MONITOR_EVENT(ProtocolConstants.MSG_MONITOR_EVENT),
    METHOD_INFO(ProtocolConstants.MSG_METHOD_INFO),
    JMX_DATA(ProtocolConstants.MSG_JMX_DATA),
    EXCEPTION(ProtocolConstants.MSG_EXCEPTION),
    OS_METRICS(ProtocolConstants.MSG_OS_METRICS),
    JIT_EVENT(ProtocolConstants.MSG_JIT_EVENT),
    CLASS_HISTO(ProtocolConstants.MSG_CLASS_HISTO),
    FINALIZER(ProtocolConstants.MSG_FINALIZER),
    SAFEPOINT(ProtocolConstants.MSG_SAFEPOINT),
    NATIVE_MEM(ProtocolConstants.MSG_NATIVE_MEM),
    GC_DETAIL(ProtocolConstants.MSG_GC_DETAIL),
    THREAD_CPU(ProtocolConstants.MSG_THREAD_CPU),
    CLASSLOADER(ProtocolConstants.MSG_CLASSLOADER),
    STRING_TABLE(ProtocolConstants.MSG_STRING_TABLE),
    JFR_EVENT(ProtocolConstants.MSG_JFR_EVENT),
    NETWORK(ProtocolConstants.MSG_NETWORK),
    LOCK_EVENT(ProtocolConstants.MSG_LOCK_EVENT),
    CPU_USAGE(ProtocolConstants.MSG_CPU_USAGE),
    PROCESS_LIST(ProtocolConstants.MSG_PROCESS_LIST),
    INSTR_EVENT(ProtocolConstants.MSG_INSTR_EVENT),
    DEBUG_BP_HIT(ProtocolConstants.MSG_DEBUG_BP_HIT),
    DEBUG_CLASS_BYTES(ProtocolConstants.MSG_DEBUG_CLASS_BYTES),
    FIELD_WATCH(ProtocolConstants.MSG_FIELD_WATCH),
    JVM_CONFIG(ProtocolConstants.MSG_JVM_CONFIG),
    JMX_BROWSE(ProtocolConstants.MSG_JMX_BROWSE),
    THREAD_DUMP_MSG(ProtocolConstants.MSG_THREAD_DUMP),
    DEADLOCK_MSG(ProtocolConstants.MSG_DEADLOCK),
    GC_ROOT_MSG(ProtocolConstants.MSG_GC_ROOT),
    OBJ_LIFETIME(ProtocolConstants.MSG_OBJ_LIFETIME),
    MONITOR_MAP(ProtocolConstants.MSG_MONITOR_MAP),
    HOT_SWAP_RESULT(ProtocolConstants.MSG_HOT_SWAP_RESULT),
    QUEUE_STATS(ProtocolConstants.MSG_QUEUE_STATS),
    UNKNOWN(-1);

    private static final Map<Integer, MessageType> BY_CODE = new HashMap<Integer, MessageType>();

    static {
        for (MessageType mt : values()) {
            if (mt != UNKNOWN) {
                BY_CODE.put(Integer.valueOf(mt.code), mt);
            }
        }
    }

    private final int code;

    MessageType(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static MessageType fromCode(int code) {
        MessageType mt = BY_CODE.get(Integer.valueOf(code));
        return mt != null ? mt : UNKNOWN;
    }
}
