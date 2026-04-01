package it.denzosoft.jvmmonitor.analysis.rules;

import it.denzosoft.jvmmonitor.analysis.AlarmThresholds;
import it.denzosoft.jvmmonitor.analysis.AnalysisContext;
import it.denzosoft.jvmmonitor.model.Diagnosis;
import it.denzosoft.jvmmonitor.model.LockEvent;
import it.denzosoft.jvmmonitor.model.ThreadInfo;
import java.util.*;

/**
 * Detects thread problems:
 * - Lock contention (>20% threads BLOCKED with lock hotspot identification)
 * - Thread leak (thread count growing unbounded)
 * - Deadlock (DEADLOCK event type in lock events)
 */
public class ThreadContentionRule implements DiagnosticRule {

    public String getName() {
        return "Thread Analysis";
    }

    public List<Diagnosis> evaluate(AnalysisContext ctx) {
        List<Diagnosis> results = new ArrayList<Diagnosis>();
        AlarmThresholds t = ctx.getThresholds();
        List<ThreadInfo> threads = ctx.getLatestThreads();
        if (threads.isEmpty()) return results;

        double blockedPct = ctx.getBlockedThreadPercent();
        int blockedCount = ctx.getBlockedThreadCount();

        /* ── 1. Lock contention with hotspot identification ── */
        if (blockedPct >= t.blockedWarnPct && blockedCount >= t.blockedMinCount) {
            int severity = blockedPct > t.blockedCritPct ? 2 : 1;

            /* Identify which locks are contended */
            List<LockEvent> lockEvents = ctx.getRecentLockEvents(60);
            Map<String, int[]> lockHotspots = new LinkedHashMap<String, int[]>();
            for (int i = 0; i < lockEvents.size(); i++) {
                LockEvent e = lockEvents.get(i);
                if (e.getEventType() == LockEvent.CONTENDED_ENTER) {
                    String key = e.getLockDisplayName();
                    int[] cnt = lockHotspots.get(key);
                    if (cnt == null) { cnt = new int[]{0}; lockHotspots.put(key, cnt); }
                    cnt[0]++;
                }
            }

            StringBuilder evidence = new StringBuilder();
            evidence.append(String.format("%d/%d threads BLOCKED (%.0f%%).",
                    blockedCount, threads.size(), blockedPct));
            if (!lockHotspots.isEmpty()) {
                /* Sort by count */
                List<Map.Entry<String, int[]>> sorted =
                        new ArrayList<Map.Entry<String, int[]>>(lockHotspots.entrySet());
                Collections.sort(sorted, new Comparator<Map.Entry<String, int[]>>() {
                    public int compare(Map.Entry<String, int[]> a, Map.Entry<String, int[]> b) {
                        return b.getValue()[0] - a.getValue()[0];
                    }
                });
                evidence.append(" Contended locks:\n");
                int shown = Math.min(sorted.size(), 3);
                for (int i = 0; i < shown; i++) {
                    evidence.append(String.format("  %d x %s\n",
                            sorted.get(i).getValue()[0], sorted.get(i).getKey()));
                }
            }

            String topLock = !lockHotspots.isEmpty()
                    ? lockHotspots.keySet().iterator().next() : "unknown";

            results.add(Diagnosis.builder()
                .timestamp(System.currentTimeMillis())
                .category("Lock Contention")
                .severity(severity)
                .summary(String.format(
                    "%d/%d threads (%.0f%%) BLOCKED — lock contention bottleneck on %s",
                    blockedCount, threads.size(), blockedPct, topLock))
                .evidence(evidence.toString())
                .location(topLock)
                .suggestedAction("Check Locks tab for contention hotspots. " +
                    "Reduce lock scope, use read-write locks, or use lock-free data structures.")
                .build());
        }

        /* ── 2. Deadlock detection ───────────────────────── */
        List<LockEvent> lockEvents = ctx.getRecentLockEvents(60);
        for (int i = 0; i < lockEvents.size(); i++) {
            if (lockEvents.get(i).getEventType() == LockEvent.DEADLOCK) {
                results.add(Diagnosis.builder()
                    .timestamp(System.currentTimeMillis())
                    .category("Deadlock Detected")
                    .severity(2)
                    .summary("Deadlock detected! Threads are permanently blocked waiting for each other. " +
                        "Application is partially or fully hung.")
                    .evidence("DEADLOCK event received from agent")
                    .suggestedAction("Use Deadlock Detection in Tools tab to see the full chain. " +
                        "Fix lock ordering to prevent future deadlocks.")
                    .build());
                break;  /* one deadlock alarm is enough */
            }
        }

        /* ── 3. High thread count (potential thread leak) ── */
        int threadCount = threads.size();
        if (threadCount > t.threadCountWarn) {
            int severity = threadCount > t.threadCountCrit ? 2 : 1;
            results.add(Diagnosis.builder()
                .timestamp(System.currentTimeMillis())
                .category("High Thread Count")
                .severity(severity)
                .summary(String.format(
                    "%d threads — possible thread leak. Each thread uses ~1MB stack memory.",
                    threadCount))
                .evidence(String.format("Thread count: %d", threadCount))
                .suggestedAction("Check Threads tab for thread names growing with a counter pattern " +
                    "(e.g., pool-1-thread-999). Fix thread pool configuration or executor lifecycle.")
                .build());
        }

        return results;
    }
}
