package it.denzosoft.jvmmonitor.model;

/**
 * Full thread dump with stack traces for all threads.
 */
public final class ThreadDump {

    private final long timestamp;
    private final ThreadStack[] threads;

    public ThreadDump(long timestamp, ThreadStack[] threads) {
        this.timestamp = timestamp;
        this.threads = threads;
    }

    public long getTimestamp() { return timestamp; }
    public ThreadStack[] getThreads() { return threads; }
    public int getThreadCount() { return threads != null ? threads.length : 0; }

    /** Format as text (like jstack output). */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Full thread dump at ").append(new java.util.Date(timestamp)).append("\n\n");
        if (threads != null) {
            for (int i = 0; i < threads.length; i++) {
                ThreadStack t = threads[i];
                sb.append('"').append(t.name).append("\" tid=").append(t.threadId);
                sb.append(" ").append(t.state);
                if (t.daemon) sb.append(" daemon");
                sb.append('\n');
                for (int j = 0; j < t.frames.length; j++) {
                    sb.append("    at ").append(t.frames[j]).append('\n');
                }
                if (t.lockName != null && !t.lockName.isEmpty()) {
                    sb.append("    - waiting on <").append(t.lockName).append(">\n");
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    public static final class ThreadStack {
        public final long threadId;
        public final String name;
        public final String state;
        public final boolean daemon;
        public final String[] frames;
        public final String lockName;
        public final String lockOwner;

        public ThreadStack(long threadId, String name, String state, boolean daemon,
                           String[] frames, String lockName, String lockOwner) {
            this.threadId = threadId;
            this.name = name;
            this.state = state;
            this.daemon = daemon;
            this.frames = frames;
            this.lockName = lockName != null ? lockName : "";
            this.lockOwner = lockOwner != null ? lockOwner : "";
        }
    }
}
