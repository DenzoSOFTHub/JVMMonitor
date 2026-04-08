package it.denzosoft.jvmmonitor.analysis;

import java.io.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configurable alarm thresholds. All values have sensible defaults.
 * Can be saved to / loaded from a .properties-style text file.
 */
public class AlarmThresholds {

    /* ── Memory ────────────────────────────────────── */
    /** Minimum Full GCs needed to detect live set growth trend. */
    public int liveSetMinFullGcs = 3;
    /** Old Gen after Full GC % threshold for exhaustion alarm. */
    public double oldGenAfterFullGcPct = 80;
    /** Allocation/reclaim rate ratio to trigger pressure alarm. */
    public double allocPressureRatio = 1.5;

    /* ── GC ────────────────────────────────────────── */
    /** GC throughput % below which to warn (time spent in app vs GC). */
    public double gcThroughputWarnPct = 90;
    /** GC throughput % below which to raise critical. */
    public double gcThroughputCritPct = 80;
    /** GC pause ms above which to raise critical. */
    public double gcPauseMaxMs = 1000;
    /** Minimum Full GCs in 5min to consider a Full GC storm. */
    public int gcFullStormMinCount = 3;
    /** Minimum reclaim % during Full GC storm (below = live set too big). */
    public double gcFullReclaimMinPct = 20;
    /** Minimum explicit System.gc() calls in 5min to warn. */
    public int gcExplicitMinCount = 2;
    /** Avg promoted bytes per young GC as % of heap — high = undersized young gen. */
    public double gcPromotionPctWarn = 15;
    /** Avg process CPU % during GC windows above which GC is CPU-bound. */
    public double gcCpuHighPct = 85;
    /** Heap used after last GC as % of max — critical no-headroom threshold. */
    public double gcHeapAfterGcCritPct = 90;

    /* ── CPU ───────────────────────────────────────── */
    /** JVM CPU % above which to warn (sustained over 30s). */
    public double cpuWarnPct = 80;
    /** JVM CPU % above which to raise critical. */
    public double cpuCritPct = 95;
    /** Single thread CPU % above which to flag as runaway. */
    public double runawayThreadCpuPct = 50;

    /* ── Threads ───────────────────────────────────── */
    /** Blocked thread % above which to warn. */
    public double blockedWarnPct = 20;
    /** Blocked thread % above which to raise critical. */
    public double blockedCritPct = 50;
    /** Minimum blocked thread count to trigger alarm. */
    public int blockedMinCount = 3;
    /** Thread count above which to warn (thread leak). */
    public int threadCountWarn = 500;
    /** Thread count above which to raise critical. */
    public int threadCountCrit = 1000;

    /* ── Exceptions ────────────────────────────────── */
    /** Exception rate spike multiplier vs baseline to trigger storm alarm. */
    public double exceptionSpikeMultiplier = 3.0;
    /** Minimum exceptions/min to consider a rate issue. */
    public double exceptionMinRate = 10;
    /** Recurring bug threshold: same location count in 60s. */
    public int recurringBugMinCount = 10;
    /** High overall exception rate threshold (per minute). */
    public double exceptionHighRate = 100;

    /* ── Network ───────────────────────────────────── */
    /** CLOSE_WAIT count above which to warn. */
    public int closeWaitWarn = 5;
    /** CLOSE_WAIT count above which to raise critical. */
    public int closeWaitCrit = 50;
    /** TCP retransmit % above which to warn. */
    public double retransmitWarnPct = 5;
    /** TCP retransmit % above which to raise critical. */
    public double retransmitCritPct = 15;
    /** Established connections above which to warn. */
    public int establishedWarn = 500;

    /* ── Response Time ─────────────────────────────── */
    /** Response time degradation multiplier vs baseline. */
    public double responseTimeDegradationRatio = 2.0;
    /** Minimum response time (ms) to consider degradation. */
    public double responseTimeMinMs = 100;
    /** JDBC query slow threshold (ms). */
    public double jdbcSlowQueryMs = 1000;
    /** JDBC getConnection slow threshold (ms). */
    public double jdbcSlowConnectMs = 500;

    /* ── Classloader ───────────────────────────────── */
    /** Classloader count above which to warn. */
    public int classloaderWarn = 20;
    /** Classloader count above which to raise critical. */
    public int classloaderCrit = 50;

    /* ── Safepoint ─────────────────────────────────── */
    /** Safepoint sync time (ms) above which to warn. */
    public double safepointSyncWarnMs = 50;
    /** Safepoint sync time (ms) above which to raise critical. */
    public double safepointSyncCritMs = 200;
    /** Safepoint total time (ms) above which to warn. */
    public double safepointTotalWarnMs = 100;
    /** Safepoint total time (ms) above which to raise critical. */
    public double safepointTotalCritMs = 500;

    /** Save thresholds to a file. */
    public void save(File file) throws IOException {
        Map<String, String> props = toMap();
        BufferedWriter w = new BufferedWriter(new FileWriter(file));
        try {
            w.write("# JVMMonitor Alarm Thresholds\n");
            w.write("# Generated by JVMMonitor v1.1.0\n\n");
            for (Map.Entry<String, String> e : props.entrySet()) {
                w.write(e.getKey() + "=" + e.getValue() + "\n");
            }
        } finally {
            w.close();
        }
    }

    /** Load thresholds from a file. Unknown keys are ignored. */
    public void load(File file) throws IOException {
        BufferedReader r = new BufferedReader(new FileReader(file));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim();
                setFromString(key, val);
            }
        } finally {
            r.close();
        }
    }

    /** Returns all thresholds as key=value map for display. */
    public Map<String, String> toMap() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("memory.liveSetMinFullGcs", String.valueOf(liveSetMinFullGcs));
        m.put("memory.oldGenAfterFullGcPct", String.valueOf(oldGenAfterFullGcPct));
        m.put("memory.allocPressureRatio", String.valueOf(allocPressureRatio));
        m.put("gc.throughputWarnPct", String.valueOf(gcThroughputWarnPct));
        m.put("gc.throughputCritPct", String.valueOf(gcThroughputCritPct));
        m.put("gc.pauseMaxMs", String.valueOf(gcPauseMaxMs));
        m.put("gc.fullStormMinCount", String.valueOf(gcFullStormMinCount));
        m.put("gc.fullReclaimMinPct", String.valueOf(gcFullReclaimMinPct));
        m.put("gc.explicitMinCount", String.valueOf(gcExplicitMinCount));
        m.put("gc.promotionPctWarn", String.valueOf(gcPromotionPctWarn));
        m.put("gc.cpuHighPct", String.valueOf(gcCpuHighPct));
        m.put("gc.heapAfterGcCritPct", String.valueOf(gcHeapAfterGcCritPct));
        m.put("cpu.warnPct", String.valueOf(cpuWarnPct));
        m.put("cpu.critPct", String.valueOf(cpuCritPct));
        m.put("cpu.runawayThreadPct", String.valueOf(runawayThreadCpuPct));
        m.put("threads.blockedWarnPct", String.valueOf(blockedWarnPct));
        m.put("threads.blockedCritPct", String.valueOf(blockedCritPct));
        m.put("threads.blockedMinCount", String.valueOf(blockedMinCount));
        m.put("threads.countWarn", String.valueOf(threadCountWarn));
        m.put("threads.countCrit", String.valueOf(threadCountCrit));
        m.put("exceptions.spikeMultiplier", String.valueOf(exceptionSpikeMultiplier));
        m.put("exceptions.minRate", String.valueOf(exceptionMinRate));
        m.put("exceptions.recurringBugMin", String.valueOf(recurringBugMinCount));
        m.put("exceptions.highRate", String.valueOf(exceptionHighRate));
        m.put("network.closeWaitWarn", String.valueOf(closeWaitWarn));
        m.put("network.closeWaitCrit", String.valueOf(closeWaitCrit));
        m.put("network.retransmitWarnPct", String.valueOf(retransmitWarnPct));
        m.put("network.retransmitCritPct", String.valueOf(retransmitCritPct));
        m.put("network.establishedWarn", String.valueOf(establishedWarn));
        m.put("response.degradationRatio", String.valueOf(responseTimeDegradationRatio));
        m.put("response.minMs", String.valueOf(responseTimeMinMs));
        m.put("response.jdbcSlowMs", String.valueOf(jdbcSlowQueryMs));
        m.put("response.jdbcSlowConnectMs", String.valueOf(jdbcSlowConnectMs));
        m.put("classloader.warn", String.valueOf(classloaderWarn));
        m.put("classloader.crit", String.valueOf(classloaderCrit));
        m.put("safepoint.syncWarnMs", String.valueOf(safepointSyncWarnMs));
        m.put("safepoint.syncCritMs", String.valueOf(safepointSyncCritMs));
        m.put("safepoint.totalWarnMs", String.valueOf(safepointTotalWarnMs));
        m.put("safepoint.totalCritMs", String.valueOf(safepointTotalCritMs));
        return m;
    }

    private void setFromString(String key, String val) {
        try {
            if ("memory.liveSetMinFullGcs".equals(key)) liveSetMinFullGcs = Integer.parseInt(val);
            else if ("memory.oldGenAfterFullGcPct".equals(key)) oldGenAfterFullGcPct = Double.parseDouble(val);
            else if ("memory.allocPressureRatio".equals(key)) allocPressureRatio = Double.parseDouble(val);
            else if ("gc.throughputWarnPct".equals(key)) gcThroughputWarnPct = Double.parseDouble(val);
            else if ("gc.throughputCritPct".equals(key)) gcThroughputCritPct = Double.parseDouble(val);
            else if ("gc.pauseMaxMs".equals(key)) gcPauseMaxMs = Double.parseDouble(val);
            else if ("gc.fullStormMinCount".equals(key)) gcFullStormMinCount = Integer.parseInt(val);
            else if ("gc.fullReclaimMinPct".equals(key)) gcFullReclaimMinPct = Double.parseDouble(val);
            else if ("gc.explicitMinCount".equals(key)) gcExplicitMinCount = Integer.parseInt(val);
            else if ("gc.promotionPctWarn".equals(key)) gcPromotionPctWarn = Double.parseDouble(val);
            else if ("gc.cpuHighPct".equals(key)) gcCpuHighPct = Double.parseDouble(val);
            else if ("gc.heapAfterGcCritPct".equals(key)) gcHeapAfterGcCritPct = Double.parseDouble(val);
            else if ("cpu.warnPct".equals(key)) cpuWarnPct = Double.parseDouble(val);
            else if ("cpu.critPct".equals(key)) cpuCritPct = Double.parseDouble(val);
            else if ("cpu.runawayThreadPct".equals(key)) runawayThreadCpuPct = Double.parseDouble(val);
            else if ("threads.blockedWarnPct".equals(key)) blockedWarnPct = Double.parseDouble(val);
            else if ("threads.blockedCritPct".equals(key)) blockedCritPct = Double.parseDouble(val);
            else if ("threads.blockedMinCount".equals(key)) blockedMinCount = Integer.parseInt(val);
            else if ("threads.countWarn".equals(key)) threadCountWarn = Integer.parseInt(val);
            else if ("threads.countCrit".equals(key)) threadCountCrit = Integer.parseInt(val);
            else if ("exceptions.spikeMultiplier".equals(key)) exceptionSpikeMultiplier = Double.parseDouble(val);
            else if ("exceptions.minRate".equals(key)) exceptionMinRate = Double.parseDouble(val);
            else if ("exceptions.recurringBugMin".equals(key)) recurringBugMinCount = Integer.parseInt(val);
            else if ("exceptions.highRate".equals(key)) exceptionHighRate = Double.parseDouble(val);
            else if ("network.closeWaitWarn".equals(key)) closeWaitWarn = Integer.parseInt(val);
            else if ("network.closeWaitCrit".equals(key)) closeWaitCrit = Integer.parseInt(val);
            else if ("network.retransmitWarnPct".equals(key)) retransmitWarnPct = Double.parseDouble(val);
            else if ("network.retransmitCritPct".equals(key)) retransmitCritPct = Double.parseDouble(val);
            else if ("network.establishedWarn".equals(key)) establishedWarn = Integer.parseInt(val);
            else if ("response.degradationRatio".equals(key)) responseTimeDegradationRatio = Double.parseDouble(val);
            else if ("response.minMs".equals(key)) responseTimeMinMs = Double.parseDouble(val);
            else if ("response.jdbcSlowMs".equals(key)) jdbcSlowQueryMs = Double.parseDouble(val);
            else if ("response.jdbcSlowConnectMs".equals(key)) jdbcSlowConnectMs = Double.parseDouble(val);
            else if ("classloader.warn".equals(key)) classloaderWarn = Integer.parseInt(val);
            else if ("classloader.crit".equals(key)) classloaderCrit = Integer.parseInt(val);
            else if ("safepoint.syncWarnMs".equals(key)) safepointSyncWarnMs = Double.parseDouble(val);
            else if ("safepoint.syncCritMs".equals(key)) safepointSyncCritMs = Double.parseDouble(val);
            else if ("safepoint.totalWarnMs".equals(key)) safepointTotalWarnMs = Double.parseDouble(val);
            else if ("safepoint.totalCritMs".equals(key)) safepointTotalCritMs = Double.parseDouble(val);
        } catch (NumberFormatException e) {
            /* skip invalid values */
        }
    }
}
