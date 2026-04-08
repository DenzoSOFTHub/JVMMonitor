package it.denzosoft.jvmmonitor.cli;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.model.*;
import it.denzosoft.jvmmonitor.net.AgentConnection;
import it.denzosoft.jvmmonitor.net.AttachHelper;

import it.denzosoft.jvmmonitor.analysis.AlarmThresholds;
import it.denzosoft.jvmmonitor.analysis.CpuProfileAggregator;
import it.denzosoft.jvmmonitor.storage.SessionSerializer;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * CLI interface for JVMMonitor.
 *
 * Usage:
 *   java -jar jvmmonitor.jar connect <host> <port>
 *   java -jar jvmmonitor.jar attach <pid> [--port <port>]
 *   java -jar jvmmonitor.jar list
 */
public class CliMain {

    private static JVMMonitorCollector collector;
    private static volatile boolean running = true;

    public static void main(String[] args) {
        collector = new JVMMonitorCollector();
        System.out.println("JVMMonitor v1.1.0");

        /* Parse initial command from args */
        if (args.length > 0) {
            String cmd = args[0].toLowerCase();
            if ("connect".equals(cmd) && args.length >= 3) {
                try {
                    doConnect(args[1], Integer.parseInt(args[2]));
                } catch (NumberFormatException e) {
                    System.err.println("Invalid port: " + args[2]);
                    return;
                }
            } else if ("attach".equals(cmd) && args.length >= 2) {
                int port = 9090;
                for (int i = 2; i < args.length; i++) {
                    if ("--port".equals(args[i]) && i + 1 < args.length) {
                        try { port = Integer.parseInt(args[++i]); }
                        catch (NumberFormatException e) { System.err.println("Invalid port: " + args[i]); return; }
                    }
                }
                doAttachAndConnect(args[1], port);
            } else if ("list".equals(cmd)) {
                listJvms();
                return;
            } else if ("--help".equals(cmd) || "-h".equals(cmd)) {
                printUsage();
                return;
            }
        } else {
            System.out.println("No command specified. Type 'help' for commands.");
        }

        System.out.println("Type 'help' for available commands.\n");

        /* Diagnosis thread */
        Thread diagThread = new Thread(new Runnable() {
            public void run() {
                diagnosisLoop();
            }
        }, "diagnosis-loop");
        diagThread.setDaemon(true);
        diagThread.start();

        /* Interactive command loop */
        commandLoop();

        collector.disconnect();
    }

    private static void commandLoop() {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        while (running) {
            try {
                System.out.print("jvm-monitor> ");
                String line = reader.readLine();
                if (line == null) break;
                line = line.trim();
                if (line.isEmpty()) continue;
                processCommand(line);
            } catch (IOException e) {
                break;
            }
        }
    }

    private static void processCommand(String line) {
        String[] parts = line.split("\\s+");
        String cmd = parts[0].toLowerCase();

        try {
            if ("help".equals(cmd)) {
                printHelp();
            } else if ("connect".equals(cmd)) {
                if (parts.length < 3) {
                    System.out.println("Usage: connect <host> <port>");
                } else {
                    try { doConnect(parts[1], Integer.parseInt(parts[2])); }
                    catch (NumberFormatException e) { System.out.println("Invalid port: " + parts[2]); }
                }
            } else if ("attach".equals(cmd)) {
                if (parts.length < 2) {
                    System.out.println("Usage: attach <pid> [--port <port>]");
                } else {
                    int port = 9090;
                    for (int i = 2; i < parts.length; i++) {
                        if ("--port".equals(parts[i]) && i + 1 < parts.length) {
                            try { port = Integer.parseInt(parts[++i]); }
                            catch (NumberFormatException e) { System.out.println("Invalid port"); }
                        }
                    }
                    doAttachAndConnect(parts[1], port);
                }
            } else if ("disconnect".equals(cmd)) {
                collector.disconnect();
                System.out.println("Disconnected.");
            } else if ("detach".equals(cmd)) {
                handleDetach();
            } else if ("status".equals(cmd)) {
                printStatus();
            } else if ("enable".equals(cmd)) {
                handleEnable(parts);
            } else if ("disable".equals(cmd)) {
                handleDisable(parts);
            } else if ("modules".equals(cmd)) {
                handleModules();
            } else if ("diagnose".equals(cmd)) {
                handleDiagnose();
            } else if ("threads".equals(cmd)) {
                handleThreads();
            } else if ("memory".equals(cmd)) {
                handleMemory();
            } else if ("gc".equals(cmd)) {
                handleGc();
            } else if ("alarms".equals(cmd)) {
                handleAlarms();
            } else if ("exceptions".equals(cmd)) {
                handleExceptions();
            } else if ("os".equals(cmd)) {
                handleOs();
            } else if ("jit".equals(cmd)) {
                handleJit();
            } else if ("classloaders".equals(cmd)) {
                handleClassloaders();
            } else if ("nativemem".equals(cmd)) {
                handleNativeMemory();
            } else if ("safepoints".equals(cmd)) {
                handleSafepoints();
            } else if ("histogram".equals(cmd)) {
                handleHistogram();
            } else if ("gcdetail".equals(cmd)) {
                handleGcDetail();
            } else if ("cpu".equals(cmd)) {
                handleCpu();
            } else if ("network".equals(cmd) || "net".equals(cmd)) {
                handleNetwork();
            } else if ("locks".equals(cmd)) {
                handleLocks();
            } else if ("queues".equals(cmd) || "messaging".equals(cmd)) {
                handleQueues();
            } else if ("integration".equals(cmd)) {
                handleIntegration();
            } else if ("processes".equals(cmd) || "ps".equals(cmd)) {
                handleProcesses();
            } else if ("profiler".equals(cmd)) {
                handleProfiler(parts);
            } else if ("instrument".equals(cmd) || "instr".equals(cmd)) {
                handleInstrument(parts);
            } else if ("save".equals(cmd)) {
                handleSave(parts);
            } else if ("load".equals(cmd)) {
                handleLoad(parts);
            } else if ("export".equals(cmd)) {
                handleExport(parts);
            } else if ("threshold".equals(cmd) || "thresholds".equals(cmd)) {
                handleThreshold(parts);
            } else if ("classes".equals(cmd)) {
                handleClasses(parts);
            } else if ("decompile".equals(cmd)) {
                handleDecompile(parts);
            } else if ("settings".equals(cmd) || "config".equals(cmd)) {
                handleSettings(parts);
            } else if ("watch".equals(cmd)) {
                handleWatch(parts);
            } else if ("list".equals(cmd)) {
                listJvms();
            } else if ("quit".equals(cmd) || "exit".equals(cmd)) {
                running = false;
            } else {
                System.out.println("Unknown command: " + cmd + ". Type 'help' for commands.");
            }
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    private static void doConnect(String host, int port) {
        try {
            collector.connect(host, port);
            Thread.sleep(500); /* let handshake arrive */
            AgentConnection conn = collector.getConnection();
            if (conn != null && conn.isConnected()) {
                System.out.println("Connected to agent PID " + conn.getAgentPid() +
                        " @ " + conn.getAgentHostname() + " (" + conn.getJvmInfo() + ")");
            } else {
                System.out.println("Connected to " + host + ":" + port + " (waiting for handshake...)");
            }
        } catch (Exception e) {
            System.out.println("Failed to connect to " + host + ":" + port + " — " + e.getMessage());
        }
    }

    private static void doAttachAndConnect(String pid, int port) {
        try {
            String options = "port=" + port;

            /* Try Java agent first (portable), fall back to native */
            String javaAgent = findJavaAgentPath();
            if (javaAgent != null) {
                System.out.println("Injecting Java agent into PID " + pid + " (port " + port + ")...");
                System.out.println("Agent: " + javaAgent);
                AttachHelper.attachJavaAgent(pid, javaAgent, options);
            } else {
                String nativeAgent = findAgentLibrary();
                if (nativeAgent == null || !new java.io.File(nativeAgent).isFile()) {
                    System.out.println("Agent not found. Place jvmmonitor-agent.jar or jvmmonitor.so in the current directory or dist/.");
                    return;
                }
                System.out.println("Injecting native agent into PID " + pid + " (port " + port + ")...");
                System.out.println("Agent: " + nativeAgent);
                AttachHelper.attach(pid, nativeAgent, options);
            }
            System.out.println("Agent injected. Connecting...");

            Thread.sleep(1500); /* let agent start listening */
            doConnect("127.0.0.1", port);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            System.out.println("Failed to attach: " + msg);
        }
    }

    private static String getJarDir() {
        try {
            String path = CliMain.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI().getPath();
            return new java.io.File(path).getParent();
        } catch (Exception e) {
            return null;
        }
    }

    private static String findJavaAgentPath() {
        String cwd = System.getProperty("user.dir");
        String jarDir = getJarDir();
        java.util.List paths = new java.util.ArrayList();
        if (jarDir != null) {
            paths.add(jarDir + "/jvmmonitor-agent.jar");
            paths.add(jarDir + "/../jvmmonitor-agent.jar");
        }
        paths.add(cwd + "/jvmmonitor-agent.jar");
        paths.add(cwd + "/dist/jvmmonitor-agent.jar");
        for (int i = 0; i < paths.size(); i++) {
            java.io.File f = new java.io.File((String) paths.get(i));
            if (f.isFile()) return f.getAbsolutePath();
        }
        return null;
    }

    private static void handleEnable(String[] parts) throws IOException {
        if (parts.length < 3) {
            System.out.println("Usage: enable <module> <level> [--target <class.method>] [--duration <sec>]");
            return;
        }
        String module = parts[1];
        int level;
        try { level = Integer.parseInt(parts[2]); }
        catch (NumberFormatException e) { System.out.println("Invalid level: " + parts[2]); return; }
        String target = null;
        int duration = 300;

        for (int i = 3; i < parts.length; i++) {
            if ("--target".equals(parts[i]) && i + 1 < parts.length) {
                target = parts[++i];
            } else if ("--duration".equals(parts[i]) && i + 1 < parts.length) {
                try { duration = Integer.parseInt(parts[++i]); }
                catch (NumberFormatException e) { System.out.println("Invalid duration"); }
            }
        }

        AgentConnection conn = collector.getConnection();
        if (conn == null || !conn.isConnected()) {
            System.out.println("No agent connected. Use 'connect' or 'attach' first.");
            return;
        }

        conn.enableModule(module, level, target, duration);
        System.out.println(module.toUpperCase() + " -> Level " + level +
                (target != null ? " (target: " + target + ")" : "") +
                " [duration: " + duration + "s]");
    }

    private static void handleDisable(String[] parts) throws IOException {
        if (parts.length < 2) {
            System.out.println("Usage: disable <module>");
            return;
        }
        AgentConnection conn = collector.getConnection();
        if (conn == null || !conn.isConnected()) {
            System.out.println("No agent connected.");
            return;
        }
        conn.disableModule(parts[1]);
        System.out.println(parts[1].toUpperCase() + " -> Level 0 (disabled)");
    }

    private static void handleModules() throws IOException {
        AgentConnection conn = collector.getConnection();
        if (conn == null || !conn.isConnected()) {
            System.out.println("No agent connected.");
            return;
        }
        conn.requestModuleList();
        try { Thread.sleep(500); } catch (InterruptedException e) { /* ignore */ }
        List<ModuleStatus> statuses = conn.getModuleStatuses();
        if (statuses.isEmpty()) {
            System.out.println("No module data received (try again).");
            return;
        }
        System.out.println(String.format("%-20s %-10s %-10s %s",
                "MODULE", "LEVEL", "MAX", "STATUS"));
        for (int i = 0; i < statuses.size(); i++) {
            ModuleStatus ms = statuses.get(i);
            System.out.println(String.format("%-20s %-10d %-10d %s",
                    ms.getName(), ms.getCurrentLevel(), ms.getMaxLevel(),
                    ms.isActive() ? "ACTIVE" : "off"));
        }
    }

    private static void printStatus() {
        AgentConnection conn = collector.getConnection();
        System.out.println("=== JVMMonitor Status ===");
        if (conn != null && conn.isConnected()) {
            System.out.println("Agent:  CONNECTED (PID " + conn.getAgentPid() +
                    " @ " + conn.getAgentHostname() + ")");
            System.out.println("JVM:    " + conn.getJvmInfo());
        } else {
            System.out.println("Agent:  NOT CONNECTED");
        }

        System.out.println("Events: CPU=" + collector.getStore().getCpuSampleCount() +
                " GC=" + collector.getStore().getGcEventCount() +
                " Mem=" + collector.getStore().getMemorySnapshotCount() +
                " Exc=" + collector.getStore().getExceptionCount() +
                " JIT=" + collector.getStore().getJitEventCount());

        MemorySnapshot mem = collector.getStore().getLatestMemorySnapshot();
        if (mem != null) {
            System.out.println("Heap:   " + mem.getHeapUsedMB() + " / " + mem.getHeapMaxMB() +
                    String.format(" (%.1f%%)", mem.getHeapUsagePercent()));
        }

        List<AlarmEvent> alarms = collector.getStore().getActiveAlarms();
        if (!alarms.isEmpty()) {
            System.out.println("Alarms: " + alarms.size() + " active");
            for (int i = 0; i < alarms.size(); i++) {
                AlarmEvent a = alarms.get(i);
                System.out.println("  " + AlarmEvent.severityToString(a.getSeverity()) +
                        " " + a.getMessage());
            }
        } else {
            System.out.println("Alarms: none");
        }
    }

    private static void handleDiagnose() {
        List<Diagnosis> diagnoses = collector.getDiagnosisEngine().runDiagnostics();
        if (diagnoses.isEmpty()) {
            System.out.println("No issues detected.");
        } else {
            for (int i = 0; i < diagnoses.size(); i++) {
                System.out.println(diagnoses.get(i).toString());
            }
        }
    }

    private static void handleThreads() {
        List<ThreadInfo> threads = collector.getStore().getLatestThreadInfo();
        if (threads.isEmpty()) {
            System.out.println("No thread data available.");
            return;
        }
        System.out.println(String.format("%-6s %-30s %-15s %s", "ID", "NAME", "STATE", "DAEMON"));
        for (int i = 0; i < threads.size(); i++) {
            ThreadInfo t = threads.get(i);
            System.out.println(String.format("%-6d %-30s %-15s %s",
                    t.getThreadId(), truncate(t.getName(), 30),
                    ThreadInfo.stateToString(t.getState()),
                    t.isDaemon() ? "yes" : "no"));
        }
    }

    private static void handleMemory() {
        MemorySnapshot mem = collector.getStore().getLatestMemorySnapshot();
        if (mem == null) {
            System.out.println("No memory data available.");
            return;
        }
        System.out.println("=== Memory ===");
        System.out.println("Heap:     " + mem.getHeapUsedMB() + " / " + mem.getHeapMaxMB() +
                String.format(" (%.1f%%)", mem.getHeapUsagePercent()));
        System.out.println(String.format("Non-Heap: %.1f MB / %.1f MB",
                mem.getNonHeapUsed() / (1024.0 * 1024.0),
                mem.getNonHeapMax() / (1024.0 * 1024.0)));
        double growth = collector.getAnalysisContext().getHeapGrowthRateMBPerHour(5);
        System.out.println(String.format("Growth:   %.1f MB/h (last 5 min trend)", growth));
    }

    private static void handleGc() {
        it.denzosoft.jvmmonitor.analysis.AnalysisContext ctx = collector.getAnalysisContext();
        System.out.println("=== GC (last 60s) ===");
        System.out.println(String.format("Frequency:  %.0f GC/min", ctx.getGcFrequencyPerMinute(60)));
        System.out.println(String.format("Avg Pause:  %.1f ms", ctx.getAvgGcPauseMs(60)));
        System.out.println(String.format("Max Pause:  %.1f ms", ctx.getMaxGcPauseMs(60)));
        System.out.println(String.format("Throughput: %.1f%%", ctx.getGcThroughputPercent(60)));
    }

    private static void handleAlarms() {
        List<AlarmEvent> alarms = collector.getStore().getActiveAlarms();
        if (alarms.isEmpty()) {
            System.out.println("No active alarms.");
        } else {
            for (int i = 0; i < alarms.size(); i++) {
                AlarmEvent a = alarms.get(i);
                System.out.println(AlarmEvent.severityToString(a.getSeverity()) + " " +
                        a.getAlarmTypeName() + ": " + a.getMessage() +
                        String.format(" (value=%.1f, threshold=%.1f)", a.getValue(), a.getThreshold()));
            }
        }
    }

    private static void handleExceptions() {
        long now = System.currentTimeMillis();
        List<ExceptionEvent> events = collector.getStore().getExceptions(now - 60000, now);
        if (events.isEmpty()) {
            System.out.println("No exception data available (enable exceptions module first).");
            return;
        }
        System.out.println("=== Exceptions (last 60s) ===");
        ExceptionEvent latest = events.get(events.size() - 1);
        System.out.println(String.format("Total thrown:  %d", latest.getTotalThrown()));
        System.out.println(String.format("Total caught:  %d", latest.getTotalCaught()));
        System.out.println(String.format("Rate:          %.0f/min", events.size() * 60.0 / 60));
        System.out.println(String.format("%-40s %-30s %s", "EXCEPTION", "THROWN AT", "CAUGHT?"));
        int shown = Math.min(events.size(), 20);
        for (int i = events.size() - shown; i < events.size(); i++) {
            ExceptionEvent e = events.get(i);
            System.out.println(String.format("%-40s %-30s %s",
                    truncate(e.getDisplayName(), 40),
                    truncate(e.getThrowClass() + "." + e.getThrowMethod(), 30),
                    e.isCaught() ? "yes" : "NO"));
        }
    }

    private static void handleOs() {
        OsMetrics os = collector.getStore().getLatestOsMetrics();
        if (os == null) {
            System.out.println("No OS metrics available (enable os module first).");
            return;
        }
        System.out.println("=== OS Metrics ===");
        System.out.println(String.format("RSS:          %.1f MB", os.getRssMB()));
        System.out.println(String.format("VM Size:      %.1f MB", os.getVmSizeMB()));
        System.out.println(String.format("Open FDs:     %d", os.getOpenFileDescriptors()));
        System.out.println(String.format("OS Threads:   %d", os.getOsThreadCount()));
        System.out.println(String.format("TCP:          %d established, %d close_wait, %d time_wait",
                os.getTcpEstablished(), os.getTcpCloseWait(), os.getTcpTimeWait()));
        System.out.println(String.format("Ctx Switches: %d voluntary, %d involuntary",
                os.getVoluntaryContextSwitches(), os.getInvoluntaryContextSwitches()));
    }

    private static void handleJit() {
        long now = System.currentTimeMillis();
        List<JitEvent> events = collector.getStore().getJitEvents(now - 60000, now);
        if (events.isEmpty()) {
            System.out.println("No JIT data available (enable jit module first).");
            return;
        }
        int compiled = 0, unloaded = 0;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).getEventType() == JitEvent.COMPILED) compiled++;
            else if (events.get(i).getEventType() == JitEvent.UNLOADED) unloaded++;
        }
        System.out.println("=== JIT (last 60s) ===");
        System.out.println(String.format("Compiled: %d, Deoptimized: %d", compiled, unloaded));
        JitEvent latest = events.get(events.size() - 1);
        System.out.println(String.format("Total compiled (cumulative): %d", latest.getTotalCompiled()));
        System.out.println("Recent compilations:");
        int shown = Math.min(events.size(), 10);
        for (int i = events.size() - shown; i < events.size(); i++) {
            JitEvent e = events.get(i);
            if (e.getEventType() == JitEvent.COMPILED && !e.getClassName().isEmpty()) {
                System.out.println(String.format("  %s %s.%s (%d bytes)",
                        e.getTypeName(), e.getClassName(), e.getMethodName(), e.getCodeSize()));
            }
        }
    }

    private static void handleClassloaders() {
        ClassloaderStats stats = collector.getStore().getLatestClassloaderStats();
        if (stats == null) {
            System.out.println("No classloader data available (enable classloaders module first).");
            return;
        }
        System.out.println("=== Classloaders ===");
        System.out.println(String.format("%-50s %s", "CLASSLOADER", "CLASSES"));
        ClassloaderStats.LoaderInfo[] loaders = stats.getLoaders();
        for (int i = 0; i < loaders.length; i++) {
            System.out.println(String.format("%-50s %d",
                    truncate(loaders[i].getLoaderClass(), 50),
                    loaders[i].getClassCount()));
        }
        System.out.println(String.format("Total: %d loaders, %d classes",
                stats.getLoaderCount(), stats.getTotalClassCount()));
    }

    private static void handleNativeMemory() {
        NativeMemoryStats nms = collector.getStore().getLatestNativeMemory();
        if (nms == null) {
            System.out.println("No native memory data available (enable nativemem module first).");
            return;
        }
        System.out.println("=== Native Memory ===");
        if (nms.isAvailable()) {
            System.out.println(nms.getRawOutput());
        } else {
            System.out.println(nms.getRawOutput());
        }
    }

    private static void handleSafepoints() {
        SafepointEvent sp = collector.getStore().getLatestSafepoint();
        if (sp == null) {
            System.out.println("No safepoint data available (enable safepoint module first).");
            return;
        }
        System.out.println("=== Safepoints ===");
        if (sp.isAvailable()) {
            System.out.println(String.format("Count:     %d", sp.getSafepointCount()));
            System.out.println(String.format("Total:     %d ms", sp.getTotalTimeMs()));
            System.out.println(String.format("Sync time: %d ms", sp.getSyncTimeMs()));
            if (sp.getSafepointCount() > 0) {
                System.out.println(String.format("Avg time:  %.1f ms",
                        (double) sp.getTotalTimeMs() / sp.getSafepointCount()));
                System.out.println(String.format("Avg sync:  %.1f ms",
                        (double) sp.getSyncTimeMs() / sp.getSafepointCount()));
            }
        } else {
            System.out.println("Safepoint data not available on this JVM.");
        }
    }

    private static void handleHistogram() {
        ClassHistogram histo = collector.getStore().getLatestClassHistogram();
        if (histo == null) {
            System.out.println("No class histogram available (enable histogram module first).");
            return;
        }
        System.out.println(String.format("=== Class Histogram (took %.1fms) ===",
                histo.getElapsedMs()));
        System.out.println(String.format("%-50s %10s %12s", "CLASS", "INSTANCES", "SIZE"));
        ClassHistogram.Entry[] entries = histo.getEntries();
        int shown = Math.min(entries.length, 30);
        for (int i = 0; i < shown; i++) {
            if (entries[i] != null) {
                System.out.println(String.format("%-50s %10d %10.1f MB",
                        truncate(it.denzosoft.jvmmonitor.gui.chart.ClassNameFormatter.format(entries[i].getClassName()), 50),
                        entries[i].getInstanceCount(),
                        entries[i].getTotalSizeMB()));
            }
        }
    }

    private static void handleGcDetail() {
        GcDetail detail = collector.getStore().getLatestGcDetail();
        if (detail == null) {
            System.out.println("No GC detail data available (enable gcdetail module first).");
            return;
        }
        System.out.println("=== GC Collectors ===");
        GcDetail.CollectorInfo[] collectors = detail.getCollectors();
        for (int i = 0; i < collectors.length; i++) {
            GcDetail.CollectorInfo c = collectors[i];
            System.out.println(String.format("%-30s collections=%d  time=%dms",
                    c.getName(), c.getCollectionCount(), c.getCollectionTimeMs()));
            String[] pools = c.getMemoryPools();
            if (pools != null && pools.length > 0) {
                StringBuilder sb = new StringBuilder("  Pools: ");
                for (int p = 0; p < pools.length; p++) {
                    if (p > 0) sb.append(", ");
                    sb.append(pools[p]);
                }
                System.out.println(sb.toString());
            }
        }
    }

    /* ── CPU Usage ──────────────────────────────────── */

    private static void handleCpu() {
        CpuUsageSnapshot cpu = collector.getStore().getLatestCpuUsage();
        if (cpu == null) {
            System.out.println("No CPU data available.");
            return;
        }
        System.out.println("=== CPU Usage ===");
        System.out.println(String.format("System CPU:  %.1f%%", cpu.getSystemCpuPercent()));
        System.out.println(String.format("JVM CPU:     %.1f%%", cpu.getProcessCpuPercent()));
        System.out.println(String.format("Available:   %.1f%%", cpu.getAvailableCpuPercent()));
        System.out.println(String.format("Processors:  %d", cpu.getAvailableProcessors()));
        System.out.println(String.format("User time:   %d ms", cpu.getProcessUserTimeMs()));
        System.out.println(String.format("System time: %d ms", cpu.getProcessSystemTimeMs()));

        CpuUsageSnapshot.ThreadCpuInfo[] threads = cpu.getTopThreads();
        if (threads != null && threads.length > 0) {
            System.out.println(String.format("\n%-6s %-30s %8s %8s %s", "TID", "NAME", "CPU%", "TIME", "STATE"));
            int shown = Math.min(threads.length, 15);
            for (int i = 0; i < shown; i++) {
                CpuUsageSnapshot.ThreadCpuInfo t = threads[i];
                System.out.println(String.format("%-6d %-30s %7.1f%% %6dms %s",
                        t.threadId, truncate(t.threadName, 30),
                        t.cpuPercent, t.cpuTimeMs, t.state));
            }
        }
    }

    /* ── Network ──────────────────────────────────── */

    private static void handleNetwork() {
        NetworkSnapshot net = collector.getStore().getLatestNetworkSnapshot();
        if (net == null) {
            System.out.println("No network data available (enable network module first).");
            return;
        }
        System.out.println("=== Network ===");
        System.out.println(String.format("Established: %d  Close_Wait: %d  Time_Wait: %d  Listen: %d",
                net.getEstablishedCount(), net.getCloseWaitCount(),
                net.getTimeWaitCount(), net.getListenCount()));
        System.out.println(String.format("Segments in: %d  out: %d  retrans: %d  errors: %d",
                net.getInSegments(), net.getOutSegments(),
                net.getRetransSegments(), net.getInErrors()));

        NetworkSnapshot.SocketInfo[] sockets = net.getSockets();
        if (sockets != null && sockets.length > 0) {
            System.out.println(String.format("\n%-4s %-25s %-12s %8s %10s %10s %s",
                    "DIR", "DESTINATION", "STATE", "REQS", "BYTES_IN", "BYTES_OUT", "SERVICE"));
            for (int i = 0; i < sockets.length; i++) {
                if (sockets[i] == null) continue;
                NetworkSnapshot.SocketInfo s = sockets[i];
                System.out.println(String.format("%-4s %-25s %-12s %8d %10s %10s %s",
                        s.getDirection(), truncate(s.getDestination(), 25),
                        s.getStateName(), s.requestCount,
                        s.formatBytes(s.bytesIn), s.formatBytes(s.bytesOut),
                        s.serviceName));
            }
        }
    }

    /* ── Locks ────────────────────────────────────── */

    private static void handleLocks() {
        long now = System.currentTimeMillis();
        List<LockEvent> events = collector.getStore().getLockEvents(now - 60000, now);
        if (events.isEmpty()) {
            System.out.println("No lock data available (enable locks module first).");
            return;
        }
        /* Aggregate by lock */
        Map<String, int[]> hotspots = new LinkedHashMap<String, int[]>();
        for (int i = 0; i < events.size(); i++) {
            LockEvent e = events.get(i);
            if (e.getEventType() != LockEvent.CONTENDED_ENTER) continue;
            String key = e.getLockDisplayName();
            int[] cnt = hotspots.get(key);
            if (cnt == null) { cnt = new int[]{0}; hotspots.put(key, cnt); }
            cnt[0]++;
        }

        System.out.println("=== Lock Contention (last 60s: " + events.size() + " events) ===");
        System.out.println(String.format("%-50s %s", "LOCK", "CONTENTIONS"));
        List<Map.Entry<String, int[]>> sorted = new ArrayList<Map.Entry<String, int[]>>(hotspots.entrySet());
        Collections.sort(sorted, new Comparator<Map.Entry<String, int[]>>() {
            public int compare(Map.Entry<String, int[]> a, Map.Entry<String, int[]> b) {
                return b.getValue()[0] - a.getValue()[0];
            }
        });
        int shown = Math.min(sorted.size(), 20);
        for (int i = 0; i < shown; i++) {
            System.out.println(String.format("%-50s %d",
                    truncate(sorted.get(i).getKey(), 50), sorted.get(i).getValue()[0]));
        }

        /* Recent events */
        System.out.println(String.format("\n%-20s %-30s %-20s %s",
                "THREAD", "LOCK", "OWNER", "WAITERS"));
        int evShown = Math.min(events.size(), 10);
        for (int i = events.size() - evShown; i < events.size(); i++) {
            LockEvent e = events.get(i);
            System.out.println(String.format("%-20s %-30s %-20s %d",
                    truncate(e.getThreadName(), 20),
                    truncate(e.getLockDisplayName(), 30),
                    truncate(e.getOwnerThreadName(), 20),
                    e.getWaiterCount()));
        }
    }

    /* ── Queues / Messaging ───────────────────────── */

    private static void handleQueues() {
        QueueStats qs = collector.getStore().getLatestQueueStats();
        if (qs == null || qs.getQueueCount() == 0) {
            System.out.println("No messaging data available.");
            return;
        }
        System.out.println("=== Message Queues ===");
        System.out.println(String.format("%-25s %-10s %8s %8s %8s %8s %8s %8s",
                "QUEUE", "TYPE", "DEPTH", "ENQ/s", "DEQ/s", "CONSUM", "LAG", "AGE(ms)"));
        QueueStats.QueueInfo[] queues = qs.getQueues();
        for (int i = 0; i < queues.length; i++) {
            if (queues[i] == null) continue;
            QueueStats.QueueInfo q = queues[i];
            System.out.println(String.format("%-25s %-10s %8d %8d %8d %8d %8d %8d",
                    truncate(q.name, 25), q.type, q.depth, q.enqueueRate,
                    q.dequeueRate, q.consumerCount, q.consumerLag, q.oldestMessageAge));
        }
    }

    /* ── Integration ──────────────────────────────── */

    private static void handleIntegration() {
        NetworkSnapshot net = collector.getStore().getLatestNetworkSnapshot();
        if (net == null) {
            System.out.println("No network data available.");
            return;
        }
        /* Group sockets by remote address */
        Map<String, int[]> systems = new LinkedHashMap<String, int[]>();
        Map<String, long[]> traffic = new LinkedHashMap<String, long[]>();
        NetworkSnapshot.SocketInfo[] sockets = net.getSockets();
        if (sockets == null) return;
        for (int i = 0; i < sockets.length; i++) {
            if (sockets[i] == null) continue;
            String dest = sockets[i].getDestination();
            if (dest == null || dest.isEmpty()) continue;
            String ip = dest.contains(":") ? dest.substring(0, dest.lastIndexOf(':')) : dest;
            int[] cnt = systems.get(ip);
            if (cnt == null) { cnt = new int[]{0}; systems.put(ip, cnt); }
            cnt[0]++;
            long[] t = traffic.get(ip);
            if (t == null) { t = new long[]{0, 0}; traffic.put(ip, t); }
            t[0] += sockets[i].bytesIn;
            t[1] += sockets[i].bytesOut;
        }
        System.out.println("=== External Systems ===");
        System.out.println(String.format("%-25s %8s %12s %12s",
                "SYSTEM", "CONNS", "BYTES_IN", "BYTES_OUT"));
        for (Map.Entry<String, int[]> entry : systems.entrySet()) {
            long[] t = traffic.get(entry.getKey());
            System.out.println(String.format("%-25s %8d %12s %12s",
                    entry.getKey(), entry.getValue()[0],
                    formatBytes(t[0]), formatBytes(t[1])));
        }
    }

    /* ── Processes ────────────────────────────────── */

    private static void handleProcesses() {
        ProcessInfo info = collector.getStore().getLatestProcessInfo();
        if (info == null) {
            System.out.println("No process data available.");
            return;
        }
        System.out.println("=== System Processes ===");
        System.out.println(String.format("System memory: %.0f MB total, %.0f MB free",
                info.getTotalMemoryBytes() / (1024.0 * 1024.0),
                info.getFreeMemoryBytes() / (1024.0 * 1024.0)));
        ProcessInfo.ProcessEntry[] procs = info.getTopProcesses();
        if (procs != null && procs.length > 0) {
            System.out.println(String.format("\n%-8s %-30s %8s %10s %8s",
                    "PID", "NAME", "CPU%", "RSS(MB)", "THREADS"));
            int shown = Math.min(procs.length, 20);
            for (int i = 0; i < shown; i++) {
                if (procs[i] == null) continue;
                System.out.println(String.format("%-8d %-30s %7.1f%% %10.1f %8d",
                        procs[i].pid, truncate(procs[i].name, 30),
                        procs[i].cpuPercent,
                        procs[i].rssBytes / (1024.0 * 1024.0),
                        procs[i].threads));
            }
        }
    }

    /* ── CPU Profiler ─────────────────────────────── */

    private static long profilerStartTs = 0;

    private static void handleProfiler(String[] parts) {
        if (parts.length < 2) {
            System.out.println("Usage: profiler start|stop|status|hotmethods");
            return;
        }
        String sub = parts[1].toLowerCase();
        if ("start".equals(sub)) {
            profilerStartTs = System.currentTimeMillis();
            AgentConnection conn = collector.getConnection();
            if (conn != null && conn.isConnected()) {
                try { conn.enableModule("alloc", 1, null, 0); } catch (Exception e) { /* ignore */ }
            }
            System.out.println("CPU profiler started. Collecting samples...");
        } else if ("stop".equals(sub) || "hotmethods".equals(sub)) {
            if (profilerStartTs == 0) {
                System.out.println("Profiler not started. Use 'profiler start' first.");
                return;
            }
            long now = System.currentTimeMillis();
            List<CpuSample> samples = collector.getStore().getCpuSamples(profilerStartTs, now);
            if (samples.isEmpty()) {
                System.out.println("No CPU samples collected.");
                return;
            }

            /* Resolve names from method cache */
            AgentConnection conn = collector.getConnection();
            Map methodCache = conn != null ? conn.getMethodNameCache() : new HashMap();
            for (int i = 0; i < samples.size(); i++) {
                CpuSample s = samples.get(i);
                CpuSample.StackFrame[] frames = s.getFrames();
                if (frames != null) {
                    for (int f = 0; f < frames.length; f++) {
                        if (frames[f].getClassName() == null) {
                            String[] names = (String[]) methodCache.get(Long.valueOf(frames[f].getMethodId()));
                            if (names != null) {
                                frames[f].setClassName(names[0]);
                                frames[f].setMethodName(names[1]);
                            }
                        }
                    }
                }
            }

            CpuProfileAggregator agg = new CpuProfileAggregator();
            agg.aggregate(samples);

            System.out.println("=== CPU Profiler (" + samples.size() + " samples, " +
                    ((now - profilerStartTs) / 1000) + "s) ===");
            System.out.println(String.format("%-50s %8s %6s", "METHOD", "SAMPLES", "%"));
            List<CpuProfileAggregator.MethodNode> hot = agg.getTopMethods(20);
            for (int i = 0; i < hot.size(); i++) {
                CpuProfileAggregator.MethodNode m = hot.get(i);
                double pct = m.selfPercent(samples.size());
                System.out.println(String.format("%-50s %8d %5.1f%%",
                        truncate(m.displayName, 50), m.selfCount, pct));
            }

            if ("stop".equals(sub)) {
                profilerStartTs = 0;
                System.out.println("Profiler stopped.");
            }
        } else if ("status".equals(sub)) {
            if (profilerStartTs > 0) {
                long elapsed = (System.currentTimeMillis() - profilerStartTs) / 1000;
                long samples = collector.getStore().getCpuSampleCount();
                System.out.println("Profiler running for " + elapsed + "s, " + samples + " samples.");
            } else {
                System.out.println("Profiler not running.");
            }
        } else {
            System.out.println("Usage: profiler start|stop|status|hotmethods");
        }
    }

    /* ── Instrumentation ──────────────────────────── */

    private static long instrStartTs = 0;

    private static void handleInstrument(String[] parts) {
        if (parts.length < 2) {
            System.out.println("Usage: instrument start [packages] [probes] | stop | events | jdbc | http");
            return;
        }
        String sub = parts[1].toLowerCase();
        if ("start".equals(sub)) {
            instrStartTs = System.currentTimeMillis();
            AgentConnection conn = collector.getConnection();
            if (conn != null && conn.isConnected()) {
                try {
                    String[] pkgs = parts.length > 2 ? parts[2].split(",") : new String[]{"com.myapp"};
                    String[] probes = parts.length > 3 ? parts[3].split(",")
                            : new String[]{"jdbc", "spring", "http", "messaging"};
                    conn.sendInstrumentationConfig(pkgs, probes);
                    conn.startInstrumentation();
                    System.out.println("Instrumentation started. Packages: " + join(pkgs) +
                            " Probes: " + join(probes));
                } catch (Exception e) {
                    System.out.println("Failed: " + e.getMessage());
                }
            } else {
                System.out.println("No agent connected.");
            }
        } else if ("stop".equals(sub)) {
            AgentConnection conn = collector.getConnection();
            if (conn != null && conn.isConnected()) {
                try { conn.stopInstrumentation(); } catch (Exception e) { /* ignore */ }
            }
            System.out.println("Instrumentation stopped.");
        } else if ("events".equals(sub)) {
            showInstrEvents(false);
        } else if ("jdbc".equals(sub)) {
            showJdbcEvents();
        } else if ("http".equals(sub)) {
            showHttpEvents();
        } else {
            System.out.println("Usage: instrument start [packages] [probes] | stop | events | jdbc | http");
        }
    }

    private static void showInstrEvents(boolean filterHttp) {
        long now = System.currentTimeMillis();
        long from = instrStartTs > 0 ? instrStartTs : now - 300000;
        List<InstrumentationEvent> events = collector.getStore().getInstrumentationEvents(from, now);
        if (events.isEmpty()) {
            System.out.println("No instrumentation events.");
            return;
        }
        /* Aggregate by method */
        Map<String, long[]> stats = new LinkedHashMap<String, long[]>();
        for (int i = 0; i < events.size(); i++) {
            InstrumentationEvent e = events.get(i);
            if (e.getDurationNanos() <= 0) continue;
            String key = e.getDisplayClassName() + "." + e.getMethodName();
            long[] s = stats.get(key);
            if (s == null) { s = new long[3]; stats.put(key, s); }
            s[0] += e.getDurationNanos();
            s[1]++;
            if (e.getDurationNanos() > s[2]) s[2] = e.getDurationNanos();
        }
        /* Sort by total duration */
        List<Map.Entry<String, long[]>> sorted = new ArrayList<Map.Entry<String, long[]>>(stats.entrySet());
        Collections.sort(sorted, new Comparator<Map.Entry<String, long[]>>() {
            public int compare(Map.Entry<String, long[]> a, Map.Entry<String, long[]> b) {
                return Long.compare(b.getValue()[0], a.getValue()[0]);
            }
        });

        System.out.println("=== Instrumented Methods (" + events.size() + " events) ===");
        System.out.println(String.format("%-50s %8s %10s %10s %10s", "METHOD", "CALLS", "TOTAL(ms)", "AVG(ms)", "MAX(ms)"));
        int shown = Math.min(sorted.size(), 20);
        for (int i = 0; i < shown; i++) {
            Map.Entry<String, long[]> entry = sorted.get(i);
            long[] s = entry.getValue();
            System.out.println(String.format("%-50s %8d %10.0f %10.1f %10.1f",
                    truncate(entry.getKey(), 50), s[1],
                    s[0] / 1000000.0, s[0] / (double) s[1] / 1000000.0,
                    s[2] / 1000000.0));
        }
    }

    private static void showJdbcEvents() {
        long now = System.currentTimeMillis();
        long from = instrStartTs > 0 ? instrStartTs : now - 300000;
        List<InstrumentationEvent> events = collector.getStore().getInstrumentationEvents(from, now);
        List<InstrumentationEvent> jdbc = new ArrayList<InstrumentationEvent>();
        for (int i = 0; i < events.size(); i++) {
            int t = events.get(i).getEventType();
            if (t == InstrumentationEvent.TYPE_JDBC_QUERY) jdbc.add(events.get(i));
        }
        if (jdbc.isEmpty()) {
            System.out.println("No JDBC events.");
            return;
        }
        /* Aggregate by SQL */
        Map<String, long[]> sqlStats = new LinkedHashMap<String, long[]>();
        for (int i = 0; i < jdbc.size(); i++) {
            String sql = jdbc.get(i).getContext();
            if (sql.length() > 60) sql = sql.substring(0, 60);
            long[] s = sqlStats.get(sql);
            if (s == null) { s = new long[3]; sqlStats.put(sql, s); }
            s[0] += jdbc.get(i).getDurationNanos();
            s[1]++;
            if (jdbc.get(i).getDurationNanos() > s[2]) s[2] = jdbc.get(i).getDurationNanos();
        }
        System.out.println("=== JDBC Queries (" + jdbc.size() + " queries) ===");
        System.out.println(String.format("%-60s %6s %10s %10s", "SQL", "COUNT", "AVG(ms)", "MAX(ms)"));
        for (Map.Entry<String, long[]> entry : sqlStats.entrySet()) {
            long[] s = entry.getValue();
            System.out.println(String.format("%-60s %6d %10.1f %10.1f",
                    truncate(entry.getKey(), 60), s[1],
                    s[0] / (double) s[1] / 1000000.0, s[2] / 1000000.0));
        }
    }

    private static void showHttpEvents() {
        long now = System.currentTimeMillis();
        long from = instrStartTs > 0 ? instrStartTs : now - 300000;
        List<InstrumentationEvent> events = collector.getStore().getInstrumentationEvents(from, now);
        List<InstrumentationEvent> http = new ArrayList<InstrumentationEvent>();
        for (int i = 0; i < events.size(); i++) {
            InstrumentationEvent e = events.get(i);
            if (e.getDepth() == 0 && e.getContext() != null) {
                String ctx = e.getContext().trim();
                if (ctx.startsWith("GET ") || ctx.startsWith("POST ") || ctx.startsWith("PUT ")
                    || ctx.startsWith("DELETE ") || ctx.startsWith("PATCH ") || ctx.startsWith("HEAD ")
                    || ctx.startsWith("OPTIONS ")) {
                    http.add(e);
                }
            }
        }
        if (http.isEmpty()) {
            System.out.println("No HTTP events.");
            return;
        }
        /* Aggregate by URL */
        Map<String, long[]> urlStats = new LinkedHashMap<String, long[]>();
        for (int i = 0; i < http.size(); i++) {
            String url = http.get(i).getContext();
            long[] s = urlStats.get(url);
            if (s == null) { s = new long[4]; urlStats.put(url, s); }
            s[0] += http.get(i).getDurationNanos();
            s[1]++;
            if (http.get(i).getDurationNanos() > s[2]) s[2] = http.get(i).getDurationNanos();
            if (http.get(i).isException()) s[3]++;
        }
        System.out.println("=== HTTP Requests (" + http.size() + ") ===");
        System.out.println(String.format("%-40s %6s %10s %10s %6s", "URL", "COUNT", "AVG(ms)", "MAX(ms)", "ERRS"));
        for (Map.Entry<String, long[]> entry : urlStats.entrySet()) {
            long[] s = entry.getValue();
            System.out.println(String.format("%-40s %6d %10.1f %10.1f %6d",
                    truncate(entry.getKey(), 40), s[1],
                    s[0] / (double) s[1] / 1000000.0, s[2] / 1000000.0, s[3]));
        }
    }

    /* ── Session Save / Load ──────────────────────── */

    private static void handleSave(String[] parts) {
        String filename = parts.length > 1 ? parts[1]
                : "jvmmonitor-session-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".jvmsession.gz";
        try {
            long start = System.currentTimeMillis();
            SessionSerializer.save(collector.getStore(), new File(filename));
            long elapsed = System.currentTimeMillis() - start;
            long size = new File(filename).length();
            System.out.println("Session saved to " + filename +
                    " (" + formatBytes(size) + ", " + elapsed + "ms)");
        } catch (Exception e) {
            System.out.println("Save failed: " + e.getMessage());
        }
    }

    private static void handleLoad(String[] parts) {
        if (parts.length < 2) {
            System.out.println("Usage: load <filename.jvmsession.gz>");
            return;
        }
        try {
            long start = System.currentTimeMillis();
            long saveTs = SessionSerializer.load(collector.getStore(), new File(parts[1]));
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("Session loaded from " + parts[1] + " (" + elapsed + "ms)");
            System.out.println("Saved at: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(saveTs)));
            System.out.println("Use 'status', 'memory', 'threads' etc. to view loaded data.");
        } catch (Exception e) {
            System.out.println("Load failed: " + e.getMessage());
        }
    }

    /* ── Export HTML ───────────────────────────────── */

    private static void handleExport(String[] parts) {
        String filename = parts.length > 1 ? parts[1]
                : "jvmmonitor-report-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".html";
        try {
            long now = System.currentTimeMillis();
            long from = now - 300000;
            it.denzosoft.jvmmonitor.storage.EventStore store = collector.getStore();

            FileWriter fw = new FileWriter(filename);
            fw.write("<!DOCTYPE html><html><head><meta charset='UTF-8'>\n");
            fw.write("<title>JVMMonitor CLI Report</title>\n");
            fw.write("<style>body{font-family:monospace;margin:20px;font-size:13px}" +
                    "table{border-collapse:collapse;margin:10px 0}" +
                    "th,td{border:1px solid #ddd;padding:4px 8px;text-align:left}" +
                    "th{background:#f5f5f5}</style></head><body>\n");
            fw.write("<h1>JVMMonitor Report</h1>\n");
            fw.write("<p>Generated: " + new Date() + "</p>\n");

            /* Memory */
            MemorySnapshot mem = store.getLatestMemorySnapshot();
            if (mem != null) {
                fw.write("<h2>Memory</h2><p>Heap: " + mem.getHeapUsedMB() + " / " + mem.getHeapMaxMB() +
                        String.format(" (%.1f%%)</p>\n", mem.getHeapUsagePercent()));
            }

            /* Threads */
            List<ThreadInfo> threads = store.getLatestThreadInfo();
            fw.write("<h2>Threads (" + threads.size() + ")</h2>\n");
            fw.write("<table><tr><th>ID</th><th>Name</th><th>State</th></tr>\n");
            for (int i = 0; i < threads.size(); i++) {
                ThreadInfo t = threads.get(i);
                fw.write("<tr><td>" + t.getThreadId() + "</td><td>" + t.getName() +
                        "</td><td>" + ThreadInfo.stateToString(t.getState()) + "</td></tr>\n");
            }
            fw.write("</table>\n");

            /* Diagnostics */
            List<Diagnosis> diag = collector.getDiagnosisEngine().runDiagnostics();
            fw.write("<h2>Diagnostics (" + diag.size() + ")</h2>\n");
            for (int i = 0; i < diag.size(); i++) {
                fw.write("<pre>" + diag.get(i).toString() + "</pre>\n");
            }

            fw.write("</body></html>\n");
            fw.close();
            System.out.println("Report exported to " + filename);
        } catch (Exception e) {
            System.out.println("Export failed: " + e.getMessage());
        }
    }

    /* ── Alarm Thresholds ─────────────────────────── */

    private static void handleThreshold(String[] parts) {
        AlarmThresholds t = collector.getThresholds();
        if (parts.length < 2) {
            System.out.println("Usage: threshold show | save <file> | load <file> | set <key> <value>");
            return;
        }
        String sub = parts[1].toLowerCase();
        if ("show".equals(sub)) {
            Map<String, String> map = t.toMap();
            System.out.println(String.format("%-35s %s", "PARAMETER", "VALUE"));
            for (Map.Entry<String, String> entry : map.entrySet()) {
                System.out.println(String.format("%-35s %s", entry.getKey(), entry.getValue()));
            }
        } else if ("save".equals(sub)) {
            String file = parts.length > 2 ? parts[2] : "jvmmonitor.thresholds";
            try {
                t.save(new File(file));
                System.out.println("Thresholds saved to " + file);
            } catch (Exception e) {
                System.out.println("Save failed: " + e.getMessage());
            }
        } else if ("load".equals(sub)) {
            if (parts.length < 3) {
                System.out.println("Usage: threshold load <file>");
                return;
            }
            try {
                t.load(new File(parts[2]));
                System.out.println("Thresholds loaded from " + parts[2]);
            } catch (Exception e) {
                System.out.println("Load failed: " + e.getMessage());
            }
        } else if ("set".equals(sub)) {
            if (parts.length < 4) {
                System.out.println("Usage: threshold set <key> <value>");
                System.out.println("Use 'threshold show' to see available keys.");
                return;
            }
            /* Save current, modify, reload */
            try {
                File tmp = File.createTempFile("jvmmon", ".tmp");
                t.save(tmp);
                /* Append the new value */
                FileWriter fw = new FileWriter(tmp, true);
                fw.write(parts[2] + "=" + parts[3] + "\n");
                fw.close();
                t.load(tmp);
                tmp.delete();
                System.out.println(parts[2] + " = " + parts[3]);
            } catch (Exception e) {
                System.out.println("Set failed: " + e.getMessage());
            }
        } else {
            System.out.println("Usage: threshold show | save <file> | load <file> | set <key> <value>");
        }
    }

    /* ── Watch mode ───────────────────────────────── */

    /* ── Settings ──────────────────────────── */

    /* ── Classes / Decompile ────────────────── */

    private static void handleClasses(String[] parts) {
        AgentConnection conn = collector.getConnection();
        if (conn == null || !conn.isConnected()) {
            System.out.println("Not connected to any agent.");
            return;
        }
        String filter = parts.length > 1 ? parts[1] : "";
        System.out.println("Loading classes" + (filter.length() > 0 ? " matching " + filter + "..." : " (all)..."));

        /* Register listener for CLASS_INFO responses */
        final java.util.List classNames = new java.util.ArrayList();
        conn.setDiagnosticListener(new AgentConnection.DiagnosticListener() {
            public void onDiagnosticMessage(it.denzosoft.jvmmonitor.protocol.MessageType type,
                                             it.denzosoft.jvmmonitor.protocol.EventMessage msg) {
                if (type == it.denzosoft.jvmmonitor.protocol.MessageType.CLASS_INFO) {
                    int off = 8;
                    if (off + 2 > msg.getPayloadLength()) return;
                    int count = msg.readU16(off); off += 2;
                    for (int i = 0; i < count && off + 2 <= msg.getPayloadLength(); i++) {
                        String name = msg.readString(off);
                        off += msg.stringFieldLength(off);
                        if (name != null && name.length() > 0) classNames.add(name);
                    }
                }
            }
        });

        try {
            conn.listClasses(filter);
            Thread.sleep(2000); /* wait for response */
        } catch (Exception e) {
            System.out.println("Failed: " + e.getMessage());
            return;
        }

        /* Filter out inner classes from display */
        java.util.List outerClasses = new java.util.ArrayList();
        int innerCount = 0;
        for (int i = 0; i < classNames.size(); i++) {
            String name = (String) classNames.get(i);
            String simpleName = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
            if (simpleName.contains("$")) {
                innerCount++;
            } else {
                outerClasses.add(name);
            }
        }

        java.util.Collections.sort(outerClasses);
        System.out.println(outerClasses.size() + " classes (" + innerCount + " inner classes hidden):");
        for (int i = 0; i < outerClasses.size(); i++) {
            /* Count inner classes for this outer */
            String outer = (String) outerClasses.get(i);
            int inners = 0;
            for (int j = 0; j < classNames.size(); j++) {
                String cls = (String) classNames.get(j);
                if (cls.startsWith(outer + "$")) inners++;
            }
            String suffix = inners > 0 ? " (+" + inners + " inner)" : "";
            System.out.println("  " + outer + suffix);
        }
    }

    private static void handleDecompile(String[] parts) {
        if (parts.length < 2) {
            System.out.println("Usage: decompile <className>");
            System.out.println("  e.g.: decompile com.myapp.service.OrderService");
            return;
        }
        AgentConnection conn = collector.getConnection();
        if (conn == null || !conn.isConnected()) {
            System.out.println("Not connected to any agent.");
            return;
        }
        String className = parts[1];
        System.out.println("Requesting bytecode for " + className + " (+ inner classes)...");
        try {
            conn.debugGetClassBytes(className);
            /* Also request inner classes */
            /* We need the class list to find inners — do a quick list */
            final java.util.List allNames = new java.util.ArrayList();
            conn.setDiagnosticListener(new AgentConnection.DiagnosticListener() {
                public void onDiagnosticMessage(it.denzosoft.jvmmonitor.protocol.MessageType type,
                                                 it.denzosoft.jvmmonitor.protocol.EventMessage msg) {
                    if (type == it.denzosoft.jvmmonitor.protocol.MessageType.CLASS_INFO) {
                        int off = 8;
                        if (off + 2 > msg.getPayloadLength()) return;
                        int count = msg.readU16(off); off += 2;
                        for (int i = 0; i < count && off + 2 <= msg.getPayloadLength(); i++) {
                            String name = msg.readString(off);
                            off += msg.stringFieldLength(off);
                            if (name != null) allNames.add(name);
                        }
                    }
                }
            });
            String pkg = className.contains(".") ? className.substring(0, className.lastIndexOf('.')) : "";
            conn.listClasses(pkg);
            Thread.sleep(2000);

            int innerRequested = 0;
            for (int i = 0; i < allNames.size(); i++) {
                String cls = (String) allNames.get(i);
                if (cls.startsWith(className + "$")) {
                    conn.debugGetClassBytes(cls);
                    innerRequested++;
                }
            }

            System.out.println("Decompilation requested for " + className +
                    (innerRequested > 0 ? " + " + innerRequested + " inner class(es)" : ""));
            System.out.println("Note: decompiled source appears in the GUI Source Viewer tab.");
            System.out.println("Full bytecode decompilation requires the DenzoSOFT Java Decompiler (included in GUI).");
        } catch (Exception e) {
            System.out.println("Failed: " + e.getMessage());
        }
    }

    private static void handleDetach() {
        AgentConnection conn = collector.getConnection();
        if (conn == null || !conn.isConnected()) {
            System.out.println("Not connected to any agent.");
            return;
        }
        try {
            System.out.println("Sending DETACH command to agent...");
            System.out.println("This will stop all agent modules, close the transport,");
            System.out.println("and remove all instrumentation. The agent becomes dormant.");
            System.out.println("To restart, re-attach the agent to the JVM.");
            conn.sendCommand(it.denzosoft.jvmmonitor.protocol.ProtocolConstants.CMD_DETACH, new byte[0]);
            Thread.sleep(500);
            collector.disconnect();
            System.out.println("Agent detached and disconnected.");
        } catch (Exception e) {
            System.out.println("Detach failed: " + e.getMessage());
        }
    }

    private static void handleSettings(String[] parts) {
        if (parts.length < 2) {
            System.out.println("Usage: settings show | save <file> | load <file> | set <key> <value>");
            System.out.println();
            System.out.println("Keys:");
            System.out.println("  connection.host        Host to connect to (default: 127.0.0.1)");
            System.out.println("  connection.port        Port to connect to (default: 9090)");
            System.out.println("  instr.defaultPackages  Default packages for instrumentation");
            System.out.println("  instr.captureParams    Capture params & return values (true/false, default: false)");
            System.out.println("  instr.maxValueLength   Max chars per value (-1=unlimited, default: 500)");
            System.out.println();
            System.out.println("  + all 'threshold' keys (use 'threshold show' to list)");
            return;
        }
        String sub = parts[1].toLowerCase();
        if ("show".equals(sub)) {
            System.out.println(String.format("%-30s %s", "PARAMETER", "VALUE"));
            System.out.println(String.format("%-30s %s", "connection.host", "127.0.0.1"));
            System.out.println(String.format("%-30s %s", "connection.port", "9090"));
            System.out.println(String.format("%-30s %s", "instr.defaultPackages", "com.myapp"));
            System.out.println(String.format("%-30s %s", "instr.captureParams", "false"));
            System.out.println(String.format("%-30s %s", "instr.maxValueLength", "500"));
            System.out.println("---");
            /* Also show thresholds */
            Map map = collector.getThresholds().toMap();
            Iterator it = map.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry entry = (Map.Entry) it.next();
                System.out.println(String.format("%-30s %s", entry.getKey(), entry.getValue()));
            }
        } else if ("save".equals(sub)) {
            String file = parts.length > 2 ? parts[2] : "jvmmonitor.settings";
            try {
                BufferedWriter w = new BufferedWriter(new FileWriter(file));
                w.write("# JVMMonitor Settings v1.1.0\n\n");
                w.write("# Connection\n");
                w.write("connection.host=127.0.0.1\n");
                w.write("connection.port=9090\n\n");
                w.write("# Instrumentation\n");
                w.write("instr.defaultPackages=com.myapp\n");
                w.write("instr.captureParams=false\n");
                w.write("instr.maxValueLength=500\n\n");
                w.write("# Alarm Thresholds\n");
                collector.getThresholds().save(new File(file + ".tmp"));
                BufferedReader r = new BufferedReader(new FileReader(file + ".tmp"));
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.startsWith("#")) w.write(line + "\n");
                }
                r.close();
                new File(file + ".tmp").delete();
                w.close();
                System.out.println("Settings saved to " + file);
            } catch (Exception e) {
                System.out.println("Save failed: " + e.getMessage());
            }
        } else if ("load".equals(sub)) {
            if (parts.length < 3) {
                System.out.println("Usage: settings load <file>");
                return;
            }
            try {
                collector.getThresholds().load(new File(parts[2]));
                System.out.println("Settings loaded from " + parts[2]);
            } catch (Exception e) {
                System.out.println("Load failed: " + e.getMessage());
            }
        } else if ("set".equals(sub)) {
            if (parts.length < 4) {
                System.out.println("Usage: settings set <key> <value>");
                return;
            }
            /* Delegate to threshold if it's a threshold key */
            String key = parts[2];
            String val = parts[3];
            Map map = collector.getThresholds().toMap();
            if (map.containsKey(key)) {
                String[] thParts = new String[]{"threshold", "set", key, val};
                handleThreshold(thParts);
            } else {
                System.out.println("Setting " + key + " = " + val);
                System.out.println("Note: connection and instrumentation settings take effect on next connect/start.");
            }
        } else {
            System.out.println("Usage: settings show | save <file> | load <file> | set <key> <value>");
        }
    }

    private static void handleWatch(String[] parts) {
        if (parts.length < 2) {
            System.out.println("Usage: watch <command> [interval_sec]");
            System.out.println("  e.g.: watch status 5");
            System.out.println("  e.g.: watch cpu 2");
            System.out.println("Press Enter to stop watching.");
            return;
        }
        final String watchCmd = parts[1];
        int interval = 5;
        if (parts.length > 2) {
            try { interval = Integer.parseInt(parts[2]); }
            catch (NumberFormatException e) { System.out.println("Invalid interval, using default 5s"); }
        }
        final int intervalMs = interval * 1000;

        System.out.println("Watching '" + watchCmd + "' every " + interval + "s. Press Enter to stop.\n");

        final boolean[] watching = new boolean[]{true};
        /* Background thread for auto-refresh */
        Thread watchThread = new Thread(new Runnable() {
            public void run() {
                while (watching[0]) {
                    try {
                        /* Clear screen effect */
                        System.out.println("\n--- " +
                                new SimpleDateFormat("HH:mm:ss").format(new Date()) +
                                " ---");
                        processCommand(watchCmd);
                        Thread.sleep(intervalMs);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }, "watch-thread");
        watchThread.setDaemon(true);
        watchThread.start();

        /* Wait for Enter to stop */
        try {
            new BufferedReader(new InputStreamReader(System.in)).readLine();
        } catch (IOException e) { /* ignore */ }
        watching[0] = false;
        watchThread.interrupt();
        System.out.println("Watch stopped.");
    }

    /* ── Utility ──────────────────────────────────── */

    private static String formatBytes(long bytes) {
        if (bytes > 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        if (bytes > 1024) return String.format("%.1f KB", bytes / 1024.0);
        return bytes + " B";
    }

    private static String join(String[] arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(arr[i]);
        }
        return sb.toString();
    }

    private static void diagnosisLoop() {
        while (running) {
            try {
                Thread.sleep(10000);
                AgentConnection conn = collector.getConnection();
                if (conn != null && conn.isConnected()) {
                    List<Diagnosis> diagnoses = collector.getDiagnosisEngine().runDiagnostics();
                    for (int i = 0; i < diagnoses.size(); i++) {
                        Diagnosis d = diagnoses.get(i);
                        if (d.getSeverity() >= 2) {
                            System.out.println("\n! " + d.getCategory() + ": " + d.getSummary());
                            if (d.getSuggestedAction() != null) {
                                System.out.println("  -> " + d.getSuggestedAction());
                            }
                            System.out.print("jvm-monitor> ");
                        }
                    }
                }
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private static void listJvms() {
        try {
            List<String[]> vms = AttachHelper.listJvms();
            System.out.println(String.format("%-8s %s", "PID", "NAME"));
            for (int i = 0; i < vms.size(); i++) {
                System.out.println(String.format("%-8s %s", vms.get(i)[0], vms.get(i)[1]));
            }
        } catch (Exception e) {
            System.err.println("Error listing JVMs: " + e.getMessage());
        }
    }

    private static String findAgentLibrary() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        String libName = osName.contains("win") ? "jvmmonitor.dll" : "jvmmonitor.so";
        String subDir = osName.contains("win") ? "windows" : "linux";
        String cwd = System.getProperty("user.dir");
        String jarDir = getJarDir();

        java.util.List paths = new java.util.ArrayList();
        if (jarDir != null) {
            paths.add(jarDir + "/" + subDir + "/" + libName);
            paths.add(jarDir + "/" + libName);
            paths.add(jarDir + "/../" + subDir + "/" + libName);
        }
        paths.add(cwd + "/" + libName);
        paths.add(cwd + "/dist/" + subDir + "/" + libName);
        paths.add(cwd + "/dist/" + libName);
        paths.add("/usr/local/lib/" + libName);

        for (int i = 0; i < paths.size(); i++) {
            java.io.File f = new java.io.File((String) paths.get(i));
            if (f.isFile()) return f.getAbsolutePath();
        }
        return null;
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java -jar jvmmonitor.jar connect <host> <port>");
        System.out.println("  java -jar jvmmonitor.jar attach <pid> [--port <port>]");
        System.out.println("  java -jar jvmmonitor.jar list");
        System.out.println("  java -jar jvmmonitor.jar --help");
    }

    private static void printHelp() {
        System.out.println("Connection:");
        System.out.println("  list                            List available JVMs");
        System.out.println("  attach <pid> [--port <port>]    Inject agent and connect");
        System.out.println("  connect <host> <port>           Connect to running agent");
        System.out.println("  disconnect                      Disconnect from agent (agent keeps running)");
        System.out.println("  detach                          Shutdown agent completely (zero overhead, re-attachable)");
        System.out.println();
        System.out.println("Monitoring:");
        System.out.println("  status                          Overview (heap, events, alarms)");
        System.out.println("  memory                          Heap/non-heap usage + growth rate");
        System.out.println("  gc                              GC frequency, pauses, throughput");
        System.out.println("  gcdetail                        Per-collector GC details");
        System.out.println("  threads                         Thread states table");
        System.out.println("  cpu                             CPU usage + per-thread CPU (hot threads)");
        System.out.println("  exceptions                      Recent exceptions + hotspots");
        System.out.println("  network                         TCP connections, bytes, services");
        System.out.println("  locks                           Lock contention hotspots + events");
        System.out.println("  queues                          Message queue depth, rates, lag");
        System.out.println("  integration                     External systems by IP");
        System.out.println("  os                              RSS, FDs, TCP, context switches");
        System.out.println("  processes                       OS process list (CPU, RSS)");
        System.out.println("  jit                             JIT compilation events");
        System.out.println("  classloaders                    Classloader breakdown");
        System.out.println("  nativemem                       Native memory (NMT)");
        System.out.println("  safepoints                      Safepoint statistics");
        System.out.println("  histogram                       Class histogram");
        System.out.println();
        System.out.println("Profiling:");
        System.out.println("  profiler start|stop|hotmethods  CPU sampling profiler");
        System.out.println("  instrument start [pkgs] [probes]  Start method instrumentation");
        System.out.println("  instrument stop|events|jdbc|http  View instrumentation data");
        System.out.println();
        System.out.println("Analysis:");
        System.out.println("  diagnose                        Run all diagnostic rules now");
        System.out.println("  alarms                          Show active alarms");
        System.out.println("  threshold show|set|save|load    Configure alarm thresholds");
        System.out.println();
        System.out.println("Session:");
        System.out.println("  save [file]                     Save session (.jvmsession.gz)");
        System.out.println("  load <file>                     Load session for replay");
        System.out.println("  export [file]                   Export HTML report");
        System.out.println();
        System.out.println("Agent:");
        System.out.println("  enable <module> <level>         Activate module at level");
        System.out.println("    [--target <class.method>]     Target specific code");
        System.out.println("    [--duration <seconds>]        Auto-disable after N seconds");
        System.out.println("  disable <module>                Deactivate module");
        System.out.println("  modules                         List agent modules and status");
        System.out.println();
        System.out.println("Classes:");
        System.out.println("  classes [package]               List loaded classes (e.g. classes com.myapp)");
        System.out.println("  decompile <className>           Decompile class + inner classes");
        System.out.println();
        System.out.println("Settings:");
        System.out.println("  settings show                   Show all configurable parameters");
        System.out.println("  settings set <key> <value>      Change a setting");
        System.out.println("  settings save <file>            Save all settings to file");
        System.out.println("  settings load <file>            Load settings from file");
        System.out.println();
        System.out.println("Tools:");
        System.out.println("  watch <command> [interval_sec]  Auto-refresh (e.g. watch cpu 2)");
        System.out.println("  quit                            Exit");
        System.out.println();
        System.out.println("Modules: alloc, locks, memory, resources, io, method,");
        System.out.println("         exceptions, os, jit, histogram, safepoint,");
        System.out.println("         nativemem, gcdetail, classloaders, strings");
        System.out.println("Levels:  0=off, 1=statistical, 2=detailed, 3=surgical");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
