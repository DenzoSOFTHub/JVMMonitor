# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

All builds run from Linux. Requires gcc, x86_64-w64-mingw32-gcc, JDK 8+, and Maven 3+.

```bash
make              # Build everything: Linux + Windows + Java collector
make linux        # Linux native agent only (dist/linux/jvmmonitor.so)
make windows      # Windows native agent only (dist/windows/jvmmonitor.dll)
make java         # Java collector only (dist/jvmmonitor.jar)
make clean        # Clean all build artifacts
cd java-agent && mvn package   # Java agent (dist/jvmmonitor-agent.jar)
```

Install all build deps (Ubuntu/Debian):
```bash
sudo apt-get update && sudo apt-get install -y build-essential gcc-mingw-w64-x86-64 default-jdk maven
```

If `javac` is not on PATH: `export JAVA_HOME=/path/to/jdk`

## Test Commands

```bash
make test         # Run all tests (36 C + 120 Java)
make test-c       # C agent unit tests only
make test-java    # Java collector tests only (mvn test in collector/)
cd collector && mvn test -Dtest=ProtocolDecoderTest   # Single test class
```

C tests use a custom framework in `agent/test/test_main.c`. Java tests use JUnit 4.

## Architecture

**Two-component system**: C native agent (JVMTI) + Java collector, communicating over a custom binary TCP protocol.

### Performance Impact
- Core overhead (no modules enabled): **~1-2% CPU, ~2-5 MB RSS**
- No collector connected: near-zero impact (ring buffer drained silently)
- On-demand modules add overhead only when enabled (2-15% depending on module)
- Histogram walks heap with pause (0.5-5s) — one-shot, not continuous
- Debugger pauses thread at breakpoint only — zero overhead otherwise

### Native Agent (C) - TCP Server — Linux/Windows
- Version: 1.1.0. Entry points: `Agent_OnLoad` / `Agent_OnAttach` / `Agent_OnUnload`
- Lock-free SPSC ring buffer → TCP transport
- Wire protocol: big-endian binary, 10-byte header (magic `0x4A564D4D`, version, type, length)
- Protocol constants must stay in sync: `agent/include/jvmmon/protocol.h` ↔ `collector/.../protocol/ProtocolConstants.java`
- Crash handler: auto-saves diagnostic dump on VMDeath/signal. Signal handler uses ONLY async-signal-safe functions (write, open, close)
- Capabilities: per-capability graceful degradation (each capability requested individually, missing ones skipped)
- Runtime injection (Agent_OnAttach) fully supported: AttachCurrentThread in thread_monitor and cpu_sampler, PushLocalFrame in jmx_reader. Produces Memory, Threads, CPU Usage, OS Metrics, GC events, Thread CPU.
- Auto-activated modules at startup (Dashboard data always available): `os`, `threadcpu`, `network`, `gcdetail`
- Detach command: shuts down agent completely (stops all modules, closes transport, zero overhead). Agent becomes dormant, restartable via re-attach. Different from disconnect (which only disconnects the collector).
- Rate limiter: atomic CAS for thread-safe exception throttling
- Transport: 100 Hz idle polling (10ms sleep)
- 15 activatable modules registered in module_registry
- JMX Browser: responds to `DIAG_CMD_JMX_LIST_MBEANS` (0x33) and `DIAG_CMD_JMX_GET_ATTRS` (0x34) via JNI MBeanServer calls

### Java Agent - TCP Server — Any JVM Platform
- Version: 1.1.0. Entry points: `premain` / `agentmain`
- Pure Java using `java.lang.management` MXBeans + `java.lang.instrument` + Javassist (shaded)
- Same binary TCP protocol as C agent — collector works unchanged
- 14 modules: memory, gc, threads, cpu_usage, thread_cpu, os, classloaders, jit, cpu_sampler, histogram, deadlock, instrumentation, jvmconfig, web_probe
- Core modules (always on, auto-activated): memory, gc, threads, cpu_usage, os, jvmconfig — Dashboard charts populate without user intervention
- Bytecode instrumentation via Javassist: 8 probes (JDBC, Spring, HTTP, Messaging, Mail, Cache, Disk I/O, Socket I/O)
- Web Probe: automatic browser user action capture via Servlet bytecode injection + BeaconBridge (System.properties cross-classloader bridge)
- JvmConfigCollector: sends `RuntimeMXBean.getInputArguments()` + key system properties as `MSG_JVM_CONFIG` at startup (core)
- JMX Browser: handles `DIAG_CMD_JMX_LIST_MBEANS` / `DIAG_CMD_JMX_GET_ATTRS` via ManagementFactory.getPlatformMBeanServer(), decomposes CompositeData into `attr.subkey` pairs
- GC notification API (Java 7+) with polling fallback (Java 6)
- CPU/OS metrics via com.sun.management reflection (graceful degradation)
- CPU Usage wire format: fixed-point i64 (value * 1000), NOT IEEE double — must match C agent encoding
- Limitations: no AsyncGetCallTrace (uses dumpAllThreads fallback), no breakpoints, no field watch, no native memory tracking, no per-socket byte counters (no NetworkCollector)

### Collector (Java) - TCP Client
- Package: `it.denzosoft.jvmmonitor`. Version: 1.1.0
- Source compatibility: Java 1.6 (no diamond, no lambda, no try-with-resources)
- Zero external runtime dependencies
- Includes DenzoSOFT Java Decompiler sources (`it.denzosoft.javadecompiler`, 139 files)
- Entry point: `it.denzosoft.jvmmonitor.Main`
- Production hardening: socket cleanup on error, method cache capped at 10K, EDT crash prevention via safeRefresh(), histogram TOCTOU fixed
- Background refresh: all panels update data in background via updateData(), charts/tables always current

### GUI Tabs (17)
Dashboard, Memory, GC Analysis, Threads, Exceptions, Network, Integration, Messaging, Locks, CPU Usage, CPU Profiler, Instrumentation, System, Debugger, Tools (16 sub-tabs), JMX Browser, Settings (4 sub-tabs: Connection, GUI & Instrumentation, Alarm Thresholds, Import/Export)
- Toolbar: Connect, Attach, Disconnect, **Demo Agent** (green button — launches in-process DemoAgent on free port and auto-connects), Refresh
- Dashboard: 6 real-time mini-charts (Thread States, CPU Usage, Allocation Rate, Disk I/O, Network seg/s, Heap) + summary bar + alarms. All charts auto-populate regardless of module settings.
- Dashboard Network chart uses aggregate TCP inSegs/outSegs deltas (not per-socket bytes, which /proc/self/net/tcp does not expose)
- JMX Browser: top-level tab (not sub-tab of Tools). Loaded on-demand via Refresh button. JTree grouped by domain, click MBean → loads attributes, CompositeData auto-expanded into sub-branches. Memory/CPU charts are independent of JMX browser.
- Module activation bars: panels requiring on-demand modules (Exceptions, Locks, Network) show yellow bar with "Enable" button; turns green with "Disable" when data arrives
- Background refresh: all panels update data via updateData() in background, not just the visible one
- Attach dialog: ComboBox to choose agent type (Java Agent portable/recommended, Native Agent, Custom with file browser), shows found/not found status, detects if agent already running on port
- Instrumentation: parameter capture checkbox ("Capture params & return values") with max value length field; applies also to SQL string truncation

### CLI Commands (30+)
Full feature parity with GUI: status, memory, gc, threads, cpu, exceptions, network, locks, queues, integration, processes, os, jit, classloaders, nativemem, safepoints, histogram, profiler, instrument, diagnose, alarms, threshold, save, load, export, watch, enable, disable, modules, detach, settings (show|set|save|load)

### Key Packages
- `gui/` — Swing panels (16 main + sub-tabs, including Settings with 4 sub-tabs)
- `gui/chart/` — TimeSeriesChart, StackedAreaChart, BarChart, SparklinePanel, FlameGraph, TraceTreeTable, AlignedCellRenderer, CsvExporter, ClassNameFormatter
- `model/` — 20+ data model classes
- `storage/` — EventStore interface + InMemoryEventStore (circular buffers)
- `analysis/` — CpuProfileAggregator, HeapAnalyzer, DiagnosisEngine + 11 rules, AlarmThresholds (configurable)
  - Diagnostic rules: GcPressure, HeapGrowth, ThreadContention, ExceptionRate, ClassloaderLeak, SafepointPause, CpuSaturation, ConnectionLeak, ResponseDegradation, **GcTuning** (Full GC storm, System.gc(), promotion pressure, GC CPU, heap saturation), **JvmFlags** (-Xmx/-Xms analysis, HeapDumpOnOOM, MaxMetaspaceSize, deprecated GC, JDWP, compressed oops, ExitOnOutOfMemoryError)
- `debug/` — DecompilerBridge (wraps DenzoSOFT decompiler)
- `net/` — AgentConnection (protocol handler), AttachHelper
- `protocol/` — ProtocolConstants, MessageType, ProtocolDecoder/Encoder, EventMessage
- `demo/` — DemoAgent, DemoSession, DemoSwingSession
- `cli/` — CliMain

### Data Flow
```
JVM (JVMTI) → Agent modules → ring_buffer → TCP → Collector (ProtocolDecoder) → EventStore → CLI/GUI/DiagnosisEngine
```

### Build Outputs
```
dist/
  linux/jvmmonitor.so       # ~103 KB (native agent, Linux)
  windows/jvmmonitor.dll    # ~416 KB (native agent, Windows)
  jvmmonitor-agent.jar      # ~900 KB (Java agent, any platform, includes Javassist)
  jvmmonitor.jar            # ~830 KB (collector, includes decompiler)
```

### Demo Mode
```bash
java -cp dist/jvmmonitor.jar it.denzosoft.jvmmonitor.demo.DemoSession [durationSec] [refreshSec]
java -cp dist/jvmmonitor.jar it.denzosoft.jvmmonitor.demo.DemoSwingSession [screenshotDir]
java -cp dist/jvmmonitor.jar it.denzosoft.jvmmonitor.demo.DemoAgent [port]
```
Or use the **"Demo Agent"** button in the Swing GUI toolbar for one-click in-process demo.

## Code Style

- **All code, comments, Javadoc, documentation, and string literals must be in English.** No other languages.
- **Java 1.6 source compatibility**: No diamond `<>`, no lambda, no try-with-resources, no String switch, no multi-catch.
- **Numbers right-aligned, text left-aligned** in all tables (use `AlignedCellRenderer`).
- **All charts must have tooltips** showing values on hover, time labels on X axis, gradient fills.
- **Zero dependencies**: only JDK classes at runtime. JUnit 4 for tests.
- **Thread safety**: store methods are synchronized. GUI refresh wrapped in safeRefresh(). Volatile for snapshot-only fields.
- **Error handling**: all panel refresh() must be exception-safe. Log errors to stderr with `[JVMMonitor]` prefix.
