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
 * End-to-end test: simulates a native agent listening on a port (server).
 * The Java AgentConnection connects to it as a client.
 * We verify the collector correctly decodes messages sent by the fake agent.
 */
public class EndToEndTest {

    private static final int TEST_PORT = 19876;
    private ServerSocket fakeAgentServer;
    private Socket fakeAgentSocket;
    private InMemoryEventStore store;
    private AgentConnection connection;

    @Before
    public void setUp() throws Exception {
        store = new InMemoryEventStore();
        /* Start a fake "agent" server */
        fakeAgentServer = new ServerSocket(TEST_PORT);

        /* Connect the client in a background thread (connect blocks until accepted) */
        final AgentConnection[] holder = new AgentConnection[1];
        Thread connectThread = new Thread(new Runnable() {
            public void run() {
                try {
                    holder[0] = new AgentConnection("127.0.0.1", TEST_PORT, store);
                    holder[0].connect();
                } catch (IOException e) {
                    /* test will fail */
                }
            }
        });
        connectThread.start();

        /* Accept the client connection */
        fakeAgentSocket = fakeAgentServer.accept();
        connectThread.join(2000);
        connection = holder[0];

        /* Send handshake from "agent" */
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

    private byte[] buildGcEventPayload(long timestamp, int gcType, long durationNanos,
                                        int gcCount, int fullGcCount) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeLong(timestamp);
        dos.writeByte(gcType);
        dos.writeLong(durationNanos);
        dos.writeInt(gcCount);
        dos.writeInt(fullGcCount);
        dos.flush();
        return baos.toByteArray();
    }

    private byte[] buildMemoryPayload(long timestamp, long heapUsed, long heapMax,
                                       long nonHeapUsed, long nonHeapMax) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeLong(timestamp);
        dos.writeLong(heapUsed);
        dos.writeLong(heapMax);
        dos.writeLong(nonHeapUsed);
        dos.writeLong(nonHeapMax);
        dos.flush();
        return baos.toByteArray();
    }

    private byte[] buildAlarmPayload(long timestamp, int type, int severity,
                                      long valueX1000, long thresholdX1000,
                                      String message) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeLong(timestamp);
        dos.writeByte(type);
        dos.writeByte(severity);
        dos.writeLong(valueX1000);
        dos.writeLong(thresholdX1000);
        byte[] msgBytes = message.getBytes("UTF-8");
        dos.writeShort(msgBytes.length);
        dos.write(msgBytes);
        dos.flush();
        return baos.toByteArray();
    }

    private byte[] buildThreadPayload(long timestamp, int eventType, long threadId,
                                       String name, int state, boolean daemon) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeLong(timestamp);
        dos.writeByte(eventType);
        dos.writeLong(threadId);
        byte[] nameBytes = name.getBytes("UTF-8");
        dos.writeShort(nameBytes.length);
        dos.write(nameBytes);
        dos.writeInt(state);
        dos.writeByte(daemon ? 1 : 0);
        dos.flush();
        return baos.toByteArray();
    }

    @Test
    public void testHandshake() throws Exception {
        assertTrue("Should be connected", connection.isConnected());
        assertEquals(12345, connection.getAgentPid());
        assertEquals("test-host", connection.getAgentHostname());
        assertEquals("JDK 8", connection.getJvmInfo());
    }

    @Test
    public void testGcEventFlow() throws Exception {
        long now = System.currentTimeMillis();
        sendMessage(fakeAgentSocket.getOutputStream(), ProtocolConstants.MSG_GC_EVENT,
                buildGcEventPayload(now, 3, 150000000L, 42, 5));
        Thread.sleep(300);

        List<GcEvent> events = store.getGcEvents(now - 1000, now + 1000);
        assertEquals(1, events.size());
        assertEquals(3, events.get(0).getGcType());
        assertEquals(150000000L, events.get(0).getDurationNanos());
        assertEquals(42, events.get(0).getGcCount());
        assertEquals(5, events.get(0).getFullGcCount());
    }

    @Test
    public void testMemorySnapshotFlow() throws Exception {
        long now = System.currentTimeMillis();
        long heapUsed = 512 * 1024 * 1024L;
        long heapMax = 1024 * 1024 * 1024L;
        sendMessage(fakeAgentSocket.getOutputStream(), ProtocolConstants.MSG_MEMORY_SNAPSHOT,
                buildMemoryPayload(now, heapUsed, heapMax, 64 * 1024 * 1024L, 256 * 1024 * 1024L));
        Thread.sleep(300);

        MemorySnapshot latest = store.getLatestMemorySnapshot();
        assertNotNull(latest);
        assertEquals(heapUsed, latest.getHeapUsed());
        assertEquals(heapMax, latest.getHeapMax());
        assertEquals(50.0, latest.getHeapUsagePercent(), 0.1);
    }

    @Test
    public void testAlarmFlow() throws Exception {
        long now = System.currentTimeMillis();
        sendMessage(fakeAgentSocket.getOutputStream(), ProtocolConstants.MSG_ALARM,
                buildAlarmPayload(now, 2, 1, 250000L, 200000L, "GC pause > 200ms"));
        Thread.sleep(300);

        List<AlarmEvent> alarms = store.getActiveAlarms();
        assertEquals(1, alarms.size());
        assertEquals("GC pause > 200ms", alarms.get(0).getMessage());
    }

    @Test
    public void testThreadSnapshotFlow() throws Exception {
        long now = System.currentTimeMillis();
        sendMessage(fakeAgentSocket.getOutputStream(), ProtocolConstants.MSG_THREAD_SNAPSHOT,
                buildThreadPayload(now, 3, 42L, "http-worker-1", 2, false));
        Thread.sleep(300);

        List<ThreadInfo> threads = store.getLatestThreadInfo();
        assertEquals(1, threads.size());
        assertEquals("http-worker-1", threads.get(0).getName());
        assertEquals(2, threads.get(0).getState());
    }

    @Test
    public void testMultipleMessagesInSequence() throws Exception {
        OutputStream out = fakeAgentSocket.getOutputStream();
        long now = System.currentTimeMillis();

        for (int i = 0; i < 5; i++) {
            sendMessage(out, ProtocolConstants.MSG_GC_EVENT,
                    buildGcEventPayload(now + i, 1, 10000000L * (i + 1), i + 1, 0));
        }
        for (int i = 0; i < 3; i++) {
            sendMessage(out, ProtocolConstants.MSG_MEMORY_SNAPSHOT,
                    buildMemoryPayload(now + i, 100 * 1024 * 1024L * (i + 1),
                            2 * 1024 * 1024 * 1024L, 0, 0));
        }
        Thread.sleep(500);

        assertEquals(5, store.getGcEventCount());
        assertEquals(3, store.getMemorySnapshotCount());
    }

    @Test
    public void testHeartbeatDoesNotStore() throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        new DataOutputStream(baos).writeLong(System.currentTimeMillis());
        sendMessage(fakeAgentSocket.getOutputStream(), ProtocolConstants.MSG_HEARTBEAT,
                baos.toByteArray());
        Thread.sleep(200);

        assertEquals(0, store.getGcEventCount());
        assertEquals(0, store.getCpuSampleCount());
        assertEquals(0, store.getMemorySnapshotCount());
    }

    @Test
    public void testClientSendsCommand() throws Exception {
        /* Client sends enable command to agent */
        connection.enableModule("alloc", 2, null, 300);

        /* Read what the "agent" received */
        Thread.sleep(200);
        java.io.InputStream in = fakeAgentSocket.getInputStream();
        assertTrue("Agent should receive command data", in.available() > 0);

        ProtocolDecoder decoder = new ProtocolDecoder(in);
        EventMessage msg = decoder.readMessage();
        assertEquals(MessageType.COMMAND, msg.getType());
        assertEquals(ProtocolConstants.CMD_ENABLE_MODULE, msg.readU8(0));
    }
}
