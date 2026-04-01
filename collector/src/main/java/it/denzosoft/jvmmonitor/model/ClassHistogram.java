package it.denzosoft.jvmmonitor.model;

public final class ClassHistogram {

    private final long timestamp;
    private final long elapsedNanos;
    private final Entry[] entries;

    public ClassHistogram(long timestamp, long elapsedNanos, Entry[] entries) {
        this.timestamp = timestamp;
        this.elapsedNanos = elapsedNanos;
        this.entries = entries;
    }

    public long getTimestamp() { return timestamp; }
    public long getElapsedNanos() { return elapsedNanos; }
    public double getElapsedMs() { return elapsedNanos / 1000000.0; }
    public Entry[] getEntries() { return entries; }
    public int getEntryCount() { return entries != null ? entries.length : 0; }

    public static final class Entry {
        private final String className;
        private final int instanceCount;
        private final long totalSize;

        public Entry(String className, int instanceCount, long totalSize) {
            this.className = className;
            this.instanceCount = instanceCount;
            this.totalSize = totalSize;
        }

        public String getClassName() { return className; }
        public int getInstanceCount() { return instanceCount; }
        public long getTotalSize() { return totalSize; }
        public double getTotalSizeMB() { return totalSize / (1024.0 * 1024.0); }
    }
}
