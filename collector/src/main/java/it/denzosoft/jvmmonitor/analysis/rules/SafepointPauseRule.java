package it.denzosoft.jvmmonitor.analysis.rules;

import it.denzosoft.jvmmonitor.analysis.AlarmThresholds;
import it.denzosoft.jvmmonitor.analysis.AnalysisContext;
import it.denzosoft.jvmmonitor.model.Diagnosis;
import it.denzosoft.jvmmonitor.model.SafepointEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SafepointPauseRule implements DiagnosticRule {

    public String getName() {
        return "Safepoint Pause";
    }

    public List<Diagnosis> evaluate(AnalysisContext ctx) {
        SafepointEvent sp = ctx.getLatestSafepoint();
        if (sp == null || !sp.isAvailable() || sp.getSafepointCount() == 0) {
            return Collections.emptyList();
        }

        double avgTimeMs = (double) sp.getTotalTimeMs() / sp.getSafepointCount();
        double avgSyncMs = (double) sp.getSyncTimeMs() / sp.getSafepointCount();

        List<Diagnosis> results = new ArrayList<Diagnosis>();
        AlarmThresholds t = ctx.getThresholds();

        if (avgSyncMs > t.safepointSyncWarnMs) {
            int severity = avgSyncMs > t.safepointSyncCritMs ? 2 : 1;
            results.add(Diagnosis.builder()
                .timestamp(System.currentTimeMillis())
                .category("Safepoint Sync Delay")
                .severity(severity)
                .summary(String.format(
                    "Avg safepoint sync time %.1fms across %d safepoints — threads slow to reach safepoint",
                    avgSyncMs, sp.getSafepointCount()))
                .evidence(String.format(
                    "Total safepoints: %d, total time: %dms, sync time: %dms, avg sync: %.1fms",
                    sp.getSafepointCount(), sp.getTotalTimeMs(), sp.getSyncTimeMs(), avgSyncMs))
                .fix("Check for long-running counted loops or JNI calls that delay safepoint")
                .suggestedAction("enable safepoint 1")
                .build());
        }

        if (avgTimeMs > t.safepointTotalWarnMs) {
            int severity = avgTimeMs > t.safepointTotalCritMs ? 2 : 1;
            results.add(Diagnosis.builder()
                .timestamp(System.currentTimeMillis())
                .category("High Safepoint Time")
                .severity(severity)
                .summary(String.format(
                    "Avg safepoint pause %.1fms — application experiencing frequent pauses",
                    avgTimeMs))
                .evidence(String.format(
                    "Total safepoints: %d, avg pause: %.1fms",
                    sp.getSafepointCount(), avgTimeMs))
                .build());
        }

        return results;
    }
}
