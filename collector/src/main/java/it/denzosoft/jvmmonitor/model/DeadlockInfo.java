package it.denzosoft.jvmmonitor.model;

/**
 * Deadlock detection result.
 */
public final class DeadlockInfo {

    private final long timestamp;
    private final boolean deadlockFound;
    private final DeadlockChain[] chains;

    public DeadlockInfo(long timestamp, boolean deadlockFound, DeadlockChain[] chains) {
        this.timestamp = timestamp;
        this.deadlockFound = deadlockFound;
        this.chains = chains;
    }

    public long getTimestamp() { return timestamp; }
    public boolean isDeadlockFound() { return deadlockFound; }
    public DeadlockChain[] getChains() { return chains; }

    public String toText() {
        if (!deadlockFound) return "No deadlocks detected.";
        StringBuilder sb = new StringBuilder();
        sb.append("DEADLOCK DETECTED at ").append(new java.util.Date(timestamp)).append("\n\n");
        for (int i = 0; i < chains.length; i++) {
            sb.append("Deadlock chain #").append(i + 1).append(":\n");
            DeadlockChain c = chains[i];
            for (int j = 0; j < c.threads.length; j++) {
                sb.append("  \"").append(c.threads[j]).append("\" holds <").append(c.locksHeld[j]);
                sb.append("> waiting for <").append(c.locksWaiting[j]).append(">\n");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public static final class DeadlockChain {
        public final String[] threads;
        public final String[] locksHeld;
        public final String[] locksWaiting;

        public DeadlockChain(String[] threads, String[] locksHeld, String[] locksWaiting) {
            this.threads = threads;
            this.locksHeld = locksHeld;
            this.locksWaiting = locksWaiting;
        }
    }
}
