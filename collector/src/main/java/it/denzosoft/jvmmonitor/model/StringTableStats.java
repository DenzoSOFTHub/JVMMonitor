package it.denzosoft.jvmmonitor.model;

public final class StringTableStats {

    private final long timestamp;
    private final boolean available;
    private final String rawOutput;

    public StringTableStats(long timestamp, boolean available, String rawOutput) {
        this.timestamp = timestamp;
        this.available = available;
        this.rawOutput = rawOutput;
    }

    public long getTimestamp() { return timestamp; }
    public boolean isAvailable() { return available; }
    public String getRawOutput() { return rawOutput; }
}
