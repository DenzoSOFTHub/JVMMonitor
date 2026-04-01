package it.denzosoft.jvmmonitor.analysis.rules;

import it.denzosoft.jvmmonitor.analysis.AlarmThresholds;
import it.denzosoft.jvmmonitor.analysis.AnalysisContext;
import it.denzosoft.jvmmonitor.model.Diagnosis;
import it.denzosoft.jvmmonitor.model.InstrumentationEvent;
import java.util.*;

/**
 * Detects response time degradation by analyzing instrumentation events.
 * Compares recent response times against earlier baseline.
 * Also detects slow JDBC queries and connection pool exhaustion.
 */
public class ResponseDegradationRule implements DiagnosticRule {

    public String getName() {
        return "Response Time Degradation";
    }

    public List<Diagnosis> evaluate(AnalysisContext ctx) {
        List<Diagnosis> results = new ArrayList<Diagnosis>();
        long now = System.currentTimeMillis();

        /* Need at least 2 minutes of data to compare */
        List<InstrumentationEvent> recent = ctx.getStore()
                .getInstrumentationEvents(now - 60000, now);
        List<InstrumentationEvent> baseline = ctx.getStore()
                .getInstrumentationEvents(now - 300000, now - 60000);

        if (recent.size() < 5 || baseline.size() < 5) return results;
        AlarmThresholds t = ctx.getThresholds();

        /* ── 1. Overall response time degradation ────────── */
        /* Aggregate by method: compare recent avg vs baseline avg */
        Map<String, long[]> recentStats = aggregateByMethod(recent);
        Map<String, long[]> baselineStats = aggregateByMethod(baseline);

        for (Map.Entry<String, long[]> entry : recentStats.entrySet()) {
            String method = entry.getKey();
            long[] rStats = entry.getValue();  /* [totalNanos, count] */
            long[] bStats = baselineStats.get(method);
            if (bStats == null || bStats[1] < 3 || rStats[1] < 3) continue;

            double recentAvgMs = (rStats[0] / (double) rStats[1]) / 1000000.0;
            double baselineAvgMs = (bStats[0] / (double) bStats[1]) / 1000000.0;

            if (baselineAvgMs <= 0) continue;
            double ratio = recentAvgMs / baselineAvgMs;

            /* Report if response time degraded beyond threshold */
            if (ratio > t.responseTimeDegradationRatio && recentAvgMs > t.responseTimeMinMs) {
                int severity = ratio > 5.0 || recentAvgMs > 1000 ? 2 : 1;
                results.add(Diagnosis.builder()
                    .timestamp(now)
                    .category("Response Time Degradation")
                    .severity(severity)
                    .summary(String.format(
                        "%s: avg response time %.0fms (was %.0fms) — %.1fx slower. " +
                        "Based on %d recent vs %d baseline calls.",
                        method, recentAvgMs, baselineAvgMs, ratio,
                        rStats[1], bStats[1]))
                    .evidence(String.format(
                        "Recent: %.0fms avg (%d calls), Baseline: %.0fms avg (%d calls), Ratio: %.1fx",
                        recentAvgMs, rStats[1], baselineAvgMs, bStats[1], ratio))
                    .location(method)
                    .suggestedAction("Check if degradation correlates with GC pauses, CPU load, or lock contention. " +
                        "Use Request Tracer to see which sub-calls are slower.")
                    .build());

                if (results.size() >= 3) break;  /* limit to top 3 degraded methods */
            }
        }

        /* ── 2. Slow JDBC queries ────────────────────────── */
        int slowQueries = 0;
        double maxQueryMs = 0;
        String slowestQuery = "";
        for (int i = 0; i < recent.size(); i++) {
            InstrumentationEvent e = recent.get(i);
            if (e.getEventType() == InstrumentationEvent.TYPE_JDBC_QUERY) {
                double ms = e.getDurationNanos() / 1000000.0;
                if (ms > t.jdbcSlowQueryMs) {
                    slowQueries++;
                    if (ms > maxQueryMs) {
                        maxQueryMs = ms;
                        String q = e.getContext();
                        slowestQuery = q.length() > 80 ? q.substring(0, 80) + "..." : q;
                    }
                }
            }
        }
        if (slowQueries > 0) {
            int severity = slowQueries > 10 || maxQueryMs > 5000 ? 2 : 1;
            results.add(Diagnosis.builder()
                .timestamp(now)
                .category("Slow JDBC Queries")
                .severity(severity)
                .summary(String.format(
                    "%d queries > 1s in the last minute. Slowest: %.0fms. " +
                    "Query: %s",
                    slowQueries, maxQueryMs, slowestQuery))
                .evidence(String.format(
                    "Slow queries (>1s): %d, Max: %.0fms", slowQueries, maxQueryMs))
                .location("JDBC / Database")
                .suggestedAction("Check SQL Statistics in Instrumentation tab. " +
                    "Look for missing indexes, full table scans, or lock waits on DB side.")
                .build());
        }

        /* ── 3. JDBC connection pool exhaustion ──────────── */
        int slowConnects = 0;
        for (int i = 0; i < recent.size(); i++) {
            InstrumentationEvent e = recent.get(i);
            if (e.getEventType() == InstrumentationEvent.TYPE_JDBC_CONNECT
                && e.getDurationNanos() > (long)(t.jdbcSlowConnectMs * 1000000)) {
                slowConnects++;
            }
        }
        if (slowConnects > 0) {
            results.add(Diagnosis.builder()
                .timestamp(now)
                .category("JDBC Connection Pool Exhaustion")
                .severity(slowConnects > 5 ? 2 : 1)
                .summary(String.format(
                    "%d getConnection() calls took > 500ms in the last minute — " +
                    "connection pool likely exhausted. Threads are waiting for available connections.",
                    slowConnects))
                .evidence(String.format("Slow getConnection() calls: %d", slowConnects))
                .location("JDBC Connection Pool")
                .suggestedAction("Check Connection Monitor for leaked connections. " +
                    "Increase pool size or fix connection leaks (missing close() in finally blocks).")
                .build());
        }

        return results;
    }

    private static Map<String, long[]> aggregateByMethod(List<InstrumentationEvent> events) {
        Map<String, long[]> stats = new LinkedHashMap<String, long[]>();
        for (int i = 0; i < events.size(); i++) {
            InstrumentationEvent e = events.get(i);
            if (e.getDurationNanos() <= 0) continue;
            if (e.getDepth() > 0) continue;  /* only root-level calls */
            String key = e.getDisplayClassName() + "." + e.getMethodName();
            long[] s = stats.get(key);
            if (s == null) { s = new long[2]; stats.put(key, s); }
            s[0] += e.getDurationNanos();
            s[1]++;
        }
        return stats;
    }
}
