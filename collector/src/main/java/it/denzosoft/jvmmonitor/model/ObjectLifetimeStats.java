package it.denzosoft.jvmmonitor.model;

/**
 * Object lifetime distribution: how long objects live before being collected.
 * Built from JVMTI SetTag + ObjectFree events.
 */
public final class ObjectLifetimeStats {

    private final long timestamp;
    private final int totalTracked;
    private final int totalFreed;
    private final int stillAlive;
    private final LifetimeBucket[] buckets;
    private final ClassLifetime[] topLongLived;

    public ObjectLifetimeStats(long timestamp, int totalTracked, int totalFreed, int stillAlive,
                                LifetimeBucket[] buckets, ClassLifetime[] topLongLived) {
        this.timestamp = timestamp;
        this.totalTracked = totalTracked;
        this.totalFreed = totalFreed;
        this.stillAlive = stillAlive;
        this.buckets = buckets;
        this.topLongLived = topLongLived;
    }

    public long getTimestamp() { return timestamp; }
    public int getTotalTracked() { return totalTracked; }
    public int getTotalFreed() { return totalFreed; }
    public int getStillAlive() { return stillAlive; }
    public LifetimeBucket[] getBuckets() { return buckets; }
    public ClassLifetime[] getTopLongLived() { return topLongLived; }

    /** Time bucket for lifetime distribution histogram. */
    public static final class LifetimeBucket {
        public final String label;      /* e.g., "<1 GC", "1-5 GC", ">10 GC", "never freed" */
        public final int count;
        public final double percent;

        public LifetimeBucket(String label, int count, double percent) {
            this.label = label;
            this.count = count;
            this.percent = percent;
        }
    }

    /** Per-class lifetime info. */
    public static final class ClassLifetime {
        public final String className;
        public final int count;
        public final double avgLifetimeMs;
        public final double maxLifetimeMs;
        public final int neverFreedCount;

        public ClassLifetime(String className, int count, double avgLifetimeMs,
                             double maxLifetimeMs, int neverFreedCount) {
            this.className = className;
            this.count = count;
            this.avgLifetimeMs = avgLifetimeMs;
            this.maxLifetimeMs = maxLifetimeMs;
            this.neverFreedCount = neverFreedCount;
        }
    }
}
