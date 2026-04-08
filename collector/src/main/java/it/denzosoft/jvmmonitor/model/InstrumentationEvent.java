package it.denzosoft.jvmmonitor.model;

/**
 * Event from the JVMTI instrumentation engine.
 * Captures method entry/exit with timing, thread, and optional context
 * (e.g., SQL statement for JDBC, URL for HTTP, bean name for Spring).
 */
public final class InstrumentationEvent {

    public static final int TYPE_METHOD_ENTER = 1;
    public static final int TYPE_METHOD_EXIT  = 2;
    public static final int TYPE_JDBC_QUERY   = 3;
    public static final int TYPE_JDBC_CONNECT = 4;
    public static final int TYPE_JDBC_CLOSE   = 5;
    public static final int TYPE_HTTP_REQUEST  = 6;
    public static final int TYPE_HTTP_RESPONSE = 7;
    public static final int TYPE_DISK_READ     = 8;
    public static final int TYPE_DISK_WRITE    = 9;
    public static final int TYPE_SOCKET_READ   = 10;
    public static final int TYPE_SOCKET_WRITE  = 11;
    public static final int TYPE_SOCKET_CONNECT = 12;
    public static final int TYPE_SOCKET_CLOSE   = 13;
    public static final int TYPE_USER_ACTION    = 20;

    private final long timestamp;
    private final int eventType;
    private final long threadId;
    private final String threadName;
    private final String className;
    private final String methodName;
    private final long durationNanos;    /* 0 for entry events, >0 for exit */
    private final String context;         /* SQL, URL, bean name, etc. */
    private final long traceId;          /* correlate entry->exit + call chain */
    private final long parentTraceId;    /* caller's traceId for tree building */
    private final int depth;             /* call depth in the trace */
    private final boolean isException;
    private final String paramsJson;
    private final String returnValueJson;
    private final long allocatedBytes;   /* bytes allocated during this method */
    private final long blockedTimeMs;    /* ms spent in BLOCKED state */
    private final long waitedTimeMs;     /* ms spent in WAITING state */
    private final long cpuTimeNs;        /* CPU time consumed (nanoseconds) */

    public InstrumentationEvent(long timestamp, int eventType, long threadId, String threadName,
                                 String className, String methodName, long durationNanos,
                                 String context, long traceId, long parentTraceId,
                                 int depth, boolean isException) {
        this(timestamp, eventType, threadId, threadName, className, methodName,
             durationNanos, context, traceId, parentTraceId, depth, isException, null, null, 0, 0, 0, 0);
    }

    public InstrumentationEvent(long timestamp, int eventType, long threadId, String threadName,
                                 String className, String methodName, long durationNanos,
                                 String context, long traceId, long parentTraceId,
                                 int depth, boolean isException,
                                 String paramsJson, String returnValueJson) {
        this(timestamp, eventType, threadId, threadName, className, methodName,
             durationNanos, context, traceId, parentTraceId, depth, isException,
             paramsJson, returnValueJson, 0, 0, 0, 0);
    }

    public InstrumentationEvent(long timestamp, int eventType, long threadId, String threadName,
                                 String className, String methodName, long durationNanos,
                                 String context, long traceId, long parentTraceId,
                                 int depth, boolean isException,
                                 String paramsJson, String returnValueJson,
                                 long allocatedBytes, long blockedTimeMs, long waitedTimeMs) {
        this(timestamp, eventType, threadId, threadName, className, methodName,
             durationNanos, context, traceId, parentTraceId, depth, isException,
             paramsJson, returnValueJson, allocatedBytes, blockedTimeMs, waitedTimeMs, 0);
    }

    public InstrumentationEvent(long timestamp, int eventType, long threadId, String threadName,
                                 String className, String methodName, long durationNanos,
                                 String context, long traceId, long parentTraceId,
                                 int depth, boolean isException,
                                 String paramsJson, String returnValueJson,
                                 long allocatedBytes, long blockedTimeMs, long waitedTimeMs,
                                 long cpuTimeNs) {
        this.timestamp = timestamp;
        this.eventType = eventType;
        this.threadId = threadId;
        this.threadName = threadName;
        this.className = className;
        this.methodName = methodName;
        this.durationNanos = durationNanos;
        this.context = context != null ? context : "";
        this.traceId = traceId;
        this.parentTraceId = parentTraceId;
        this.depth = depth;
        this.isException = isException;
        this.paramsJson = paramsJson;
        this.allocatedBytes = allocatedBytes;
        this.blockedTimeMs = blockedTimeMs;
        this.waitedTimeMs = waitedTimeMs;
        this.cpuTimeNs = cpuTimeNs;
        this.returnValueJson = returnValueJson;
    }

    public long getTimestamp() { return timestamp; }
    public int getEventType() { return eventType; }
    public long getThreadId() { return threadId; }
    public String getThreadName() { return threadName; }
    public String getClassName() { return className; }
    public String getMethodName() { return methodName; }
    public long getDurationNanos() { return durationNanos; }
    public double getDurationMs() { return durationNanos / 1000000.0; }
    public String getContext() { return context; }
    public long getTraceId() { return traceId; }
    public long getParentTraceId() { return parentTraceId; }
    public int getDepth() { return depth; }
    public boolean isException() { return isException; }
    public String getParamsJson() { return paramsJson; }
    public String getReturnValueJson() { return returnValueJson; }
    public boolean hasParams() { return paramsJson != null && paramsJson.length() > 0; }
    public boolean hasReturnValue() { return returnValueJson != null && returnValueJson.length() > 0; }
    public long getAllocatedBytes() { return allocatedBytes; }
    public double getAllocatedMB() { return allocatedBytes / (1024.0 * 1024.0); }
    public long getBlockedTimeMs() { return blockedTimeMs; }
    public long getWaitedTimeMs() { return waitedTimeMs; }
    public long getCpuTimeNs() { return cpuTimeNs; }
    public double getCpuTimeMs() { return cpuTimeNs / 1000000.0; }

    public String getFullMethodName() {
        return className + "." + methodName;
    }

    public String getEventTypeName() {
        switch (eventType) {
            case TYPE_METHOD_ENTER: return "ENTER";
            case TYPE_METHOD_EXIT:  return "METHOD";
            case TYPE_JDBC_QUERY:   return "JDBC";
            case TYPE_JDBC_CONNECT: return "JDBC_CONN";
            case TYPE_JDBC_CLOSE:   return "JDBC_CLOSE";
            case TYPE_HTTP_REQUEST: return "HTTP_REQ";
            case TYPE_HTTP_RESPONSE: return "HTTP_RESP";
            case TYPE_DISK_READ:    return "DISK_RD";
            case TYPE_DISK_WRITE:   return "DISK_WR";
            case TYPE_SOCKET_READ:  return "SOCK_RD";
            case TYPE_SOCKET_WRITE: return "SOCK_WR";
            case TYPE_SOCKET_CONNECT: return "SOCK_CONN";
            case TYPE_SOCKET_CLOSE: return "SOCK_CLOSE";
            case TYPE_USER_ACTION:  return "USER";
            default: return "?";
        }
    }

    public String getDisplayClassName() {
        if (className == null) return "?";
        return className.replace('/', '.');
    }
}
