package it.denzosoft.jvmmonitor.analysis.rules;

import it.denzosoft.jvmmonitor.analysis.AnalysisContext;
import it.denzosoft.jvmmonitor.model.Diagnosis;
import it.denzosoft.jvmmonitor.model.JvmConfig;
import it.denzosoft.jvmmonitor.model.MemorySnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Static JVM flag analysis based on startup -XX: / -X arguments
 * obtained from RuntimeMXBean.getInputArguments() (always available via JMX).
 *
 * Detects well-known misconfigurations:
 *   - No explicit -Xmx / -Xms (container OOM risk)
 *   - -Xmx and -Xms not equal (heap resize churn)
 *   - -Xmx > 32GB with no -XX:-UseCompressedOops warning (lost compressed oops)
 *   - Missing -XX:+HeapDumpOnOutOfMemoryError (no post-mortem)
 *   - No -XX:HeapDumpPath configured
 *   - Outdated collectors: -XX:+UseConcMarkSweepGC (removed in Java 14+)
 *   - Old serial / parallel GC on large heap (>4GB)
 *   - -XX:MaxPermSize on Java 8+ (ignored, indicates stale scripts)
 *   - Missing -XX:MaxMetaspaceSize (unbounded metaspace growth)
 *   - Explicit GC not disabled (System.gc() honored)
 *   - -verbose:gc without GC log rotation
 *   - Debug agent left on (-agentlib:jdwp) in production-like heap sizes
 */
public class JvmFlagsRule implements DiagnosticRule {

    public String getName() {
        return "JVM Flags";
    }

    public List<Diagnosis> evaluate(AnalysisContext ctx) {
        List<Diagnosis> results = new ArrayList<Diagnosis>();

        JvmConfig cfg = ctx.getJvmConfig();
        if (cfg == null) return results;

        String[] args = cfg.getVmArguments();
        if (args == null || args.length == 0) return results;

        String javaVersion = cfg.getJavaVersion() != null ? cfg.getJavaVersion() : "";
        int major = parseJavaMajor(javaVersion);

        long xmx = -1;
        long xms = -1;
        boolean hasHeapDumpOnOom = false;
        boolean hasHeapDumpPath = false;
        boolean hasMaxMetaspace = false;
        boolean hasMaxPermSize = false;
        boolean hasCms = false;
        boolean hasG1 = false;
        boolean hasZgc = false;
        boolean hasShenandoah = false;
        boolean hasParallelGc = false;
        boolean hasSerialGc = false;
        boolean hasDisableExplicitGc = false;
        boolean hasVerboseGc = false;
        boolean hasGcLogFile = false;
        boolean hasJdwp = false;
        boolean hasCompressedOopsOff = false;
        boolean hasExitOnOom = false;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a == null) continue;
            if (a.startsWith("-Xmx")) xmx = parseSize(a.substring(4));
            else if (a.startsWith("-Xms")) xms = parseSize(a.substring(4));
            else if (a.contains("+HeapDumpOnOutOfMemoryError")) hasHeapDumpOnOom = true;
            else if (a.startsWith("-XX:HeapDumpPath=")) hasHeapDumpPath = true;
            else if (a.startsWith("-XX:MaxMetaspaceSize=")) hasMaxMetaspace = true;
            else if (a.startsWith("-XX:MaxPermSize=")) hasMaxPermSize = true;
            else if (a.contains("+UseConcMarkSweepGC")) hasCms = true;
            else if (a.contains("+UseG1GC")) hasG1 = true;
            else if (a.contains("+UseZGC")) hasZgc = true;
            else if (a.contains("+UseShenandoahGC")) hasShenandoah = true;
            else if (a.contains("+UseParallelGC") || a.contains("+UseParallelOldGC")) hasParallelGc = true;
            else if (a.contains("+UseSerialGC")) hasSerialGc = true;
            else if (a.contains("+DisableExplicitGC")) hasDisableExplicitGc = true;
            else if (a.equals("-verbose:gc") || a.equals("-verbosegc")) hasVerboseGc = true;
            else if (a.startsWith("-Xlog:gc") || a.startsWith("-Xloggc:")) hasGcLogFile = true;
            else if (a.startsWith("-agentlib:jdwp") || a.startsWith("-Xrunjdwp")) hasJdwp = true;
            else if (a.contains("-UseCompressedOops")) hasCompressedOopsOff = true;
            else if (a.contains("+ExitOnOutOfMemoryError")) hasExitOnOom = true;
        }

        /* ── 1. No -Xmx specified ── */
        if (xmx <= 0) {
            results.add(warn("No -Xmx specified — JVM will use platform default "
                    + "(typically 25% of RAM), risky under container limits",
                    "Set -Xmx explicitly and size -Xms equal to -Xmx"));
        }

        /* ── 2. -Xmx != -Xms ── */
        if (xmx > 0 && xms > 0 && xmx != xms) {
            results.add(info(String.format(
                    "-Xms (%s) differs from -Xmx (%s) — heap resize churn at startup",
                    fmtBytes(xms), fmtBytes(xmx)),
                    "Set -Xms equal to -Xmx to avoid runtime heap resizing"));
        }

        /* ── 3. Xmx > 32GB with no explicit compressed oops ── */
        if (xmx > 32L * 1024 * 1024 * 1024) {
            results.add(warn(String.format(
                    "-Xmx %s exceeds 32GB — compressed oops disabled, "
                            + "object headers grow from 12 to 16 bytes",
                    fmtBytes(xmx)),
                    "Either cap -Xmx just under 32GB or accept the overhead"));
        }
        if (hasCompressedOopsOff && xmx > 0 && xmx < 32L * 1024 * 1024 * 1024) {
            results.add(warn("-XX:-UseCompressedOops with heap < 32GB "
                    + "— wastes memory for no reason",
                    "Remove -XX:-UseCompressedOops"));
        }

        /* ── 4. Missing -XX:+HeapDumpOnOutOfMemoryError ── */
        if (!hasHeapDumpOnOom) {
            results.add(warn("Missing -XX:+HeapDumpOnOutOfMemoryError — "
                    + "no post-mortem dump will be produced on OOM",
                    "Add -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/log/jvm"));
        } else if (!hasHeapDumpPath) {
            results.add(info("HeapDumpOnOutOfMemoryError enabled but no -XX:HeapDumpPath set "
                    + "— dump will go to the current working directory",
                    "Add -XX:HeapDumpPath=/path/to/dumps"));
        }

        /* ── 5. Missing -XX:MaxMetaspaceSize (Java 8+) ── */
        if (major >= 8 && !hasMaxMetaspace) {
            results.add(warn("Missing -XX:MaxMetaspaceSize — metaspace can grow unbounded, "
                    + "masking classloader leaks until native OOM",
                    "Add -XX:MaxMetaspaceSize=256m (or appropriate cap)"));
        }

        /* ── 6. Stale -XX:MaxPermSize on Java 8+ ── */
        if (major >= 8 && hasMaxPermSize) {
            results.add(info("-XX:MaxPermSize is ignored on Java 8+ (PermGen was removed). "
                    + "Startup script is stale.",
                    "Replace -XX:MaxPermSize with -XX:MaxMetaspaceSize"));
        }

        /* ── 7. Deprecated CMS collector ── */
        if (hasCms) {
            if (major >= 14) {
                results.add(crit("-XX:+UseConcMarkSweepGC was removed in Java 14 — "
                        + "flag ignored, default G1 is in effect",
                        "Remove the flag and explicitly set -XX:+UseG1GC"));
            } else if (major >= 9) {
                results.add(warn("CMS is deprecated since Java 9 and removed in Java 14",
                        "Migrate to -XX:+UseG1GC"));
            }
        }

        /* ── 8. SerialGC / ParallelGC on large heaps ── */
        if ((hasSerialGc || hasParallelGc) && xmx > 4L * 1024 * 1024 * 1024) {
            results.add(warn(String.format(
                    "Serial/Parallel GC selected with -Xmx %s — long STW pauses expected on large heaps",
                    fmtBytes(xmx)),
                    "Use -XX:+UseG1GC (or ZGC/Shenandoah on Java 15+) for heaps > 4GB"));
        }

        /* ── 9. Explicit GC not disabled ── */
        if (!hasDisableExplicitGc && !hasG1 && !hasZgc && !hasShenandoah) {
            results.add(info("Explicit System.gc() is honored (no -XX:+DisableExplicitGC). "
                    + "RMI DGC or misbehaving libraries can trigger Full GCs.",
                    "Add -XX:+DisableExplicitGC"));
        }

        /* ── 10. verbose:gc without log rotation ── */
        if (hasVerboseGc && !hasGcLogFile) {
            results.add(info("verbose:gc enabled but no -Xlog:gc file rotation configured — "
                    + "GC log goes to stdout",
                    "Use -Xlog:gc*:file=gc.log:tags:filecount=5,filesize=100M (Java 9+)"));
        }

        /* ── 11. JDWP debug agent attached ── */
        if (hasJdwp) {
            results.add(warn("-agentlib:jdwp debug agent attached — disables several JIT "
                    + "optimizations and exposes a debug port",
                    "Remove jdwp in production"));
        }

        /* ── 12. OOM without ExitOnOutOfMemoryError ── */
        if (!hasExitOnOom) {
            results.add(info("Missing -XX:+ExitOnOutOfMemoryError — the JVM may limp along "
                    + "in a degraded state after OOM instead of being restarted",
                    "Add -XX:+ExitOnOutOfMemoryError (and rely on supervisor to restart)"));
        }

        /* ── 13. Heap way smaller than what the process seems to need ── */
        MemorySnapshot mem = ctx.getLatestMemory();
        if (mem != null && xmx > 0 && mem.getHeapUsed() > xmx * 0.9) {
            results.add(crit(String.format(
                    "Heap used %.1f%% of -Xmx (%s) — configured heap is too small",
                    mem.getHeapUsed() * 100.0 / xmx, fmtBytes(xmx)),
                    "Increase -Xmx or profile retention"));
        }

        return results;
    }

    private static Diagnosis warn(String summary, String fix) {
        return Diagnosis.builder()
                .timestamp(System.currentTimeMillis())
                .category("JVM Flags")
                .severity(1)
                .summary(summary)
                .fix(fix)
                .build();
    }

    private static Diagnosis crit(String summary, String fix) {
        return Diagnosis.builder()
                .timestamp(System.currentTimeMillis())
                .category("JVM Flags")
                .severity(2)
                .summary(summary)
                .fix(fix)
                .build();
    }

    private static Diagnosis info(String summary, String fix) {
        return Diagnosis.builder()
                .timestamp(System.currentTimeMillis())
                .category("JVM Flags")
                .severity(0)
                .summary(summary)
                .fix(fix)
                .build();
    }

    /** Parse -Xmx/-Xms size argument (e.g. "4g", "512m", "2048k", "1073741824"). */
    static long parseSize(String s) {
        if (s == null || s.length() == 0) return -1;
        s = s.trim().toLowerCase();
        long mult = 1;
        char last = s.charAt(s.length() - 1);
        if (last == 'k') { mult = 1024L; s = s.substring(0, s.length() - 1); }
        else if (last == 'm') { mult = 1024L * 1024; s = s.substring(0, s.length() - 1); }
        else if (last == 'g') { mult = 1024L * 1024 * 1024; s = s.substring(0, s.length() - 1); }
        else if (last == 't') { mult = 1024L * 1024 * 1024 * 1024; s = s.substring(0, s.length() - 1); }
        try {
            return Long.parseLong(s) * mult;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Extract the major version number from a java.version string. */
    static int parseJavaMajor(String v) {
        if (v == null || v.length() == 0) return 0;
        /* "1.8.0_xxx" -> 8,  "11.0.2" -> 11, "17.0.4+8" -> 17 */
        try {
            if (v.startsWith("1.")) {
                int dot = v.indexOf('.', 2);
                return Integer.parseInt(dot > 0 ? v.substring(2, dot) : v.substring(2));
            }
            int end = 0;
            while (end < v.length() && Character.isDigit(v.charAt(end))) end++;
            return end > 0 ? Integer.parseInt(v.substring(0, end)) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String fmtBytes(long b) {
        if (b >= 1024L * 1024 * 1024) return String.format("%.1fG", b / (1024.0 * 1024 * 1024));
        if (b >= 1024L * 1024) return String.format("%.0fM", b / (1024.0 * 1024));
        if (b >= 1024L) return String.format("%.0fK", b / 1024.0);
        return b + "B";
    }
}
