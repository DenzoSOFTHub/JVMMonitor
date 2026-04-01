package it.denzosoft.jvmmonitor.model;

/**
 * Monitor ownership map: which threads hold which monitors, and who's waiting.
 * Used to visualize lock ordering and detect potential deadlocks.
 */
public final class MonitorMap {

    private final long timestamp;
    private final ThreadMonitorInfo[] threads;

    public MonitorMap(long timestamp, ThreadMonitorInfo[] threads) {
        this.timestamp = timestamp;
        this.threads = threads;
    }

    public long getTimestamp() { return timestamp; }
    public ThreadMonitorInfo[] getThreads() { return threads; }

    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Monitor Ownership Map:\n\n");
        if (threads != null) {
            for (int i = 0; i < threads.length; i++) {
                ThreadMonitorInfo t = threads[i];
                sb.append('"').append(t.threadName).append("\" (").append(t.threadState).append(")\n");
                if (t.ownedMonitors != null) {
                    for (int j = 0; j < t.ownedMonitors.length; j++) {
                        sb.append("  HOLDS: ").append(t.ownedMonitors[j]).append('\n');
                    }
                }
                if (t.waitingOn != null && !t.waitingOn.isEmpty()) {
                    sb.append("  WAITING ON: ").append(t.waitingOn).append('\n');
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    public static final class ThreadMonitorInfo {
        public final long threadId;
        public final String threadName;
        public final String threadState;
        public final String[] ownedMonitors;
        public final String waitingOn;

        public ThreadMonitorInfo(long threadId, String threadName, String threadState,
                                  String[] ownedMonitors, String waitingOn) {
            this.threadId = threadId;
            this.threadName = threadName;
            this.threadState = threadState;
            this.ownedMonitors = ownedMonitors;
            this.waitingOn = waitingOn != null ? waitingOn : "";
        }
    }
}
