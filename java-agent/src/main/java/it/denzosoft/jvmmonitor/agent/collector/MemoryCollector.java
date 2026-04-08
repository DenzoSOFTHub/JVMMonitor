package it.denzosoft.jvmmonitor.agent.collector;

import it.denzosoft.jvmmonitor.agent.AgentConfig;
import it.denzosoft.jvmmonitor.agent.transport.MessageQueue;
import it.denzosoft.jvmmonitor.agent.transport.ProtocolWriter;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;

/** Collects heap and non-heap memory usage via MemoryMXBean. */
public class MemoryCollector extends AbstractCollector {

    private static final int MSG_MEMORY_SNAPSHOT = 0x40;
    private final MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();

    public MemoryCollector(MessageQueue queue, AgentConfig config) {
        super(queue);
        this.intervalMs = config.getMonitorInterval();
    }

    public String getName() { return "memory"; }
    public int getMaxLevel() { return 2; }
    public boolean isCore() { return true; }

    protected void collect() throws Exception {
        long now = System.currentTimeMillis();
        MemoryUsage heap = memBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memBean.getNonHeapMemoryUsage();

        byte[] msg = ProtocolWriter.payload()
                .writeU64(now)
                .writeI64(heap.getUsed())
                .writeI64(heap.getMax() > 0 ? heap.getMax() : heap.getCommitted())
                .writeI64(nonHeap.getUsed())
                .writeI64(nonHeap.getMax() > 0 ? nonHeap.getMax() : nonHeap.getCommitted())
                .buildMessage(MSG_MEMORY_SNAPSHOT);
        queue.enqueue(msg);
    }
}
