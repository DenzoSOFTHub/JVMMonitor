package it.denzosoft.jvmmonitor.integration;

import it.denzosoft.jvmmonitor.model.*;
import it.denzosoft.jvmmonitor.protocol.*;
import it.denzosoft.jvmmonitor.storage.InMemoryEventStore;
import it.denzosoft.jvmmonitor.net.AgentConnection;
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

/**
 * Tests for new message type handlers in AgentConnection.
 * Simulates an agent sending various message types and verifies
 * the collector correctly decodes and stores them.
 */
public class NewMessageTypesTest {

    private static final int TEST_PORT = 19877;
    private ServerSocket fakeAgentServer;
    private Socket fakeAgentSocket;
    private InMemoryEventStore store;
    private AgentConnection connection;

    @Before
    public void setUp() throws Exception {
        store = new InMemoryEventStore();
        fakeAgentServer = new ServerSocket(TEST_PORT);

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
        fakeAgentSocket = fakeAgentServer.accept();
        connectThread.join(2000);
        connection = holder[0];

        /* Send handshake */
        sendMessage(fakeAgentSocket.getOutputStream(), ProtocolConstants.MSG_HANDSHAKE,
                buildHandshakePayload());
        Thread.sleep(300);
    }

    @After
    public void tearDown() {
        if (connection != null) connection.disconnect();
        try { if (fakeAgentSocket != null) fakeAgentSocket.close(); } catch (IOException e) { }
        try { if (fakeAgentServer != null) fakeAgentServer.close(); } catch (IOException e) { }
    }

    private void sendMessage(OutputStream out, int msgType, byte[] payload) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(ProtocolConstants.MAGIC);
        dos.writeByte(ProtocolConstants.VERSION);
        dos.writeByte(msgType);
        dos.writeInt(payload.length);
        dos.write(payload);
        dos.flush();
    }

    private byte[] buildHandshakePayload() throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(1);
        dos.writeInt(12345);
        dos.writeShort(9);
        dos.writeBytes("test-host");
        dos.writeShort(5);
        dos.writeBytes("JDK 8");
        dos.flush();
        return baos.toByteArray();
    }

    @Test
    public void testOsMetricsFlow() throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeLong(System.currentTimeMillis()); /* timestamp */
        dos.writeInt(256);                          /* fd count */
        dos.writeLong(512 * 1024 * 1024L);         /* rss */
        dos.writeLong(2048L * 1024 * 1024);         /* vmsize */
        dos.writeLong(1000);                         /* vol cs */
        dos.writeLong(50);                           /* invol cs */
        dos.writeInt(42);                            /* tcp est */
        dos.writeInt(3);                             /* tcp cw */
        dos.writeInt(7);                             /* tcp tw */
        dos.writeInt(120);                           /* os threads */
        dos.flush();

        sendMessage(fakeAgentSocket.getOutputStream(), ProtocolConstants.MSG_OS_METRICS,
                baos.toByteArray());
        Thread.sleep(300);

        OsMetrics os = store.getLatestOsMetrics();
        assertNotNull(os);
        assertEquals(256, os.getOpenFileDescriptors());
        assertEquals(512.0, os.getRssMB(), 0.1);
        assertEquals(42, os.getTcpEstablished());
        assertEquals(120, os.getOsThreadCount());
    }

    @Test
    public void testExceptionFlow() throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeLong(System.currentTimeMillis()); /* timestamp */
        dos.writeInt(10);                           /* total thrown */
        dos.writeInt(8);                            /* total caught */
        dos.writeInt(2);                            /* total dropped */
        writeString(dos, "Ljava/lang/NullPointerException;");
        writeString(dos, "com.example.Foo");
        writeString(dos, "bar");
        dos.writeLong(42);                          /* throw location */
        dos.writeByte(1);                           /* caught = true */
        writeString(dos, "com.example.Handler");
        writeString(dos, "handle");
        dos.writeShort(0);                          /* 0 stack frames */
        dos.flush();

        sendMessage(fakeAgentSocket.getOutputStream(), ProtocolConstants.MSG_EXCEPTION,
                baos.toByteArray());
        Thread.sleep(300);

        assertEquals(1, store.getExceptionCount());
        ExceptionEvent exc = store.getLatestException();
        assertNotNull(exc);
        assertEquals("java.lang.NullPointerException", exc.getDisplayName());
        assertEquals(10, exc.getTotalThrown());
        assertTrue(exc.isCaught());
        assertEquals("com.example.Handler", exc.getCatchClass());
    }

    @Test
    public void testJitCompiledFlow() throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeLong(System.currentTimeMillis()); /* timestamp */
        dos.writeByte(1);                           /* COMPILED */
        writeString(dos, "com.example.Foo");
        writeString(dos, "bar");
        dos.writeInt(1024);                         /* code size */
        dos.writeLong(0xDEADBEEFL);                 /* code addr */
        dos.writeInt(42);                           /* total compiled */
        dos.flush();

        sendMessage(fakeAgentSocket.getOutputStream(), ProtocolConstants.MSG_JIT_EVENT,
                baos.toByteArray());
        Thread.sleep(300);

        assertEquals(1, store.getJitEventCount());
        long now = System.currentTimeMillis();
        List<JitEvent> events = store.getJitEvents(now - 5000, now + 5000);
        assertEquals(1, events.size());
        assertEquals("com.example.Foo", events.get(0).getClassName());
        assertEquals(1024, events.get(0).getCodeSize());
    }

    @Test
    public void testClassHistogramFlow() throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeLong(System.currentTimeMillis()); /* timestamp */
        dos.writeLong(150000000L);                  /* elapsed nanos */
        dos.writeShort(2);                          /* entry count */
        /* Entry 1 */
        writeString(dos, "[B");
        dos.writeInt(50000);
        dos.writeLong(10 * 1024 * 1024L);
        /* Entry 2 */
        writeString(dos, "Ljava/lang/String;");
        dos.writeInt(30000);
        dos.writeLong(5 * 1024 * 1024L);
        dos.flush();

        sendMessage(fakeAgentSocket.getOutputStream(), ProtocolConstants.MSG_CLASS_HISTO,
                baos.toByteArray());
        Thread.sleep(300);

        ClassHistogram histo = store.getLatestClassHistogram();
        assertNotNull(histo);
        assertEquals(2, histo.getEntryCount());
        assertEquals(150.0, histo.getElapsedMs(), 0.1);
        assertEquals("[B", histo.getEntries()[0].getClassName());
        assertEquals(50000, histo.getEntries()[0].getInstanceCount());
    }

    @Test
    public void testSafepointFlow() throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeLong(System.currentTimeMillis()); /* timestamp */
        dos.writeLong(1000);                        /* count */
        dos.writeLong(5000);                        /* total time ms */
        dos.writeLong(500);                         /* sync time ms */
        dos.flush();

        sendMessage(fakeAgentSocket.getOutputStream(), ProtocolConstants.MSG_SAFEPOINT,
                baos.toByteArray());
        Thread.sleep(300);

        SafepointEvent sp = store.getLatestSafepoint();
        assertNotNull(sp);
        assertTrue(sp.isAvailable());
        assertEquals(1000, sp.getSafepointCount());
        assertEquals(5000, sp.getTotalTimeMs());
    }

    @Test
    public void testNativeMemoryFlow() throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeLong(System.currentTimeMillis()); /* timestamp */
        dos.writeByte(1);                           /* available */
        writeString(dos, "Total: reserved=1234KB");
        dos.flush();

        sendMessage(fakeAgentSocket.getOutputStream(), ProtocolConstants.MSG_NATIVE_MEM,
                baos.toByteArray());
        Thread.sleep(300);

        NativeMemoryStats nms = store.getLatestNativeMemory();
        assertNotNull(nms);
        assertTrue(nms.isAvailable());
        assertTrue(nms.getRawOutput().contains("reserved=1234KB"));
    }

    @Test
    public void testGcDetailFlow() throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeLong(System.currentTimeMillis()); /* timestamp */
        dos.writeShort(1);                          /* 1 collector */
        writeString(dos, "PS Scavenge");
        dos.writeLong(100);                         /* collection count */
        dos.writeLong(5000);                        /* collection time ms */
        dos.writeShort(2);                          /* 2 pools */
        writeString(dos, "PS Eden Space");
        writeString(dos, "PS Survivor");
        dos.flush();

        sendMessage(fakeAgentSocket.getOutputStream(), ProtocolConstants.MSG_GC_DETAIL,
                baos.toByteArray());
        Thread.sleep(300);

        GcDetail detail = store.getLatestGcDetail();
        assertNotNull(detail);
        assertEquals(1, detail.getCollectorCount());
        assertEquals("PS Scavenge", detail.getCollectors()[0].getName());
        assertEquals(100, detail.getCollectors()[0].getCollectionCount());
        assertEquals(2, detail.getCollectors()[0].getMemoryPools().length);
    }

    @Test
    public void testClassloaderFlow() throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeLong(System.currentTimeMillis()); /* timestamp */
        dos.writeShort(2);                          /* 2 loaders */
        writeString(dos, "bootstrap");
        dos.writeInt(1500);
        writeString(dos, "Ljava/net/URLClassLoader;");
        dos.writeInt(200);
        dos.flush();

        sendMessage(fakeAgentSocket.getOutputStream(), ProtocolConstants.MSG_CLASSLOADER,
                baos.toByteArray());
        Thread.sleep(300);

        ClassloaderStats stats = store.getLatestClassloaderStats();
        assertNotNull(stats);
        assertEquals(2, stats.getLoaderCount());
        assertEquals(1700, stats.getTotalClassCount());
    }

    @Test
    public void testStringTableFlow() throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeLong(System.currentTimeMillis()); /* timestamp */
        dos.writeByte(1);                           /* available */
        writeString(dos, "StringTable: buckets=60013");
        dos.flush();

        sendMessage(fakeAgentSocket.getOutputStream(), ProtocolConstants.MSG_STRING_TABLE,
                baos.toByteArray());
        Thread.sleep(300);

        StringTableStats sts = store.getLatestStringTableStats();
        assertNotNull(sts);
        assertTrue(sts.isAvailable());
        assertTrue(sts.getRawOutput().contains("60013"));
    }

    @Test
    public void testModuleEventFlow() throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeLong(System.currentTimeMillis()); /* timestamp */
        writeString(dos, "exceptions");
        dos.writeByte(0);                           /* old level */
        dos.writeByte(1);                           /* new level */
        dos.writeByte(3);                           /* max level */
        dos.flush();

        sendMessage(fakeAgentSocket.getOutputStream(), ProtocolConstants.MSG_MODULE_EVENT,
                baos.toByteArray());
        Thread.sleep(300);

        List<ModuleStatus> statuses = connection.getModuleStatuses();
        assertFalse(statuses.isEmpty());
        ModuleStatus ms = statuses.get(0);
        assertEquals("exceptions", ms.getName());
        assertEquals(1, ms.getCurrentLevel());
        assertEquals(3, ms.getMaxLevel());
    }

    private void writeString(DataOutputStream dos, String s) throws IOException {
        byte[] bytes = s.getBytes("UTF-8");
        dos.writeShort(bytes.length);
        dos.write(bytes);
    }
}
