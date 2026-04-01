package it.denzosoft.jvmmonitor.model;

public final class CpuSample {

    private final long timestamp;
    private final long threadId;
    private final StackFrame[] frames;

    public CpuSample(long timestamp, long threadId, StackFrame[] frames) {
        this.timestamp = timestamp;
        this.threadId = threadId;
        this.frames = frames;
    }

    public long getTimestamp() { return timestamp; }
    public long getThreadId() { return threadId; }
    public StackFrame[] getFrames() { return frames; }
    public int getDepth() { return frames != null ? frames.length : 0; }

    public static final class StackFrame {
        private final long methodId;
        private final int lineNumber;
        private String className;
        private String methodName;

        public StackFrame(long methodId, int lineNumber) {
            this.methodId = methodId;
            this.lineNumber = lineNumber;
        }

        public long getMethodId() { return methodId; }
        public int getLineNumber() { return lineNumber; }

        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }

        public String getMethodName() { return methodName; }
        public void setMethodName(String methodName) { this.methodName = methodName; }

        public String getDisplayName() {
            if (className != null && methodName != null) {
                return className + "." + methodName + "():" + lineNumber;
            }
            return "method@0x" + Long.toHexString(methodId) + ":" + lineNumber;
        }
    }
}
