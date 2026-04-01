package it.denzosoft.jvmmonitor.model;

public final class GcDetail {

    private final long timestamp;
    private final CollectorInfo[] collectors;

    public GcDetail(long timestamp, CollectorInfo[] collectors) {
        this.timestamp = timestamp;
        this.collectors = collectors;
    }

    public long getTimestamp() { return timestamp; }
    public CollectorInfo[] getCollectors() { return collectors; }
    public int getCollectorCount() { return collectors != null ? collectors.length : 0; }

    public static final class CollectorInfo {
        private final String name;
        private final long collectionCount;
        private final long collectionTimeMs;
        private final String[] memoryPools;

        public CollectorInfo(String name, long collectionCount, long collectionTimeMs,
                             String[] memoryPools) {
            this.name = name;
            this.collectionCount = collectionCount;
            this.collectionTimeMs = collectionTimeMs;
            this.memoryPools = memoryPools;
        }

        public String getName() { return name; }
        public long getCollectionCount() { return collectionCount; }
        public long getCollectionTimeMs() { return collectionTimeMs; }
        public String[] getMemoryPools() { return memoryPools; }
    }
}
