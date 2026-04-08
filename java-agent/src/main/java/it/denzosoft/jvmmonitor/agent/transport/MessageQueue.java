package it.denzosoft.jvmmonitor.agent.transport;

/**
 * Bounded message queue with drop-oldest semantics.
 * Replaces the C agent's lock-free ring buffer.
 * Thread-safe via synchronized.
 */
public final class MessageQueue {

    private final byte[][] slots;
    private int writeIdx;
    private int readIdx;
    private int count;
    private long droppedCount;

    public MessageQueue(int capacity) {
        slots = new byte[capacity][];
        writeIdx = 0;
        readIdx = 0;
        count = 0;
    }

    /** Enqueue a message. If full, drops oldest. */
    public synchronized void enqueue(byte[] message) {
        slots[writeIdx] = message;
        writeIdx = (writeIdx + 1) % slots.length;
        if (count < slots.length) {
            count++;
        } else {
            readIdx = (readIdx + 1) % slots.length; /* drop oldest */
            droppedCount++;
        }
    }

    /** Number of messages dropped due to queue overflow. */
    public synchronized long getDroppedCount() {
        return droppedCount;
    }

    /** Dequeue a message. Returns null if empty. */
    public synchronized byte[] dequeue() {
        if (count == 0) return null;
        byte[] msg = slots[readIdx];
        slots[readIdx] = null;
        readIdx = (readIdx + 1) % slots.length;
        count--;
        return msg;
    }

    public synchronized int size() {
        return count;
    }

    public synchronized boolean isEmpty() {
        return count == 0;
    }
}
