package it.denzosoft.jvmmonitor.model;

/**
 * Snapshot of OS processes running on the machine.
 * Shows who is competing with the JVM for CPU and memory.
 */
public final class ProcessInfo {

    private final long timestamp;
    private final long totalMemoryBytes;
    private final long freeMemoryBytes;
    private final long swapTotalBytes;
    private final long swapUsedBytes;
    private final double loadAvg1;
    private final double loadAvg5;
    private final double loadAvg15;
    private final int totalProcesses;
    private final ProcessEntry[] topProcesses;

    public ProcessInfo(long timestamp, long totalMemoryBytes, long freeMemoryBytes,
                       long swapTotalBytes, long swapUsedBytes,
                       double loadAvg1, double loadAvg5, double loadAvg15,
                       int totalProcesses, ProcessEntry[] topProcesses) {
        this.timestamp = timestamp;
        this.totalMemoryBytes = totalMemoryBytes;
        this.freeMemoryBytes = freeMemoryBytes;
        this.swapTotalBytes = swapTotalBytes;
        this.swapUsedBytes = swapUsedBytes;
        this.loadAvg1 = loadAvg1;
        this.loadAvg5 = loadAvg5;
        this.loadAvg15 = loadAvg15;
        this.totalProcesses = totalProcesses;
        this.topProcesses = topProcesses;
    }

    public long getTimestamp() { return timestamp; }
    public long getTotalMemoryBytes() { return totalMemoryBytes; }
    public long getFreeMemoryBytes() { return freeMemoryBytes; }
    public long getSwapTotalBytes() { return swapTotalBytes; }
    public long getSwapUsedBytes() { return swapUsedBytes; }
    public double getLoadAvg1() { return loadAvg1; }
    public double getLoadAvg5() { return loadAvg5; }
    public double getLoadAvg15() { return loadAvg15; }
    public int getTotalProcesses() { return totalProcesses; }
    public ProcessEntry[] getTopProcesses() { return topProcesses; }

    public double getTotalMemoryMB() { return totalMemoryBytes / (1024.0 * 1024.0); }
    public double getFreeMemoryMB() { return freeMemoryBytes / (1024.0 * 1024.0); }
    public double getUsedMemoryPercent() {
        return totalMemoryBytes > 0
                ? (1.0 - (double) freeMemoryBytes / totalMemoryBytes) * 100 : 0;
    }

    public static final class ProcessEntry {
        public final int pid;
        public final String name;
        public final double cpuPercent;
        public final long rssBytes;
        public final int threads;
        public final String state;

        public ProcessEntry(int pid, String name, double cpuPercent,
                            long rssBytes, int threads, String state) {
            this.pid = pid;
            this.name = name;
            this.cpuPercent = cpuPercent;
            this.rssBytes = rssBytes;
            this.threads = threads;
            this.state = state;
        }

        public double getRssMB() { return rssBytes / (1024.0 * 1024.0); }
    }
}
