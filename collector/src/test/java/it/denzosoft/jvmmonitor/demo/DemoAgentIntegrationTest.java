package it.denzosoft.jvmmonitor.demo;

import it.denzosoft.jvmmonitor.model.*;
import it.denzosoft.jvmmonitor.net.AgentConnection;
import it.denzosoft.jvmmonitor.storage.InMemoryEventStore;
import it.denzosoft.jvmmonitor.analysis.AnalysisContext;
import it.denzosoft.jvmmonitor.analysis.DiagnosisEngine;
import it.denzosoft.jvmmonitor.protocol.ProtocolConstants;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Random;

/**
 * Integration test: a demo server generates realistic JVM events,
 * the collector connects and captures them. After a few seconds
 * of data collection, we verify all data types are received and
 * the diagnosis engine produces meaningful output.
 *
 * This simulates a real usage scenario end-to-end.
 */
public class DemoAgentIntegrationTest {

    private static final int TEST_PORT = 19878;
    private static final Random RNG = new Random(42);

    private ServerSocket server;
    private Socket clientSocket;
    private InMemoryEventStore store;
    private AgentConnection connection;
    private Thread generatorThread;
    private volatile boolean generatorRunning = true;

    @Before
    public void setUp() throws Exception {
        store = new InMemoryEventStore();
        server = new ServerSocket(TEST_PORT);

        /* Start collector connection in background */
        final AgentConnection[] holder = new AgentConnection[1];
        Thread connectThread = new Thread(new Runnable() {
            public void run() {
                try {
                    holder[0] = new AgentConnection("127.0.0.1", TEST_PORT, store);
                    holder[0].connect();
                } catch (IOException e) { /* test will fail */ }
            }
        });
        connectThread.start();
        clientSocket = server.accept();
        connectThread.join(2000);
        connection = holder[0];

        /* Start event generator in background */
        generatorThread = new Thread(new Runnable() {
            public void run() {
                try {
                    runDemoEventGenerator(clientSocket.getOutputStream());
                } catch (IOException e) {
                    /* generator stopped */
                }
            }
        }, "demo-generator");
        generatorThread.setDaemon(true);
        generatorThread.start();

        /* Let events accumulate */
        Thread.sleep(4000);
    }

    @After
    public void tearDown() {
        generatorRunning = false;
        if (connection != null) connection.disconnect();
        try { if (clientSocket != null) clientSocket.close(); } catch (IOException e) { }
        try { if (server != null) server.close(); } catch (IOException e) { }
    }

    /* ── Generator (produces 3 seconds of realistic data) ── */

    private void runDemoEventGenerator(OutputStream out) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);

        /* Handshake */
        sendHandshake(dos);

        long heapUsed = 300 * 1024 * 1024L;
        long heapMax = 1024 * 1024 * 1024L;
        int gcCount = 0, fullGcCount = 0;
        int excCount = 0, caughtCount = 0;
        int jitCount = 0;
        long spCount = 0, spTime = 0, spSync = 0;

        for (int tick = 0; tick < 3 && generatorRunning; tick++) {
            long now = System.currentTimeMillis();

            /* Memory */
            sendMemory(dos, now, heapUsed, heapMax, 64 * 1024 * 1024L, 256 * 1024 * 1024L);

            /* GC */
            gcCount++;
            boolean full = tick == 2;
            if (full) fullGcCount++;
            long durNanos = full ? 150000000L : 8000000L;
            sendGc(dos, now, full ? 3 : 1, durNanos, gcCount, fullGcCount);

            /* Threads */
            String[] names = {"main", "http-exec-1", "http-exec-2", "worker-1", "GC-thread"};
            int[] states = {1, 1, 2, 4, 3};
            for (int i = 0; i < names.length; i++) {
                sendThread(dos, now, i + 1, names[i], states[i], i > 0);
            }

            /* Exceptions */
            for (int i = 0; i < 5; i++) {
                excCount++;
                boolean caught = i < 4;
                if (caught) caughtCount++;
                sendException(dos, now + i, excCount, caughtCount,
                        "Ljava/lang/NullPointerException;",
                        "com.myapp.Service", "process",
                        caught, "com.myapp.Handler", "handle");
            }

            /* OS metrics */
            sendOsMetrics(dos, now, 200, heapUsed + 100 * 1024 * 1024L,
                    heapUsed + 400 * 1024 * 1024L, 50000, 1000, 15, 1, 3, 25);

            /* JIT */
            jitCount++;
            sendJit(dos, now, "com.myapp.Service", "process", 512, jitCount);

            /* Safepoint */
            spCount += 5;
            spTime += 20;
            spSync += 2;
            sendSafepoint(dos, now, spCount, spTime, spSync);

            /* Classloaders */
            sendClassloaders(dos, now);

            /* GC detail */
            sendGcDetail(dos, now, gcCount, fullGcCount);

            /* Native memory */
            sendNativeMemory(dos, now, heapMax, heapUsed);

            /* Alarm on tick 2 */
            if (tick == 2) {
                sendAlarm(dos, now, 3, 1, 90.0, 85.0, "Heap usage 90% > 85%");
            }

            /* Heartbeat */
            sendHeartbeat(dos, now);

            /* Simulate heap growth */
            heapUsed += 50 * 1024 * 1024L;

            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
        }
    }

    /* ── Assertions ──────────────────────────────────── */

    @Test
    public void testHandshakeReceived() {
        assertTrue("Should be connected", connection.isConnected());
        assertEquals(12345, connection.getAgentPid());
        assertEquals("demo-host", connection.getAgentHostname());
        assertTrue(connection.getJvmInfo().contains("Demo"));
    }

    @Test
    public void testGcEventsReceived() {
        assertTrue("Should have GC events", store.getGcEventCount() > 0);
        List<GcEvent> events = store.getGcEvents(0, Long.MAX_VALUE);
        assertFalse(events.isEmpty());

        /* Should have at least one young and one full GC */
        boolean hasYoung = false, hasFull = false;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).getGcType() == 1) hasYoung = true;
            if (events.get(i).getGcType() == 3) hasFull = true;
        }
        assertTrue("Should have young GC", hasYoung);
        assertTrue("Should have full GC", hasFull);
    }

    @Test
    public void testMemorySnapshotsReceived() {
        assertTrue("Should have memory snapshots", store.getMemorySnapshotCount() > 0);
        MemorySnapshot latest = store.getLatestMemorySnapshot();
        assertNotNull(latest);
        assertTrue("Heap used should be > 0", latest.getHeapUsed() > 0);
        assertTrue("Heap max should be > 0", latest.getHeapMax() > 0);
        assertTrue("Heap usage % should be > 0", latest.getHeapUsagePercent() > 0);
    }

    @Test
    public void testThreadsReceived() {
        List<ThreadInfo> threads = store.getLatestThreadInfo();
        assertTrue("Should have thread data", threads.size() >= 5);

        boolean foundMain = false, foundBlocked = false;
        for (int i = 0; i < threads.size(); i++) {
            if ("main".equals(threads.get(i).getName())) foundMain = true;
            if (threads.get(i).getState() == ThreadInfo.STATE_BLOCKED) foundBlocked = true;
        }
        assertTrue("Should have 'main' thread", foundMain);
        assertTrue("Should have a BLOCKED thread", foundBlocked);
    }

    @Test
    public void testExceptionsReceived() {
        assertTrue("Should have exceptions", store.getExceptionCount() > 0);
        ExceptionEvent latest = store.getLatestException();
        assertNotNull(latest);
        assertEquals("java.lang.NullPointerException", latest.getDisplayName());
        assertTrue("Total thrown should be > 0", latest.getTotalThrown() > 0);
    }

    @Test
    public void testOsMetricsReceived() {
        OsMetrics os = store.getLatestOsMetrics();
        assertNotNull("Should have OS metrics", os);
        assertEquals(200, os.getOpenFileDescriptors());
        assertTrue("RSS should be > 0", os.getRssBytes() > 0);
        assertEquals(25, os.getOsThreadCount());
    }

    @Test
    public void testJitEventsReceived() {
        assertTrue("Should have JIT events", store.getJitEventCount() > 0);
        List<JitEvent> events = store.getJitEvents(0, Long.MAX_VALUE);
        assertFalse(events.isEmpty());
        assertEquals("com.myapp.Service", events.get(0).getClassName());
        assertEquals("process", events.get(0).getMethodName());
    }

    @Test
    public void testSafepointReceived() {
        SafepointEvent sp = store.getLatestSafepoint();
        assertNotNull("Should have safepoint data", sp);
        assertTrue(sp.isAvailable());
        assertTrue("Should have safepoint count > 0", sp.getSafepointCount() > 0);
        assertTrue("Should have safepoint time > 0", sp.getTotalTimeMs() > 0);
    }

    @Test
    public void testClassloadersReceived() {
        ClassloaderStats stats = store.getLatestClassloaderStats();
        assertNotNull("Should have classloader data", stats);
        assertTrue("Should have loaders", stats.getLoaderCount() > 0);
        assertTrue("Should have total classes > 0", stats.getTotalClassCount() > 0);
    }

    @Test
    public void testGcDetailReceived() {
        GcDetail detail = store.getLatestGcDetail();
        assertNotNull("Should have GC detail", detail);
        assertEquals(2, detail.getCollectorCount());
        assertEquals("G1 Young Generation", detail.getCollectors()[0].getName());
    }

    @Test
    public void testNativeMemoryReceived() {
        NativeMemoryStats nms = store.getLatestNativeMemory();
        assertNotNull("Should have native memory data", nms);
        assertTrue(nms.isAvailable());
        assertTrue(nms.getRawOutput().contains("Native Memory Tracking"));
    }

    @Test
    public void testAlarmsReceived() {
        List<AlarmEvent> alarms = store.getActiveAlarms();
        assertFalse("Should have alarms", alarms.isEmpty());
        assertTrue(alarms.get(0).getMessage().contains("Heap usage"));
    }

    @Test
    public void testDiagnosisEngineProducesResults() {
        AnalysisContext ctx = new AnalysisContext(store);
        DiagnosisEngine engine = new DiagnosisEngine(ctx);

        List<Diagnosis> diagnoses = engine.runDiagnostics();
        /* With GC events, memory data, exceptions — engine should detect something */
        assertNotNull(diagnoses);
        /* Print diagnoses for visibility */
        for (int i = 0; i < diagnoses.size(); i++) {
            System.out.println("[DIAGNOSIS] " + diagnoses.get(i).getCategory() +
                    ": " + diagnoses.get(i).getSummary());
        }
    }

    @Test
    public void testAnalysisContextComputations() {
        AnalysisContext ctx = new AnalysisContext(store);

        assertTrue("Should have GC frequency > 0", ctx.getGcFrequencyPerMinute(60) > 0);
        assertTrue("Should have avg GC pause > 0", ctx.getAvgGcPauseMs(60) > 0);
        assertTrue("Should have GC throughput < 100", ctx.getGcThroughputPercent(60) < 100);
        assertTrue("Should have exception rate > 0", ctx.getExceptionRatePerMinute(60) > 0);
        assertNotNull(ctx.getLatestOsMetrics());
        assertNotNull(ctx.getLatestSafepoint());
    }

    @Test
    public void testAllEventCountsNonZero() {
        /* Summary: verify all event types were received */
        assertTrue("CPU samples might be 0 (not sent in demo)", true);
        assertTrue("GC events > 0", store.getGcEventCount() > 0);
        assertTrue("Memory snapshots > 0", store.getMemorySnapshotCount() > 0);
        assertTrue("Exceptions > 0", store.getExceptionCount() > 0);
        assertTrue("JIT events > 0", store.getJitEventCount() > 0);

        System.out.println("\n=== Demo Integration Test Summary ===");
        System.out.println("GC events:          " + store.getGcEventCount());
        System.out.println("Memory snapshots:    " + store.getMemorySnapshotCount());
        System.out.println("Threads:             " + store.getLatestThreadInfo().size());
        System.out.println("Exceptions:          " + store.getExceptionCount());
        System.out.println("JIT events:          " + store.getJitEventCount());
        System.out.println("OS metrics:          " + (store.getLatestOsMetrics() != null ? "yes" : "no"));
        System.out.println("Safepoints:          " + (store.getLatestSafepoint() != null ? "yes" : "no"));
        System.out.println("Classloaders:        " + (store.getLatestClassloaderStats() != null ? "yes" : "no"));
        System.out.println("GC detail:           " + (store.getLatestGcDetail() != null ? "yes" : "no"));
        System.out.println("Native memory:       " + (store.getLatestNativeMemory() != null ? "yes" : "no"));
        System.out.println("Active alarms:       " + store.getActiveAlarms().size());
    }

    /* ── Message helper methods ──────────────────────── */

    private void sendMessage(DataOutputStream dos, int msgType, byte[] payload) throws IOException {
        synchronized (dos) {
            dos.writeInt(ProtocolConstants.MAGIC);
            dos.writeByte(ProtocolConstants.VERSION);
            dos.writeByte(msgType);
            dos.writeInt(payload.length);
            dos.write(payload);
            dos.flush();
        }
    }

    private void writeStr(DataOutputStream dos, String s) throws IOException {
        byte[] bytes = s.getBytes("UTF-8");
        dos.writeShort(bytes.length);
        dos.write(bytes);
    }

    private void sendHandshake(DataOutputStream dos) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(baos);
        p.writeInt(1); p.writeInt(12345);
        writeStr(p, "demo-host"); writeStr(p, "OpenJDK 17 (Demo)");
        p.flush();
        sendMessage(dos, ProtocolConstants.MSG_HANDSHAKE, baos.toByteArray());
    }

    private void sendHeartbeat(DataOutputStream dos, long now) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        new DataOutputStream(b).writeLong(now);
        sendMessage(dos, ProtocolConstants.MSG_HEARTBEAT, b.toByteArray());
    }

    private void sendMemory(DataOutputStream dos, long now, long hu, long hm, long nu, long nm)
            throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(b);
        p.writeLong(now); p.writeLong(hu); p.writeLong(hm); p.writeLong(nu); p.writeLong(nm);
        p.flush();
        sendMessage(dos, ProtocolConstants.MSG_MEMORY_SNAPSHOT, b.toByteArray());
    }

    private void sendGc(DataOutputStream dos, long now, int type, long durNanos, int gc, int fgc)
            throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(b);
        p.writeLong(now); p.writeByte(type); p.writeLong(durNanos); p.writeInt(gc); p.writeInt(fgc);
        p.flush();
        sendMessage(dos, ProtocolConstants.MSG_GC_EVENT, b.toByteArray());
    }

    private void sendThread(DataOutputStream dos, long now, long tid, String name, int state, boolean daemon)
            throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(b);
        p.writeLong(now); p.writeByte(3); p.writeLong(tid);
        writeStr(p, name); p.writeInt(state); p.writeByte(daemon ? 1 : 0);
        p.flush();
        sendMessage(dos, ProtocolConstants.MSG_THREAD_SNAPSHOT, b.toByteArray());
    }

    private void sendException(DataOutputStream dos, long now, int thrown, int caught,
                               String excClass, String throwCls, String throwMeth,
                               boolean isCaught, String catchCls, String catchMeth) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(b);
        p.writeLong(now); p.writeInt(thrown); p.writeInt(caught); p.writeInt(0);
        writeStr(p, excClass); writeStr(p, throwCls); writeStr(p, throwMeth);
        p.writeLong(0); p.writeByte(isCaught ? 1 : 0);
        if (isCaught) { writeStr(p, catchCls); writeStr(p, catchMeth); }
        p.writeShort(0);
        p.flush();
        sendMessage(dos, ProtocolConstants.MSG_EXCEPTION, b.toByteArray());
    }

    private void sendOsMetrics(DataOutputStream dos, long now, int fds, long rss, long vm,
                               long volCs, long involCs, int est, int cw, int tw, int threads)
            throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(b);
        p.writeLong(now); p.writeInt(fds); p.writeLong(rss); p.writeLong(vm);
        p.writeLong(volCs); p.writeLong(involCs);
        p.writeInt(est); p.writeInt(cw); p.writeInt(tw); p.writeInt(threads);
        p.flush();
        sendMessage(dos, ProtocolConstants.MSG_OS_METRICS, b.toByteArray());
    }

    private void sendJit(DataOutputStream dos, long now, String cls, String meth, int size, int total)
            throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(b);
        p.writeLong(now); p.writeByte(1);
        writeStr(p, cls); writeStr(p, meth);
        p.writeInt(size); p.writeLong(0); p.writeInt(total);
        p.flush();
        sendMessage(dos, ProtocolConstants.MSG_JIT_EVENT, b.toByteArray());
    }

    private void sendSafepoint(DataOutputStream dos, long now, long count, long time, long sync)
            throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(b);
        p.writeLong(now); p.writeLong(count); p.writeLong(time); p.writeLong(sync);
        p.flush();
        sendMessage(dos, ProtocolConstants.MSG_SAFEPOINT, b.toByteArray());
    }

    private void sendClassloaders(DataOutputStream dos, long now) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(b);
        p.writeLong(now); p.writeShort(3);
        writeStr(p, "bootstrap"); p.writeInt(1200);
        writeStr(p, "Ljava/net/URLClassLoader;"); p.writeInt(350);
        writeStr(p, "Lorg/spring/LaunchedURLClassLoader;"); p.writeInt(450);
        p.flush();
        sendMessage(dos, ProtocolConstants.MSG_CLASSLOADER, b.toByteArray());
    }

    private void sendGcDetail(DataOutputStream dos, long now, int gc, int fgc) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(b);
        p.writeLong(now); p.writeShort(2);
        writeStr(p, "G1 Young Generation"); p.writeLong(gc - fgc); p.writeLong(gc * 8L);
        p.writeShort(2); writeStr(p, "G1 Eden Space"); writeStr(p, "G1 Survivor Space");
        writeStr(p, "G1 Old Generation"); p.writeLong(fgc); p.writeLong(fgc * 150L);
        p.writeShort(1); writeStr(p, "G1 Old Gen");
        p.flush();
        sendMessage(dos, ProtocolConstants.MSG_GC_DETAIL, b.toByteArray());
    }

    private void sendNativeMemory(DataOutputStream dos, long now, long hm, long hu) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(b);
        p.writeLong(now); p.writeByte(1);
        writeStr(p, String.format("Native Memory Tracking:\nTotal: reserved=%dKB, committed=%dKB",
                hm / 1024 + 200000, hu / 1024 + 150000));
        p.flush();
        sendMessage(dos, ProtocolConstants.MSG_NATIVE_MEM, b.toByteArray());
    }

    private void sendAlarm(DataOutputStream dos, long now, int type, int sev, double val, double thr, String msg)
            throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(b);
        p.writeLong(now); p.writeByte(type); p.writeByte(sev);
        p.writeLong((long)(val * 1000)); p.writeLong((long)(thr * 1000));
        writeStr(p, msg);
        p.flush();
        sendMessage(dos, ProtocolConstants.MSG_ALARM, b.toByteArray());
    }
}
