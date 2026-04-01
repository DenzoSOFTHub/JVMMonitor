package it.denzosoft.jvmmonitor.demo;

import it.denzosoft.jvmmonitor.protocol.ProtocolConstants;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Random;

/**
 * Simulates a JVMMonitor native agent for demo/testing purposes.
 * Listens on a TCP port, accepts one client, and sends a realistic
 * stream of profiling events (GC, memory, threads, exceptions, OS metrics, etc.).
 *
 * Usage:
 *   java -cp jvmmonitor.jar it.denzosoft.jvmmonitor.demo.DemoAgent [port]
 *
 * Then connect the collector:
 *   java -jar jvmmonitor.jar connect 127.0.0.1 <port>
 */
public class DemoAgent {

    private static final int DEFAULT_PORT = 9090;
    private static final Random RNG = new Random(42);

    private final int port;
    private volatile boolean running = true;

    /* Simulated JVM state */
    private long heapUsed = 256 * 1024 * 1024L;
    private long heapMax = 1024 * 1024 * 1024L;
    private long nonHeapUsed = 64 * 1024 * 1024L;
    private long nonHeapMax = 256 * 1024 * 1024L;
    private int gcCount = 0;
    private int fullGcCount = 0;
    private int exceptionCount = 0;
    private int caughtCount = 0;
    private int jitCompiled = 0;
    private long safepointCount = 0;
    private long safepointTimeMs = 0;
    private long safepointSyncMs = 0;

    /* Thread simulation */
    private static final String[] THREAD_NAMES = {
        "main", "http-nio-8080-exec-1", "http-nio-8080-exec-2", "http-nio-8080-exec-3",
        "http-nio-8080-exec-4", "http-nio-8080-exec-5", "scheduler-1", "scheduler-2",
        "HikariPool-1-connection-1", "HikariPool-1-connection-2", "HikariPool-1-connection-3",
        "kafka-consumer-1", "kafka-producer-1", "lettuce-epollEventLoop-1",
        "AsyncHttpClient-1", "AsyncHttpClient-2", "GC-worker-1", "GC-worker-2",
        "Signal Dispatcher", "Finalizer", "Reference Handler", "CompilerThread0"
    };

    /* Exception simulation */
    private static final String[][] EXCEPTION_SCENARIOS = {
        {"Ljava/lang/NullPointerException;", "com.myapp.service.UserService", "findById"},
        {"Ljava/net/SocketTimeoutException;", "com.myapp.client.HttpClient", "execute"},
        {"Ljava/sql/SQLException;", "com.myapp.dao.OrderDao", "findOrders"},
        {"Ljava/lang/IllegalArgumentException;", "com.myapp.api.RestController", "validate"},
        {"Ljava/util/NoSuchElementException;", "com.myapp.cache.LruCache", "get"},
        {"Ljava/io/IOException;", "com.myapp.io.FileProcessor", "readChunk"},
        {"Ljava/lang/NumberFormatException;", "com.myapp.parser.ConfigParser", "parseInt"},
    };

    /* JIT compilation simulation */
    private static final String[][] JIT_METHODS = {
        {"com.myapp.service.UserService", "findById"},
        {"com.myapp.dao.OrderDao", "findOrders"},
        {"com.myapp.cache.LruCache", "get"},
        {"com.myapp.cache.LruCache", "put"},
        {"java.util.HashMap", "getNode"},
        {"java.util.ArrayList", "grow"},
        {"com.myapp.api.RestController", "handleRequest"},
        {"com.myapp.serializer.JsonMapper", "serialize"},
        {"com.myapp.util.StringHelper", "escape"},
        {"java.lang.String", "hashCode"},
    };

    /* CPU sample simulation: realistic call stacks */
    private static final String[][][] CPU_STACKS = {
        {{"com.myapp.dao.OrderDao", "findOrders"}, {"com.myapp.service.OrderService", "getOrders"}, {"com.myapp.api.RestController", "handleRequest"}, {"org.springframework.web.servlet.FrameworkServlet", "service"}, {"javax.servlet.http.HttpServlet", "service"}},
        {{"java.util.HashMap", "getNode"}, {"com.myapp.cache.LruCache", "get"}, {"com.myapp.service.UserService", "findById"}, {"com.myapp.api.RestController", "handleRequest"}, {"org.springframework.web.servlet.FrameworkServlet", "service"}},
        {{"com.myapp.serializer.JsonMapper", "serialize"}, {"com.myapp.api.RestController", "handleRequest"}, {"org.springframework.web.servlet.FrameworkServlet", "service"}},
        {{"java.lang.String", "hashCode"}, {"java.util.HashMap", "getNode"}, {"com.myapp.cache.LruCache", "get"}, {"com.myapp.service.UserService", "findById"}},
        {{"com.myapp.io.FileProcessor", "readChunk"}, {"com.myapp.io.FileProcessor", "processFile"}, {"com.myapp.service.ImportService", "importData"}, {"com.myapp.scheduler.BatchJob", "run"}},
        {{"java.util.ArrayList", "grow"}, {"java.util.ArrayList", "add"}, {"com.myapp.service.OrderService", "collectResults"}, {"com.myapp.api.RestController", "handleRequest"}},
        {{"com.myapp.util.StringHelper", "escape"}, {"com.myapp.serializer.JsonMapper", "serialize"}, {"com.myapp.api.RestController", "handleRequest"}},
        {{"com.myapp.client.HttpClient", "execute"}, {"com.myapp.service.PaymentService", "charge"}, {"com.myapp.api.RestController", "handleRequest"}},
        {{"java.net.SocketInputStream", "read"}, {"com.myapp.client.HttpClient", "execute"}, {"com.myapp.service.PaymentService", "charge"}},
        {{"com.myapp.parser.ConfigParser", "parseInt"}, {"com.myapp.config.AppConfig", "reload"}, {"com.myapp.scheduler.ConfigRefresh", "run"}},
    };

    public DemoAgent(int port) {
        this.port = port;
    }

    public void run() throws IOException {
        ServerSocket server = new ServerSocket(port);
        System.out.println("=== JVMMonitor Demo Agent ===");
        System.out.println("Listening on port " + port);
        System.out.println("Connect with: java -jar jvmmonitor.jar connect 127.0.0.1 " + port);
        System.out.println("Press Ctrl+C to stop.\n");

        while (running) {
            System.out.println("Waiting for collector connection...");
            Socket client = server.accept();
            System.out.println("Collector connected from " + client.getRemoteSocketAddress());

            try {
                handleClient(client);
            } catch (IOException e) {
                System.out.println("Collector disconnected: " + e.getMessage());
            } finally {
                try { client.close(); } catch (IOException e) { /* ignore */ }
            }
            System.out.println("Session ended. Waiting for new connection...\n");
        }

        server.close();
    }

    private void handleClient(Socket client) throws IOException {
        OutputStream out = client.getOutputStream();
        DataOutputStream dos = new DataOutputStream(out);

        /* 1. Send handshake */
        sendHandshake(dos);
        System.out.println("  -> Handshake sent");

        /* 2. Send method name resolution for CPU profiler */
        sendMethodInfo(dos);

        /* 3. Event generation loop */
        long tick = 0;
        while (running && !client.isClosed()) {
            long now = System.currentTimeMillis();
            try {
                /* Every tick (~1s): memory snapshot + thread states */
                sendMemorySnapshot(dos, now);
                sendThreadSnapshots(dos, now);

                /* GC events: probabilistic, more frequent as heap fills up */
                double heapPct = (double) heapUsed / heapMax;
                if (RNG.nextDouble() < 0.3 + heapPct * 0.4) {
                    sendGcEvent(dos, now);
                }

                /* CPU samples: ~5-10 per tick */
                int sampleCount = 5 + RNG.nextInt(6);
                for (int i = 0; i < sampleCount; i++) {
                    sendCpuSample(dos, now + i);
                }

                /* Allocation samples: ~10-20 per tick */
                int allocCount = 10 + RNG.nextInt(11);
                for (int i = 0; i < allocCount; i++) {
                    sendAllocSample(dos, now + i);
                }

                /* Instrumentation events: simulate request traces */
                sendRequestTrace(dos, now, tick);

                /* Exceptions: ~3-5 per tick with bursts */
                int excBurst = RNG.nextDouble() < 0.1 ? 15 : (2 + RNG.nextInt(4));
                for (int i = 0; i < excBurst; i++) {
                    sendException(dos, now + i);
                }

                /* Queue stats: every 5 ticks */
                if (tick % 5 == 0) {
                    sendQueueStats(dos, now, tick);
                }

                /* CPU usage: every 2 ticks */
                if (tick % 2 == 0) {
                    sendCpuUsage(dos, now, tick);
                }

                /* Process list: every 10 ticks */
                if (tick % 10 == 0) {
                    sendProcessList(dos, now, tick);
                }

                /* OS metrics: every 5 ticks */
                if (tick % 5 == 0) {
                    sendOsMetrics(dos, now);
                }

                /* Network snapshot: every 3 ticks */
                if (tick % 3 == 0) {
                    sendNetworkSnapshot(dos, now, tick);
                }

                /* JIT events: a few per tick early on, then rare */
                if (tick < 20 || RNG.nextDouble() < 0.05) {
                    sendJitEvent(dos, now);
                }

                /* Lock events: 2-5 per tick */
                int lockBurst = 2 + RNG.nextInt(4);
                for (int i = 0; i < lockBurst; i++) {
                    sendLockEvent(dos, now + i);
                }

                /* Safepoint: every 5 ticks */
                if (tick % 5 == 0) {
                    sendSafepointEvent(dos, now);
                }

                /* Classloader stats: every 10 ticks */
                if (tick % 10 == 0) {
                    sendClassloaderStats(dos, now);
                }

                /* GC detail: every 10 ticks */
                if (tick % 10 == 0) {
                    sendGcDetail(dos, now);
                }

                /* Class histogram: every 15 ticks */
                if (tick % 15 == 0) {
                    sendClassHistogram(dos, now, tick);
                }

                /* Native memory: every 15 ticks */
                if (tick % 15 == 0) {
                    sendNativeMemory(dos, now);
                }

                /* Alarms: when heap is critical */
                if (heapPct > 0.85 && tick % 5 == 0) {
                    sendAlarm(dos, now, 3, heapPct > 0.95 ? 2 : 1,
                            heapPct * 100, 85.0,
                            String.format("Heap usage %.0f%% > 85%%", heapPct * 100));
                }

                /* Simulate heap growth (leak-like) */
                heapUsed += (long)(RNG.nextGaussian() * 2 * 1024 * 1024 + 3 * 1024 * 1024);
                if (heapUsed > heapMax * 0.98) {
                    /* "Full GC" resets heap */
                    heapUsed = (long)(heapMax * (0.2 + RNG.nextDouble() * 0.15));
                    fullGcCount++;
                }
                if (heapUsed < 50 * 1024 * 1024L) {
                    heapUsed = 50 * 1024 * 1024L;
                }
                nonHeapUsed += (long)(RNG.nextGaussian() * 100 * 1024);
                if (nonHeapUsed > nonHeapMax * 0.9) nonHeapUsed = (long)(nonHeapMax * 0.5);
                if (nonHeapUsed < 30 * 1024 * 1024L) nonHeapUsed = 30 * 1024 * 1024L;

                /* Heartbeat */
                sendHeartbeat(dos, now);

                tick++;
                if (tick % 10 == 0) {
                    System.out.printf("  [tick %d] heap=%.0fMB (%.0f%%) gc=%d fullgc=%d exc=%d jit=%d%n",
                            tick, heapUsed / (1024.0 * 1024.0),
                            heapPct * 100, gcCount, fullGcCount, exceptionCount, jitCompiled);
                }

                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /* ── Message senders ─────────────────────────────── */

    private void sendHandshake(DataOutputStream dos) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(baos);
        p.writeInt(1);                  /* version */
        p.writeInt(12345);              /* PID */
        writeStr(p, "demo-host");       /* hostname */
        writeStr(p, "OpenJDK 17.0.9 (Demo)"); /* JVM info */
        p.flush();
        sendMessage(dos, ProtocolConstants.MSG_HANDSHAKE, baos.toByteArray());
    }

    private void sendMethodInfo(DataOutputStream dos) throws IOException {
        /* Send name resolution for all methods used in CPU stacks */
        java.util.Set<String> sent = new java.util.HashSet<String>();
        for (int s = 0; s < CPU_STACKS.length; s++) {
            for (int f = 0; f < CPU_STACKS[s].length; f++) {
                String className = CPU_STACKS[s][f][0];
                String methodName = CPU_STACKS[s][f][1];
                String key = className + "." + methodName;
                if (sent.add(key)) {
                    long methodId = key.hashCode() & 0xFFFFFFFFL;
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    DataOutputStream p = new DataOutputStream(baos);
                    p.writeLong(methodId);
                    writeStr(p, className);
                    writeStr(p, methodName);
                    p.flush();
                    sendMessage(dos, ProtocolConstants.MSG_METHOD_INFO, baos.toByteArray());
                }
            }
        }
    }

    private void sendHeartbeat(DataOutputStream dos, long now) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        new DataOutputStream(baos).writeLong(now);
        sendMessage(dos, ProtocolConstants.MSG_HEARTBEAT, baos.toByteArray());
    }

    private void sendMemorySnapshot(DataOutputStream dos, long now) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(baos);
        p.writeLong(now);
        p.writeLong(heapUsed);
        p.writeLong(heapMax);
        p.writeLong(nonHeapUsed);
        p.writeLong(nonHeapMax);
        p.flush();
        sendMessage(dos, ProtocolConstants.MSG_MEMORY_SNAPSHOT, baos.toByteArray());
    }

    /* Allocation scenarios: {className, size range, allocSite} */
    private static final String[][] ALLOC_SCENARIOS = {
        {"Lcom/myapp/model/Order;", "512", "com.myapp.service.OrderService.createOrder"},
        {"Lcom/myapp/model/User;", "384", "com.myapp.service.UserService.findById"},
        {"Lcom/myapp/dto/OrderDTO;", "256", "com.myapp.api.RestController.handleRequest"},
        {"[B", "8192", "com.myapp.io.FileProcessor.readChunk"},
        {"Ljava/lang/String;", "64", "com.myapp.parser.ConfigParser.parse"},
        {"Ljava/util/HashMap$Node;", "48", "com.myapp.cache.LruCache.put"},
        {"Ljava/util/ArrayList;", "80", "com.myapp.service.OrderService.collectResults"},
        {"[Ljava/lang/Object;", "4096", "java.util.ArrayList.grow"},
        {"Lcom/myapp/event/AuditEvent;", "320", "com.myapp.service.AuditService.log"},
        {"Lcom/myapp/cache/CacheEntry;", "192", "com.myapp.cache.LruCache.put"},
        {"Ljava/nio/HeapByteBuffer;", "16384", "com.myapp.client.HttpClient.execute"},
        {"Lcom/myapp/model/Payment;", "448", "com.myapp.service.PaymentService.charge"},
        {"Ljava/lang/StringBuilder;", "128", "com.myapp.serializer.JsonMapper.serialize"},
        {"[C", "256", "java.lang.String.<init>"},
        {"Lcom/myapp/dto/ResponseDTO;", "160", "com.myapp.api.RestController.handleRequest"},
    };

    private static long traceIdCounter = 1000;

    /** Simulate a full request trace: Controller -> Service -> DAO -> JDBC */
    private static long connIdCounter = 5000;

    private void sendRequestTrace(DataOutputStream dos, long now, long tick) throws IOException {
        int threadIdx = 1 + RNG.nextInt(5); /* http threads */
        String threadName = THREAD_NAMES[threadIdx];
        long rootTrace = ++traceIdCounter;

        /* Simulate JDBC connection open at start of each request batch */
        long connId = ++connIdCounter;
        sendInstrEvent(dos, now, 4, threadIdx, threadName,
                "com.zaxxer.hikari.HikariDataSource", "getConnection",
                1000000L, "jdbc:postgresql://192.168.0.10:5432/mydb",
                connId, 0, 0, false);

        /* Simulate 2-3 request traces per tick */
        int traceCount = 2 + RNG.nextInt(2);
        for (int t = 0; t < traceCount; t++) {
            long ts = now + t * 100;
            rootTrace = ++traceIdCounter;

            /* Controller.handleRequest (total ~50ms) */
            long t1 = ++traceIdCounter;
            sendInstrEvent(dos, ts, 2, threadIdx, threadName,
                    "com.myapp.api.RestController", "handleRequest",
                    40000000L + RNG.nextInt(20000000),
                    "GET /api/orders", t1, 0, 0, false);

            /* Service.getOrders (total ~30ms) */
            long t2 = ++traceIdCounter;
            sendInstrEvent(dos, ts + 2, 2, threadIdx, threadName,
                    "com.myapp.service.OrderService", "getOrders",
                    25000000L + RNG.nextInt(15000000),
                    "", t2, t1, 1, false);

            /* DAO.findOrders (total ~15ms) */
            long t3 = ++traceIdCounter;
            sendInstrEvent(dos, ts + 4, 2, threadIdx, threadName,
                    "com.myapp.dao.OrderDao", "findOrders",
                    12000000L + RNG.nextInt(8000000),
                    "", t3, t2, 2, false);

            /* JDBC query (~10ms) */
            long t4 = ++traceIdCounter;
            String[] sqls = {
                "SELECT o.* FROM orders o WHERE o.user_id = ? ORDER BY created_at DESC LIMIT 20",
                "SELECT u.name, u.email FROM users u WHERE u.id = ?",
                "INSERT INTO audit_log (action, user_id, ts) VALUES (?, ?, ?)",
                "UPDATE orders SET status = ? WHERE id = ? AND version = ?",
            };
            sendInstrEvent(dos, ts + 6, 3, threadIdx, threadName,
                    "java.sql.PreparedStatement", "executeQuery",
                    8000000L + RNG.nextInt(5000000),
                    sqls[RNG.nextInt(sqls.length)], t4, t3, 3, false);

            /* Cache lookup (~1ms) */
            long t5 = ++traceIdCounter;
            sendInstrEvent(dos, ts + 8, 2, threadIdx, threadName,
                    "com.myapp.cache.LruCache", "get",
                    500000L + RNG.nextInt(500000),
                    "key=user:12345", t5, t2, 2, false);

            /* Occasional Redis call */
            if (RNG.nextDouble() < 0.3) {
                long t6 = ++traceIdCounter;
                sendInstrEvent(dos, ts + 10, 2, threadIdx, threadName,
                        "com.myapp.cache.RedisClient", "get",
                        2000000L + RNG.nextInt(3000000),
                        "GET user:12345", t6, t5, 3, false);
            }

            /* Occasional exception in trace */
            if (RNG.nextDouble() < 0.1) {
                long t7 = ++traceIdCounter;
                sendInstrEvent(dos, ts + 12, 2, threadIdx, threadName,
                        "com.myapp.service.PaymentService", "charge",
                        100000000L + RNG.nextInt(50000000),
                        "timeout calling payment gateway", t7, t1, 1, true);
            }
        }

        /* Close connection (but leak one every 20 ticks) */
        if (tick % 20 != 0) {
            sendInstrEvent(dos, now + traceCount * 100 + 50, 5, threadIdx, threadName,
                    "com.zaxxer.hikari.ProxyConnection", "close",
                    500000L, "jdbc:postgresql://192.168.0.10:5432/mydb",
                    connId, 0, 0, false);
        }
        /* else: connection leaked! Will show in Connection Monitor */

        /* ── Disk I/O events (type 8=read, 9=write) ─────── */
        if (RNG.nextDouble() < 0.4) {
            String[] files = {
                "/var/log/myapp/application.log", "/tmp/cache/session-data.bin",
                "/opt/myapp/config/settings.xml", "/var/data/export/report.csv",
                "/opt/myapp/data/index.dat"
            };
            long[] sizes = {4096, 65536, 1024, 262144, 32768};
            int fi = RNG.nextInt(files.length);
            int ioThread = 7 + RNG.nextInt(2); /* scheduler threads */
            String ioThreadName = THREAD_NAMES[ioThread];
            /* File read */
            sendInstrEvent(dos, now + 200, 8, ioThread, ioThreadName,
                    "java.io.FileInputStream", "read",
                    500000L + RNG.nextInt(2000000),
                    files[fi] + "|" + sizes[fi],
                    ++traceIdCounter, 0, 0, false);
            /* File write */
            if (RNG.nextDouble() < 0.6) {
                sendInstrEvent(dos, now + 210, 9, ioThread, ioThreadName,
                        "java.io.FileOutputStream", "write",
                        800000L + RNG.nextInt(3000000),
                        files[RNG.nextInt(files.length)] + "|" + (1024 + RNG.nextInt(131072)),
                        ++traceIdCounter, 0, 0, false);
            }
        }

        /* ── Socket I/O events (type 10=read, 11=write, 12=connect, 13=close) ── */
        if (RNG.nextDouble() < 0.5) {
            String[] remotes = {
                "192.168.0.10:5432", "10.0.1.50:6379", "10.0.2.20:9092",
                "api.external.com:443", "172.16.0.5:8080"
            };
            int si = RNG.nextInt(remotes.length);
            /* Socket read */
            sendInstrEvent(dos, now + 220, 10, threadIdx, threadName,
                    "java.net.SocketInputStream", "read",
                    1000000L + RNG.nextInt(5000000),
                    remotes[si] + "|" + (256 + RNG.nextInt(16384)),
                    ++traceIdCounter, 0, 0, false);
            /* Socket write */
            sendInstrEvent(dos, now + 230, 11, threadIdx, threadName,
                    "java.net.SocketOutputStream", "write",
                    500000L + RNG.nextInt(2000000),
                    remotes[si] + "|" + (128 + RNG.nextInt(8192)),
                    ++traceIdCounter, 0, 0, false);
            /* Occasional connect */
            if (RNG.nextDouble() < 0.1) {
                sendInstrEvent(dos, now + 240, 12, threadIdx, threadName,
                        "java.net.Socket", "connect",
                        5000000L + RNG.nextInt(20000000),
                        remotes[RNG.nextInt(remotes.length)],
                        ++traceIdCounter, 0, 0, false);
            }
        }
    }

    private void sendInstrEvent(DataOutputStream dos, long ts, int eventType,
                                 int threadIdx, String threadName,
                                 String className, String methodName,
                                 long durationNanos, String context,
                                 long traceId, long parentTraceId,
                                 int depth, boolean isException) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(baos);
        p.writeLong(ts);
        p.writeByte(eventType);
        p.writeLong(threadIdx);
        writeStr(p, threadName);
        writeStr(p, className);
        writeStr(p, methodName);
        p.writeLong(durationNanos);
        writeStr(p, context);
        p.writeLong(traceId);
        p.writeLong(parentTraceId);
        p.writeInt(depth);
        p.writeByte(isException ? 1 : 0);
        p.flush();
        sendMessage(dos, ProtocolConstants.MSG_INSTR_EVENT, baos.toByteArray());
    }

    private void sendAllocSample(DataOutputStream dos, long now) throws IOException {
        String[] scenario = ALLOC_SCENARIOS[RNG.nextInt(ALLOC_SCENARIOS.length)];
        int baseSize = Integer.parseInt(scenario[1]);
        long size = baseSize + RNG.nextInt(baseSize / 2);
        int threadIdx = 1 + RNG.nextInt(THREAD_NAMES.length - 1);

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(baos);
        p.writeLong(now);
        writeStr(p, scenario[0]);           /* class name */
        p.writeLong(size);                  /* object size */
        p.writeLong(threadIdx);             /* thread ID */
        writeStr(p, THREAD_NAMES[threadIdx]); /* thread name */
        writeStr(p, scenario[2]);           /* allocation site */
        p.flush();
        sendMessage(dos, ProtocolConstants.MSG_ALLOC_SAMPLE, baos.toByteArray());
    }

    private void sendCpuSample(DataOutputStream dos, long now) throws IOException {
        /* Pick a random stack and a random thread */
        String[][] stack = CPU_STACKS[RNG.nextInt(CPU_STACKS.length)];
        int threadIdx = 1 + RNG.nextInt(THREAD_NAMES.length - 1); /* skip main */

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(baos);
        p.writeLong(now);                         /* timestamp */
        p.writeLong(threadIdx);                   /* thread ID */
        p.writeShort(stack.length);               /* frame count */
        for (int i = 0; i < stack.length; i++) {
            /* methodId = hash of class+method (stable across calls) */
            long methodId = (stack[i][0] + "." + stack[i][1]).hashCode() & 0xFFFFFFFFL;
            p.writeLong(methodId);                /* method ID */
            p.writeInt(10 + RNG.nextInt(200));    /* line number */
        }
        p.flush();
        sendMessage(dos, ProtocolConstants.MSG_CPU_SAMPLE, baos.toByteArray());
    }

    private static final String[] GC_CAUSES = {
        "Allocation Failure", "Allocation Failure", "Allocation Failure",
        "G1 Evacuation Pause", "G1 Evacuation Pause",
        "GCLocker Initiated GC", "System.gc()",
        "Metadata GC Threshold", "G1 Humongous Allocation",
        "G1 Preventive Collection"
    };

    private void sendGcEvent(DataOutputStream dos, long now) throws IOException {
        gcCount++;
        boolean isFull = RNG.nextDouble() < 0.1;
        int type = isFull ? 3 : 1;
        long durationNanos = isFull
                ? (50 + RNG.nextInt(200)) * 1000000L
                : (2 + RNG.nextInt(20)) * 1000000L;

        /* Compute heap state before/after GC */
        long edenMax = heapMax / 3;
        long oldMax = heapMax * 2 / 3;
        long edenBefore, edenAfter, oldBefore, oldAfter, heapBefore, heapAfter;

        if (isFull) {
            edenBefore = edenMax * (60 + RNG.nextInt(30)) / 100;
            edenAfter = 0;
            oldBefore = oldMax * (70 + RNG.nextInt(25)) / 100;
            oldAfter = oldMax * (15 + RNG.nextInt(15)) / 100;
        } else {
            edenBefore = edenMax * (80 + RNG.nextInt(20)) / 100;
            edenAfter = edenMax * RNG.nextInt(5) / 100;
            long promoted = edenBefore * (3 + RNG.nextInt(8)) / 100;
            oldBefore = heapUsed * 60 / 100;
            oldAfter = oldBefore + promoted;
        }
        heapBefore = edenBefore + oldBefore;
        heapAfter = edenAfter + oldAfter;

        String cause = isFull ? "System.gc()" : GC_CAUSES[RNG.nextInt(GC_CAUSES.length)];
        double cpuAtGc = 30 + RNG.nextDouble() * 40;

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(baos);
        p.writeLong(now);
        p.writeByte(type);
        p.writeLong(durationNanos);
        p.writeInt(gcCount);
        p.writeInt(fullGcCount);
        /* Extended fields */
        p.writeLong(heapBefore);
        p.writeLong(heapAfter);
        p.writeLong(edenBefore);
        p.writeLong(edenAfter);
        p.writeLong(oldBefore);
        p.writeLong(oldAfter);
        writeStr(p, cause);
        p.writeLong((long)(cpuAtGc * 1000));
        p.flush();
        sendMessage(dos, ProtocolConstants.MSG_GC_EVENT, baos.toByteArray());
    }

    private void sendThreadSnapshots(DataOutputStream dos, long now) throws IOException {
        for (int i = 0; i < THREAD_NAMES.length; i++) {
            int state;
            if (i == 0) state = 1; /* main = RUNNABLE */
            else if (THREAD_NAMES[i].contains("GC") || THREAD_NAMES[i].contains("Signal")
                     || THREAD_NAMES[i].contains("Finalizer") || THREAD_NAMES[i].contains("Reference")) {
                state = RNG.nextDouble() < 0.8 ? 3 : 1; /* mostly WAITING */
            } else {
                double r = RNG.nextDouble();
                if (r < 0.4) state = 1;       /* RUNNABLE */
                else if (r < 0.55) state = 2;  /* BLOCKED */
                else if (r < 0.8) state = 3;   /* WAITING */
                else state = 4;                 /* TIMED_WAITING */
            }
            boolean daemon = i > 0;

            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            DataOutputStream p = new DataOutputStream(baos);
            p.writeLong(now);
            p.writeByte(3); /* event type = snapshot */
            p.writeLong(i + 1);
            writeStr(p, THREAD_NAMES[i]);
            p.writeInt(state);
            p.writeByte(daemon ? 1 : 0);
            p.flush();
            sendMessage(dos, ProtocolConstants.MSG_THREAD_SNAPSHOT, baos.toByteArray());
        }
    }

    private void sendException(DataOutputStream dos, long now) throws IOException {
        exceptionCount++;
        String[] scenario = EXCEPTION_SCENARIOS[RNG.nextInt(EXCEPTION_SCENARIOS.length)];
        boolean caught = RNG.nextDouble() < 0.85;
        if (caught) caughtCount++;

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(baos);
        p.writeLong(now);
        p.writeInt(exceptionCount);
        p.writeInt(caughtCount);
        p.writeInt(0); /* dropped */
        writeStr(p, scenario[0]); /* exception class */
        writeStr(p, scenario[1]); /* throw class */
        writeStr(p, scenario[2]); /* throw method */
        p.writeLong(RNG.nextInt(500)); /* location */
        p.writeByte(caught ? 1 : 0);
        if (caught) {
            writeStr(p, scenario[1]); /* catch class = same */
            writeStr(p, "handleError");
        }
        /* Stack frames: pick a matching CPU stack and use it */
        String[][] stack = CPU_STACKS[RNG.nextInt(CPU_STACKS.length)];
        int depth = Math.min(stack.length, 5);
        p.writeShort(depth);
        for (int f = 0; f < depth; f++) {
            String key = stack[f][0] + "." + stack[f][1];
            long methodId = key.hashCode() & 0xFFFFFFFFL;
            p.writeLong(methodId);
            p.writeInt(10 + RNG.nextInt(200)); /* line number */
        }
        p.flush();
        sendMessage(dos, ProtocolConstants.MSG_EXCEPTION, baos.toByteArray());
    }

    private void sendNetworkSnapshot(DataOutputStream dos, long now, long tick) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(baos);
        p.writeLong(now);

        /* TCP counters */
        long baseSegs = tick * 500;
        p.writeLong(200 + tick);
        p.writeLong(50 + tick / 2);
        p.writeLong(baseSegs + RNG.nextInt(100));
        p.writeLong(baseSegs + RNG.nextInt(100));
        p.writeLong(tick * 2 + RNG.nextInt(5));
        p.writeLong(RNG.nextInt(3));
        p.writeLong(tick / 5);
        p.writeLong(12 + RNG.nextInt(5));

        /* Socket list with traffic data */
        /* format: addr(4)+port(2)+addr(4)+port(2)+state(1)+txQ(4)+rxQ(4)+bytesIn(8)+bytesOut(8)+reqs(4)+svc(str) */
        p.writeShort(18); /* total sockets */

        long baseBytesPerTick = tick * 50000;

        /* LISTEN ports */
        writeSocketFull(p, 0, 8080, 0, 0, 10, 0, 0, 0, 0, 0, "HTTP Server");
        writeSocketFull(p, 0, 8443, 0, 0, 10, 0, 0, 0, 0, 0, "HTTPS Server");
        writeSocketFull(p, 0, 9090, 0, 0, 10, 0, 0, 0, 0, 0, "JVMMonitor");

        /* Inbound: clients -> our :8080 */
        writeSocketFull(p, 0, 8080, 0x0100A8C0L, 52301, 1, RNG.nextInt(50), RNG.nextInt(30),
                baseBytesPerTick * 3 + RNG.nextInt(10000), baseBytesPerTick * 8 + RNG.nextInt(20000),
                (int)(tick * 15 + RNG.nextInt(5)), "client-web-01");
        writeSocketFull(p, 0, 8080, 0x0200A8C0L, 52302, 1, RNG.nextInt(50), RNG.nextInt(30),
                baseBytesPerTick * 2 + RNG.nextInt(8000), baseBytesPerTick * 6 + RNG.nextInt(15000),
                (int)(tick * 12 + RNG.nextInt(5)), "client-web-02");
        writeSocketFull(p, 0, 8080, 0x0164A8C0L, 52303, 1, RNG.nextInt(80), RNG.nextInt(40),
                baseBytesPerTick * 5 + RNG.nextInt(15000), baseBytesPerTick * 12 + RNG.nextInt(30000),
                (int)(tick * 25 + RNG.nextInt(8)), "load-balancer");
        writeSocketFull(p, 0, 8443, 0x0100A8C0L, 43201, 1, RNG.nextInt(30), RNG.nextInt(20),
                baseBytesPerTick + RNG.nextInt(5000), baseBytesPerTick * 2 + RNG.nextInt(8000),
                (int)(tick * 8 + RNG.nextInt(3)), "mobile-app");

        /* Outbound: our app -> external services */
        writeSocketFull(p, 0, 40001, 0x0A00A8C0L, 5432, 1, RNG.nextInt(100), RNG.nextInt(50),
                baseBytesPerTick * 10 + RNG.nextInt(30000), baseBytesPerTick * 4 + RNG.nextInt(10000),
                (int)(tick * 30 + RNG.nextInt(10)), "PostgreSQL master");
        writeSocketFull(p, 0, 40002, 0x1400A8C0L, 5432, 1, RNG.nextInt(60), RNG.nextInt(30),
                baseBytesPerTick * 5 + RNG.nextInt(15000), baseBytesPerTick * 2 + RNG.nextInt(5000),
                (int)(tick * 18 + RNG.nextInt(5)), "PostgreSQL replica");
        writeSocketFull(p, 0, 40003, 0x1E00A8C0L, 6379, 1, RNG.nextInt(20), RNG.nextInt(10),
                baseBytesPerTick * 15 + RNG.nextInt(5000), baseBytesPerTick + RNG.nextInt(3000),
                (int)(tick * 80 + RNG.nextInt(20)), "Redis cache");
        writeSocketFull(p, 0, 40004, 0x2800A8C0L, 9092, 1, RNG.nextInt(200), RNG.nextInt(150),
                baseBytesPerTick * 8 + RNG.nextInt(20000), baseBytesPerTick * 3 + RNG.nextInt(8000),
                (int)(tick * 10 + RNG.nextInt(3)), "Kafka broker-1");
        writeSocketFull(p, 0, 40005, 0x01010101L, 443, 1, RNG.nextInt(50), RNG.nextInt(30),
                baseBytesPerTick + RNG.nextInt(5000), baseBytesPerTick * 2 + RNG.nextInt(6000),
                (int)(tick * 5 + RNG.nextInt(2)), "payment-api.ext.com");
        writeSocketFull(p, 0, 40006, 0x02020202L, 443, 1, RNG.nextInt(40), RNG.nextInt(20),
                baseBytesPerTick / 2 + RNG.nextInt(3000), baseBytesPerTick + RNG.nextInt(4000),
                (int)(tick * 3 + RNG.nextInt(2)), "geocoding-svc.ext.com");

        /* Problematic connections */
        writeSocketFull(p, 0, 40500, 0x0A00A8C0L, 5432, 8, 0, 1024,
                500000, 0, 0, "PostgreSQL (LEAK!)");
        writeSocketFull(p, 0, 41000, 0x01010101L, 443, 1, 8192, 0,
                0, 50000, 3, "payment-api (STUCK)");
        writeSocketFull(p, 0, 41500, 0x640064ACL, 8080, 2, 0, 0,
                0, 0, 0, "unknown-svc (HANGING)");

        p.flush();
        sendMessage(dos, ProtocolConstants.MSG_NETWORK, baos.toByteArray());
    }

    private void writeSocketFull(DataOutputStream p, long localAddr, int localPort,
                                  long remoteAddr, int remotePort, int state,
                                  int txQueue, int rxQueue,
                                  long bytesIn, long bytesOut, int requests,
                                  String serviceName) throws IOException {
        p.writeInt((int) localAddr);
        p.writeShort(localPort);
        p.writeInt((int) remoteAddr);
        p.writeShort(remotePort);
        p.writeByte(state);
        p.writeInt(txQueue);
        p.writeInt(rxQueue);
        p.writeLong(bytesIn);
        p.writeLong(bytesOut);
        p.writeInt(requests);
        writeStr(p, serviceName);
    }

    private void sendQueueStats(DataOutputStream dos, long now, long tick) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(baos);
        p.writeLong(now);

        String[][] queues = {
            {"order-events", "Kafka"},
            {"payment-notifications", "Kafka"},
            {"user-audit-log", "Kafka"},
            {"email-outbox", "JMS/ActiveMQ"},
            {"task-queue", "RabbitMQ"},
            {"dead-letter-queue", "RabbitMQ"},
        };
        p.writeShort(queues.length);

        for (int i = 0; i < queues.length; i++) {
            writeStr(p, queues[i][0]);
            writeStr(p, queues[i][1]);

            long baseDepth;
            boolean isBacklogged = (i == 3 || i == 5); /* email-outbox and DLQ */
            if (isBacklogged) {
                baseDepth = 500 + tick * 10 + RNG.nextInt(100);
            } else {
                baseDepth = 5 + RNG.nextInt(20);
            }
            long enqRate = 20 + RNG.nextInt(30);
            long deqRate = isBacklogged ? enqRate / 2 : enqRate + RNG.nextInt(5);
            int consumers = isBacklogged ? 1 : 2 + RNG.nextInt(3);
            int producers = 1 + RNG.nextInt(2);
            long totalEnq = tick * enqRate * 5 + RNG.nextInt(100);
            long totalDeq = tick * deqRate * 5 + RNG.nextInt(80);
            long lag = isBacklogged ? baseDepth * 2 : RNG.nextInt(10);
            long oldest = isBacklogged ? 30000 + tick * 1000 : 500 + RNG.nextInt(2000);

            p.writeLong(baseDepth);
            p.writeLong(enqRate);
            p.writeLong(deqRate);
            p.writeInt(consumers);
            p.writeInt(producers);
            p.writeLong(totalEnq);
            p.writeLong(totalDeq);
            p.writeLong(lag);
            p.writeLong(oldest);
        }
        p.flush();
        sendMessage(dos, ProtocolConstants.MSG_QUEUE_STATS, baos.toByteArray());
    }

    private void sendCpuUsage(DataOutputStream dos, long now, long tick) throws IOException {
        /* Simulate realistic CPU patterns */
        double baseSysCpu = 35 + Math.sin(tick * 0.1) * 15 + RNG.nextGaussian() * 5;
        if (baseSysCpu < 5) baseSysCpu = 5;
        if (baseSysCpu > 95) baseSysCpu = 95;

        double baseProcCpu = baseSysCpu * (0.4 + RNG.nextDouble() * 0.3);
        if (baseProcCpu > baseSysCpu) baseProcCpu = baseSysCpu - 2;

        long userTime = tick * 800 + RNG.nextInt(100);
        long sysTime = tick * 200 + RNG.nextInt(50);

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(baos);
        p.writeLong(now);
        p.writeLong((long)(baseSysCpu * 1000));    /* system CPU % * 1000 */
        p.writeInt(4);                               /* available processors */
        p.writeLong((long)(baseProcCpu * 1000));    /* process CPU % * 1000 */
        p.writeLong(userTime);                       /* process user time ms */
        p.writeLong(sysTime);                        /* process system time ms */

        /* Top threads by CPU */
        String[][] threadCpu = {
            {"http-nio-8080-exec-1", "RUNNABLE"},
            {"http-nio-8080-exec-2", "RUNNABLE"},
            {"http-nio-8080-exec-3", "BLOCKED"},
            {"scheduler-1", "RUNNABLE"},
            {"kafka-consumer-1", "TIMED_WAITING"},
            {"GC-worker-1", "RUNNABLE"},
            {"CompilerThread0", "RUNNABLE"},
            {"AsyncHttpClient-1", "RUNNABLE"},
        };

        p.writeShort(threadCpu.length);
        for (int i = 0; i < threadCpu.length; i++) {
            p.writeLong(i + 1);                      /* thread ID */
            writeStr(p, threadCpu[i][0]);             /* name */
            long threadCpuTime = tick * (100 - i * 10) + RNG.nextInt(50);
            p.writeLong(threadCpuTime);               /* cumulative CPU time ms */
            double threadPct = baseProcCpu / threadCpu.length * (1.5 - i * 0.15) + RNG.nextGaussian() * 2;
            if (threadPct < 0) threadPct = 0.1;
            p.writeLong((long)(threadPct * 1000));    /* CPU % * 1000 */
            writeStr(p, threadCpu[i][1]);              /* state */
        }
        p.flush();
        sendMessage(dos, ProtocolConstants.MSG_CPU_USAGE, baos.toByteArray());
    }

    private void sendProcessList(DataOutputStream dos, long now, long tick) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(baos);
        p.writeLong(now);
        p.writeLong(16L * 1024 * 1024 * 1024);      /* total mem: 16 GB */
        long freeMem = (long)((6.0 + Math.sin(tick * 0.05) * 2) * 1024 * 1024 * 1024);
        p.writeLong(freeMem);                         /* free mem */
        p.writeLong(4L * 1024 * 1024 * 1024);        /* swap total: 4 GB */
        p.writeLong((long)(0.5 * 1024 * 1024 * 1024)); /* swap used */
        p.writeLong((long)(2.5 * 1000 + RNG.nextInt(500)));  /* load avg 1 */
        p.writeLong((long)(2.2 * 1000 + RNG.nextInt(300)));  /* load avg 5 */
        p.writeLong((long)(1.8 * 1000 + RNG.nextInt(200)));  /* load avg 15 */
        p.writeInt(180 + RNG.nextInt(20));            /* total processes */

        /* Top processes */
        String[][] procs = {
            {"12345", "java (myapp)", "S"},
            {"456", "postgres", "S"},
            {"789", "nginx", "S"},
            {"1001", "redis-server", "S"},
            {"1200", "kafka", "S"},
            {"2", "kthreadd", "S"},
            {"50", "ksoftirqd/0", "S"},
            {"1500", "node", "S"},
            {"1800", "dockerd", "S"},
            {"2000", "containerd", "S"},
        };
        double[] cpus = {25 + RNG.nextDouble() * 10, 8 + RNG.nextDouble() * 5,
                         3 + RNG.nextDouble() * 2, 2 + RNG.nextDouble() * 3,
                         5 + RNG.nextDouble() * 3, 0.5, 0.3,
                         4 + RNG.nextDouble() * 2, 1.5 + RNG.nextDouble(),
                         0.8 + RNG.nextDouble() * 0.5};
        long[] rss = {heapUsed + 200 * 1024 * 1024L, 500 * 1024 * 1024L,
                      80 * 1024 * 1024L, 150 * 1024 * 1024L,
                      800 * 1024 * 1024L, 0, 0,
                      300 * 1024 * 1024L, 200 * 1024 * 1024L,
                      100 * 1024 * 1024L};
        int[] threads = {THREAD_NAMES.length + 5, 20, 4, 4, 50, 1, 1, 12, 30, 15};

        p.writeShort(procs.length);
        for (int i = 0; i < procs.length; i++) {
            p.writeInt(Integer.parseInt(procs[i][0]));
            writeStr(p, procs[i][1]);
            p.writeLong((long)(cpus[i] * 1000));
            p.writeLong(rss[i]);
            p.writeInt(threads[i]);
            writeStr(p, procs[i][2]);
        }
        p.flush();
        sendMessage(dos, ProtocolConstants.MSG_PROCESS_LIST, baos.toByteArray());
    }

    private void sendOsMetrics(DataOutputStream dos, long now) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(baos);
        p.writeLong(now);
        p.writeInt(180 + RNG.nextInt(50));          /* fd count */
        p.writeLong(heapUsed + 100 * 1024 * 1024L); /* rss ~ heap + overhead */
        p.writeLong(heapUsed + 500 * 1024 * 1024L); /* vm size */
        p.writeLong(50000 + exceptionCount * 10L);   /* vol ctx switches */
        p.writeLong(1000 + exceptionCount);          /* invol ctx switches */
        p.writeInt(12 + RNG.nextInt(5));             /* tcp established */
        p.writeInt(RNG.nextInt(3));                  /* tcp close_wait */
        p.writeInt(RNG.nextInt(8));                  /* tcp time_wait */
        p.writeInt(THREAD_NAMES.length + RNG.nextInt(5)); /* os threads */
        p.flush();
        sendMessage(dos, ProtocolConstants.MSG_OS_METRICS, baos.toByteArray());
    }

    private void sendJitEvent(DataOutputStream dos, long now) throws IOException {
        jitCompiled++;
        String[] method = JIT_METHODS[RNG.nextInt(JIT_METHODS.length)];
        int codeSize = 64 + RNG.nextInt(4096);

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(baos);
        p.writeLong(now);
        p.writeByte(1); /* COMPILED */
        writeStr(p, method[0]);
        writeStr(p, method[1]);
        p.writeInt(codeSize);
        p.writeLong(0x7F000000L + jitCompiled * 0x100L); /* code addr */
        p.writeInt(jitCompiled);
        p.flush();
        sendMessage(dos, ProtocolConstants.MSG_JIT_EVENT, baos.toByteArray());
    }

    private static int lockContentionCount = 0;

    /* Simulated lock contention scenarios */
    private static final String[][] LOCK_SCENARIOS = {
        /* {waiterThread, lockClass, ownerThread, stack: class, method, class, method...} */
        {"http-nio-8080-exec-1", "Ljava/util/concurrent/ConcurrentHashMap;", "http-nio-8080-exec-3",
         "com.myapp.cache.LruCache", "put", "com.myapp.service.UserService", "findById"},
        {"http-nio-8080-exec-2", "Lcom/myapp/dao/ConnectionPool;", "scheduler-1",
         "com.myapp.dao.ConnectionPool", "getConnection", "com.myapp.dao.OrderDao", "findOrders"},
        {"kafka-consumer-1", "Ljava/util/LinkedList;", "http-nio-8080-exec-4",
         "com.myapp.queue.EventQueue", "take", "com.myapp.service.EventProcessor", "process"},
        {"http-nio-8080-exec-3", "Ljava/lang/Object;", "http-nio-8080-exec-1",
         "com.myapp.sync.RateLimiter", "acquire", "com.myapp.api.RestController", "handleRequest"},
        {"scheduler-2", "Lcom/myapp/service/AuditService;", "http-nio-8080-exec-2",
         "com.myapp.service.AuditService", "log", "com.myapp.scheduler.BatchJob", "run"},
        {"HikariPool-1-connection-1", "Ljava/util/concurrent/locks/ReentrantLock;", "HikariPool-1-connection-2",
         "com.zaxxer.hikari.pool.HikariPool", "getConnection", "com.myapp.dao.OrderDao", "findOrders"},
    };

    private void sendLockEvent(DataOutputStream dos, long now) throws IOException {
        lockContentionCount++;
        String[] scenario = LOCK_SCENARIOS[RNG.nextInt(LOCK_SCENARIOS.length)];
        /* Alternate between CONTENDED_ENTER (1) and CONTENDED_EXIT (2) */
        int eventType = RNG.nextDouble() < 0.6 ? 1 : 2;

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(baos);
        p.writeLong(now);                            /* timestamp */
        p.writeByte(eventType);                      /* event type */
        writeStr(p, scenario[0]);                    /* waiter thread name */
        writeStr(p, scenario[1]);                    /* lock class name */
        p.writeInt(scenario[1].hashCode());          /* lock hash */
        p.writeInt(lockContentionCount);             /* total contentions */
        writeStr(p, scenario[2]);                    /* owner thread name */
        p.writeInt(1);                               /* owner entry count */
        p.writeInt(1 + RNG.nextInt(3));              /* waiter count */

        /* Stack frames (2 frames from scenario) */
        int frameCount = (scenario.length - 3) / 2;
        p.writeShort(frameCount);
        for (int f = 0; f < frameCount; f++) {
            writeStr(p, scenario[3 + f * 2]);        /* class */
            writeStr(p, scenario[4 + f * 2]);        /* method */
        }
        p.flush();
        sendMessage(dos, ProtocolConstants.MSG_LOCK_EVENT, baos.toByteArray());
    }

    private void sendSafepointEvent(DataOutputStream dos, long now) throws IOException {
        safepointCount += 5 + RNG.nextInt(10);
        safepointTimeMs += 10 + RNG.nextInt(30);
        safepointSyncMs += 1 + RNG.nextInt(5);

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(baos);
        p.writeLong(now);
        p.writeLong(safepointCount);
        p.writeLong(safepointTimeMs);
        p.writeLong(safepointSyncMs);
        p.flush();
        sendMessage(dos, ProtocolConstants.MSG_SAFEPOINT, baos.toByteArray());
    }

    private void sendClassHistogram(DataOutputStream dos, long now, long tick) throws IOException {
        /* Simulate realistic class histogram with some classes growing (leak) */
        String[][] classes = {
            {"[B", "50000", "10485760"},                  /* byte[] — 10 MB, stable */
            {"Ljava/lang/String;", "30000", "5242880"},   /* String — 5 MB, grows */
            {"[C", "28000", "4194304"},                   /* char[] — 4 MB, stable */
            {"Ljava/util/HashMap$Node;", "15000", "2097152"}, /* HashMap.Node — grows (leak!) */
            {"Ljava/lang/Object;", "12000", "1048576"},   /* Object — stable */
            {"[Ljava/lang/Object;", "8000", "3145728"},   /* Object[] — grows */
            {"Lcom/myapp/model/Order;", "5000", "2621440"}, /* app class — grows (leak!) */
            {"Lcom/myapp/model/User;", "3000", "1572864"},  /* app class — grows */
            {"Lcom/myapp/cache/CacheEntry;", "4500", "2359296"}, /* cache — grows (leak!) */
            {"Ljava/util/ArrayList;", "6000", "524288"},  /* ArrayList — grows */
            {"Ljava/util/concurrent/ConcurrentHashMap$Node;", "7000", "1048576"}, /* stable */
            {"[Ljava/util/HashMap$Node;", "3000", "786432"}, /* stable */
            {"Lcom/myapp/dto/OrderDTO;", "2000", "1048576"}, /* grows */
            {"Ljava/lang/ref/WeakReference;", "4000", "262144"}, /* stable */
            {"Lcom/myapp/event/AuditEvent;", "1500", "786432"}, /* grows (leak!) */
        };

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(baos);
        p.writeLong(now);
        p.writeLong(50000000L + RNG.nextInt(10000000)); /* elapsed nanos */
        p.writeShort(classes.length);

        for (int i = 0; i < classes.length; i++) {
            writeStr(p, classes[i][0]);
            int baseCount = Integer.parseInt(classes[i][1]);
            long baseSize = Long.parseLong(classes[i][2]);

            /* Some classes grow over time (simulating leaks) */
            int growth = 0;
            if (i == 1 || i == 3 || i == 5 || i == 6 || i == 7 || i == 8 || i == 9 || i == 12 || i == 14) {
                growth = (int)(tick * (5 + i * 3)); /* growing classes */
            }
            p.writeInt(baseCount + growth);
            p.writeLong(baseSize + (long)growth * (baseSize / baseCount));
        }
        p.flush();
        sendMessage(dos, ProtocolConstants.MSG_CLASS_HISTO, baos.toByteArray());
    }

    private void sendClassloaderStats(DataOutputStream dos, long now) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(baos);
        p.writeLong(now);
        p.writeShort(4); /* 4 loaders */
        writeStr(p, "bootstrap");
        p.writeInt(1200 + RNG.nextInt(100));
        writeStr(p, "Ljdk/internal/loader/ClassLoaders$AppClassLoader;");
        p.writeInt(350 + RNG.nextInt(50));
        writeStr(p, "Ljdk/internal/loader/ClassLoaders$PlatformClassLoader;");
        p.writeInt(80 + RNG.nextInt(20));
        writeStr(p, "Lorg/springframework/boot/loader/LaunchedURLClassLoader;");
        p.writeInt(450 + RNG.nextInt(100));
        p.flush();
        sendMessage(dos, ProtocolConstants.MSG_CLASSLOADER, baos.toByteArray());
    }

    private void sendGcDetail(DataOutputStream dos, long now) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(baos);
        p.writeLong(now);
        p.writeShort(2); /* 2 collectors */

        writeStr(p, "G1 Young Generation");
        p.writeLong(gcCount - fullGcCount);
        p.writeLong(gcCount * 8L);
        p.writeShort(2); /* pools */
        writeStr(p, "G1 Eden Space");
        writeStr(p, "G1 Survivor Space");

        writeStr(p, "G1 Old Generation");
        p.writeLong(fullGcCount);
        p.writeLong(fullGcCount * 150L);
        p.writeShort(1);
        writeStr(p, "G1 Old Gen");

        p.flush();
        sendMessage(dos, ProtocolConstants.MSG_GC_DETAIL, baos.toByteArray());
    }

    private void sendNativeMemory(DataOutputStream dos, long now) throws IOException {
        long reservedKB = heapMax / 1024 + 200000;
        long committedKB = heapUsed / 1024 + 150000;

        String nmtOutput = String.format(
                "Native Memory Tracking:\n\n" +
                "Total: reserved=%dKB, committed=%dKB\n" +
                "-                 Java Heap (reserved=%dKB, committed=%dKB)\n" +
                "-                     Class (reserved=80000KB, committed=75000KB)\n" +
                "-                    Thread (reserved=32000KB, committed=32000KB)\n" +
                "-                      Code (reserved=50000KB, committed=%dKB)\n" +
                "-                        GC (reserved=40000KB, committed=38000KB)\n" +
                "-                  Internal (reserved=5000KB, committed=4500KB)\n" +
                "-                    Symbol (reserved=12000KB, committed=11500KB)\n",
                reservedKB, committedKB,
                heapMax / 1024, heapUsed / 1024,
                30000 + jitCompiled * 10);

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(baos);
        p.writeLong(now);
        p.writeByte(1); /* available */
        writeStr(p, nmtOutput);
        p.flush();
        sendMessage(dos, ProtocolConstants.MSG_NATIVE_MEM, baos.toByteArray());
    }

    private void sendAlarm(DataOutputStream dos, long now, int alarmType, int severity,
                           double value, double threshold, String message) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(baos);
        p.writeLong(now);
        p.writeByte(alarmType);
        p.writeByte(severity);
        p.writeLong((long)(value * 1000));
        p.writeLong((long)(threshold * 1000));
        writeStr(p, message);
        p.flush();
        sendMessage(dos, ProtocolConstants.MSG_ALARM, baos.toByteArray());
    }

    /* ── Protocol helpers ────────────────────────────── */

    private static void sendMessage(DataOutputStream dos, int msgType, byte[] payload) throws IOException {
        synchronized (dos) {
            dos.writeInt(ProtocolConstants.MAGIC);
            dos.writeByte(ProtocolConstants.VERSION);
            dos.writeByte(msgType);
            dos.writeInt(payload.length);
            dos.write(payload);
            dos.flush();
        }
    }

    private static void writeStr(DataOutputStream dos, String s) throws IOException {
        byte[] bytes = s.getBytes("UTF-8");
        dos.writeShort(bytes.length);
        dos.write(bytes);
    }

    /* ── Main ────────────────────────────────────────── */

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port: " + args[0]);
                System.exit(1);
            }
        }
        new DemoAgent(port).run();
    }
}
