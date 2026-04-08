package it.denzosoft.jvmmonitor.agent.collector;

import it.denzosoft.jvmmonitor.agent.module.Module;
import it.denzosoft.jvmmonitor.agent.transport.MessageQueue;
import it.denzosoft.jvmmonitor.agent.transport.ProtocolWriter;
import it.denzosoft.jvmmonitor.agent.util.AgentLogger;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/** Detects deadlocks via ThreadMXBean.findDeadlockedThreads(). On-demand only. */
public class DeadlockDetector implements Module {

    private static final int MSG_DEADLOCK = 0xD6;
    private final MessageQueue queue;
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    private volatile boolean active;

    public DeadlockDetector(MessageQueue queue) {
        this.queue = queue;
    }

    public String getName() { return "deadlock"; }
    public int getMaxLevel() { return 1; }
    public boolean isCore() { return false; }
    public boolean isActive() { return active; }

    public void activate(int level) {
        if (active) return;
        active = true;
        /* Run in background to avoid blocking the command dispatcher thread */
        Thread t = new Thread(new Runnable() {
            public void run() {
                detect();
                active = false;
            }
        }, "jvmmonitor-deadlock");
        t.setDaemon(true);
        t.start();
    }

    public void deactivate() {
        active = false;
    }

    public void detect() {
        long[] deadlocked = threadBean.findDeadlockedThreads();
        boolean found = deadlocked != null && deadlocked.length > 0;

        try {
            ProtocolWriter.PayloadBuilder pb = ProtocolWriter.payload();
            pb.writeU64(System.currentTimeMillis());
            pb.writeU8(found ? 1 : 0); /* deadlock found flag */

            if (found) {
                java.lang.management.ThreadInfo[] infos =
                        threadBean.getThreadInfo(deadlocked, true, true);

                /* Build text description */
                StringBuilder text = new StringBuilder();
                text.append("Deadlock detected involving " + deadlocked.length + " threads:\n\n");
                for (int i = 0; i < infos.length; i++) {
                    if (infos[i] == null) continue;
                    text.append("Thread: " + infos[i].getThreadName() +
                            " (ID=" + infos[i].getThreadId() + ")\n");
                    text.append("  State: " + infos[i].getThreadState() + "\n");
                    if (infos[i].getLockName() != null) {
                        text.append("  Waiting for: " + infos[i].getLockName() + "\n");
                    }
                    if (infos[i].getLockOwnerName() != null) {
                        text.append("  Held by: " + infos[i].getLockOwnerName() +
                                " (ID=" + infos[i].getLockOwnerId() + ")\n");
                    }
                    text.append("\n");
                }

                pb.writeU16(1); /* chain count */
                pb.writeString(text.toString());
            } else {
                pb.writeU16(0); /* no chains */
            }

            queue.enqueue(pb.buildMessage(MSG_DEADLOCK));
        } catch (Exception e) {
            AgentLogger.error("Deadlock detection failed: " + e.getMessage());
        }

        if (found) {
            AgentLogger.info("DEADLOCK detected! " + deadlocked.length + " threads involved");
        }
    }
}
