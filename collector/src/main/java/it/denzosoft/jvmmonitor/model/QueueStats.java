package it.denzosoft.jvmmonitor.model;

/**
 * Message queue statistics snapshot.
 * Covers JMS queues, Kafka topics, RabbitMQ queues, etc.
 */
public final class QueueStats {

    private final long timestamp;
    private final QueueInfo[] queues;

    public QueueStats(long timestamp, QueueInfo[] queues) {
        this.timestamp = timestamp;
        this.queues = queues;
    }

    public long getTimestamp() { return timestamp; }
    public QueueInfo[] getQueues() { return queues; }
    public int getQueueCount() { return queues != null ? queues.length : 0; }

    public static final class QueueInfo {
        public final String name;
        public final String type;            /* JMS, Kafka, RabbitMQ, ActiveMQ */
        public final long depth;             /* messages waiting in queue */
        public final long enqueueRate;       /* messages/sec enqueued */
        public final long dequeueRate;       /* messages/sec dequeued */
        public final int consumerCount;
        public final int producerCount;
        public final long totalEnqueued;     /* cumulative */
        public final long totalDequeued;     /* cumulative */
        public final long consumerLag;       /* Kafka: offset - committed */
        public final long oldestMessageAge;  /* ms since oldest message was enqueued */

        public QueueInfo(String name, String type, long depth,
                         long enqueueRate, long dequeueRate,
                         int consumerCount, int producerCount,
                         long totalEnqueued, long totalDequeued,
                         long consumerLag, long oldestMessageAge) {
            this.name = name;
            this.type = type;
            this.depth = depth;
            this.enqueueRate = enqueueRate;
            this.dequeueRate = dequeueRate;
            this.consumerCount = consumerCount;
            this.producerCount = producerCount;
            this.totalEnqueued = totalEnqueued;
            this.totalDequeued = totalDequeued;
            this.consumerLag = consumerLag;
            this.oldestMessageAge = oldestMessageAge;
        }

        public boolean isBacklogged() { return depth > 1000 || consumerLag > 10000; }
        public boolean isStale() { return oldestMessageAge > 60000; }
    }
}
