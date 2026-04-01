package it.denzosoft.jvmmonitor.model;

public final class JitEvent {

    public static final int COMPILED = 1;
    public static final int UNLOADED = 2;

    private final long timestamp;
    private final int eventType;
    private final String className;
    private final String methodName;
    private final int codeSize;
    private final long codeAddr;
    private final int totalCompiled;

    public JitEvent(long timestamp, int eventType, String className, String methodName,
                    int codeSize, long codeAddr, int totalCompiled) {
        this.timestamp = timestamp;
        this.eventType = eventType;
        this.className = className;
        this.methodName = methodName;
        this.codeSize = codeSize;
        this.codeAddr = codeAddr;
        this.totalCompiled = totalCompiled;
    }

    public long getTimestamp() { return timestamp; }
    public int getEventType() { return eventType; }
    public String getClassName() { return className; }
    public String getMethodName() { return methodName; }
    public int getCodeSize() { return codeSize; }
    public long getCodeAddr() { return codeAddr; }
    public int getTotalCompiled() { return totalCompiled; }

    public String getTypeName() {
        switch (eventType) {
            case COMPILED: return "COMPILED";
            case UNLOADED: return "UNLOADED";
            default: return "UNKNOWN";
        }
    }
}
