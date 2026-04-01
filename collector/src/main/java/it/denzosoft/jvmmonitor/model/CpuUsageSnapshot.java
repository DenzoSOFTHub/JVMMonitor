package it.denzosoft.jvmmonitor.model;

/**
 * CPU usage snapshot capturing system-wide and JVM process CPU utilization,
 * plus per-thread CPU time for identifying which threads consume the most CPU.
 */
public final class CpuUsageSnapshot {

    private final long timestamp;

    /* System-wide CPU */
    private final double systemCpuPercent;    /* total system CPU usage 0-100 */
    private final int availableProcessors;

    /* JVM process CPU */
    private final double processCpuPercent;   /* JVM process CPU usage 0-100 */
    private final long processUserTimeMs;     /* cumulative user time */
    private final long processSystemTimeMs;   /* cumulative kernel time */

    /* Per-thread top consumers */
    private final ThreadCpuInfo[] topThreads;

    public CpuUsageSnapshot(long timestamp,
                             double systemCpuPercent, int availableProcessors,
                             double processCpuPercent, long processUserTimeMs, long processSystemTimeMs,
                             ThreadCpuInfo[] topThreads) {
        this.timestamp = timestamp;
        this.systemCpuPercent = systemCpuPercent;
        this.availableProcessors = availableProcessors;
        this.processCpuPercent = processCpuPercent;
        this.processUserTimeMs = processUserTimeMs;
        this.processSystemTimeMs = processSystemTimeMs;
        this.topThreads = topThreads;
    }

    public long getTimestamp() { return timestamp; }
    public double getSystemCpuPercent() { return systemCpuPercent; }
    public int getAvailableProcessors() { return availableProcessors; }
    public double getProcessCpuPercent() { return processCpuPercent; }
    public long getProcessUserTimeMs() { return processUserTimeMs; }
    public long getProcessSystemTimeMs() { return processSystemTimeMs; }
    public ThreadCpuInfo[] getTopThreads() { return topThreads; }
    public int getTopThreadCount() { return topThreads != null ? topThreads.length : 0; }

    /** Available CPU headroom: how much CPU capacity is unused. */
    public double getAvailableCpuPercent() {
        return Math.max(0, 100 - systemCpuPercent);
    }

    /** JVM CPU as fraction of total system capacity. */
    public double getJvmCpuOfSystem() {
        return systemCpuPercent > 0 ? (processCpuPercent / systemCpuPercent) * 100 : 0;
    }

    public static final class ThreadCpuInfo {
        public final long threadId;
        public final String threadName;
        public final long cpuTimeMs;        /* cumulative CPU time */
        public final double cpuPercent;      /* CPU % in the measurement interval */
        public final String state;

        public ThreadCpuInfo(long threadId, String threadName, long cpuTimeMs,
                             double cpuPercent, String state) {
            this.threadId = threadId;
            this.threadName = threadName;
            this.cpuTimeMs = cpuTimeMs;
            this.cpuPercent = cpuPercent;
            this.state = state;
        }
    }
}
