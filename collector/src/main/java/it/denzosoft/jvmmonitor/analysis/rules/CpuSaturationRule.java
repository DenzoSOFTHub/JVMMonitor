package it.denzosoft.jvmmonitor.analysis.rules;

import it.denzosoft.jvmmonitor.analysis.AlarmThresholds;
import it.denzosoft.jvmmonitor.analysis.AnalysisContext;
import it.denzosoft.jvmmonitor.model.CpuUsageSnapshot;
import it.denzosoft.jvmmonitor.model.Diagnosis;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Detects CPU saturation (sustained high JVM CPU) and runaway threads.
 * CPU at 80%+ for 30s+ is always a problem — no GC context needed.
 */
public class CpuSaturationRule implements DiagnosticRule {

    public String getName() {
        return "CPU Saturation";
    }

    public List<Diagnosis> evaluate(AnalysisContext ctx) {
        List<Diagnosis> results = new ArrayList<Diagnosis>();
        AlarmThresholds t = ctx.getThresholds();

        /* ── 1. Sustained high JVM CPU ───────────────── */
        double avgCpu = ctx.getAvgJvmCpuPercent(30);
        if (avgCpu > t.cpuWarnPct) {
            int severity = avgCpu > t.cpuCritPct ? 2 : 1;

            /* Check if CPU is GC-induced */
            double throughput = ctx.getGcThroughputPercent(30);
            String cause = throughput < 90
                    ? String.format("GC is consuming %.0f%% of CPU time (throughput %.0f%%).", 100 - throughput, throughput)
                    : "Application code is consuming most CPU.";

            results.add(Diagnosis.builder()
                .timestamp(System.currentTimeMillis())
                .category("CPU Saturation")
                .severity(severity)
                .summary(String.format(
                    "JVM CPU at %.0f%% (30s avg). %s", avgCpu, cause))
                .evidence(String.format(
                    "Avg JVM CPU: %.0f%%, GC throughput: %.0f%%", avgCpu, throughput))
                .suggestedAction(throughput < 90
                    ? "CPU is GC-driven: fix memory pressure first (check heap/GC alarms)."
                    : "Start CPU profiler to identify hot methods. Check thread CPU table for runaway threads.")
                .build());
        }

        /* ── 2. Single runaway thread ────────────────── */
        CpuUsageSnapshot latest = ctx.getLatestCpuUsage();
        if (latest != null && latest.getTopThreads() != null) {
            CpuUsageSnapshot.ThreadCpuInfo[] threads = latest.getTopThreads();
            for (int i = 0; i < threads.length; i++) {
                if (threads[i].cpuPercent > t.runawayThreadCpuPct) {
                    results.add(Diagnosis.builder()
                        .timestamp(System.currentTimeMillis())
                        .category("Runaway Thread")
                        .severity(1)
                        .summary(String.format(
                            "Thread '%s' consuming %.0f%% CPU — possible infinite loop or heavy computation.",
                            threads[i].threadName, threads[i].cpuPercent))
                        .evidence(String.format(
                            "Thread ID: %d, CPU: %.0f%%, State: %s",
                            threads[i].threadId, threads[i].cpuPercent, threads[i].state))
                        .location("Thread: " + threads[i].threadName)
                        .suggestedAction("Check thread dump for this thread's stack trace. " +
                            "Start CPU profiler filtered on this thread name.")
                        .build());
                    break;  /* only report the worst one */
                }
            }
        }

        return results;
    }
}
