package it.denzosoft.jvmmonitor.model;

public final class MemorySnapshot {

    private final long timestamp;
    private final long heapUsed;
    private final long heapMax;
    private final long nonHeapUsed;
    private final long nonHeapMax;

    public MemorySnapshot(long timestamp, long heapUsed, long heapMax,
                          long nonHeapUsed, long nonHeapMax) {
        this.timestamp = timestamp;
        this.heapUsed = heapUsed;
        this.heapMax = heapMax;
        this.nonHeapUsed = nonHeapUsed;
        this.nonHeapMax = nonHeapMax;
    }

    public long getTimestamp() { return timestamp; }
    public long getHeapUsed() { return heapUsed; }
    public long getHeapMax() { return heapMax; }
    public long getNonHeapUsed() { return nonHeapUsed; }
    public long getNonHeapMax() { return nonHeapMax; }

    public double getHeapUsagePercent() {
        return heapMax > 0 ? (double) heapUsed / heapMax * 100.0 : 0.0;
    }

    public String getHeapUsedMB() {
        return String.format("%.1f MB", heapUsed / (1024.0 * 1024.0));
    }

    public String getHeapMaxMB() {
        return String.format("%.1f MB", heapMax / (1024.0 * 1024.0));
    }
}
