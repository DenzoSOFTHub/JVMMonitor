package it.denzosoft.jvmmonitor.analysis.rules;

import it.denzosoft.jvmmonitor.analysis.AlarmThresholds;
import it.denzosoft.jvmmonitor.analysis.AnalysisContext;
import it.denzosoft.jvmmonitor.model.Diagnosis;
import java.util.ArrayList;
import java.util.List;

public class GcPressureRule implements DiagnosticRule {

    public String getName() {
        return "GC Pressure";
    }

    public List<Diagnosis> evaluate(AnalysisContext ctx) {
        List<Diagnosis> results = new ArrayList<Diagnosis>();
        AlarmThresholds t = ctx.getThresholds();

        double throughput = ctx.getGcThroughputPercent(60);
        double gcPerMin = ctx.getGcFrequencyPerMinute(60);
        double avgPauseMs = ctx.getAvgGcPauseMs(60);
        double maxPauseMs = ctx.getMaxGcPauseMs(60);

        if (throughput < t.gcThroughputWarnPct) {
            int severity = throughput < t.gcThroughputCritPct ? 2 : 1;
            results.add(Diagnosis.builder()
                .timestamp(System.currentTimeMillis())
                .category("GC Pressure")
                .severity(severity)
                .summary(String.format(
                    "GC throughput %.1f%% — losing %.1f%% of time to GC. " +
                    "%.0f GC/min, avg pause %.1fms, max pause %.1fms",
                    throughput, 100.0 - throughput, gcPerMin, avgPauseMs, maxPauseMs))
                .evidence(String.format(
                    "GC frequency: %.0f/min, avg pause: %.1fms, max: %.1fms",
                    gcPerMin, avgPauseMs, maxPauseMs))
                .suggestedAction("enable alloc 1")
                .build());
        }

        if (maxPauseMs > t.gcPauseMaxMs) {
            results.add(Diagnosis.builder()
                .timestamp(System.currentTimeMillis())
                .category("Long GC Pause")
                .severity(2)
                .summary(String.format(
                    "Max GC pause %.0fms in last 60s — application freeze detected", maxPauseMs))
                .suggestedAction("enable alloc 1")
                .build());
        }

        return results;
    }
}
