package it.denzosoft.jvmmonitor.agent.collector;

import it.denzosoft.jvmmonitor.agent.AgentConfig;
import it.denzosoft.jvmmonitor.agent.transport.MessageQueue;
import it.denzosoft.jvmmonitor.agent.transport.ProtocolWriter;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;

/**
 * Collects OS-level metrics.
 * Uses portable JMX APIs where possible, with /proc fallback on Linux.
 */
public class OsMetricsCollector extends AbstractCollector {

    private static final int MSG_OS_METRICS = 0xB2;
    private final OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
    private Method getTotalPhysicalMemory;
    private Method getFreePhysicalMemory;

    public OsMetricsCollector(MessageQueue queue, AgentConfig config) {
        super(queue);
        this.intervalMs = 5000; /* every 5 seconds */

        try {
            getTotalPhysicalMemory = osBean.getClass().getMethod("getTotalPhysicalMemorySize");
            getTotalPhysicalMemory.setAccessible(true);
        } catch (Exception e) { /* not available */ }

        try {
            getFreePhysicalMemory = osBean.getClass().getMethod("getFreePhysicalMemorySize");
            getFreePhysicalMemory.setAccessible(true);
        } catch (Exception e) { /* not available */ }
    }

    public String getName() { return "os"; }
    public int getMaxLevel() { return 1; }
    /* Core: Dashboard Disk I/O + System Resources panels depend on this. */
    public boolean isCore() { return true; }

    protected void collect() throws Exception {
        long now = System.currentTimeMillis();

        int fdCount = -1;
        long rssBytes = -1;
        long vmSizeBytes = -1;
        long volCtxSw = -1;
        long involCtxSw = -1;
        int tcpEstablished = -1;
        int tcpCloseWait = -1;
        int tcpTimeWait = -1;
        int osThreads = osBean.getAvailableProcessors();

        /* Try /proc on Linux for detailed metrics */
        File procSelf = new File("/proc/self");
        if (procSelf.exists()) {
            fdCount = countOpenFds();
            long[] mem = readProcStatus();
            if (mem != null) {
                rssBytes = mem[0];
                vmSizeBytes = mem[1];
                osThreads = (int) mem[2];
            }
        }

        /* Try com.sun.management for open FD count */
        if (fdCount < 0) {
            try {
                Method m = osBean.getClass().getMethod("getOpenFileDescriptorCount");
                m.setAccessible(true);
                fdCount = ((Number) m.invoke(osBean)).intValue();
            } catch (Exception e) { /* not available */ }
        }

        byte[] msg = ProtocolWriter.payload()
                .writeU64(now)
                .writeI32(fdCount)
                .writeI64(rssBytes)
                .writeI64(vmSizeBytes)
                .writeI64(volCtxSw)
                .writeI64(involCtxSw)
                .writeI32(tcpEstablished)
                .writeI32(tcpCloseWait)
                .writeI32(tcpTimeWait)
                .writeI32(osThreads)
                .buildMessage(MSG_OS_METRICS);
        queue.enqueue(msg);
    }

    private int countOpenFds() {
        try {
            File fdDir = new File("/proc/self/fd");
            String[] fds = fdDir.list();
            return fds != null ? fds.length : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Read RSS, VmSize, Threads from /proc/self/status. Returns [rss, vmsize, threads] or null. */
    private long[] readProcStatus() {
        java.io.BufferedReader reader = null;
        try {
            reader = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/self/status"));
            long rss = -1, vmSize = -1;
            int threads = -1;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("VmRSS:")) {
                    rss = parseKb(line) * 1024;
                } else if (line.startsWith("VmSize:")) {
                    vmSize = parseKb(line) * 1024;
                } else if (line.startsWith("Threads:")) {
                    threads = (int) parseKb(line);
                }
            }
            return new long[]{rss, vmSize, threads};
        } catch (Exception e) {
            return null;
        } finally {
            if (reader != null) { try { reader.close(); } catch (Exception ignored) {} }
        }
    }

    private static long parseKb(String line) {
        /* "VmRSS:    12345 kB" -> 12345 */
        String[] parts = line.split("\\s+");
        if (parts.length >= 2) {
            try {
                return Long.parseLong(parts[1]);
            } catch (NumberFormatException e) { /* ignore */ }
        }
        return -1;
    }
}
