package it.denzosoft.jvmmonitor.net;

import it.denzosoft.jvmmonitor.model.*;
import it.denzosoft.jvmmonitor.protocol.*;
import it.denzosoft.jvmmonitor.storage.EventStore;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Connects to a running JVMMonitor agent.
 * The agent is the TCP server (listens on a port).
 * This client connects, receives events, and sends commands.
 */
public class AgentConnection {

    private final String host;
    private final int port;
    private final EventStore store;
    private Socket socket;
    private OutputStream outputStream;
    private Thread readerThread;
    private volatile boolean running;
    private volatile boolean connected;

    /* Handshake info received from agent */
    private int agentVersion;
    private int agentPid;
    private String agentHostname = "";
    private String jvmInfo = "";

    /* Module status tracking */
    private final List<ModuleStatus> moduleStatuses =
            java.util.Collections.synchronizedList(new ArrayList<ModuleStatus>());

    /* Method name resolution cache: methodId -> {className, methodName}. Capped at 10000 entries. */
    private static final int MAX_METHOD_CACHE = 10000;
    private final Map<Long, String[]> methodNameCache = new ConcurrentHashMap<Long, String[]>();

    public AgentConnection(String host, int port, EventStore store) {
        this.host = host;
        this.port = port;
        this.store = store;
    }

    public AgentConnection(int port, EventStore store) {
        this("127.0.0.1", port, store);
    }

    public void connect() throws IOException {
        Socket s = new Socket(host, port);
        try {
            outputStream = s.getOutputStream();
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) { }
            throw e;
        }
        socket = s;
        running = true;

        readerThread = new Thread(new Runnable() {
            public void run() {
                readLoop();
            }
        }, "jvmmon-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    public void disconnect() {
        running = false;
        connected = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException e) { /* ignore */ }
    }

    public boolean isConnected() {
        return connected;
    }

    public int getAgentVersion() { return agentVersion; }
    public int getAgentPid() { return agentPid; }
    public String getAgentHostname() { return agentHostname; }
    public Map<Long, String[]> getMethodNameCache() { return methodNameCache; }
    public String getJvmInfo() { return jvmInfo; }
    public List<ModuleStatus> getModuleStatuses() { return moduleStatuses; }

    public void sendCommand(int cmdSubtype, byte[] payload) throws IOException {
        if (!connected || outputStream == null) {
            throw new IOException("Not connected to agent");
        }
        ProtocolEncoder.sendCommand(outputStream, cmdSubtype, payload);
    }

    public void enableModule(String name, int level, String target, int durationSec)
            throws IOException {
        byte[] data = ProtocolEncoder.encodeEnableModule(name, level, target, durationSec);
        sendCommand(ProtocolConstants.CMD_ENABLE_MODULE, data);
    }

    public void disableModule(String name) throws IOException {
        byte[] data = ProtocolEncoder.encodeDisableModule(name);
        sendCommand(ProtocolConstants.CMD_DISABLE_MODULE, data);
    }

    public void requestModuleList() throws IOException {
        byte[] data = ProtocolEncoder.encodeListModules();
        sendCommand(ProtocolConstants.CMD_LIST_MODULES, data);
    }

    private void readLoop() {
        try {
            ProtocolDecoder decoder = new ProtocolDecoder(socket.getInputStream());
            connected = true;

            while (running && connected) {
                EventMessage msg = decoder.readMessage();
                processMessage(msg);
            }
        } catch (IOException e) {
            /* Connection lost — log to stderr for visibility */
            if (running) {
                System.err.println("[JVMMonitor] Connection lost: " + e.getMessage());
            }
        } finally {
            connected = false;
            /* Close socket to release resources */
            try { if (socket != null) socket.close(); } catch (IOException ignored) { }
        }
    }

    private void processMessage(EventMessage msg) {
        switch (msg.getType()) {
            case HANDSHAKE:
                processHandshake(msg);
                break;
            case HEARTBEAT:
                break;
            case CPU_SAMPLE:
                processCpuSample(msg);
                break;
            case GC_EVENT:
                processGcEvent(msg);
                break;
            case THREAD_SNAPSHOT:
            case THREAD_EVENT:
                processThreadEvent(msg);
                break;
            case MEMORY_SNAPSHOT:
                processMemorySnapshot(msg);
                break;
            case ALARM:
                processAlarm(msg);
                break;
            case COMMAND_RESP:
                processCommandResp(msg);
                break;
            case MODULE_EVENT:
                processModuleEvent(msg);
                break;
            case EXCEPTION:
                processException(msg);
                break;
            case OS_METRICS:
                processOsMetrics(msg);
                break;
            case JIT_EVENT:
                processJitEvent(msg);
                break;
            case CLASS_HISTO:
                processClassHistogram(msg);
                break;
            case SAFEPOINT:
                processSafepoint(msg);
                break;
            case NATIVE_MEM:
                processNativeMemory(msg);
                break;
            case GC_DETAIL:
                processGcDetail(msg);
                break;
            case CLASSLOADER:
                processClassloader(msg);
                break;
            case STRING_TABLE:
                processStringTable(msg);
                break;
            case NETWORK:
                processNetwork(msg);
                break;
            case LOCK_EVENT:
                processLockEvent(msg);
                break;
            case CPU_USAGE:
                processCpuUsage(msg);
                break;
            case PROCESS_LIST:
                processProcessList(msg);
                break;
            case METHOD_INFO:
                processMethodInfo(msg);
                break;
            case ALLOC_SAMPLE:
                processAllocSample(msg);
                break;
            case INSTR_EVENT:
                processInstrEvent(msg);
                break;
            case QUEUE_STATS:
                processQueueStats(msg);
                break;
            case DEBUG_BP_HIT:
                processBreakpointHit(msg);
                break;
            case DEBUG_CLASS_BYTES:
                processDebugClassBytes(msg);
                break;
            case FIELD_WATCH:
            case JVM_CONFIG:
            case JMX_BROWSE:
            case THREAD_DUMP_MSG:
            case DEADLOCK_MSG:
            case GC_ROOT_MSG:
                /* These are handled via listeners set by DiagnosticToolsPanel */
                if (diagnosticListener != null) diagnosticListener.onDiagnosticMessage(msg.getType(), msg);
                break;
            case CLASS_INFO:
            case MONITOR_EVENT:
            case JMX_DATA:
            case FINALIZER:
            case THREAD_CPU:
            case JFR_EVENT:
                /* Reserved for future implementation */
                break;
            default:
                break;
        }
    }

    private void processHandshake(EventMessage msg) {
        if (msg.getPayloadLength() < 8) return;
        agentVersion = (int) msg.readU32(0);
        agentPid = (int) msg.readU32(4);
        int off = 8;
        agentHostname = msg.readString(off);
        off += msg.stringFieldLength(off);
        jvmInfo = msg.readString(off);
    }

    private void processCpuSample(EventMessage msg) {
        if (msg.getPayloadLength() < 18) return;
        long timestamp = msg.readU64(0);
        long threadId = msg.readU64(8);
        int frameCount = msg.readU16(16);
        int off = 18;

        CpuSample.StackFrame[] frames = new CpuSample.StackFrame[frameCount];
        for (int i = 0; i < frameCount && off + 12 <= msg.getPayloadLength(); i++) {
            long methodId = msg.readU64(off);
            int lineNo = msg.readI32(off + 8);
            frames[i] = new CpuSample.StackFrame(methodId, lineNo);
            /* Resolve names from cache */
            String[] names = methodNameCache.get(Long.valueOf(methodId));
            if (names != null) {
                frames[i].setClassName(names[0]);
                frames[i].setMethodName(names[1]);
            }
            off += 12;
        }
        store.storeCpuSample(new CpuSample(timestamp, threadId, frames));
    }

    private void processGcEvent(EventMessage msg) {
        if (msg.getPayloadLength() < 25) return;
        long timestamp = msg.readU64(0);
        int gcType = msg.readU8(8);
        long durationNanos = msg.readU64(9);
        int gcCount = msg.readI32(17);
        int fullGcCount = msg.readI32(21);
        int off = 25;

        /* Extended GC fields (optional) */
        long heapBefore = 0, heapAfter = 0;
        long edenBefore = 0, edenAfter = 0, oldBefore = 0, oldAfter = 0;
        String cause = "";
        double cpuAtGc = 0;

        if (off + 48 <= msg.getPayloadLength()) {
            heapBefore = msg.readI64(off); off += 8;
            heapAfter = msg.readI64(off); off += 8;
            edenBefore = msg.readI64(off); off += 8;
            edenAfter = msg.readI64(off); off += 8;
            oldBefore = msg.readI64(off); off += 8;
            oldAfter = msg.readI64(off); off += 8;
        }
        if (off + 2 <= msg.getPayloadLength()) {
            cause = msg.readString(off);
            off += msg.stringFieldLength(off);
        }
        if (off + 8 <= msg.getPayloadLength()) {
            cpuAtGc = msg.readI64(off) / 1000.0;
        }

        store.storeGcEvent(new GcEvent(timestamp, gcType, durationNanos, gcCount, fullGcCount,
                heapBefore, heapAfter, edenBefore, edenAfter, oldBefore, oldAfter, cause, cpuAtGc));
    }

    private void processThreadEvent(EventMessage msg) {
        if (msg.getPayloadLength() < 18) return;
        long timestamp = msg.readU64(0);
        long threadId = msg.readU64(9);
        int off = 17;
        String name = msg.readString(off);
        off += msg.stringFieldLength(off);
        int state = 0;
        boolean daemon = false;
        if (off + 4 <= msg.getPayloadLength()) {
            state = msg.readI32(off);
            off += 4;
        }
        if (off + 1 <= msg.getPayloadLength()) {
            daemon = msg.readU8(off) != 0;
        }
        store.storeThreadInfo(new ThreadInfo(timestamp, threadId, name, state, daemon));
    }

    private void processMemorySnapshot(EventMessage msg) {
        if (msg.getPayloadLength() < 40) return;
        long timestamp = msg.readU64(0);
        long heapUsed = msg.readI64(8);
        long heapMax = msg.readI64(16);
        long nonHeapUsed = msg.readI64(24);
        long nonHeapMax = msg.readI64(32);
        store.storeMemorySnapshot(new MemorySnapshot(
                timestamp, heapUsed, heapMax, nonHeapUsed, nonHeapMax));
    }

    private void processAlarm(EventMessage msg) {
        if (msg.getPayloadLength() < 26) return;
        long timestamp = msg.readU64(0);
        int alarmType = msg.readU8(8);
        int severity = msg.readU8(9);
        double value = msg.readU64(10) / 1000.0;
        double threshold = msg.readU64(18) / 1000.0;
        String message = msg.readString(26);
        store.storeAlarm(new AlarmEvent(
                timestamp, alarmType, severity, value, threshold, message));
    }

    private void processCommandResp(EventMessage msg) {
        if (msg.getPayloadLength() < 10) return;
        int off = 8; /* skip timestamp */
        int moduleCount = msg.readU16(off);
        off += 2;

        moduleStatuses.clear();
        for (int i = 0; i < moduleCount && off + 4 <= msg.getPayloadLength(); i++) {
            String name = msg.readString(off);
            off += msg.stringFieldLength(off);
            int currentLevel = msg.readU8(off);
            off += 1;
            int maxLevel = msg.readU8(off);
            off += 1;
            ModuleStatus ms = new ModuleStatus(name, currentLevel, maxLevel);
            moduleStatuses.add(ms);
        }
    }

    private void processModuleEvent(EventMessage msg) {
        if (msg.getPayloadLength() < 11) return;
        int off = 8; /* skip timestamp */
        String name = msg.readString(off);
        off += msg.stringFieldLength(off);
        if (off + 3 > msg.getPayloadLength()) return;
        int oldLevel = msg.readU8(off);
        int newLevel = msg.readU8(off + 1);
        int maxLevel = msg.readU8(off + 2);

        /* Update tracked module status */
        boolean found = false;
        for (int i = 0; i < moduleStatuses.size(); i++) {
            ModuleStatus ms = moduleStatuses.get(i);
            if (ms.getName().equals(name)) {
                ms.setCurrentLevel(newLevel);
                ms.setMaxLevel(maxLevel);
                found = true;
                break;
            }
        }
        if (!found) {
            moduleStatuses.add(new ModuleStatus(name, newLevel, maxLevel));
        }
    }

    private void processException(EventMessage msg) {
        if (msg.getPayloadLength() < 20) return;
        long timestamp = msg.readU64(0);
        int off = 8;
        int totalThrown = msg.readI32(off); off += 4;
        int totalCaught = msg.readI32(off); off += 4;
        int totalDropped = msg.readI32(off); off += 4;

        String exceptionClass = msg.readString(off);
        off += msg.stringFieldLength(off);
        String throwClass = msg.readString(off);
        off += msg.stringFieldLength(off);
        String throwMethod = msg.readString(off);
        off += msg.stringFieldLength(off);

        long throwLocation = 0;
        if (off + 8 <= msg.getPayloadLength()) {
            throwLocation = msg.readI64(off);
            off += 8;
        }

        boolean caught = false;
        String catchClass = "";
        String catchMethod = "";
        if (off + 1 <= msg.getPayloadLength()) {
            caught = msg.readU8(off) != 0;
            off += 1;
            if (caught && off + 2 <= msg.getPayloadLength()) {
                catchClass = msg.readString(off);
                off += msg.stringFieldLength(off);
                if (off + 2 <= msg.getPayloadLength()) {
                    catchMethod = msg.readString(off);
                    off += msg.stringFieldLength(off);
                }
            }
        }

        /* Stack frames */
        ExceptionEvent.StackFrame[] frames = null;
        if (off + 2 <= msg.getPayloadLength()) {
            int frameCount = msg.readU16(off);
            off += 2;
            frames = new ExceptionEvent.StackFrame[frameCount];
            for (int i = 0; i < frameCount && off + 12 <= msg.getPayloadLength(); i++) {
                long methodId = msg.readU64(off);
                int lineNo = msg.readI32(off + 8);
                off += 12;
                String[] names = methodNameCache.get(Long.valueOf(methodId));
                String cn = names != null ? names[0] : "method@0x" + Long.toHexString(methodId);
                String mn = names != null ? names[1] : "";
                frames[i] = new ExceptionEvent.StackFrame(cn, mn, lineNo);
            }
        }

        store.storeException(new ExceptionEvent(
                timestamp, totalThrown, totalCaught, totalDropped,
                exceptionClass, throwClass, throwMethod, throwLocation,
                caught, catchClass, catchMethod, frames));
    }

    private void processOsMetrics(EventMessage msg) {
        /* Wire: timestamp(8) + fd_count(4) + rss(8) + vmsize(8) + vol_cs(8) + invol_cs(8)
         *       + tcp_est(4) + tcp_cw(4) + tcp_tw(4) + os_threads(4) = 60 bytes */
        if (msg.getPayloadLength() < 60) return;
        long timestamp = msg.readU64(0);
        int fdCount = msg.readI32(8);
        long rssBytes = msg.readI64(12);
        long vmSizeBytes = msg.readI64(20);
        long volCs = msg.readI64(28);
        long involCs = msg.readI64(36);
        int tcpEst = msg.readI32(44);
        int tcpCw = msg.readI32(48);
        int tcpTw = msg.readI32(52);
        int osThreads = msg.readI32(56);

        store.storeOsMetrics(new OsMetrics(
                timestamp, fdCount, rssBytes, vmSizeBytes,
                volCs, involCs, tcpEst, tcpCw, tcpTw, osThreads));
    }

    private void processJitEvent(EventMessage msg) {
        if (msg.getPayloadLength() < 9) return;
        long timestamp = msg.readU64(0);
        int eventType = msg.readU8(8);
        int off = 9;

        if (eventType == JitEvent.COMPILED) {
            String className = msg.readString(off);
            off += msg.stringFieldLength(off);
            String methodName = msg.readString(off);
            off += msg.stringFieldLength(off);
            int codeSize = 0;
            long codeAddr = 0;
            int totalCompiled = 0;
            if (off + 4 <= msg.getPayloadLength()) {
                codeSize = msg.readI32(off); off += 4;
            }
            if (off + 8 <= msg.getPayloadLength()) {
                codeAddr = msg.readU64(off); off += 8;
            }
            if (off + 4 <= msg.getPayloadLength()) {
                totalCompiled = msg.readI32(off);
            }
            store.storeJitEvent(new JitEvent(
                    timestamp, eventType, className, methodName,
                    codeSize, codeAddr, totalCompiled));
        } else if (eventType == JitEvent.UNLOADED) {
            long codeAddr = 0;
            int deoptCount = 0;
            if (off + 8 <= msg.getPayloadLength()) {
                codeAddr = msg.readU64(off); off += 8;
            }
            if (off + 4 <= msg.getPayloadLength()) {
                deoptCount = msg.readI32(off);
            }
            store.storeJitEvent(new JitEvent(
                    timestamp, eventType, "", "", 0, codeAddr, deoptCount));
        }
    }

    private void processClassHistogram(EventMessage msg) {
        if (msg.getPayloadLength() < 18) return;
        long timestamp = msg.readU64(0);
        long elapsedNanos = msg.readU64(8);
        int entryCount = msg.readU16(16);
        int off = 18;

        ClassHistogram.Entry[] entries = new ClassHistogram.Entry[entryCount];
        for (int i = 0; i < entryCount && off + 6 <= msg.getPayloadLength(); i++) {
            String className = msg.readString(off);
            off += msg.stringFieldLength(off);
            int instanceCount = msg.readI32(off); off += 4;
            long totalSize = msg.readI64(off); off += 8;
            entries[i] = new ClassHistogram.Entry(className, instanceCount, totalSize);
        }
        store.storeClassHistogram(new ClassHistogram(timestamp, elapsedNanos, entries));
    }

    private void processSafepoint(EventMessage msg) {
        /* Wire: timestamp(8) + count(8) + totalTime(8) + syncTime(8) = 32 bytes */
        if (msg.getPayloadLength() < 32) return;
        long timestamp = msg.readU64(0);
        long count = msg.readI64(8);
        long totalTime = msg.readI64(16);
        long syncTime = msg.readI64(24);
        store.storeSafepoint(new SafepointEvent(timestamp, count, totalTime, syncTime));
    }

    private void processNativeMemory(EventMessage msg) {
        if (msg.getPayloadLength() < 9) return;
        long timestamp = msg.readU64(0);
        boolean available = msg.readU8(8) != 0;
        String rawOutput = "";
        if (msg.getPayloadLength() > 9) {
            rawOutput = msg.readString(9);
        }
        store.storeNativeMemory(new NativeMemoryStats(timestamp, available, rawOutput));
    }

    private void processGcDetail(EventMessage msg) {
        if (msg.getPayloadLength() < 10) return;
        long timestamp = msg.readU64(0);
        int collectorCount = msg.readU16(8);
        int off = 10;

        GcDetail.CollectorInfo[] collectors = new GcDetail.CollectorInfo[collectorCount];
        for (int i = 0; i < collectorCount && off + 2 <= msg.getPayloadLength(); i++) {
            String name = msg.readString(off);
            off += msg.stringFieldLength(off);
            long gcCount = msg.readI64(off); off += 8;
            long gcTime = msg.readI64(off); off += 8;
            int poolCount = msg.readU16(off); off += 2;

            String[] pools = new String[poolCount];
            for (int p = 0; p < poolCount && off + 2 <= msg.getPayloadLength(); p++) {
                pools[p] = msg.readString(off);
                off += msg.stringFieldLength(off);
            }
            collectors[i] = new GcDetail.CollectorInfo(name, gcCount, gcTime, pools);
        }
        store.storeGcDetail(new GcDetail(timestamp, collectors));
    }

    private void processClassloader(EventMessage msg) {
        if (msg.getPayloadLength() < 10) return;
        long timestamp = msg.readU64(0);
        int loaderCount = msg.readU16(8);
        int off = 10;

        ClassloaderStats.LoaderInfo[] loaders = new ClassloaderStats.LoaderInfo[loaderCount];
        for (int i = 0; i < loaderCount && off + 2 <= msg.getPayloadLength(); i++) {
            String loaderClass = msg.readString(off);
            off += msg.stringFieldLength(off);
            int classCount = msg.readI32(off); off += 4;
            loaders[i] = new ClassloaderStats.LoaderInfo(loaderClass, classCount);
        }
        store.storeClassloaderStats(new ClassloaderStats(timestamp, loaders));
    }

    private void processStringTable(EventMessage msg) {
        if (msg.getPayloadLength() < 9) return;
        long timestamp = msg.readU64(0);
        boolean available = msg.readU8(8) != 0;
        String rawOutput = "";
        if (msg.getPayloadLength() > 9) {
            rawOutput = msg.readString(9);
        }
        store.storeStringTableStats(new StringTableStats(timestamp, available, rawOutput));
    }

    private void processNetwork(EventMessage msg) {
        /* Wire: timestamp(8) + 8 counters * i64(8) + socketCount(2) + per-socket(17 each) */
        if (msg.getPayloadLength() < 74) return;
        long timestamp = msg.readU64(0);
        int off = 8;
        long activeOpens = msg.readI64(off); off += 8;
        long passiveOpens = msg.readI64(off); off += 8;
        long inSegs = msg.readI64(off); off += 8;
        long outSegs = msg.readI64(off); off += 8;
        long retransSegs = msg.readI64(off); off += 8;
        long inErrs = msg.readI64(off); off += 8;
        long outRsts = msg.readI64(off); off += 8;
        long currEstab = msg.readI64(off); off += 8;

        int socketCount = msg.readU16(off); off += 2;
        java.util.List<NetworkSnapshot.SocketInfo> socketList = new java.util.ArrayList<NetworkSnapshot.SocketInfo>();
        for (int i = 0; i < socketCount && off + 17 <= msg.getPayloadLength(); i++) {
            long localAddr = msg.readU32(off); off += 4;
            int localPort = msg.readU16(off); off += 2;
            long remoteAddr = msg.readU32(off); off += 4;
            int remotePort = msg.readU16(off); off += 2;
            int state = msg.readU8(off); off += 1;
            long txQueue = msg.readU32(off); off += 4;
            long rxQueue = msg.readU32(off); off += 4;
            /* Extended fields (optional) */
            long bytesIn = 0, bytesOut = 0;
            int reqCount = 0;
            String svcName = "";
            if (off + 20 <= msg.getPayloadLength()) {
                bytesIn = msg.readI64(off); off += 8;
                bytesOut = msg.readI64(off); off += 8;
                reqCount = msg.readI32(off); off += 4;
                if (off + 2 <= msg.getPayloadLength()) {
                    svcName = msg.readString(off);
                    off += msg.stringFieldLength(off);
                }
            }
            socketList.add(new NetworkSnapshot.SocketInfo(
                    localAddr, localPort, remoteAddr, remotePort, state, txQueue, rxQueue,
                    bytesIn, bytesOut, reqCount, svcName));
        }
        NetworkSnapshot.SocketInfo[] sockets = socketList.toArray(
                new NetworkSnapshot.SocketInfo[socketList.size()]);
        store.storeNetworkSnapshot(new NetworkSnapshot(
                timestamp, activeOpens, passiveOpens, inSegs, outSegs,
                retransSegs, inErrs, outRsts, currEstab, sockets));
    }

    /**
     * Process METHOD_INFO messages that provide method name resolution.
     * Wire format: methodId(8) + className(string) + methodName(string)
     */
    private void processCpuUsage(EventMessage msg) {
        if (msg.getPayloadLength() < 36) return;
        long timestamp = msg.readU64(0);
        int off = 8;
        /* Read doubles as fixed-point (value * 1000) */
        double sysCpu = msg.readI64(off) / 1000.0; off += 8;
        int processors = msg.readI32(off); off += 4;
        double procCpu = msg.readI64(off) / 1000.0; off += 8;
        long userTime = msg.readI64(off); off += 8;
        long sysTime = msg.readI64(off); off += 8;

        /* Per-thread CPU info */
        CpuUsageSnapshot.ThreadCpuInfo[] threads = null;
        if (off + 2 <= msg.getPayloadLength()) {
            int threadCount = msg.readU16(off); off += 2;
            threads = new CpuUsageSnapshot.ThreadCpuInfo[threadCount];
            for (int i = 0; i < threadCount && off + 10 <= msg.getPayloadLength(); i++) {
                long threadId = msg.readU64(off); off += 8;
                String name = msg.readString(off); off += msg.stringFieldLength(off);
                long cpuTime = msg.readI64(off); off += 8;
                double cpuPct = msg.readI64(off) / 1000.0; off += 8;
                String state = msg.readString(off); off += msg.stringFieldLength(off);
                threads[i] = new CpuUsageSnapshot.ThreadCpuInfo(threadId, name, cpuTime, cpuPct, state);
            }
        }
        if (threads == null) threads = new CpuUsageSnapshot.ThreadCpuInfo[0];

        store.storeCpuUsage(new CpuUsageSnapshot(
                timestamp, sysCpu, processors, procCpu, userTime, sysTime, threads));
    }

    private void processProcessList(EventMessage msg) {
        if (msg.getPayloadLength() < 52) return;
        long timestamp = msg.readU64(0);
        int off = 8;
        long totalMem = msg.readI64(off); off += 8;
        long freeMem = msg.readI64(off); off += 8;
        long swapTotal = msg.readI64(off); off += 8;
        long swapUsed = msg.readI64(off); off += 8;
        double load1 = msg.readI64(off) / 1000.0; off += 8;
        double load5 = msg.readI64(off) / 1000.0; off += 8;
        double load15 = msg.readI64(off) / 1000.0; off += 8;
        int totalProcs = msg.readI32(off); off += 4;

        int procCount = 0;
        if (off + 2 <= msg.getPayloadLength()) {
            procCount = msg.readU16(off); off += 2;
        }
        ProcessInfo.ProcessEntry[] procs = new ProcessInfo.ProcessEntry[procCount];
        for (int i = 0; i < procCount && off + 4 <= msg.getPayloadLength(); i++) {
            int pid = msg.readI32(off); off += 4;
            String name = msg.readString(off); off += msg.stringFieldLength(off);
            double cpuPct = msg.readI64(off) / 1000.0; off += 8;
            long rss = msg.readI64(off); off += 8;
            int threads = msg.readI32(off); off += 4;
            String state = msg.readString(off); off += msg.stringFieldLength(off);
            procs[i] = new ProcessInfo.ProcessEntry(pid, name, cpuPct, rss, threads, state);
        }
        store.storeProcessInfo(new ProcessInfo(
                timestamp, totalMem, freeMem, swapTotal, swapUsed,
                load1, load5, load15, totalProcs, procs));
    }

    private void processLockEvent(EventMessage msg) {
        if (msg.getPayloadLength() < 12) return;
        long timestamp = msg.readU64(0);
        int off = 8;
        int eventType = msg.readU8(off); off += 1;

        String threadName = msg.readString(off);
        off += msg.stringFieldLength(off);
        String lockClassName = msg.readString(off);
        off += msg.stringFieldLength(off);

        int lockHash = 0;
        int totalContentions = 0;
        if (off + 8 <= msg.getPayloadLength()) {
            lockHash = msg.readI32(off); off += 4;
            totalContentions = msg.readI32(off); off += 4;
        }

        String ownerThread = "";
        int ownerEntryCount = 0;
        int waiterCount = 0;
        if (off + 2 <= msg.getPayloadLength()) {
            ownerThread = msg.readString(off);
            off += msg.stringFieldLength(off);
        }
        if (off + 8 <= msg.getPayloadLength()) {
            ownerEntryCount = msg.readI32(off); off += 4;
            waiterCount = msg.readI32(off); off += 4;
        }

        /* Stack frames */
        LockEvent.StackFrame[] frames = null;
        if (off + 2 <= msg.getPayloadLength()) {
            int frameCount = msg.readU16(off); off += 2;
            frames = new LockEvent.StackFrame[frameCount];
            for (int i = 0; i < frameCount && off + 4 <= msg.getPayloadLength(); i++) {
                String cn = msg.readString(off); off += msg.stringFieldLength(off);
                String mn = msg.readString(off); off += msg.stringFieldLength(off);
                frames[i] = new LockEvent.StackFrame(cn, mn);
            }
        }

        store.storeLockEvent(new LockEvent(
                timestamp, eventType, threadName, lockClassName, lockHash,
                totalContentions, ownerThread, ownerEntryCount, waiterCount, frames));
    }

    /** Send instrumentation configuration to agent. */
    public void sendInstrumentationConfig(String[] packages, String[] probes) throws java.io.IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);
        dos.writeShort(packages.length);
        for (int i = 0; i < packages.length; i++) {
            byte[] b = packages[i].getBytes("UTF-8");
            dos.writeShort(b.length);
            dos.write(b);
        }
        dos.writeShort(probes.length);
        for (int i = 0; i < probes.length; i++) {
            byte[] b = probes[i].getBytes("UTF-8");
            dos.writeShort(b.length);
            dos.write(b);
        }
        dos.flush();
        sendCommand(ProtocolConstants.INSTR_CMD_CONFIGURE, baos.toByteArray());
    }

    public void startInstrumentation() throws java.io.IOException {
        sendCommand(ProtocolConstants.INSTR_CMD_START, new byte[0]);
    }

    public void stopInstrumentation() throws java.io.IOException {
        sendCommand(ProtocolConstants.INSTR_CMD_STOP, new byte[0]);
    }

    /* ── Debugger commands ────────────────────────── */

    /** Breakpoint hit listener — set by the debugger GUI. */
    private volatile BreakpointListener breakpointListener;
    private volatile ClassBytesListener classBytesListener;
    private volatile DiagnosticListener diagnosticListener;

    public interface DiagnosticListener {
        void onDiagnosticMessage(MessageType type, EventMessage msg);
    }
    public void setDiagnosticListener(DiagnosticListener l) { this.diagnosticListener = l; }

    public interface BreakpointListener {
        void onBreakpointHit(BreakpointHit hit);
    }
    public interface ClassBytesListener {
        void onClassBytes(String className, byte[] bytes);
    }

    public void setBreakpointListener(BreakpointListener l) { this.breakpointListener = l; }
    public void setClassBytesListener(ClassBytesListener l) { this.classBytesListener = l; }

    public void debugSetBreakpoint(String className, int lineNumber) throws java.io.IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);
        byte[] cn = className.getBytes("UTF-8");
        dos.writeShort(cn.length); dos.write(cn);
        dos.writeInt(lineNumber);
        dos.flush();
        sendCommand(ProtocolConstants.DEBUG_CMD_SET_BP, baos.toByteArray());
    }

    public void debugRemoveBreakpoint(String className, int lineNumber) throws java.io.IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);
        byte[] cn = className.getBytes("UTF-8");
        dos.writeShort(cn.length); dos.write(cn);
        dos.writeInt(lineNumber);
        dos.flush();
        sendCommand(ProtocolConstants.DEBUG_CMD_REMOVE_BP, baos.toByteArray());
    }

    public void debugResume(long threadId) throws java.io.IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        new java.io.DataOutputStream(baos).writeLong(threadId);
        sendCommand(ProtocolConstants.DEBUG_CMD_RESUME, baos.toByteArray());
    }

    public void debugStepOver(long threadId) throws java.io.IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        new java.io.DataOutputStream(baos).writeLong(threadId);
        sendCommand(ProtocolConstants.DEBUG_CMD_STEP_OVER, baos.toByteArray());
    }

    public void debugStepInto(long threadId) throws java.io.IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        new java.io.DataOutputStream(baos).writeLong(threadId);
        sendCommand(ProtocolConstants.DEBUG_CMD_STEP_INTO, baos.toByteArray());
    }

    public void debugStepOut(long threadId) throws java.io.IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        new java.io.DataOutputStream(baos).writeLong(threadId);
        sendCommand(ProtocolConstants.DEBUG_CMD_STEP_OUT, baos.toByteArray());
    }

    public void debugGetClassBytes(String className) throws java.io.IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);
        byte[] cn = className.getBytes("UTF-8");
        dos.writeShort(cn.length); dos.write(cn);
        dos.flush();
        sendCommand(ProtocolConstants.DEBUG_CMD_GET_CLASS, baos.toByteArray());
    }

    private void processBreakpointHit(EventMessage msg) {
        if (msg.getPayloadLength() < 20) return;
        long timestamp = msg.readU64(0);
        int off = 8;
        long threadId = msg.readU64(off); off += 8;
        String threadName = msg.readString(off); off += msg.stringFieldLength(off);
        String className = msg.readString(off); off += msg.stringFieldLength(off);
        String methodName = msg.readString(off); off += msg.stringFieldLength(off);
        int lineNumber = 0;
        if (off + 4 <= msg.getPayloadLength()) { lineNumber = msg.readI32(off); off += 4; }

        /* Variables */
        int varCount = 0;
        if (off + 2 <= msg.getPayloadLength()) { varCount = msg.readU16(off); off += 2; }
        BreakpointHit.DebugVariable[] vars = new BreakpointHit.DebugVariable[varCount];
        for (int i = 0; i < varCount && off + 2 <= msg.getPayloadLength(); i++) {
            String vName = msg.readString(off); off += msg.stringFieldLength(off);
            String vType = msg.readString(off); off += msg.stringFieldLength(off);
            String vValue = msg.readString(off); off += msg.stringFieldLength(off);
            vars[i] = new BreakpointHit.DebugVariable(vName, vType, vValue);
        }

        /* Class bytes (optional, may come separately) */
        byte[] classBytes = null;
        if (off + 4 <= msg.getPayloadLength()) {
            int cbLen = msg.readI32(off); off += 4;
            if (cbLen > 0 && off + cbLen <= msg.getPayloadLength()) {
                classBytes = new byte[cbLen];
                System.arraycopy(msg.getPayload(), off, classBytes, 0, cbLen);
            }
        }

        BreakpointHit hit = new BreakpointHit(timestamp, threadId, threadName,
                className, methodName, lineNumber, vars, classBytes);
        BreakpointListener l = breakpointListener;
        if (l != null) l.onBreakpointHit(hit);
    }

    private void processDebugClassBytes(EventMessage msg) {
        if (msg.getPayloadLength() < 10) return;
        int off = 0;
        String className = msg.readString(off); off += msg.stringFieldLength(off);
        int cbLen = 0;
        if (off + 4 <= msg.getPayloadLength()) { cbLen = msg.readI32(off); off += 4; }
        byte[] classBytes = null;
        if (cbLen > 0 && off + cbLen <= msg.getPayloadLength()) {
            classBytes = new byte[cbLen];
            System.arraycopy(msg.getPayload(), off, classBytes, 0, cbLen);
        }
        ClassBytesListener l = classBytesListener;
        if (l != null && classBytes != null) l.onClassBytes(className, classBytes);
    }

    private void processInstrEvent(EventMessage msg) {
        if (msg.getPayloadLength() < 20) return;
        long timestamp = msg.readU64(0);
        int off = 8;
        int eventType = msg.readU8(off); off += 1;
        long threadId = msg.readU64(off); off += 8;
        String threadName = msg.readString(off); off += msg.stringFieldLength(off);
        String className = msg.readString(off); off += msg.stringFieldLength(off);
        String methodName = msg.readString(off); off += msg.stringFieldLength(off);
        long durationNanos = 0;
        if (off + 8 <= msg.getPayloadLength()) { durationNanos = msg.readI64(off); off += 8; }
        String context = "";
        if (off + 2 <= msg.getPayloadLength()) { context = msg.readString(off); off += msg.stringFieldLength(off); }
        long traceId = 0;
        if (off + 8 <= msg.getPayloadLength()) { traceId = msg.readU64(off); off += 8; }
        long parentTraceId = 0;
        if (off + 8 <= msg.getPayloadLength()) { parentTraceId = msg.readU64(off); off += 8; }
        int depth = 0;
        if (off + 4 <= msg.getPayloadLength()) { depth = msg.readI32(off); off += 4; }
        boolean isExc = false;
        if (off + 1 <= msg.getPayloadLength()) { isExc = msg.readU8(off) != 0; }

        store.storeInstrumentationEvent(new InstrumentationEvent(
                timestamp, eventType, threadId, threadName, className, methodName,
                durationNanos, context, traceId, parentTraceId, depth, isExc));
    }

    private void processQueueStats(EventMessage msg) {
        if (msg.getPayloadLength() < 10) return;
        long timestamp = msg.readU64(0);
        int off = 8;
        int queueCount = msg.readU16(off); off += 2;

        QueueStats.QueueInfo[] queues = new QueueStats.QueueInfo[queueCount];
        for (int i = 0; i < queueCount && off + 2 <= msg.getPayloadLength(); i++) {
            String name = msg.readString(off); off += msg.stringFieldLength(off);
            String type = msg.readString(off); off += msg.stringFieldLength(off);
            long depth = 0, enqRate = 0, deqRate = 0, totalEnq = 0, totalDeq = 0, lag = 0, oldest = 0;
            int consumers = 0, producers = 0;
            if (off + 72 <= msg.getPayloadLength()) {
                depth = msg.readI64(off); off += 8;
                enqRate = msg.readI64(off); off += 8;
                deqRate = msg.readI64(off); off += 8;
                consumers = msg.readI32(off); off += 4;
                producers = msg.readI32(off); off += 4;
                totalEnq = msg.readI64(off); off += 8;
                totalDeq = msg.readI64(off); off += 8;
                lag = msg.readI64(off); off += 8;
                oldest = msg.readI64(off); off += 8;
            }
            queues[i] = new QueueStats.QueueInfo(name, type, depth, enqRate, deqRate,
                    consumers, producers, totalEnq, totalDeq, lag, oldest);
        }
        store.storeQueueStats(new QueueStats(timestamp, queues));
    }

    private void processAllocSample(EventMessage msg) {
        if (msg.getPayloadLength() < 10) return;
        long timestamp = msg.readU64(0);
        int off = 8;
        String className = msg.readString(off); off += msg.stringFieldLength(off);
        long size = 0;
        if (off + 8 <= msg.getPayloadLength()) { size = msg.readI64(off); off += 8; }
        long threadId = 0;
        if (off + 8 <= msg.getPayloadLength()) { threadId = msg.readU64(off); off += 8; }
        String threadName = "";
        if (off + 2 <= msg.getPayloadLength()) { threadName = msg.readString(off); off += msg.stringFieldLength(off); }
        String allocSite = "";
        if (off + 2 <= msg.getPayloadLength()) { allocSite = msg.readString(off); }
        store.storeAllocationEvent(new AllocationEvent(timestamp, className, size, threadId, threadName, allocSite));
    }

    private void processMethodInfo(EventMessage msg) {
        if (msg.getPayloadLength() < 12) return;
        int off = 0;
        while (off + 10 <= msg.getPayloadLength()) {
            long methodId = msg.readU64(off);
            off += 8;
            if (off + 2 > msg.getPayloadLength()) break;
            String className = msg.readString(off);
            off += msg.stringFieldLength(off);
            if (off + 2 > msg.getPayloadLength()) break;
            String methodName = msg.readString(off);
            off += msg.stringFieldLength(off);
            if (methodNameCache.size() < MAX_METHOD_CACHE) {
                methodNameCache.put(Long.valueOf(methodId), new String[]{className, methodName});
            }
        }
    }
}
