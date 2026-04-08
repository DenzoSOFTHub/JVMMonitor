package it.denzosoft.jvmmonitor.agent.instrument;

import it.denzosoft.jvmmonitor.agent.transport.MessageQueue;
import it.denzosoft.jvmmonitor.agent.transport.ProtocolWriter;
import it.denzosoft.jvmmonitor.agent.util.AgentLogger;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Static recorder called from Javassist-injected bytecode.
 * Captures: duration, allocated bytes, blocked time, waited time per method call.
 */
public final class InstrumentationRecorder {

    private static volatile MessageQueue queue;
    private static volatile boolean recording;
    private static final AtomicLong traceIdGen = new AtomicLong(10000);

    /* ThreadMXBean for blocked/waited time */
    private static final ThreadMXBean THREAD_BEAN = ManagementFactory.getThreadMXBean();

    /* com.sun.management.ThreadMXBean for allocated bytes (optional) */
    private static volatile java.lang.reflect.Method getAllocMethod;
    private static volatile boolean allocChecked;

    /* Per-thread stack of counters captured at method entry */
    private static final ThreadLocal COUNTER_STACK = new ThreadLocal() {
        protected Object initialValue() { return new long[64 * 4]; /* [allocBytes, blockedMs, waitedMs, cpuNs] x 64 depth */ }
    };
    private static final ThreadLocal DEPTH = new ThreadLocal() {
        protected Object initialValue() { return new int[]{0}; }
    };

    private InstrumentationRecorder() {}

    public static void init(MessageQueue q) {
        queue = q;
        /* Try to enable thread contention monitoring for blocked/waited times */
        try {
            if (!THREAD_BEAN.isThreadContentionMonitoringEnabled()) {
                THREAD_BEAN.setThreadContentionMonitoringEnabled(true);
            }
        } catch (Exception e) { /* not supported on this JVM */ }
    }

    public static void setRecording(boolean r) { recording = r; }
    public static boolean isRecording() { return recording; }

    /**
     * Called at method entry by injected code. Pushes current counters.
     */
    public static void pushCounters() {
        if (!recording) return;
        try {
            int[] dp = (int[]) DEPTH.get();
            int d = dp[0];
            if (d >= 64) return;
            long[] stack = (long[]) COUNTER_STACK.get();
            int base = d * 4;

            stack[base] = getAllocatedBytes();
            java.lang.management.ThreadInfo ti = THREAD_BEAN.getThreadInfo(Thread.currentThread().getId());
            stack[base + 1] = ti != null ? ti.getBlockedTime() : 0;
            stack[base + 2] = ti != null ? ti.getWaitedTime() : 0;
            stack[base + 3] = THREAD_BEAN.getCurrentThreadCpuTime(); /* nanoseconds */

            dp[0] = d + 1;
        } catch (Exception e) { /* ignore */ }
    }

    /**
     * Pop counters and compute deltas.
     * Returns [allocDeltaBytes, blockedDeltaMs, waitedDeltaMs, cpuDeltaNs].
     */
    private static long[] popCounters() {
        long[] result = new long[]{0, 0, 0, 0};
        try {
            int[] dp = (int[]) DEPTH.get();
            int d = dp[0] - 1;
            if (d < 0) return result;
            dp[0] = d;
            long[] stack = (long[]) COUNTER_STACK.get();
            int base = d * 4;

            long allocNow = getAllocatedBytes();
            java.lang.management.ThreadInfo ti = THREAD_BEAN.getThreadInfo(Thread.currentThread().getId());
            long blockedNow = ti != null ? ti.getBlockedTime() : 0;
            long waitedNow = ti != null ? ti.getWaitedTime() : 0;
            long cpuNow = THREAD_BEAN.getCurrentThreadCpuTime();

            result[0] = allocNow - stack[base];
            result[1] = blockedNow - stack[base + 1];
            result[2] = waitedNow - stack[base + 2];
            result[3] = cpuNow - stack[base + 3];
            for (int i = 0; i < 4; i++) if (result[i] < 0) result[i] = 0;
        } catch (Exception e) { /* ignore */ }
        return result;
    }

    private static long getAllocatedBytes() {
        if (!allocChecked) {
            allocChecked = true;
            try {
                getAllocMethod = THREAD_BEAN.getClass().getMethod(
                        "getThreadAllocatedBytes", new Class[]{long.class});
                getAllocMethod.setAccessible(true);
            } catch (Exception e) { getAllocMethod = null; }
        }
        if (getAllocMethod == null) return 0;
        try {
            Object r = getAllocMethod.invoke(THREAD_BEAN,
                    new Object[]{new Long(Thread.currentThread().getId())});
            return ((Long) r).longValue();
        } catch (Exception e) { return 0; }
    }

    public static void recordMethodExit(int eventType, String className, String methodName,
                                         long durationNanos, String context) {
        recordMethodExit(eventType, className, methodName, durationNanos, context, null, null);
    }

    public static void recordMethodExit(int eventType, String className, String methodName,
                                         long durationNanos, String context,
                                         Object[] params, Object returnValue) {
        long[] counters = popCounters();
        MessageQueue q = queue;
        if (!recording || q == null) return;

        try {
            long now = System.currentTimeMillis();
            long threadId = Thread.currentThread().getId();
            String threadName = Thread.currentThread().getName();
            long traceId = traceIdGen.incrementAndGet();

            String paramsJson = ParamSerializer.serializeParams(params);
            String retJson = ParamSerializer.serializeReturnValue(returnValue);
            context = truncateContext(context);

            ProtocolWriter.PayloadBuilder pb = ProtocolWriter.payload();
            pb.writeU64(now);
            pb.writeU8(eventType);
            pb.writeU64(threadId);
            pb.writeString(threadName);
            pb.writeString(className != null ? className : "");
            pb.writeString(methodName != null ? methodName : "");
            pb.writeU64(durationNanos);
            pb.writeString(context != null ? context : "");
            pb.writeU64(traceId);
            pb.writeU64(0);   /* parentTraceId */
            pb.writeI32(0);   /* depth */
            pb.writeU8(0);    /* isException */
            pb.writeString(paramsJson != null ? paramsJson : "");
            pb.writeString(retJson != null ? retJson : "");
            /* v1.1.1: resource counters */
            pb.writeI64(counters[0]); /* allocatedBytes */
            pb.writeI64(counters[1]); /* blockedTimeMs */
            pb.writeI64(counters[2]); /* waitedTimeMs */
            pb.writeI64(counters[3]); /* cpuTimeNs */

            q.enqueue(pb.buildMessage(0xC1));
        } catch (Exception e) { /* ignore */ }
    }

    public static void recordMethodException(int eventType, String className, String methodName,
                                              long durationNanos, String context) {
        recordMethodException(eventType, className, methodName, durationNanos, context, null);
    }

    public static void recordMethodException(int eventType, String className, String methodName,
                                              long durationNanos, String context, Object[] params) {
        long[] counters = popCounters();
        MessageQueue q = queue;
        if (!recording || q == null) return;

        try {
            long now = System.currentTimeMillis();
            long threadId = Thread.currentThread().getId();
            String threadName = Thread.currentThread().getName();
            long traceId = traceIdGen.incrementAndGet();

            String paramsJson = ParamSerializer.serializeParams(params);
            context = truncateContext(context);

            ProtocolWriter.PayloadBuilder pb = ProtocolWriter.payload();
            pb.writeU64(now);
            pb.writeU8(eventType);
            pb.writeU64(threadId);
            pb.writeString(threadName);
            pb.writeString(className != null ? className : "");
            pb.writeString(methodName != null ? methodName : "");
            pb.writeU64(durationNanos);
            pb.writeString(context != null ? context : "");
            pb.writeU64(traceId);
            pb.writeU64(0);
            pb.writeI32(0);
            pb.writeU8(1);    /* isException */
            pb.writeString(paramsJson != null ? paramsJson : "");
            pb.writeString("");
            /* v1.1.1: resource counters */
            pb.writeI64(counters[0]);
            pb.writeI64(counters[1]);
            pb.writeI64(counters[2]); /* waitedTimeMs */
            pb.writeI64(counters[3]); /* cpuTimeNs */

            q.enqueue(pb.buildMessage(0xC1));
        } catch (Exception e) { /* ignore */ }
    }

    private static String truncateContext(String ctx) {
        if (ctx == null) return "";
        int max = ParamSerializer.getMaxValueLength();
        if (max < 0 || ctx.length() <= max) return ctx;
        return ctx.substring(0, max) + "...(" + ctx.length() + " chars)";
    }
}
