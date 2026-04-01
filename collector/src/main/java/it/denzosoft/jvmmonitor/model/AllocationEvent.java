package it.denzosoft.jvmmonitor.model;

/**
 * Single object allocation event captured by the JVMTI VMObjectAlloc callback.
 * Each event records what class was allocated, how large, by which thread, and where.
 */
public final class AllocationEvent {

    private final long timestamp;
    private final String className;
    private final long size;
    private final long threadId;
    private final String threadName;
    private final String allocSite;  /* class.method where allocation happened */

    public AllocationEvent(long timestamp, String className, long size,
                           long threadId, String threadName, String allocSite) {
        this.timestamp = timestamp;
        this.className = className;
        this.size = size;
        this.threadId = threadId;
        this.threadName = threadName;
        this.allocSite = allocSite;
    }

    public long getTimestamp() { return timestamp; }
    public String getClassName() { return className; }
    public long getSize() { return size; }
    public long getThreadId() { return threadId; }
    public String getThreadName() { return threadName; }
    public String getAllocSite() { return allocSite; }

    public String getDisplayClassName() {
        String name = className;
        if (name != null && name.startsWith("L") && name.endsWith(";")) {
            name = name.substring(1, name.length() - 1).replace('/', '.');
        }
        return name != null ? name : "?";
    }
}
