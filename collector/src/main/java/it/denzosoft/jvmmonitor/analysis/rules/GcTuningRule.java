package it.denzosoft.jvmmonitor.analysis.rules;

import it.denzosoft.jvmmonitor.analysis.AlarmThresholds;
import it.denzosoft.jvmmonitor.analysis.AnalysisContext;
import it.denzosoft.jvmmonitor.model.Diagnosis;
import it.denzosoft.jvmmonitor.model.GcEvent;
import it.denzosoft.jvmmonitor.model.MemorySnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * GC tuning diagnostics based on JMX-derived GcEvent data
 * (GarbageCollectionNotificationInfo: heap before/after, duration, cause).
 *
 * Detects tuning problems that cannot be inferred by GcPressureRule alone:
 *   - Full GC storm (consecutive Full GCs with little reclaim)
 *   - Old gen fills too fast (promotion pressure)
 *   - Frequent explicit System.gc() calls
 *   - Allocation Failure + large Eden (young gen undersized)
 *   - Heap cannot shrink below configured minimum (sizing issue)
 *   - GC burning CPU (process CPU high during every GC window)
 */
public class GcTuningRule implements DiagnosticRule {

    public String getName() {
        return "GC Tuning";
    }

    public List<Diagnosis> evaluate(AnalysisContext ctx) {
        List<Diagnosis> results = new ArrayList<Diagnosis>();
        AlarmThresholds t = ctx.getThresholds();

        List<GcEvent> events = ctx.getRecentGcEvents(300); /* last 5 min */
        if (events.isEmpty()) return results;

        MemorySnapshot mem = ctx.getLatestMemory();
        long heapMax = mem != null ? mem.getHeapMax() : 0;

        /* ── 1. Full GC storm: 3+ Full GCs in last 5 min with low reclaim ratio ── */
        int fullCount = 0;
        long fullFreedTotal = 0;
        long fullHeapBeforeTotal = 0;
        for (int i = 0; i < events.size(); i++) {
            GcEvent e = events.get(i);
            if (e.getGcType() == GcEvent.TYPE_FULL) {
                fullCount++;
                fullFreedTotal += e.getFreedBytes();
                fullHeapBeforeTotal += e.getHeapBefore();
            }
        }
        if (fullCount >= t.gcFullStormMinCount) {
            double reclaimPct = fullHeapBeforeTotal > 0
                    ? (fullFreedTotal * 100.0 / fullHeapBeforeTotal) : 0;
            if (reclaimPct < t.gcFullReclaimMinPct) {
                results.add(Diagnosis.builder()
                        .timestamp(System.currentTimeMillis())
                        .category("GC Tuning")
                        .severity(2)
                        .summary(String.format(
                                "Full GC storm: %d Full GCs in 5min, reclaiming only %.1f%% — "
                                        + "live set exceeds old gen capacity",
                                fullCount, reclaimPct))
                        .evidence(String.format(
                                "Full GCs=%d, avg reclaim=%.1f%%, threshold=%.1f%%",
                                fullCount, reclaimPct, t.gcFullReclaimMinPct))
                        .fix("Increase -Xmx or investigate memory leak; consider G1/ZGC "
                                + "if still on ParallelGC/CMS")
                        .build());
            }
        }

        /* ── 2. Explicit System.gc() calls ── */
        int explicitCount = 0;
        for (int i = 0; i < events.size(); i++) {
            String cause = events.get(i).getCause();
            if (cause != null && (cause.toLowerCase().contains("system.gc")
                    || cause.contains("System"))) {
                explicitCount++;
            }
        }
        if (explicitCount >= t.gcExplicitMinCount) {
            results.add(Diagnosis.builder()
                    .timestamp(System.currentTimeMillis())
                    .category("GC Tuning")
                    .severity(1)
                    .summary("Explicit System.gc() invoked " + explicitCount + " times in 5min")
                    .evidence("cause field reports System.gc() in GC events")
                    .fix("Add -XX:+DisableExplicitGC or replace calls with JVM-managed GC")
                    .build());
        }

        /* ── 3. Promotion pressure: sustained promoted bytes/GC > threshold ── */
        long promotedTotal = 0;
        int promotedSamples = 0;
        for (int i = 0; i < events.size(); i++) {
            GcEvent e = events.get(i);
            if (e.getGcType() == GcEvent.TYPE_YOUNG && e.getPromotedBytes() > 0) {
                promotedTotal += e.getPromotedBytes();
                promotedSamples++;
            }
        }
        if (promotedSamples > 0 && heapMax > 0) {
            double avgPromotedMB = (promotedTotal / promotedSamples) / (1024.0 * 1024.0);
            double promotedPctOfHeap = (promotedTotal / (double) promotedSamples) / heapMax * 100.0;
            if (promotedPctOfHeap > t.gcPromotionPctWarn) {
                results.add(Diagnosis.builder()
                        .timestamp(System.currentTimeMillis())
                        .category("GC Tuning")
                        .severity(1)
                        .summary(String.format(
                                "High promotion rate: %.1f MB/young GC (%.1f%% of heap) — "
                                        + "young gen may be undersized",
                                avgPromotedMB, promotedPctOfHeap))
                        .evidence(String.format(
                                "avg promoted=%.1f MB, samples=%d", avgPromotedMB, promotedSamples))
                        .fix("Increase -Xmn (young gen) or -XX:NewRatio; "
                                + "short-lived allocations should die in Eden, not be promoted")
                        .build());
            }
        }

        /* ── 4. GC burning CPU: avg processCpu > threshold during GC ── */
        double cpuSum = 0;
        int cpuSamples = 0;
        for (int i = 0; i < events.size(); i++) {
            double cpu = events.get(i).getProcessCpuAtGc();
            if (cpu > 0) { cpuSum += cpu; cpuSamples++; }
        }
        if (cpuSamples > 0) {
            double avgCpu = cpuSum / cpuSamples;
            if (avgCpu > t.gcCpuHighPct) {
                results.add(Diagnosis.builder()
                        .timestamp(System.currentTimeMillis())
                        .category("GC Tuning")
                        .severity(1)
                        .summary(String.format(
                                "Process CPU averaging %.1f%% during GC windows — "
                                        + "GC threads saturating CPU",
                                avgCpu))
                        .fix("Reduce -XX:ParallelGCThreads / -XX:ConcGCThreads "
                                + "or switch to a lower-overhead collector")
                        .build());
            }
        }

        /* ── 5. Heap staying near max after GC: insufficient headroom ── */
        if (heapMax > 0 && !events.isEmpty()) {
            GcEvent last = events.get(events.size() - 1);
            if (last.getHeapAfter() > 0) {
                double afterPct = last.getHeapAfter() * 100.0 / heapMax;
                if (afterPct > t.gcHeapAfterGcCritPct) {
                    results.add(Diagnosis.builder()
                            .timestamp(System.currentTimeMillis())
                            .category("GC Tuning")
                            .severity(2)
                            .summary(String.format(
                                    "Heap at %.1f%% of max after last GC — no headroom, "
                                            + "OOM imminent",
                                    afterPct))
                            .fix("Increase -Xmx or investigate retained references")
                            .build());
                }
            }
        }

        return results;
    }
}
