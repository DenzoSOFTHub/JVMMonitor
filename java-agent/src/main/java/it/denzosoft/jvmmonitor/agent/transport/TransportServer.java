package it.denzosoft.jvmmonitor.agent.transport;

import it.denzosoft.jvmmonitor.agent.AgentConfig;
import it.denzosoft.jvmmonitor.agent.command.CommandDispatcher;
import it.denzosoft.jvmmonitor.agent.module.ModuleRegistry;
import it.denzosoft.jvmmonitor.agent.util.AgentLogger;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * TCP server mirroring the C agent's transport.
 * Accepts one client at a time, sends queued messages, receives commands.
 */
public class TransportServer {

    private static final int QUEUE_CAPACITY = 10000;
    private static final long HEARTBEAT_INTERVAL_MS = 5000;
    private static final long SEND_POLL_MS = 10;

    private final AgentConfig config;
    private final MessageQueue queue;
    private volatile boolean running;
    private volatile Socket clientSocket;
    private ModuleRegistry modules;
    private Thread acceptThread;

    public TransportServer(AgentConfig config) {
        this.config = config;
        this.queue = new MessageQueue(QUEUE_CAPACITY);
    }

    public MessageQueue getMessageQueue() {
        return queue;
    }

    public void setModuleRegistry(ModuleRegistry modules) {
        this.modules = modules;
    }

    public void start() {
        running = true;
        acceptThread = new Thread(new Runnable() {
            public void run() {
                acceptLoop();
            }
        }, "jvmmonitor-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void stop() {
        running = false;
        try {
            if (clientSocket != null) clientSocket.close();
        } catch (IOException e) { /* ignore */ }
        if (acceptThread != null) acceptThread.interrupt();
    }

    private void acceptLoop() {
        ServerSocket server = null;
        try {
            server = new ServerSocket(config.getPort());
            server.setSoTimeout(2000);
            AgentLogger.info("Listening on port " + config.getPort());

            while (running) {
                AgentLogger.info("Waiting for collector connection...");
                Socket socket;
                try {
                    socket = server.accept();
                } catch (SocketTimeoutException e) {
                    continue; /* re-check running flag */
                }
                AgentLogger.info("Collector connected from " + socket.getRemoteSocketAddress());

                /* Close previous client if any */
                if (clientSocket != null) {
                    try { clientSocket.close(); } catch (IOException e) { /* ignore */ }
                }
                clientSocket = socket;

                handleClient(socket);
            }
        } catch (IOException e) {
            if (running) {
                AgentLogger.error("Accept failed: " + e.getMessage());
            }
        } finally {
            if (server != null) {
                try { server.close(); } catch (IOException e) { /* ignore */ }
            }
        }
    }

    private void handleClient(final Socket socket) {
        /* Send handshake immediately */
        try {
            sendHandshake(socket.getOutputStream());
        } catch (IOException e) {
            AgentLogger.error("Handshake failed: " + e.getMessage());
            return;
        }

        /* Send thread */
        Thread sendThread = new Thread(new Runnable() {
            public void run() {
                sendLoop(socket);
            }
        }, "jvmmonitor-send");
        sendThread.setDaemon(true);
        sendThread.start();

        /* Recv thread (commands from collector) */
        Thread recvThread = new Thread(new Runnable() {
            public void run() {
                recvLoop(socket);
            }
        }, "jvmmonitor-recv");
        recvThread.setDaemon(true);
        recvThread.start();

        /* Wait for recv thread — exits when client disconnects or protocol error.
         * Then close socket to unblock send thread, and join it with timeout. */
        try {
            recvThread.join();
        } catch (InterruptedException e) { /* shutdown */ }
        try { socket.close(); } catch (IOException e) { /* ignore */ }
        try {
            sendThread.join(5000);
        } catch (InterruptedException e) { /* ignore */ }

        AgentLogger.info("Collector disconnected");
    }

    private void sendHandshake(OutputStream out) throws IOException {
        String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
        int pid = 0;
        try {
            pid = Integer.parseInt(runtimeName.substring(0, runtimeName.indexOf('@')));
        } catch (Exception e) { /* pid unknown */ }

        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            hostname = "unknown";
        }

        String jvmInfo = System.getProperty("java.vm.name", "Java") + " " +
                System.getProperty("java.version", "?") + " (Java Agent)";

        byte[] payload = ProtocolWriter.payload()
                .writeU32(1)          /* agent version */
                .writeU32(pid)        /* PID */
                .writeString(hostname)
                .writeString(jvmInfo)
                .build();

        out.write(ProtocolWriter.buildMessage(0x01, payload)); /* MSG_HANDSHAKE */
        out.flush();
        AgentLogger.info("Handshake sent (PID=" + pid + ", host=" + hostname + ")");
    }

    private void sendLoop(Socket socket) {
        long lastHeartbeat = System.currentTimeMillis();
        try {
            OutputStream out = socket.getOutputStream();
            while (running && !socket.isClosed()) {
                byte[] msg = queue.dequeue();
                if (msg != null) {
                    out.write(msg);
                    out.flush();
                    lastHeartbeat = System.currentTimeMillis();
                } else {
                    /* Check if heartbeat needed */
                    long now = System.currentTimeMillis();
                    if (now - lastHeartbeat > HEARTBEAT_INTERVAL_MS) {
                        byte[] hb = ProtocolWriter.payload()
                                .writeU64(now)
                                .buildMessage(0x02); /* MSG_HEARTBEAT */
                        out.write(hb);
                        out.flush();
                        lastHeartbeat = now;
                    }
                    Thread.sleep(SEND_POLL_MS);
                }
            }
        } catch (IOException e) {
            if (running) AgentLogger.debug("Send error: " + e.getMessage());
        } catch (InterruptedException e) {
            /* shutdown */
        }
    }

    private void recvLoop(Socket socket) {
        try {
            DataInputStream in = new DataInputStream(
                    new BufferedInputStream(socket.getInputStream()));

            while (running && !socket.isClosed()) {
                /* Read header */
                int magic = in.readInt();
                if (magic != ProtocolWriter.MAGIC) {
                    AgentLogger.error("Bad magic: 0x" + Integer.toHexString(magic));
                    break;
                }
                int version = in.readUnsignedByte();
                int msgType = in.readUnsignedByte();
                int payloadLen = in.readInt();

                if (payloadLen < 0 || payloadLen > ProtocolWriter.MAX_PAYLOAD) {
                    AgentLogger.error("Bad payload length: " + payloadLen);
                    break;
                }

                byte[] payload = new byte[payloadLen];
                in.readFully(payload);

                /* Dispatch command */
                if (modules != null) {
                    CommandDispatcher.dispatch(msgType, payload, modules, queue);
                }
            }
        } catch (IOException e) {
            if (running) AgentLogger.debug("Recv error: " + e.getMessage());
        }
    }
}
