package it.denzosoft.jvmmonitor.analysis.rules;

import it.denzosoft.jvmmonitor.analysis.AlarmThresholds;
import it.denzosoft.jvmmonitor.analysis.AnalysisContext;
import it.denzosoft.jvmmonitor.model.Diagnosis;
import it.denzosoft.jvmmonitor.model.ExceptionEvent;
import java.util.*;

/**
 * Detects exception-related problems:
 * - Exception rate spike (sudden increase vs baseline)
 * - Uncaught exceptions (thread death — always critical)
 * - Recurring bug (same exception from same location > 10x/min)
 * - High overall exception rate with hotspot identification
 */
public class ExceptionRateRule implements DiagnosticRule {

    public String getName() {
        return "Exception Analysis";
    }

    public List<Diagnosis> evaluate(AnalysisContext ctx) {
        List<Diagnosis> results = new ArrayList<Diagnosis>();
        AlarmThresholds t = ctx.getThresholds();
        long now = System.currentTimeMillis();

        List<ExceptionEvent> recent = ctx.getRecentExceptions(60);
        List<ExceptionEvent> allRecent = ctx.getRecentExceptions(300);

        /* ── 1. Exception rate spike (3x vs baseline) ────── */
        double recentRate = recent.size();  /* per minute */
        int baselineCount = allRecent.size() - recent.size();
        double baselineRate = baselineCount > 0 ? baselineCount / 4.0 : 0;
        double safeBaselineRate = baselineRate > 0.001 ? baselineRate : 0.001;
        if (recentRate > t.exceptionMinRate && baselineRate > 0
                && recentRate > baselineRate * t.exceptionSpikeMultiplier) {
            double ratio = recentRate / safeBaselineRate;
            results.add(Diagnosis.builder()
                .timestamp(now)
                .category("Exception Storm")
                .severity(recentRate > baselineRate * 10 ? 2 : 1)
                .summary(String.format(
                    "Exception rate spike: %.0f/min (was %.0f/min baseline) — %.1fx increase. " +
                    "Possible failure cascade or new bug introduced.",
                    recentRate, baselineRate, ratio))
                .evidence(String.format(
                    "Last minute: %.0f exceptions, Baseline: %.0f/min, Ratio: %.1fx",
                    recentRate, baselineRate, ratio))
                .suggestedAction("Check Exceptions tab for the new exception types causing the spike.")
                .build());
        }

        /* ── 2. Uncaught exceptions (always critical) ────── */
        int uncaught = 0;
        String lastUncaughtClass = "";
        String lastUncaughtLocation = "";
        for (int i = 0; i < recent.size(); i++) {
            ExceptionEvent e = recent.get(i);
            if (!e.isCaught()) {
                uncaught++;
                lastUncaughtClass = e.getDisplayName();
                lastUncaughtLocation = e.getThrowClass() + "." + e.getThrowMethod();
            }
        }
        if (uncaught > 0) {
            results.add(Diagnosis.builder()
                .timestamp(now)
                .category("Uncaught Exceptions")
                .severity(2)
                .summary(String.format(
                    "%d uncaught exception(s) in the last minute — threads are dying. " +
                    "Last: %s at %s", uncaught, lastUncaughtClass, lastUncaughtLocation))
                .evidence(String.format("Uncaught: %d", uncaught))
                .location(lastUncaughtLocation)
                .suggestedAction("Uncaught exceptions kill threads. Add Thread.UncaughtExceptionHandler " +
                    "and fix the root cause. Check stack trace in Exceptions tab.")
                .build());
        }

        /* ── 3. Recurring bug (same location > 10x in 1 min) ── */
        Map<String, int[]> locationCount = new LinkedHashMap<String, int[]>();
        Map<String, String> locationClass = new LinkedHashMap<String, String>();
        for (int i = 0; i < recent.size(); i++) {
            ExceptionEvent e = recent.get(i);
            String loc = e.getThrowClass() + "." + e.getThrowMethod();
            int[] cnt = locationCount.get(loc);
            if (cnt == null) { cnt = new int[]{0}; locationCount.put(loc, cnt); }
            cnt[0]++;
            locationClass.put(loc, e.getDisplayName());
        }
        int bugCount = 0;
        for (Map.Entry<String, int[]> entry : locationCount.entrySet()) {
            if (entry.getValue()[0] >= t.recurringBugMinCount) {
                String excName = locationClass.get(entry.getKey());
                results.add(Diagnosis.builder()
                    .timestamp(now)
                    .category("Recurring Bug")
                    .severity(1)
                    .summary(String.format(
                        "%s thrown %d times from %s — likely a bug, not a transient error.",
                        excName, entry.getValue()[0], entry.getKey()))
                    .evidence(String.format("%d occurrences from same location in 60s",
                        entry.getValue()[0]))
                    .location(entry.getKey())
                    .suggestedAction("Fix the root cause at this code location.")
                    .build());
                bugCount++;
                if (bugCount >= 3) break;  /* top 3 recurring bugs */
            }
        }

        /* ── 4. High overall rate with hotspot ───────────── */
        if (results.isEmpty() && recentRate >= t.exceptionHighRate) {
            /* Sort locations by count */
            List<Map.Entry<String, int[]>> sorted =
                    new ArrayList<Map.Entry<String, int[]>>(locationCount.entrySet());
            Collections.sort(sorted, new Comparator<Map.Entry<String, int[]>>() {
                public int compare(Map.Entry<String, int[]> a, Map.Entry<String, int[]> b) {
                    return b.getValue()[0] - a.getValue()[0];
                }
            });

            StringBuilder evidence = new StringBuilder();
            evidence.append(String.format("%.0f exceptions/min. Top locations:\n", recentRate));
            int shown = Math.min(sorted.size(), 5);
            for (int i = 0; i < shown; i++) {
                Map.Entry<String, int[]> entry = sorted.get(i);
                evidence.append(String.format("  %d x %s at %s\n",
                        entry.getValue()[0], locationClass.get(entry.getKey()), entry.getKey()));
            }

            String topLoc = sorted.isEmpty() ? "unknown" : sorted.get(0).getKey();
            int severity = recentRate > 1000 ? 2 : 1;
            results.add(Diagnosis.builder()
                .timestamp(now)
                .category("High Exception Rate")
                .severity(severity)
                .location(topLoc)
                .summary(String.format("%.0f exceptions/min. Top hotspot: %s (%d/min)",
                    recentRate, topLoc, sorted.isEmpty() ? 0 : sorted.get(0).getValue()[0]))
                .evidence(evidence.toString())
                .suggestedAction("Review exception hotspots in Exceptions tab.")
                .build());
        }

        return results;
    }
}
