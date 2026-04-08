package it.denzosoft.jvmmonitor.agent.collector;

import it.denzosoft.jvmmonitor.agent.AgentConfig;
import it.denzosoft.jvmmonitor.agent.transport.MessageQueue;
import it.denzosoft.jvmmonitor.agent.transport.ProtocolWriter;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/** Collects thread states via ThreadMXBean. */
public class ThreadCollector extends AbstractCollector {

    private static final int MSG_THREAD_SNAPSHOT = 0x30;
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

    /* Thread state mapping to match C agent */
    private static final int STATE_NEW = 0;
    private static final int STATE_RUNNABLE = 1;
    private static final int STATE_BLOCKED = 2;
    private static final int STATE_WAITING = 3;
    private static final int STATE_TIMED_WAITING = 4;
    private static final int STATE_TERMINATED = 5;

    public ThreadCollector(MessageQueue queue, AgentConfig config) {
        super(queue);
        this.intervalMs = config.getMonitorInterval();
    }

    public String getName() { return "threads"; }
    public int getMaxLevel() { return 2; }
    public boolean isCore() { return true; }

    protected void collect() throws Exception {
        long now = System.currentTimeMillis();
        long[] threadIds = threadBean.getAllThreadIds();
        java.lang.management.ThreadInfo[] infos = threadBean.getThreadInfo(threadIds);

        /* Build daemon lookup map once — O(N) instead of O(N^2) per-thread findThread */
        java.util.Map daemonMap = new java.util.HashMap();
        try {
            int size = Thread.activeCount() * 2;
            Thread[] allThreads = new Thread[size];
            int count = Thread.enumerate(allThreads);
            for (int t = 0; t < count; t++) {
                if (allThreads[t] != null) {
                    daemonMap.put(new Long(allThreads[t].getId()),
                            allThreads[t].isDaemon() ? Boolean.TRUE : Boolean.FALSE);
                }
            }
        } catch (Exception e) { /* ignore */ }

        for (int i = 0; i < infos.length; i++) {
            if (infos[i] == null) continue;

            int state = mapState(infos[i].getThreadState());
            Boolean d = (Boolean) daemonMap.get(new Long(infos[i].getThreadId()));
            boolean daemon = d != null ? d.booleanValue() : false;

            byte[] msg = ProtocolWriter.payload()
                    .writeU64(now)
                    .writeU8(1) /* event type: snapshot */
                    .writeU64(infos[i].getThreadId())
                    .writeString(infos[i].getThreadName())
                    .writeI32(state)
                    .writeU8(daemon ? 1 : 0)
                    .buildMessage(MSG_THREAD_SNAPSHOT);
            queue.enqueue(msg);
        }
    }

    private static int mapState(Thread.State state) {
        if (state == null) return STATE_NEW;
        switch (state.ordinal()) {
            case 0: return STATE_NEW;          /* NEW */
            case 1: return STATE_RUNNABLE;     /* RUNNABLE */
            case 2: return STATE_BLOCKED;      /* BLOCKED */
            case 3: return STATE_WAITING;      /* WAITING */
            case 4: return STATE_TIMED_WAITING;/* TIMED_WAITING */
            case 5: return STATE_TERMINATED;   /* TERMINATED */
            default: return STATE_NEW;
        }
    }

    private static Thread findThread(long id) {
        int size = Thread.activeCount() * 2;
        Thread[] threads;
        int count;
        for (int attempt = 0; attempt < 3; attempt++) {
            threads = new Thread[size];
            count = Thread.enumerate(threads);
            if (count < threads.length) {
                for (int i = 0; i < count; i++) {
                    if (threads[i] != null && threads[i].getId() == id) {
                        return threads[i];
                    }
                }
                return null;
            }
            /* Buffer was full — some threads may have been missed; retry with larger buffer */
            size = size * 2;
        }
        return null;
    }
}
