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
    private final boolean isException;   /* true if method exited with exception */

    public InstrumentationEvent(long timestamp, int eventType, long threadId, String threadName,
                                 String className, String methodName, long durationNanos,
                                 String context, long traceId, long parentTraceId,
                                 int depth, boolean isException) {
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

    public String getFullMethodName() {
        return className + "." + methodName;
    }

    public String getEventTypeName() {
        switch (eventType) {
            case TYPE_METHOD_ENTER: return "ENTER";
            case TYPE_METHOD_EXIT:  return "EXIT";
            case TYPE_JDBC_QUERY:   return "JDBC";
            case TYPE_JDBC_CONNECT: return "JDBC_CONN";
            case TYPE_JDBC_CLOSE:   return "JDBC_CLOSE";
            case TYPE_HTTP_REQUEST: return "HTTP_REQ";
            case TYPE_HTTP_RESPONSE: return "HTTP_RESP";
            default: return "?";
        }
    }

    public String getDisplayClassName() {
        if (className == null) return "?";
        return className.replace('/', '.');
    }
}
