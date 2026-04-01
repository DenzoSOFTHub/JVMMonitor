package it.denzosoft.jvmmonitor.model;

/**
 * Lock contention event. Captures which thread waited for which lock,
 * who owned it, and the call stack of the waiter.
 */
public final class LockEvent {

    public static final int CONTENDED_ENTER = 1;
    public static final int CONTENDED_EXIT  = 2;
    public static final int WAIT_ENTER      = 3;
    public static final int WAIT_EXIT       = 4;
    public static final int DEADLOCK        = 5;

    private final long timestamp;
    private final int eventType;
    private final String threadName;
    private final String lockClassName;
    private final int lockHash;
    private final int totalContentions;
    private final String ownerThreadName;
    private final int ownerEntryCount;
    private final int waiterCount;
    private final StackFrame[] stackFrames;

    public LockEvent(long timestamp, int eventType, String threadName,
                     String lockClassName, int lockHash, int totalContentions,
                     String ownerThreadName, int ownerEntryCount, int waiterCount,
                     StackFrame[] stackFrames) {
        this.timestamp = timestamp;
        this.eventType = eventType;
        this.threadName = threadName;
        this.lockClassName = lockClassName;
        this.lockHash = lockHash;
        this.totalContentions = totalContentions;
        this.ownerThreadName = ownerThreadName;
        this.ownerEntryCount = ownerEntryCount;
        this.waiterCount = waiterCount;
        this.stackFrames = stackFrames;
    }

    public long getTimestamp() { return timestamp; }
    public int getEventType() { return eventType; }
    public String getThreadName() { return threadName; }
    public String getLockClassName() { return lockClassName; }
    public int getLockHash() { return lockHash; }
    public int getTotalContentions() { return totalContentions; }
    public String getOwnerThreadName() { return ownerThreadName; }
    public int getOwnerEntryCount() { return ownerEntryCount; }
    public int getWaiterCount() { return waiterCount; }
    public StackFrame[] getStackFrames() { return stackFrames; }

    public String getEventTypeName() {
        switch (eventType) {
            case CONTENDED_ENTER: return "CONTENDED";
            case CONTENDED_EXIT:  return "ACQUIRED";
            case WAIT_ENTER:      return "WAIT";
            case WAIT_EXIT:       return "NOTIFIED";
            case DEADLOCK:        return "DEADLOCK";
            default: return "UNKNOWN";
        }
    }

    public String getLockDisplayName() {
        String name = lockClassName;
        if (name != null && name.startsWith("L") && name.endsWith(";")) {
            name = name.substring(1, name.length() - 1).replace('/', '.');
        }
        return (name != null ? name : "?") + "@" + Integer.toHexString(lockHash);
    }

    public String getStackTraceString() {
        if (stackFrames == null || stackFrames.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < stackFrames.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append("    at ").append(stackFrames[i].className)
              .append('.').append(stackFrames[i].methodName).append("()");
        }
        return sb.toString();
    }

    public static final class StackFrame {
        public final String className;
        public final String methodName;

        public StackFrame(String className, String methodName) {
            this.className = className;
            this.methodName = methodName;
        }
    }
}
