package it.denzosoft.jvmmonitor.agent.collector;

import it.denzosoft.jvmmonitor.agent.AgentConfig;
import it.denzosoft.jvmmonitor.agent.transport.MessageQueue;
import it.denzosoft.jvmmonitor.agent.transport.ProtocolWriter;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.*;

/**
 * CPU sampling via ThreadMXBean.dumpAllThreads() fallback.
 * Not as accurate as AsyncGetCallTrace (safepoint-biased) but works on all JVMs.
 * Sends MSG_CPU_SAMPLE + MSG_METHOD_INFO for name resolution.
 */
public class CpuSampleCollector extends AbstractCollector {

    private static final int MSG_CPU_SAMPLE = 0x10;
    private static final int MSG_METHOD_INFO = 0xA0;

    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    private static final int MAX_SENT_METHODS = 10000;
    private final java.util.Set sentMethodIds = new java.util.HashSet();

    public CpuSampleCollector(MessageQueue queue, AgentConfig config) {
        super(queue);
        this.intervalMs = config.getSampleInterval();
    }

    public String getName() { return "cpu_sampler"; }
    public int getMaxLevel() { return 2; }
    public boolean isCore() { return false; }

    protected void collect() throws Exception {
        long now = System.currentTimeMillis();

        /* Get all thread stack traces */
        java.lang.management.ThreadInfo[] infos = threadBean.dumpAllThreads(false, false);

        for (int t = 0; t < infos.length; t++) {
            if (infos[t] == null) continue;
            StackTraceElement[] stack = infos[t].getStackTrace();
            if (stack == null || stack.length == 0) continue;

            int frameCount = Math.min(stack.length, 32);

            ProtocolWriter.PayloadBuilder pb = ProtocolWriter.payload();
            pb.writeU64(now);
            pb.writeU64(infos[t].getThreadId());
            pb.writeU16(frameCount);

            for (int f = 0; f < frameCount; f++) {
                long methodId = syntheticMethodId(stack[f]);
                pb.writeU64(methodId);
                pb.writeI32(stack[f].getLineNumber());

                /* Send METHOD_INFO for new method IDs; cap set to prevent unbounded growth */
                if (sentMethodIds.size() >= MAX_SENT_METHODS) sentMethodIds.clear();
                if (sentMethodIds.add(Long.valueOf(methodId))) {
                    sendMethodInfo(methodId, stack[f].getClassName(), stack[f].getMethodName());
                }
            }

            queue.enqueue(pb.buildMessage(MSG_CPU_SAMPLE));
        }
    }

    private void sendMethodInfo(long methodId, String className, String methodName) {
        try {
            byte[] msg = ProtocolWriter.payload()
                    .writeU64(methodId)
                    .writeString(className != null ? className : "?")
                    .writeString(methodName != null ? methodName : "?")
                    .buildMessage(MSG_METHOD_INFO);
            queue.enqueue(msg);
        } catch (Exception e) {
            /* ignore */
        }
    }

    /** Generate a synthetic method ID from class name + method name. */
    private static long syntheticMethodId(StackTraceElement ste) {
        String key = ste.getClassName() + "." + ste.getMethodName();
        /* Use a hash that produces stable IDs */
        long h = 0;
        for (int i = 0; i < key.length(); i++) {
            h = 31 * h + key.charAt(i);
        }
        return h & 0x7FFFFFFFFFFFFFFFL; /* positive */
    }
}
