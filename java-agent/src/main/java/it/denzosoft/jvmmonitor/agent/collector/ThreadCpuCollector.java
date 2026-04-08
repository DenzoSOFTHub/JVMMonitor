package it.denzosoft.jvmmonitor.agent.collector;

import it.denzosoft.jvmmonitor.agent.AgentConfig;
import it.denzosoft.jvmmonitor.agent.transport.MessageQueue;
import it.denzosoft.jvmmonitor.agent.transport.ProtocolWriter;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/** Collects per-thread CPU time for hot thread detection. */
public class ThreadCpuCollector extends AbstractCollector {

    private static final int MSG_THREAD_CPU = 0xB9;
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

    public ThreadCpuCollector(MessageQueue queue, AgentConfig config) {
        super(queue);
        this.intervalMs = config.getMonitorInterval();
    }

    public String getName() { return "thread_cpu"; }
    public int getMaxLevel() { return 1; }
    public boolean isCore() { return false; }

    public void activate(int level) {
        /* Enable thread contention monitoring if supported */
        if (threadBean.isThreadContentionMonitoringSupported()) {
            threadBean.setThreadContentionMonitoringEnabled(true);
        }
        if (threadBean.isThreadCpuTimeSupported()) {
            threadBean.setThreadCpuTimeEnabled(true);
        }
        super.activate(level);
    }

    protected void collect() throws Exception {
        if (!threadBean.isThreadCpuTimeSupported()) return;

        long now = System.currentTimeMillis();
        long[] ids = threadBean.getAllThreadIds();
        java.lang.management.ThreadInfo[] infos = threadBean.getThreadInfo(ids);

        /* Collect CPU times */
        int count = Math.min(ids.length, 20);
        long[][] data = new long[ids.length][3]; /* [id, cpuNs, index] */
        for (int i = 0; i < ids.length; i++) {
            data[i][0] = ids[i];
            data[i][1] = threadBean.getThreadCpuTime(ids[i]);
            data[i][2] = i;
        }

        /* Sort by CPU time descending using Arrays.sort (O(n log n) vs O(n^2)) */
        java.util.Arrays.sort(data, new java.util.Comparator() {
            public int compare(Object a, Object b) {
                long av = ((long[]) a)[1], bv = ((long[]) b)[1];
                return bv > av ? 1 : (bv < av ? -1 : 0);
            }
        });

        /* Count valid entries (skip threads with cpuNs < 0) */
        int validCount = 0;
        for (int i = 0; i < data.length && validCount < count; i++) {
            if (data[i][1] >= 0) validCount++;
        }

        ProtocolWriter.PayloadBuilder pb = ProtocolWriter.payload();
        pb.writeU64(now);
        pb.writeU16(validCount);
        int sent = 0;
        for (int i = 0; i < data.length && sent < validCount; i++) {
            if (data[i][1] < 0) continue; /* skip dead/unsupported threads */
            long tid = data[i][0];
            long cpuNs = data[i][1];
            int idx = (int) data[i][2];
            String name = (idx < infos.length && infos[idx] != null) ? infos[idx].getThreadName() : "?";
            pb.writeU64(tid);
            pb.writeString(name);
            pb.writeI64(cpuNs);
            pb.writeI64(0); /* delta computed by collector */
            sent++;
        }

        queue.enqueue(pb.buildMessage(MSG_THREAD_CPU));
    }
}
