package it.denzosoft.jvmmonitor.agent.collector;

import it.denzosoft.jvmmonitor.agent.module.Module;
import it.denzosoft.jvmmonitor.agent.transport.MessageQueue;
import it.denzosoft.jvmmonitor.agent.util.AgentLogger;

/**
 * Base class for polling collectors.
 * Runs a daemon thread that calls collect() at a configurable interval.
 */
public abstract class AbstractCollector implements Module, Runnable {

    protected final MessageQueue queue;
    protected volatile boolean active;
    protected int intervalMs = 1000;
    private Thread thread;

    protected AbstractCollector(MessageQueue queue) {
        this.queue = queue;
    }

    public boolean isActive() { return active; }

    public synchronized void activate(int level) {
        if (active) return;
        active = true;
        thread = new Thread(this, "jvmmonitor-" + getName());
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void deactivate() {
        active = false;
        if (thread != null) {
            thread.interrupt();
            try { thread.join(2000); } catch (InterruptedException e) { /* ignore */ }
            thread = null;
        }
    }

    public void run() {
        while (active) {
            try {
                collect();
            } catch (Exception e) {
                AgentLogger.error(getName() + " collect error: " + e.getMessage());
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    protected abstract void collect() throws Exception;
}
