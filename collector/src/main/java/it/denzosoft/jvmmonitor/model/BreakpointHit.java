package it.denzosoft.jvmmonitor.model;

/**
 * Event sent when the JVM hits a breakpoint.
 * Contains the stopped thread, location, local variables, and class bytecode.
 */
public final class BreakpointHit {

    private final long timestamp;
    private final long threadId;
    private final String threadName;
    private final String className;
    private final String methodName;
    private final int lineNumber;
    private final DebugVariable[] variables;
    private final byte[] classBytes;

    public BreakpointHit(long timestamp, long threadId, String threadName,
                          String className, String methodName, int lineNumber,
                          DebugVariable[] variables, byte[] classBytes) {
        this.timestamp = timestamp;
        this.threadId = threadId;
        this.threadName = threadName;
        this.className = className;
        this.methodName = methodName;
        this.lineNumber = lineNumber;
        this.variables = variables;
        this.classBytes = classBytes;
    }

    public long getTimestamp() { return timestamp; }
    public long getThreadId() { return threadId; }
    public String getThreadName() { return threadName; }
    public String getClassName() { return className; }
    public String getMethodName() { return methodName; }
    public int getLineNumber() { return lineNumber; }
    public DebugVariable[] getVariables() { return variables; }
    public byte[] getClassBytes() { return classBytes; }

    public String getDisplayClassName() {
        return className != null ? className.replace('/', '.') : "?";
    }

    public static final class DebugVariable {
        public final String name;
        public final String type;
        public final String value;

        public DebugVariable(String name, String type, String value) {
            this.name = name;
            this.type = type;
            this.value = value;
        }
    }
}
