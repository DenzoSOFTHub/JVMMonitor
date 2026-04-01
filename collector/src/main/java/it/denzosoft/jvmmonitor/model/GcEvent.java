package it.denzosoft.jvmmonitor.model;

public final class GcEvent {

    public static final int TYPE_YOUNG = 1;
    public static final int TYPE_OLD = 2;
    public static final int TYPE_FULL = 3;

    private final long timestamp;
    private final int gcType;
    private final long durationNanos;
    private final int gcCount;
    private final int fullGcCount;

    /* Extended fields for GC analysis */
    private final long heapBefore;
    private final long heapAfter;
    private final long edenBefore;
    private final long edenAfter;
    private final long oldGenBefore;
    private final long oldGenAfter;
    private final String cause;
    private final double processCpuAtGc;

    public GcEvent(long timestamp, int gcType, long durationNanos,
                   int gcCount, int fullGcCount) {
        this(timestamp, gcType, durationNanos, gcCount, fullGcCount,
             0, 0, 0, 0, 0, 0, "", 0);
    }

    public GcEvent(long timestamp, int gcType, long durationNanos,
                   int gcCount, int fullGcCount,
                   long heapBefore, long heapAfter,
                   long edenBefore, long edenAfter,
                   long oldGenBefore, long oldGenAfter,
                   String cause, double processCpuAtGc) {
        this.timestamp = timestamp;
        this.gcType = gcType;
        this.durationNanos = durationNanos;
        this.gcCount = gcCount;
        this.fullGcCount = fullGcCount;
        this.heapBefore = heapBefore;
        this.heapAfter = heapAfter;
        this.edenBefore = edenBefore;
        this.edenAfter = edenAfter;
        this.oldGenBefore = oldGenBefore;
        this.oldGenAfter = oldGenAfter;
        this.cause = cause != null ? cause : "";
        this.processCpuAtGc = processCpuAtGc;
    }

    public long getTimestamp() { return timestamp; }
    public int getGcType() { return gcType; }
    public long getDurationNanos() { return durationNanos; }
    public double getDurationMs() { return durationNanos / 1000000.0; }
    public int getGcCount() { return gcCount; }
    public int getFullGcCount() { return fullGcCount; }

    public long getHeapBefore() { return heapBefore; }
    public long getHeapAfter() { return heapAfter; }
    public long getFreedBytes() { return heapBefore > heapAfter ? heapBefore - heapAfter : 0; }
    public double getFreedMB() { return getFreedBytes() / (1024.0 * 1024.0); }

    public long getEdenBefore() { return edenBefore; }
    public long getEdenAfter() { return edenAfter; }
    public long getOldGenBefore() { return oldGenBefore; }
    public long getOldGenAfter() { return oldGenAfter; }

    /** Bytes promoted from young to old gen during this GC. */
    public long getPromotedBytes() {
        long oldGrowth = oldGenAfter - oldGenBefore;
        return oldGrowth > 0 ? oldGrowth : 0;
    }
    public double getPromotedMB() { return getPromotedBytes() / (1024.0 * 1024.0); }

    public String getCause() { return cause; }
    public double getProcessCpuAtGc() { return processCpuAtGc; }

    public String getTypeName() {
        switch (gcType) {
            case TYPE_YOUNG: return "Young";
            case TYPE_OLD: return "Old";
            case TYPE_FULL: return "Full";
            default: return "Unknown";
        }
    }
}
