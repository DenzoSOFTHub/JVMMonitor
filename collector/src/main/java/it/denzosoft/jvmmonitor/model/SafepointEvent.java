package it.denzosoft.jvmmonitor.model;

public final class SafepointEvent {

    private final long timestamp;
    private final long safepointCount;
    private final long totalTimeMs;
    private final long syncTimeMs;

    public SafepointEvent(long timestamp, long safepointCount, long totalTimeMs, long syncTimeMs) {
        this.timestamp = timestamp;
        this.safepointCount = safepointCount;
        this.totalTimeMs = totalTimeMs;
        this.syncTimeMs = syncTimeMs;
    }

    public long getTimestamp() { return timestamp; }
    public long getSafepointCount() { return safepointCount; }
    public long getTotalTimeMs() { return totalTimeMs; }
    public long getSyncTimeMs() { return syncTimeMs; }
    public boolean isAvailable() { return safepointCount >= 0; }
}
