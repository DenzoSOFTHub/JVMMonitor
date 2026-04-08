package it.denzosoft.jvmmonitor.agent.collector;

import it.denzosoft.jvmmonitor.agent.AgentConfig;
import it.denzosoft.jvmmonitor.agent.transport.MessageQueue;
import it.denzosoft.jvmmonitor.agent.transport.ProtocolWriter;
import it.denzosoft.jvmmonitor.agent.util.AgentLogger;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;

/**
 * Collects CPU usage via OperatingSystemMXBean (com.sun.management extension).
 * Falls back gracefully if com.sun.management is not available (e.g., IBM J9).
 */
public class CpuUsageCollector extends AbstractCollector {

    private static final int MSG_CPU_USAGE = 0xBF;
    private final OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
    private final RuntimeMXBean rtBean = ManagementFactory.getRuntimeMXBean();
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

    /* Reflected methods (may not be available on all JVMs) */
    private Method getProcessCpuLoad;
    private Method getSystemCpuLoad;
    private Method getProcessCpuTime;

    private long lastUptime;
    private long lastProcessCpuTime;

    public CpuUsageCollector(MessageQueue queue, AgentConfig config) {
        super(queue);
        this.intervalMs = config.getMonitorInterval();

        /* Try to access com.sun.management methods via reflection */
        try {
            getProcessCpuLoad = osBean.getClass().getMethod("getProcessCpuLoad");
            getProcessCpuLoad.setAccessible(true);
        } catch (Exception e) { /* not available */ }

        try {
            getSystemCpuLoad = osBean.getClass().getMethod("getSystemCpuLoad");
            getSystemCpuLoad.setAccessible(true);
        } catch (Exception e) { /* not available */ }

        try {
            getProcessCpuTime = osBean.getClass().getMethod("getProcessCpuTime");
            getProcessCpuTime.setAccessible(true);
        } catch (Exception e) { /* not available */ }

        lastUptime = rtBean.getUptime();
        lastProcessCpuTime = getProcessCpuTimeValue();
    }

    public String getName() { return "cpu_usage"; }
    public int getMaxLevel() { return 1; }
    /* Core: Dashboard CPU chart + multiple panels depend on this, overhead negligible (JMX reflection). */
    public boolean isCore() { return true; }

    protected void collect() throws Exception {
        long now = System.currentTimeMillis();
        int processors = osBean.getAvailableProcessors();

        double sysCpu = getDoubleValue(getSystemCpuLoad) * 100;
        double procCpu = getDoubleValue(getProcessCpuLoad) * 100;

        /* Fallback: compute from process CPU time delta */
        if (procCpu < 0) {
            long uptime = rtBean.getUptime();
            long cpuTime = getProcessCpuTimeValue();
            long elapsedMs = uptime - lastUptime;
            long cpuDelta = cpuTime - lastProcessCpuTime;
            if (elapsedMs > 0 && cpuDelta > 0) {
                procCpu = (cpuDelta / 1000000.0) / elapsedMs / processors * 100;
            } else {
                procCpu = 0;
            }
            lastUptime = uptime;
            lastProcessCpuTime = cpuTime;
        }

        if (sysCpu < 0) sysCpu = 0;
        if (procCpu > 100) procCpu = 100;

        /* Get per-thread CPU info (top 10) */
        long[] threadIds = threadBean.getAllThreadIds();
        int topCount = Math.min(threadIds.length, 10);

        /* Collect thread CPU times */
        long[][] threadCpuData = new long[threadIds.length][2]; /* [id, cpuTimeNs] */
        for (int i = 0; i < threadIds.length; i++) {
            threadCpuData[i][0] = threadIds[i];
            try {
                threadCpuData[i][1] = threadBean.getThreadCpuTime(threadIds[i]);
            } catch (Exception e) {
                threadCpuData[i][1] = -1;
            }
        }

        /* Sort by CPU time descending — O(n log n) via Arrays.sort */
        java.util.Arrays.sort(threadCpuData, new java.util.Comparator() {
            public int compare(Object a, Object b) {
                long av = ((long[]) a)[1], bv = ((long[]) b)[1];
                return bv > av ? 1 : (bv < av ? -1 : 0);
            }
        });

        long processUserMs = getProcessCpuTimeValue() / 1000000;

        /* Wire format matches C agent / ProtocolDecoder: doubles as fixed-point i64 (value * 1000) */
        ProtocolWriter.PayloadBuilder pb = ProtocolWriter.payload();
        pb.writeU64(now);
        pb.writeI64((long)(sysCpu * 1000));
        pb.writeI32(processors);
        pb.writeI64((long)(procCpu * 1000));
        pb.writeI64(processUserMs);
        pb.writeI64(0); /* system time (not available via JMX) */

        /* Top threads — budget-limited to avoid exceeding MAX_PAYLOAD (8192) */
        java.lang.management.ThreadInfo[] infos = threadBean.getThreadInfo(threadIds);

        /* Pre-resolve thread names and states, compute entry sizes */
        String[] names = new String[topCount];
        String[] states = new String[topCount];
        int[] entrySizes = new int[topCount];
        for (int i = 0; i < topCount; i++) {
            long tid = threadCpuData[i][0];
            names[i] = "?";
            states[i] = "UNKNOWN";
            for (int j = 0; j < infos.length; j++) {
                if (infos[j] != null && infos[j].getThreadId() == tid) {
                    names[i] = infos[j].getThreadName();
                    states[i] = infos[j].getThreadState().name();
                    break;
                }
            }
            /* Entry size: 8(tid) + 2+nameBytes + 8(cpu) + 8(delta) + 2+stateBytes */
            entrySizes[i] = 8 + 2 + names[i].getBytes("UTF-8").length
                          + 8 + 8 + 2 + states[i].getBytes("UTF-8").length;
        }

        /* Header size: 8(ts)+8(sys)+4(proc)+8(procCpu)+8(userMs)+8(sysTime)+2(count) = 46 */
        int payloadSize = 46;
        int actualCount = 0;
        for (int i = 0; i < topCount; i++) {
            if (payloadSize + entrySizes[i] > 8000) break;
            payloadSize += entrySizes[i];
            actualCount++;
        }

        pb.writeU16(actualCount);
        for (int i = 0; i < actualCount; i++) {
            pb.writeU64(threadCpuData[i][0]);
            pb.writeString(names[i]);
            pb.writeI64(threadCpuData[i][1] / 1000000); /* cpu time ms */
            pb.writeI64(0); /* delta (computed by collector) */
            pb.writeString(states[i]);
        }

        queue.enqueue(pb.buildMessage(MSG_CPU_USAGE));
    }

    private double getDoubleValue(Method method) {
        if (method == null) return -1;
        try {
            Object result = method.invoke(osBean);
            if (result instanceof Number) return ((Number) result).doubleValue();
        } catch (Exception e) { /* ignore */ }
        return -1;
    }

    private long getProcessCpuTimeValue() {
        if (getProcessCpuTime == null) return 0;
        try {
            Object result = getProcessCpuTime.invoke(osBean);
            if (result instanceof Number) return ((Number) result).longValue();
        } catch (Exception e) { /* ignore */ }
        return 0;
    }
}
