package it.denzosoft.jvmmonitor.analysis.rules;

import it.denzosoft.jvmmonitor.analysis.AlarmThresholds;
import it.denzosoft.jvmmonitor.analysis.AnalysisContext;
import it.denzosoft.jvmmonitor.model.Diagnosis;
import it.denzosoft.jvmmonitor.model.MemorySnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Detects real memory leaks by analyzing live set trend (heap after Full GC),
 * NOT just heap usage percentage.
 *
 * Heap at 85% is NOT a problem if Full GC can reclaim half of it.
 * The real indicator is: is the floor rising after each Full GC?
 */
public class HeapGrowthRule implements DiagnosticRule {

    public String getName() {
        return "Memory Leak Detection";
    }

    public List<Diagnosis> evaluate(AnalysisContext ctx) {
        List<Diagnosis> results = new ArrayList<Diagnosis>();
        AlarmThresholds t = ctx.getThresholds();

        /* ── 1. Live set growing = true memory leak ──────────── */
        List<long[]> liveSet = ctx.getLiveSetTrend(10);
        if (liveSet.size() >= t.liveSetMinFullGcs && ctx.isLiveSetGrowing(10)) {
            double firstMB = liveSet.get(0)[1] / (1024.0 * 1024.0);
            double lastMB = liveSet.get(liveSet.size() - 1)[1] / (1024.0 * 1024.0);
            double growthMB = lastMB - firstMB;
            long timeDiffMs = liveSet.get(liveSet.size() - 1)[0] - liveSet.get(0)[0];
            double growthPerHour = timeDiffMs > 0 ? growthMB / (timeDiffMs / 3600000.0) : 0;

            MemorySnapshot mem = ctx.getLatestMemory();
            double maxMB = mem != null ? mem.getHeapMax() / (1024.0 * 1024.0) : 0;
            double hoursToOOM = growthPerHour > 0 && maxMB > lastMB
                    ? (maxMB - lastMB) / growthPerHour : -1;

            int severity = hoursToOOM > 0 && hoursToOOM < 2.0 ? 2 : 1;

            String oomEst = hoursToOOM > 0
                    ? String.format("Estimated OOM in %.1f hours.", hoursToOOM)
                    : "OOM time unknown.";

            results.add(Diagnosis.builder()
                .timestamp(System.currentTimeMillis())
                .category("Memory Leak")
                .severity(severity)
                .summary(String.format(
                    "Live set (heap after Full GC) growing: %.0f MB -> %.0f MB across %d Full GCs. " +
                    "Growth rate: %.0f MB/h. %s",
                    firstMB, lastMB, liveSet.size(), growthPerHour, oomEst))
                .evidence(String.format(
                    "Live set trend: %d Full GCs, first=%.0f MB, last=%.0f MB, delta=+%.0f MB",
                    liveSet.size(), firstMB, lastMB, growthMB))
                .location("Heap Old Generation")
                .suggestedAction("Take two class histograms 5 min apart and compare. " +
                    "Look for classes with growing instance count.")
                .build());
        }

        /* ── 2. Old Gen nearly full after Full GC = imminent OOM ── */
        double oldGenPct = ctx.getOldGenAfterFullGcPercent(5);
        if (oldGenPct > t.oldGenAfterFullGcPct) {
            int severity = oldGenPct > 90 ? 2 : 1;
            results.add(Diagnosis.builder()
                .timestamp(System.currentTimeMillis())
                .category("Old Gen Exhaustion")
                .severity(severity)
                .summary(String.format(
                    "Old Gen at %.0f%% even after Full GC — GC cannot free enough memory. " +
                    "OOM is imminent if load continues.", oldGenPct))
                .evidence(String.format("Average heap after Full GC: %.0f%% of max", oldGenPct))
                .location("Heap Old Generation")
                .suggestedAction("Increase -Xmx or fix the memory leak. " +
                    "Check class histogram for objects that should have been collected.")
                .build());
        }

        /* ── 3. Allocation pressure: allocating faster than GC can reclaim ── */
        double allocRate = ctx.getAllocationRateMBPerSec(60);
        double reclaimRate = ctx.getGcReclaimRateMBPerSec(60);
        if (allocRate > 0 && reclaimRate > 0 && allocRate > reclaimRate * t.allocPressureRatio) {
            results.add(Diagnosis.builder()
                .timestamp(System.currentTimeMillis())
                .category("Allocation Pressure")
                .severity(1)
                .summary(String.format(
                    "Allocation rate (%.0f MB/s) exceeds GC reclaim rate (%.0f MB/s) by %.0f%%. " +
                    "Heap will fill up faster than GC can free it.",
                    allocRate, reclaimRate, (allocRate / reclaimRate - 1) * 100))
                .evidence(String.format(
                    "Alloc: %.0f MB/s, Reclaim: %.0f MB/s, Ratio: %.1fx",
                    allocRate, reclaimRate, allocRate / reclaimRate))
                .suggestedAction("Reduce allocation rate: check allocation recording for top allocators. " +
                    "Consider object pooling or reducing temporary object creation.")
                .build());
        }

        return results;
    }
}
