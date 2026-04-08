package it.denzosoft.jvmmonitor.agent.collector;

import it.denzosoft.jvmmonitor.agent.AgentConfig;
import it.denzosoft.jvmmonitor.agent.transport.MessageQueue;
import it.denzosoft.jvmmonitor.agent.transport.ProtocolWriter;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;

/** Collects classloader statistics via ClassLoadingMXBean. */
public class ClassloaderCollector extends AbstractCollector {

    private static final int MSG_CLASSLOADER = 0xBA;
    private final ClassLoadingMXBean clBean = ManagementFactory.getClassLoadingMXBean();

    public ClassloaderCollector(MessageQueue queue, AgentConfig config) {
        super(queue);
        this.intervalMs = 5000;
    }

    public String getName() { return "classloaders"; }
    public int getMaxLevel() { return 1; }
    public boolean isCore() { return false; }

    protected void collect() throws Exception {
        long now = System.currentTimeMillis();
        int loaded = clBean.getLoadedClassCount();
        long totalLoaded = clBean.getTotalLoadedClassCount();
        long unloaded = clBean.getUnloadedClassCount();

        /* Build a simple classloader report: system loader + app loader */
        ProtocolWriter.PayloadBuilder pb = ProtocolWriter.payload();
        pb.writeU64(now);

        /* Enumerate classloaders from the current thread's hierarchy */
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        int loaderCount = 0;
        java.util.List<String> loaderNames = new java.util.ArrayList<String>();
        while (cl != null) {
            loaderNames.add(cl.getClass().getName());
            cl = cl.getParent();
            loaderCount++;
        }
        loaderNames.add("bootstrap"); /* bootstrap loader */
        loaderCount++;

        pb.writeU16(loaderCount);
        /* Distribute loaded classes proportionally (approximation) */
        int perLoader = loaded / Math.max(loaderCount, 1);
        int remainder = loaded - perLoader * loaderCount;
        for (int i = 0; i < loaderNames.size(); i++) {
            pb.writeString(loaderNames.get(i));
            int count = perLoader + (i == 0 ? remainder : 0);
            pb.writeI32(count);
        }

        queue.enqueue(pb.buildMessage(MSG_CLASSLOADER));
    }
}
