package it.denzosoft.jvmmonitor.analysis;

import it.denzosoft.jvmmonitor.model.*;
import it.denzosoft.jvmmonitor.storage.EventStore;
import java.util.*;

public class AnalysisContext {

    private final EventStore store;
    private final AlarmThresholds thresholds;

    public AnalysisContext(EventStore store) {
        this(store, new AlarmThresholds());
    }

    public AnalysisContext(EventStore store, AlarmThresholds thresholds) {
        this.store = store;
        this.thresholds = thresholds;
    }

    public AlarmThresholds getThresholds() {
        return thresholds;
    }

    public EventStore getStore() {
        return store;
    }

    /* ── GC ──────────────────────────────────────────── */

    public List<GcEvent> getRecentGcEvents(int lastSeconds) {
        long now = System.currentTimeMillis();
        return store.getGcEvents(now - lastSeconds * 1000L, now);
    }

    public double getGcFrequencyPerMinute(int lastSeconds) {
        List<GcEvent> events = getRecentGcEvents(lastSeconds);
        if (events.isEmpty()) return 0.0;
        return events.size() * 60.0 / lastSeconds;
    }

    public double getAvgGcPauseMs(int lastSeconds) {
        List<GcEvent> events = getRecentGcEvents(lastSeconds);
        if (events.isEmpty()) return 0.0;
        double total = 0;
        for (int i = 0; i < events.size(); i++) {
            total += events.get(i).getDurationMs();
        }
        return total / events.size();
    }

    public double getMaxGcPauseMs(int lastSeconds) {
        List<GcEvent> events = getRecentGcEvents(lastSeconds);
        double max = 0;
        for (int i = 0; i < events.size(); i++) {
            double d = events.get(i).getDurationMs();
            if (d > max) max = d;
        }
        return max;
    }

    public double getGcThroughputPercent(int lastSeconds) {
        List<GcEvent> events = getRecentGcEvents(lastSeconds);
        if (events.isEmpty()) return 100.0;
        long totalPauseNanos = 0;
        for (int i = 0; i < events.size(); i++) {
            totalPauseNanos += events.get(i).getDurationNanos();
        }
        double wallTimeNanos = lastSeconds * 1000000000.0;
        return (1.0 - totalPauseNanos / wallTimeNanos) * 100.0;
    }

    /* ── Memory ─────────────────────────────────────── */

    public MemorySnapshot getLatestMemory() {
        return store.getLatestMemorySnapshot();
    }

    public double getHeapGrowthRateMBPerHour(int lastMinutes) {
        long now = System.currentTimeMillis();
        List<MemorySnapshot> snapshots = store.getMemorySnapshots(
                now - lastMinutes * 60 * 1000L, now);
        if (snapshots.size() < 2) return 0.0;
        MemorySnapshot first = snapshots.get(0);
        MemorySnapshot last = snapshots.get(snapshots.size() - 1);
        long timeDiffMs = last.getTimestamp() - first.getTimestamp();
        if (timeDiffMs <= 0) return 0.0;
        long heapDiff = last.getHeapUsed() - first.getHeapUsed();
        double hoursElapsed = timeDiffMs / 3600000.0;
        return (heapDiff / (1024.0 * 1024.0)) / hoursElapsed;
    }

    /* ── Threads ────────────────────────────────────── */

    public List<ThreadInfo> getLatestThreads() {
        return store.getLatestThreadInfo();
    }

    public int getBlockedThreadCount() {
        List<ThreadInfo> threads = getLatestThreads();
        int blocked = 0;
        for (int i = 0; i < threads.size(); i++) {
            if (threads.get(i).getState() == ThreadInfo.STATE_BLOCKED) {
                blocked++;
            }
        }
        return blocked;
    }

    public double getBlockedThreadPercent() {
        List<ThreadInfo> threads = getLatestThreads();
        if (threads.isEmpty()) return 0.0;
        return (double) getBlockedThreadCount() / threads.size() * 100.0;
    }

    /* ── Alarms ─────────────────────────────────────── */

    public List<AlarmEvent> getActiveAlarms() {
        return store.getActiveAlarms();
    }

    /* ── Exceptions ─────────────────────────────────── */

    public List<ExceptionEvent> getRecentExceptions(int lastSeconds) {
        long now = System.currentTimeMillis();
        return store.getExceptions(now - lastSeconds * 1000L, now);
    }

    public double getExceptionRatePerMinute(int lastSeconds) {
        List<ExceptionEvent> events = getRecentExceptions(lastSeconds);
        if (events.isEmpty()) return 0.0;
        return events.size() * 60.0 / lastSeconds;
    }

    public ExceptionEvent getLatestException() {
        return store.getLatestException();
    }

    /* ── Safepoints ─────────────────────────────────── */

    public SafepointEvent getLatestSafepoint() {
        return store.getLatestSafepoint();
    }

    /* ── Classloaders ───────────────────────────────── */

    public ClassloaderStats getLatestClassloaderStats() {
        return store.getLatestClassloaderStats();
    }

    /* ── OS Metrics ─────────────────────────────────── */

    public OsMetrics getLatestOsMetrics() {
        return store.getLatestOsMetrics();
    }

    /* ── Native Memory ──────────────────────────────── */

    public NativeMemoryStats getLatestNativeMemory() {
        return store.getLatestNativeMemory();
    }

    /* ── Advanced GC analysis ──────────────────────── */

    /** Heap used after Full GC events (the "live set"). Returns list of [timestamp, heapAfterBytes]. */
    public List<long[]> getLiveSetTrend(int lastMinutes) {
        long now = System.currentTimeMillis();
        List<GcEvent> events = store.getGcEvents(now - lastMinutes * 60 * 1000L, now);
        List<long[]> points = new ArrayList<long[]>();
        for (int i = 0; i < events.size(); i++) {
            GcEvent e = events.get(i);
            if (e.getGcType() == GcEvent.TYPE_FULL && e.getHeapAfter() > 0) {
                points.add(new long[]{e.getTimestamp(), e.getHeapAfter()});
            }
        }
        return points;
    }

    /** True if live set (heap after Full GC) is monotonically growing across 3+ Full GCs. */
    public boolean isLiveSetGrowing(int lastMinutes) {
        List<long[]> points = getLiveSetTrend(lastMinutes);
        if (points.size() < 3) return false;
        int growing = 0;
        for (int i = 1; i < points.size(); i++) {
            if (points.get(i)[1] > points.get(i - 1)[1]) growing++;
        }
        return growing >= points.size() - 1;  /* all but one must be growing */
    }

    /** Average old gen usage after Full GC as percent of max. -1 if no data. */
    public double getOldGenAfterFullGcPercent(int lastMinutes) {
        long now = System.currentTimeMillis();
        List<GcEvent> events = store.getGcEvents(now - lastMinutes * 60 * 1000L, now);
        int count = 0;
        double sumPct = 0;
        MemorySnapshot mem = store.getLatestMemorySnapshot();
        if (mem == null || mem.getHeapMax() <= 0) return -1;
        for (int i = 0; i < events.size(); i++) {
            GcEvent e = events.get(i);
            if (e.getGcType() == GcEvent.TYPE_FULL && e.getHeapAfter() > 0) {
                sumPct += (double) e.getHeapAfter() / mem.getHeapMax() * 100.0;
                count++;
            }
        }
        return count > 0 ? sumPct / count : -1;
    }

    /** Allocation rate in MB/s based on GC events (allocated between consecutive GCs). */
    public double getAllocationRateMBPerSec(int lastSeconds) {
        long now = System.currentTimeMillis();
        List<GcEvent> events = store.getGcEvents(now - lastSeconds * 1000L, now);
        if (events.size() < 2) return 0;
        long totalAllocated = 0;
        long totalTimeMs = 0;
        for (int i = 1; i < events.size(); i++) {
            GcEvent e = events.get(i);
            GcEvent prev = events.get(i - 1);
            if (e.getHeapBefore() > 0 && prev.getHeapAfter() > 0) {
                long allocated = e.getHeapBefore() - prev.getHeapAfter();
                if (allocated > 0) totalAllocated += allocated;
            }
            totalTimeMs += e.getTimestamp() - prev.getTimestamp();
        }
        if (totalTimeMs <= 0) return 0;
        return (totalAllocated / (1024.0 * 1024.0)) / (totalTimeMs / 1000.0);
    }

    /** GC reclaim rate in MB/s (how fast GC frees memory). */
    public double getGcReclaimRateMBPerSec(int lastSeconds) {
        long now = System.currentTimeMillis();
        List<GcEvent> events = store.getGcEvents(now - lastSeconds * 1000L, now);
        if (events.isEmpty()) return 0;
        long totalFreed = 0;
        long totalTimeMs = 0;
        for (int i = 0; i < events.size(); i++) {
            GcEvent e = events.get(i);
            long freed = e.getHeapBefore() - e.getHeapAfter();
            if (freed > 0) totalFreed += freed;
        }
        GcEvent first = events.get(0);
        GcEvent last = events.get(events.size() - 1);
        totalTimeMs = last.getTimestamp() - first.getTimestamp();
        if (totalTimeMs <= 0) return 0;
        return (totalFreed / (1024.0 * 1024.0)) / (totalTimeMs / 1000.0);
    }

    /* ── CPU ───────────────────────────────────────── */

    public CpuUsageSnapshot getLatestCpuUsage() {
        return store.getLatestCpuUsage();
    }

    public List<CpuUsageSnapshot> getRecentCpuUsage(int lastSeconds) {
        long now = System.currentTimeMillis();
        return store.getCpuUsageHistory(now - lastSeconds * 1000L, now);
    }

    /** Average JVM CPU% over last N seconds. */
    public double getAvgJvmCpuPercent(int lastSeconds) {
        List<CpuUsageSnapshot> history = getRecentCpuUsage(lastSeconds);
        if (history.isEmpty()) return 0;
        double sum = 0;
        for (int i = 0; i < history.size(); i++) {
            sum += history.get(i).getProcessCpuPercent();
        }
        return sum / history.size();
    }

    /* ── Threads (trend) ───────────────────────────── */

    /** True if thread count has been monotonically growing over last N snapshots. */
    public boolean isThreadCountGrowing() {
        List<ThreadInfo> threads = store.getLatestThreadInfo();
        /* We only have latest snapshot; to detect trend we'd need history.
           For now, use a simple heuristic: more than 200 threads is suspicious */
        return threads.size() > 200;
    }

    public int getThreadCount() {
        return store.getLatestThreadInfo().size();
    }

    /* ── Network ───────────────────────────────────── */

    public NetworkSnapshot getLatestNetwork() {
        return store.getLatestNetworkSnapshot();
    }

    public List<NetworkSnapshot> getRecentNetworkSnapshots(int lastSeconds) {
        long now = System.currentTimeMillis();
        return store.getNetworkHistory(now - lastSeconds * 1000L, now);
    }

    /** True if CLOSE_WAIT count is growing over recent snapshots. */
    public boolean isCloseWaitGrowing(int lastSeconds) {
        List<NetworkSnapshot> history = getRecentNetworkSnapshots(lastSeconds);
        if (history.size() < 3) return false;
        int growing = 0;
        for (int i = 1; i < history.size(); i++) {
            if (history.get(i).getCloseWaitCount() > history.get(i - 1).getCloseWaitCount()) {
                growing++;
            }
        }
        return growing >= history.size() - 2;
    }

    /** TCP retransmit rate as percentage of total segments out. */
    public double getRetransmitPercent() {
        NetworkSnapshot net = store.getLatestNetworkSnapshot();
        if (net == null || net.getOutSegments() <= 0) return 0;
        return (double) net.getRetransSegments() / net.getOutSegments() * 100.0;
    }

    /* ── Locks ─────────────────────────────────────── */

    public List<LockEvent> getRecentLockEvents(int lastSeconds) {
        long now = System.currentTimeMillis();
        return store.getLockEvents(now - lastSeconds * 1000L, now);
    }
}
