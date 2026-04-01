package it.denzosoft.jvmmonitor.demo;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.analysis.AnalysisContext;
import it.denzosoft.jvmmonitor.analysis.DiagnosisEngine;
import it.denzosoft.jvmmonitor.model.*;
import it.denzosoft.jvmmonitor.storage.EventStore;

import java.io.IOException;
import java.util.List;

/**
 * Runs a full demo session: starts a DemoAgent in-process, connects
 * the collector, and prints a live console dashboard that mirrors
 * what the Swing GUI panels would display.
 *
 * Usage:
 *   java -cp jvmmonitor.jar it.denzosoft.jvmmonitor.demo.DemoSession [durationSec] [refreshSec]
 *
 * Defaults: 180 s duration, 5 s refresh.
 */
public class DemoSession {

    private static final String HRULE =
        "================================================================================";
    private static final String THIN =
        "--------------------------------------------------------------------------------";
    private static final int WIDTH = 80;

    private final int durationSec;
    private final int refreshSec;
    private final int port = 19999;

    public DemoSession(int durationSec, int refreshSec) {
        this.durationSec = durationSec;
        this.refreshSec = refreshSec;
    }

    public void run() throws Exception {
        /* 1. Start demo agent in background thread */
        final DemoAgent agent = new DemoAgent(port);
        Thread agentThread = new Thread(new Runnable() {
            public void run() {
                try { agent.run(); } catch (IOException e) { /* stopped */ }
            }
        }, "demo-agent");
        agentThread.setDaemon(true);
        agentThread.start();
        Thread.sleep(1000);

        /* 2. Connect collector */
        JVMMonitorCollector collector = new JVMMonitorCollector();
        collector.connect("127.0.0.1", port);
        Thread.sleep(1500); /* handshake + first events */

        AnalysisContext ctx = collector.getAnalysisContext();
        DiagnosisEngine engine = collector.getDiagnosisEngine();
        EventStore store = collector.getStore();

        System.out.println(HRULE);
        System.out.println(center("JVMMonitor - Live Demo Dashboard"));
        System.out.println(center("Agent PID " + collector.getConnection().getAgentPid() +
                " @ " + collector.getConnection().getAgentHostname() +
                " (" + collector.getConnection().getJvmInfo() + ")"));
        System.out.println(center("Duration: " + durationSec + "s | Refresh: " + refreshSec + "s"));
        System.out.println(HRULE);
        System.out.println();

        /* 3. Dashboard loop */
        long startTime = System.currentTimeMillis();
        long endTime = startTime + durationSec * 1000L;
        int tick = 0;

        while (System.currentTimeMillis() < endTime &&
               collector.getConnection() != null &&
               collector.getConnection().isConnected()) {

            tick++;
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            long remaining = durationSec - elapsed;

            printDashboard(tick, elapsed, remaining, store, ctx, engine);

            if (System.currentTimeMillis() < endTime) {
                Thread.sleep(refreshSec * 1000L);
            }
        }

        /* 4. Final summary */
        System.out.println();
        System.out.println(HRULE);
        System.out.println(center("SESSION ENDED"));
        System.out.println(HRULE);
        printFinalSummary(store, ctx, engine);

        collector.disconnect();
    }

    private void printDashboard(int tick, long elapsed, long remaining,
                                 EventStore store, AnalysisContext ctx,
                                 DiagnosisEngine engine) {

        System.out.println(HRULE);
        System.out.println(String.format("  TICK #%-4d                    Elapsed: %3ds / %ds remaining: %ds",
                tick, elapsed, durationSec, remaining));
        System.out.println(HRULE);

        /* ── Connection & Events ──────────────────────────────── */
        System.out.println();
        section("EVENT COUNTERS");
        System.out.println(String.format("  CPU Samples: %-10d  GC Events: %-10d  Memory Snaps: %d",
                store.getCpuSampleCount(), store.getGcEventCount(), store.getMemorySnapshotCount()));
        System.out.println(String.format("  Exceptions:  %-10d  JIT Events: %-10d  Alarms: %d",
                store.getExceptionCount(), store.getJitEventCount(), store.getActiveAlarms().size()));

        /* ── Heap Memory ──────────────────────────────────────── */
        System.out.println();
        section("HEAP MEMORY");
        MemorySnapshot mem = store.getLatestMemorySnapshot();
        if (mem != null) {
            double pct = mem.getHeapUsagePercent();
            int barLen = 40;
            int filled = (int)(pct / 100 * barLen);
            StringBuilder bar = new StringBuilder("[");
            for (int i = 0; i < barLen; i++) {
                if (i < filled) bar.append(pct > 85 ? '!' : '#');
                else bar.append('.');
            }
            bar.append("]");

            System.out.println(String.format("  Heap:     %s %s / %s (%.1f%%)",
                    bar, mem.getHeapUsedMB(), mem.getHeapMaxMB(), pct));
            System.out.println(String.format("  Non-Heap: %.1f MB / %.1f MB",
                    mem.getNonHeapUsed() / (1024.0 * 1024.0),
                    mem.getNonHeapMax() / (1024.0 * 1024.0)));

            double growth = ctx.getHeapGrowthRateMBPerHour(5);
            System.out.println(String.format("  Growth:   %.1f MB/h (5-min trend)", growth));
        } else {
            System.out.println("  (no data)");
        }

        /* ── GC ───────────────────────────────────────────────── */
        System.out.println();
        section("GARBAGE COLLECTION (last 60s)");
        System.out.println(String.format("  Frequency:  %-12s Throughput: %.1f%%",
                String.format("%.0f GC/min", ctx.getGcFrequencyPerMinute(60)),
                ctx.getGcThroughputPercent(60)));
        System.out.println(String.format("  Avg Pause:  %-12s Max Pause:  %.1f ms",
                String.format("%.1f ms", ctx.getAvgGcPauseMs(60)),
                ctx.getMaxGcPauseMs(60)));

        /* GC Detail */
        GcDetail gcDetail = store.getLatestGcDetail();
        if (gcDetail != null) {
            GcDetail.CollectorInfo[] collectors = gcDetail.getCollectors();
            for (int i = 0; i < collectors.length; i++) {
                GcDetail.CollectorInfo c = collectors[i];
                System.out.println(String.format("  [%s] collections=%d time=%dms",
                        c.getName(), c.getCollectionCount(), c.getCollectionTimeMs()));
            }
        }

        /* ── Threads ──────────────────────────────────────────── */
        System.out.println();
        section("THREADS");
        List<ThreadInfo> threads = store.getLatestThreadInfo();
        if (!threads.isEmpty()) {
            int runnable = 0, blocked = 0, waiting = 0, timedWait = 0;
            for (int i = 0; i < threads.size(); i++) {
                switch (threads.get(i).getState()) {
                    case ThreadInfo.STATE_RUNNABLE: runnable++; break;
                    case ThreadInfo.STATE_BLOCKED: blocked++; break;
                    case ThreadInfo.STATE_WAITING: waiting++; break;
                    case ThreadInfo.STATE_TIMED_WAITING: timedWait++; break;
                }
            }
            System.out.println(String.format("  Total: %d  |  RUNNABLE: %d  BLOCKED: %d  WAITING: %d  TIMED_WAITING: %d",
                    threads.size(), runnable, blocked, waiting, timedWait));

            /* Show blocked threads */
            if (blocked > 0) {
                System.out.print("  Blocked: ");
                boolean first = true;
                for (int i = 0; i < threads.size(); i++) {
                    if (threads.get(i).getState() == ThreadInfo.STATE_BLOCKED) {
                        if (!first) System.out.print(", ");
                        System.out.print(threads.get(i).getName());
                        first = false;
                    }
                }
                System.out.println();
            }
        }

        /* ── Exceptions ───────────────────────────────────────── */
        System.out.println();
        section("EXCEPTIONS (last 60s)");
        double excRate = ctx.getExceptionRatePerMinute(60);
        ExceptionEvent latestExc = store.getLatestException();
        if (latestExc != null) {
            System.out.println(String.format("  Rate: %.0f/min  |  Total thrown: %d  Caught: %d  Dropped: %d",
                    excRate, latestExc.getTotalThrown(), latestExc.getTotalCaught(),
                    latestExc.getTotalDropped()));
            /* Show last 5 */
            long now = System.currentTimeMillis();
            List<ExceptionEvent> recent = store.getExceptions(now - 60000, now);
            int show = Math.min(recent.size(), 5);
            for (int i = recent.size() - show; i < recent.size(); i++) {
                ExceptionEvent e = recent.get(i);
                System.out.println(String.format("    %-35s at %s.%s  %s",
                        trunc(e.getDisplayName(), 35),
                        trunc(e.getThrowClass(), 20), e.getThrowMethod(),
                        e.isCaught() ? "[caught]" : "[UNCAUGHT]"));
            }
        } else {
            System.out.println("  (no data)");
        }

        /* ── OS Metrics ───────────────────────────────────────── */
        System.out.println();
        section("OS METRICS");
        OsMetrics os = store.getLatestOsMetrics();
        if (os != null) {
            System.out.println(String.format("  RSS: %.0f MB  |  VM: %.0f MB  |  FDs: %d  |  Threads: %d",
                    os.getRssMB(), os.getVmSizeMB(), os.getOpenFileDescriptors(), os.getOsThreadCount()));
            System.out.println(String.format("  TCP: %d established, %d close_wait, %d time_wait  |  CtxSw: %d vol, %d invol",
                    os.getTcpEstablished(), os.getTcpCloseWait(), os.getTcpTimeWait(),
                    os.getVoluntaryContextSwitches(), os.getInvoluntaryContextSwitches()));
        } else {
            System.out.println("  (no data)");
        }

        /* ── JIT ──────────────────────────────────────────────── */
        System.out.println();
        section("JIT COMPILER");
        long now = System.currentTimeMillis();
        List<JitEvent> jitEvents = store.getJitEvents(now - 60000, now);
        if (!jitEvents.isEmpty()) {
            int compiled = 0, unloaded = 0;
            for (int i = 0; i < jitEvents.size(); i++) {
                if (jitEvents.get(i).getEventType() == JitEvent.COMPILED) compiled++;
                else unloaded++;
            }
            JitEvent last = jitEvents.get(jitEvents.size() - 1);
            System.out.println(String.format("  Compiled: %d (total: %d)  |  Deoptimized: %d",
                    compiled, last.getTotalCompiled(), unloaded));
            /* Last 3 compilations */
            int shown = 0;
            for (int i = jitEvents.size() - 1; i >= 0 && shown < 3; i--) {
                JitEvent e = jitEvents.get(i);
                if (e.getEventType() == JitEvent.COMPILED && !e.getClassName().isEmpty()) {
                    System.out.println(String.format("    -> %s.%s (%d bytes)",
                            e.getClassName(), e.getMethodName(), e.getCodeSize()));
                    shown++;
                }
            }
        } else {
            System.out.println("  (no data)");
        }

        /* ── Safepoints ───────────────────────────────────────── */
        System.out.println();
        section("SAFEPOINTS");
        SafepointEvent sp = store.getLatestSafepoint();
        if (sp != null && sp.isAvailable()) {
            double avgTime = sp.getSafepointCount() > 0
                    ? (double) sp.getTotalTimeMs() / sp.getSafepointCount() : 0;
            double avgSync = sp.getSafepointCount() > 0
                    ? (double) sp.getSyncTimeMs() / sp.getSafepointCount() : 0;
            System.out.println(String.format("  Count: %d  |  Total: %dms  |  Sync: %dms  |  Avg: %.1fms  AvgSync: %.1fms",
                    sp.getSafepointCount(), sp.getTotalTimeMs(), sp.getSyncTimeMs(),
                    avgTime, avgSync));
        } else {
            System.out.println("  (no data)");
        }

        /* ── Classloaders ─────────────────────────────────────── */
        ClassloaderStats clStats = store.getLatestClassloaderStats();
        if (clStats != null) {
            System.out.println();
            section("CLASSLOADERS");
            ClassloaderStats.LoaderInfo[] loaders = clStats.getLoaders();
            for (int i = 0; i < loaders.length; i++) {
                System.out.println(String.format("  %-55s %d classes",
                        trunc(loaders[i].getLoaderClass(), 55),
                        loaders[i].getClassCount()));
            }
            System.out.println(String.format("  Total: %d loaders, %d classes",
                    clStats.getLoaderCount(), clStats.getTotalClassCount()));
        }

        /* ── Native Memory ────────────────────────────────────── */
        NativeMemoryStats nms = store.getLatestNativeMemory();
        if (nms != null && nms.isAvailable()) {
            System.out.println();
            section("NATIVE MEMORY (NMT)");
            /* Print first 8 lines of NMT output */
            String[] lines = nms.getRawOutput().split("\n");
            int show = Math.min(lines.length, 8);
            for (int i = 0; i < show; i++) {
                System.out.println("  " + lines[i]);
            }
        }

        /* ── Alarms ───────────────────────────────────────────── */
        List<AlarmEvent> alarms = store.getActiveAlarms();
        if (!alarms.isEmpty()) {
            System.out.println();
            section("ACTIVE ALARMS");
            for (int i = 0; i < alarms.size(); i++) {
                AlarmEvent a = alarms.get(i);
                System.out.println(String.format("  [%s] %s: %s (value=%.1f threshold=%.1f)",
                        AlarmEvent.severityToString(a.getSeverity()),
                        a.getAlarmTypeName(), a.getMessage(),
                        a.getValue(), a.getThreshold()));
            }
        }

        /* ── Diagnostics ──────────────────────────────────────── */
        List<Diagnosis> diagnoses = engine.runDiagnostics();
        if (!diagnoses.isEmpty()) {
            System.out.println();
            section("DIAGNOSTICS");
            for (int i = 0; i < diagnoses.size(); i++) {
                Diagnosis d = diagnoses.get(i);
                String sev = d.getSeverity() == 2 ? "CRITICAL" : d.getSeverity() == 1 ? "WARNING" : "INFO";
                System.out.println(String.format("  [%s] %s", sev, d.getCategory()));
                System.out.println("    " + d.getSummary());
                if (d.getSuggestedAction() != null) {
                    System.out.println("    -> " + d.getSuggestedAction());
                }
            }
        }

        System.out.println();
    }

    private void printFinalSummary(EventStore store, AnalysisContext ctx, DiagnosisEngine engine) {
        System.out.println();
        section("FINAL STATISTICS");
        System.out.println(String.format("  GC Events:         %d", store.getGcEventCount()));
        System.out.println(String.format("  Memory Snapshots:  %d", store.getMemorySnapshotCount()));
        System.out.println(String.format("  Threads tracked:   %d", store.getLatestThreadInfo().size()));
        System.out.println(String.format("  Exceptions:        %d", store.getExceptionCount()));
        System.out.println(String.format("  JIT Events:        %d", store.getJitEventCount()));
        System.out.println(String.format("  Alarms fired:      %d", store.getActiveAlarms().size()));

        System.out.println();
        section("PEAK VALUES");
        MemorySnapshot mem = store.getLatestMemorySnapshot();
        if (mem != null) {
            System.out.println(String.format("  Final Heap:  %s / %s (%.1f%%)",
                    mem.getHeapUsedMB(), mem.getHeapMaxMB(), mem.getHeapUsagePercent()));
        }
        System.out.println(String.format("  Max GC Pause:  %.1f ms", ctx.getMaxGcPauseMs(300)));
        System.out.println(String.format("  GC Throughput: %.1f%%", ctx.getGcThroughputPercent(300)));

        OsMetrics os = store.getLatestOsMetrics();
        if (os != null) {
            System.out.println(String.format("  Final RSS:     %.0f MB", os.getRssMB()));
        }

        List<Diagnosis> diagnoses = engine.runDiagnostics();
        if (!diagnoses.isEmpty()) {
            System.out.println();
            section("FINAL DIAGNOSIS");
            for (int i = 0; i < diagnoses.size(); i++) {
                Diagnosis d = diagnoses.get(i);
                String sev = d.getSeverity() == 2 ? "CRITICAL" : d.getSeverity() == 1 ? "WARNING" : "INFO";
                System.out.println(String.format("  [%s] %s: %s", sev, d.getCategory(), d.getSummary()));
            }
        }

        System.out.println();
        System.out.println(HRULE);
    }

    /* ── Helpers ──────────────────────────────────────────── */

    private static void section(String title) {
        System.out.println("  --- " + title + " " + repeat('-', WIDTH - 7 - title.length()));
    }

    private static String center(String s) {
        int pad = (WIDTH - s.length()) / 2;
        if (pad <= 0) return s;
        return repeat(' ', pad) + s;
    }

    private static String repeat(char c, int n) {
        if (n <= 0) return "";
        char[] chars = new char[n];
        java.util.Arrays.fill(chars, c);
        return new String(chars);
    }

    private static String trunc(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    /* ── Main ────────────────────────────────────────────── */

    public static void main(String[] args) throws Exception {
        int duration = 180;
        int refresh = 5;
        if (args.length > 0) {
            try { duration = Integer.parseInt(args[0]); } catch (NumberFormatException e) { /* default */ }
        }
        if (args.length > 1) {
            try { refresh = Integer.parseInt(args[1]); } catch (NumberFormatException e) { /* default */ }
        }
        new DemoSession(duration, refresh).run();
    }
}
