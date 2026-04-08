package it.denzosoft.jvmmonitor.agent.collector;

import it.denzosoft.jvmmonitor.agent.module.Module;
import it.denzosoft.jvmmonitor.agent.transport.MessageQueue;
import it.denzosoft.jvmmonitor.agent.transport.ProtocolWriter;
import it.denzosoft.jvmmonitor.agent.util.AgentLogger;

import java.lang.instrument.Instrumentation;
import java.util.*;

/**
 * Class histogram via Instrumentation.getAllLoadedClasses() + getObjectSize().
 * One-shot (not continuous) — activated on demand by the collector.
 */
public class ClassHistoCollector implements Module {

    private static final int MSG_CLASS_HISTO = 0xB4;
    private final MessageQueue queue;
    private final Instrumentation instrumentation;
    private volatile boolean active;

    public ClassHistoCollector(MessageQueue queue, Instrumentation instrumentation) {
        this.queue = queue;
        this.instrumentation = instrumentation;
    }

    public String getName() { return "histogram"; }
    public int getMaxLevel() { return 1; }
    public boolean isCore() { return false; }
    public boolean isActive() { return active; }

    public void activate(int level) {
        active = true;
        /* Run once in a background thread */
        Thread t = new Thread(new Runnable() {
            public void run() {
                collectHistogram();
                active = false;
            }
        }, "jvmmonitor-histogram");
        t.setDaemon(true);
        t.start();
    }

    public void deactivate() {
        active = false;
    }

    private void collectHistogram() {
        long startNanos = System.nanoTime();
        AgentLogger.info("Collecting class histogram...");

        Class[] classes = instrumentation.getAllLoadedClasses();

        /* Count instances per class (approximation: 1 instance per loaded class for the class itself,
           actual instance counts would require heap walk which is not available in pure Java) */
        /* Instead, we report loaded classes with their estimated object size */
        Map<String, long[]> classStats = new LinkedHashMap<String, long[]>(); /* name -> [count, totalSize] */

        for (int i = 0; i < classes.length; i++) {
            try {
                String name = classes[i].getName();
                long size = instrumentation.getObjectSize(classes[i]);
                long[] stats = classStats.get(name);
                if (stats == null) {
                    stats = new long[]{1, size};
                    classStats.put(name, stats);
                } else {
                    stats[0]++;
                    stats[1] += size;
                }
            } catch (Exception e) {
                /* skip classes that can't be measured */
            }
        }

        long elapsedNanos = System.nanoTime() - startNanos;
        AgentLogger.info("Histogram collected: " + classStats.size() + " classes in " +
                (elapsedNanos / 1000000) + "ms");

        /* Sort by total size descending */
        List<Map.Entry<String, long[]>> sorted =
                new ArrayList<Map.Entry<String, long[]>>(classStats.entrySet());
        Collections.sort(sorted, new Comparator<Map.Entry<String, long[]>>() {
            public int compare(Map.Entry<String, long[]> a, Map.Entry<String, long[]> b) {
                /* Manual compare for Java 1.6 compat (Long.compare is Java 7+) */
                long bv = b.getValue()[1], av = a.getValue()[1];
                return bv > av ? 1 : (bv < av ? -1 : 0);
            }
        });

        /* Send top 100 entries */
        int count = Math.min(sorted.size(), 100);
        try {
            ProtocolWriter.PayloadBuilder pb = ProtocolWriter.payload();
            pb.writeU64(System.currentTimeMillis());
            pb.writeU64(elapsedNanos);
            pb.writeU16(count);
            for (int i = 0; i < count; i++) {
                Map.Entry<String, long[]> entry = sorted.get(i);
                pb.writeString(entry.getKey());
                pb.writeI32((int) entry.getValue()[0]); /* instance count */
                pb.writeI64(entry.getValue()[1]);        /* total size */
            }
            queue.enqueue(pb.buildMessage(MSG_CLASS_HISTO));
        } catch (Exception e) {
            AgentLogger.error("Failed to send histogram: " + e.getMessage());
        }
    }
}
