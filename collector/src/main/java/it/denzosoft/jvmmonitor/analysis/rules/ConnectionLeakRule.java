package it.denzosoft.jvmmonitor.analysis.rules;

import it.denzosoft.jvmmonitor.analysis.AlarmThresholds;
import it.denzosoft.jvmmonitor.analysis.AnalysisContext;
import it.denzosoft.jvmmonitor.model.Diagnosis;
import it.denzosoft.jvmmonitor.model.NetworkSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Detects connection leaks:
 * - TCP CLOSE_WAIT growing (socket not closed by application)
 * - High retransmit rate (network degradation)
 * - JDBC connection leaks (detected via instrumentation, signaled here)
 */
public class ConnectionLeakRule implements DiagnosticRule {

    public String getName() {
        return "Connection Leak Detection";
    }

    public List<Diagnosis> evaluate(AnalysisContext ctx) {
        List<Diagnosis> results = new ArrayList<Diagnosis>();
        AlarmThresholds t = ctx.getThresholds();

        NetworkSnapshot net = ctx.getLatestNetwork();
        if (net == null) return results;

        /* ── 1. CLOSE_WAIT growing = TCP connection leak ──── */
        int closeWait = net.getCloseWaitCount();
        if (closeWait > t.closeWaitWarn && ctx.isCloseWaitGrowing(120)) {
            int severity = closeWait > t.closeWaitCrit ? 2 : 1;
            results.add(Diagnosis.builder()
                .timestamp(System.currentTimeMillis())
                .category("TCP Connection Leak")
                .severity(severity)
                .summary(String.format(
                    "%d CLOSE_WAIT connections and growing — application is not closing sockets. " +
                    "Each leaked connection holds a file descriptor.",
                    closeWait))
                .evidence(String.format(
                    "CLOSE_WAIT: %d, Established: %d, TIME_WAIT: %d",
                    closeWait, net.getEstablishedCount(), net.getTimeWaitCount()))
                .location("Network / TCP connections")
                .suggestedAction("Enable Socket I/O probe to identify which threads open connections without closing. " +
                    "Check JDBC connection pool configuration (max-lifetime, leak-detection-threshold).")
                .build());
        }

        /* ── 2. High retransmit rate = network problems ───── */
        double retransPct = ctx.getRetransmitPercent();
        if (retransPct > t.retransmitWarnPct) {
            int severity = retransPct > t.retransmitCritPct ? 2 : 1;
            results.add(Diagnosis.builder()
                .timestamp(System.currentTimeMillis())
                .category("Network Degradation")
                .severity(severity)
                .summary(String.format(
                    "TCP retransmit rate %.1f%% — network is losing packets. " +
                    "This increases latency and reduces throughput.",
                    retransPct))
                .evidence(String.format(
                    "Retransmits: %d, Out segments: %d, Errors: %d",
                    net.getRetransSegments(), net.getOutSegments(), net.getInErrors()))
                .suggestedAction("Check network infrastructure. " +
                    "If only specific destinations are affected, check Network tab for per-connection details.")
                .build());
        }

        /* ── 3. Too many established connections ──────────── */
        int established = net.getEstablishedCount();
        if (established > t.establishedWarn) {
            results.add(Diagnosis.builder()
                .timestamp(System.currentTimeMillis())
                .category("High Connection Count")
                .severity(1)
                .summary(String.format(
                    "%d established TCP connections — possible connection pool misconfiguration " +
                    "or missing connection reuse.",
                    established))
                .evidence(String.format(
                    "Established: %d, Listen: %d, Total sockets: %d",
                    established, net.getListenCount(), net.getSocketCount()))
                .suggestedAction("Review connection pool sizes (JDBC, HTTP client). " +
                    "Check Integration tab for connections per external system.")
                .build());
        }

        return results;
    }
}
