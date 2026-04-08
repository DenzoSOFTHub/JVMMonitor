package it.denzosoft.jvmmonitor.agent.collector;

import it.denzosoft.jvmmonitor.agent.module.Module;
import it.denzosoft.jvmmonitor.agent.transport.MessageQueue;
import it.denzosoft.jvmmonitor.agent.transport.ProtocolWriter;
import it.denzosoft.jvmmonitor.agent.util.AgentLogger;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import javax.management.Notification;
import javax.management.NotificationListener;
import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;

/**
 * Collects GC events via JMX notification listener (Java 7+)
 * or polling delta fallback (Java 6).
 */
public class GcCollector implements Module {

    private static final int MSG_GC_EVENT = 0x20;
    private static final int GC_TYPE_YOUNG = 1;
    private static final int GC_TYPE_FULL = 3;

    private final MessageQueue queue;
    private volatile boolean active;
    private boolean useNotifications;

    /* Notification listener tracking for cleanup */
    private final List registeredEmitters = new ArrayList();
    private final List registeredListeners = new ArrayList();

    /* Polling fallback state */
    private long[] lastCounts;
    private long[] lastTimes;
    private Thread pollThread;

    public GcCollector(MessageQueue queue) {
        this.queue = queue;
    }

    public String getName() { return "gc"; }
    public int getMaxLevel() { return 1; }
    public boolean isCore() { return true; }
    public boolean isActive() { return active; }

    public void activate(int level) {
        if (active) return;
        active = true;

        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

        /* Try notification API (Java 7+) */
        try {
            Class.forName("com.sun.management.GarbageCollectionNotificationInfo");
            for (int i = 0; i < gcBeans.size(); i++) {
                GarbageCollectorMXBean bean = gcBeans.get(i);
                if (bean instanceof NotificationEmitter) {
                    NotificationEmitter emitter = (NotificationEmitter) bean;
                    GcNotificationListener listener = new GcNotificationListener(bean.getName());
                    emitter.addNotificationListener(listener, null, null);
                    registeredEmitters.add(emitter);
                    registeredListeners.add(listener);
                }
            }
            useNotifications = true;
            AgentLogger.info("GC collector using notification API");
            return;
        } catch (ClassNotFoundException e) {
            /* Java 6 - fall back to polling */
        } catch (Exception e) {
            AgentLogger.debug("GC notifications unavailable: " + e.getMessage());
        }

        /* Polling fallback */
        useNotifications = false;
        lastCounts = new long[gcBeans.size()];
        lastTimes = new long[gcBeans.size()];
        for (int i = 0; i < gcBeans.size(); i++) {
            lastCounts[i] = gcBeans.get(i).getCollectionCount();
            lastTimes[i] = gcBeans.get(i).getCollectionTime();
        }

        pollThread = new Thread(new Runnable() {
            public void run() {
                pollLoop();
            }
        }, "jvmmonitor-gc-poll");
        pollThread.setDaemon(true);
        pollThread.start();
        AgentLogger.info("GC collector using polling fallback");
    }

    public void deactivate() {
        active = false;
        /* Remove notification listeners to avoid leak */
        for (int i = 0; i < registeredEmitters.size(); i++) {
            try {
                ((NotificationEmitter) registeredEmitters.get(i))
                    .removeNotificationListener((NotificationListener) registeredListeners.get(i));
            } catch (Exception e) {
                AgentLogger.debug("Failed to remove GC listener: " + e.getMessage());
            }
        }
        registeredEmitters.clear();
        registeredListeners.clear();
        if (pollThread != null) pollThread.interrupt();
    }

    private void pollLoop() {
        while (active) {
            try {
                Thread.sleep(500);
                List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
                int len = Math.min(gcBeans.size(), lastCounts.length);
                for (int i = 0; i < len; i++) {
                    GarbageCollectorMXBean bean = gcBeans.get(i);
                    long count = bean.getCollectionCount();
                    long time = bean.getCollectionTime();
                    if (count > lastCounts[i]) {
                        long deltaCount = count - lastCounts[i];
                        long deltaTime = time - lastTimes[i];
                        long avgPauseMs = deltaCount > 0 ? deltaTime / deltaCount : 0;

                        int gcType = isFullGc(bean.getName()) ? GC_TYPE_FULL : GC_TYPE_YOUNG;
                        sendGcEvent(gcType, avgPauseMs * 1000000L, (int) count,
                                gcType == GC_TYPE_FULL ? (int) count : 0);

                        lastCounts[i] = count;
                        lastTimes[i] = time;
                    }
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                AgentLogger.error("GC poll error: " + e.getMessage());
            }
        }
    }

    private class GcNotificationListener implements NotificationListener {
        private final String collectorName;

        GcNotificationListener(String name) {
            this.collectorName = name;
        }

        public void handleNotification(Notification notification, Object handback) {
            if (!active) return;
            try {
                /* Extract GC info via reflection to avoid compile-time dependency on com.sun.management */
                CompositeData cd = (CompositeData) notification.getUserData();
                String gcAction = (String) cd.get("gcAction");
                CompositeData gcInfo = (CompositeData) cd.get("gcInfo");
                long duration = ((Long) gcInfo.get("duration")).longValue();
                long id = ((Long) gcInfo.get("id")).longValue();

                int gcType = (gcAction != null && gcAction.contains("major"))
                        || isFullGc(collectorName) ? GC_TYPE_FULL : GC_TYPE_YOUNG;

                /* Try to get heap before/after */
                long heapBefore = 0, heapAfter = 0;
                try {
                    javax.management.openmbean.TabularData memBefore =
                            (javax.management.openmbean.TabularData) gcInfo.get("memoryUsageBeforeGc");
                    javax.management.openmbean.TabularData memAfter =
                            (javax.management.openmbean.TabularData) gcInfo.get("memoryUsageAfterGc");
                    if (memBefore != null) {
                        for (Object row : memBefore.values()) {
                            CompositeData entry = (CompositeData) row;
                            CompositeData usage = (CompositeData) entry.get("value");
                            heapBefore += ((Long) usage.get("used")).longValue();
                        }
                    }
                    if (memAfter != null) {
                        for (Object row : memAfter.values()) {
                            CompositeData entry = (CompositeData) row;
                            CompositeData usage = (CompositeData) entry.get("value");
                            heapAfter += ((Long) usage.get("used")).longValue();
                        }
                    }
                } catch (Exception e) {
                    /* heap before/after not available */
                }

                sendGcEventExtended(gcType, duration * 1000000L, (int) id,
                        gcType == GC_TYPE_FULL ? (int) id : 0,
                        heapBefore, heapAfter);

            } catch (Exception e) {
                AgentLogger.debug("GC notification error: " + e.getMessage());
            }
        }
    }

    private void sendGcEvent(int gcType, long durationNanos, int gcCount, int fullGcCount) {
        try {
            byte[] msg = ProtocolWriter.payload()
                    .writeU64(System.currentTimeMillis())
                    .writeU8(gcType)
                    .writeU64(durationNanos)
                    .writeI32(gcCount)
                    .writeI32(fullGcCount)
                    .buildMessage(MSG_GC_EVENT);
            queue.enqueue(msg);
        } catch (Exception e) {
            AgentLogger.error("Failed to send GC event: " + e.getMessage());
        }
    }

    private void sendGcEventExtended(int gcType, long durationNanos, int gcCount, int fullGcCount,
                                      long heapBefore, long heapAfter) {
        try {
            byte[] msg = ProtocolWriter.payload()
                    .writeU64(System.currentTimeMillis())
                    .writeU8(gcType)
                    .writeU64(durationNanos)
                    .writeI32(gcCount)
                    .writeI32(fullGcCount)
                    .writeI64(heapBefore)    /* heap before */
                    .writeI64(heapAfter)     /* heap after */
                    .writeI64(0)             /* eden before */
                    .writeI64(0)             /* eden after */
                    .writeI64(0)             /* old gen before */
                    .writeI64(0)             /* old gen after */
                    .writeString("JMX")      /* cause */
                    .writeDouble(0)          /* process CPU at GC */
                    .buildMessage(MSG_GC_EVENT);
            queue.enqueue(msg);
        } catch (Exception e) {
            AgentLogger.error("Failed to send GC event: " + e.getMessage());
        }
    }

    private static boolean isFullGc(String name) {
        String lower = name.toLowerCase();
        return lower.contains("old") || lower.contains("full") || lower.contains("major")
                || lower.contains("marksweep") || lower.contains("mark sweep")
                || lower.contains("global");
    }
}
