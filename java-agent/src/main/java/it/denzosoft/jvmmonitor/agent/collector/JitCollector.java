package it.denzosoft.jvmmonitor.agent.collector;

import it.denzosoft.jvmmonitor.agent.AgentConfig;
import it.denzosoft.jvmmonitor.agent.transport.MessageQueue;
import it.denzosoft.jvmmonitor.agent.transport.ProtocolWriter;

import java.lang.management.CompilationMXBean;
import java.lang.management.ManagementFactory;

/** Collects JIT compilation statistics via CompilationMXBean. */
public class JitCollector extends AbstractCollector {

    private static final int MSG_JIT_EVENT = 0xB3;
    private static final int JIT_COMPILED = 1;
    private final CompilationMXBean compBean;
    private long lastCompilationTime;
    private int syntheticCount = 0;

    public JitCollector(MessageQueue queue, AgentConfig config) {
        super(queue);
        this.intervalMs = 2000;
        compBean = ManagementFactory.getCompilationMXBean();
        if (compBean != null && compBean.isCompilationTimeMonitoringSupported()) {
            lastCompilationTime = compBean.getTotalCompilationTime();
        }
    }

    public String getName() { return "jit"; }
    public int getMaxLevel() { return 1; }
    public boolean isCore() { return false; }

    protected void collect() throws Exception {
        if (compBean == null || !compBean.isCompilationTimeMonitoringSupported()) return;

        long now = System.currentTimeMillis();
        long totalTime = compBean.getTotalCompilationTime();
        long delta = totalTime - lastCompilationTime;

        if (delta > 0) {
            syntheticCount++;
            lastCompilationTime = totalTime;

            /* JMX doesn't give per-method JIT info, so we send a summary event */
            byte[] msg = ProtocolWriter.payload()
                    .writeU64(now)
                    .writeU8(JIT_COMPILED)
                    .writeString("") /* className - not available via JMX */
                    .writeString("(JIT compilation batch)")
                    .writeI32((int) delta) /* code size = compilation time delta as proxy */
                    .writeI64(0)          /* code addr */
                    .writeI32(syntheticCount) /* total compiled count */
                    .buildMessage(MSG_JIT_EVENT);
            queue.enqueue(msg);
        }
    }
}
