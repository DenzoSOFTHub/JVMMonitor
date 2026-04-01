# JVMMonitor v1.0.0

A comprehensive JVM profiling, monitoring, and diagnostic tool. A native JVMTI agent collects performance data from a running JVM and streams it over TCP to a Java collector with CLI, Swing GUI, and integrated Java decompiler.

**Java 1.6+ compatible | Zero external dependencies | ~830 KB single JAR**

## Features

### Performance Monitoring
- **CPU Profiling** — sampling-based profiler with flame graph, call tree, hot methods (live update during recording)
- **CPU Usage** — system vs JVM CPU% with 5-period moving average, per-thread CPU delta, hot thread detection, spike auto-capture
- **Memory Monitoring** — heap/non-heap area charts with gradient fills, allocation rate (MB/s), live data set (heap after Full GC only)
- **Memory Pools** — visual bars for Eden, Survivor, Old Gen, Metaspace with used/max
- **GC Analysis** — rectangle chart (width=duration, height=freed), CPU vs GC bubble correlation, cause table, promotion rate (Eden → Old Gen)
- **Thread Monitoring** — stacked area chart (BLOCKED bottom → RUNNABLE top), thread activity by layer (DATABASE, NETWORK, MESSAGING, etc.), thread name/package filter

### Diagnostics
- **Exception Tracking** — rate chart, hotspot detection with code location, stack traces, top exception classes
- **Lock Analysis** — contention rate, lock hotspots with code location, owner/waiter tracking, longest wait events
- **Intelligent Alarms** — 9 rule-based engines with 16+ contextual alarms (not naive thresholds):
  - Memory: live set trend on Full GC (not heap %), old gen exhaustion, allocation pressure
  - GC: throughput degradation, long pauses
  - CPU: sustained saturation with GC correlation, runaway thread detection
  - Threads: lock contention with hotspot ID, deadlock, thread leak
  - Exceptions: rate spike vs baseline (3x), uncaught (critical), recurring bug detection
  - Network: TCP connection leak (CLOSE_WAIT trend), retransmit degradation
  - Response time: degradation vs baseline, slow JDBC queries, connection pool exhaustion
  - Classloader leak, safepoint issues
- **Configurable Thresholds** — all alarm thresholds editable via GUI (Tools > Alarm Config) or CLI, save/load to `.thresholds` files
- **Memory Leak Detection** — old gen analysis, class histogram comparison (delta columns), leak suspects, big objects (avg instance >1KB)
- **Allocation Recording** — start/stop recording, aggregate by class with allocation site

### Network & Integration
- **Network Monitor** — per-connection traffic (bytes in/out, requests, direction), service name, queue backpressure, connection leak/stuck detection
- **Integration Map** — all external systems by IP with auto-detected protocol (HTTP/REST, DATABASE, CACHE, MESSAGING, MAIL, FTP, DIRECTORY)
- **Messaging** — queue depth, enqueue/dequeue rates, consumer lag, alerts for backlog/stale messages (JMS, Kafka, RabbitMQ)

### Instrumentation & Debugging
- **Instrumentation** — JVMTI method entry/exit tracing with 8 configurable probes (JDBC/JPA, Spring, HTTP, JMS/Kafka/RabbitMQ, JavaMail, Cache/Redis, Disk I/O, Socket I/O)
- **Request Tracer** — end-to-end call chain visualization as expandable tree-table (TraceTreeTable) with timing per method
- **JDBC Monitor** — 3 sub-tabs: SQL Statistics (aggregated by query), SQL Events (individual executions), Connection Monitor (open connections with thread, duration, leak detection)
- **HTTP Profiler** — HTTP request tracking with method, URL, status, duration (filtered to HTTP methods only)
- **Remote Debugger** — enable/disable toggle, conditional breakpoints, step over/into/out, variable inspection, watch expressions
- **Source Viewer** — request class bytecode from agent, decompile on-the-fly with DenzoSOFT Java Decompiler, syntax highlighting (keywords, strings, comments, annotations, numbers)

### Advanced Tools
- **Field Watch** — monitor field read/write events with old/new values and access location
- **Thread Control** — suspend/resume/stop individual threads
- **Deadlock Detection** — automatic cycle detection with chain visualization
- **GC Root Analysis** — trace reference chains to find why objects can't be collected, force GC button
- **Monitor Ownership Map** — which threads hold which locks, who's waiting
- **Object Lifetime** — start/stop tracking, lifetime distribution histogram (short-lived / old gen / never freed)
- **Hot Swap** — live code patching via JVMTI RedefineClasses (upload .class file)
- **Heap Dump** — trigger .hprof dump on agent host
- **Thread Dump** — full stack dump with save to file
- **JVM Configuration** — startup parameters (-Xmx, -XX flags), system properties, classpath
- **JMX MBean Browser** — navigate and inspect MBeans
- **Agent Modules** — progressive profiling levels (0=off, 1=statistical, 2=detailed, 3=surgical)

### System & Process
- **OS Metrics** — RSS, file descriptors, TCP connections, context switches, safepoints
- **Process Monitor** — all OS processes with CPU%, RSS, threads
- **Native Memory** — NMT integration for memory category breakdown
- **Classloader Stats** — per-loader class count, leak detection
- **JIT Monitoring** — compilation events, deoptimization tracking
- **Crash Handler** — automatic diagnostic dump on JVM death or signal (SIGSEGV, SIGABRT, SIGBUS)

### Session & Export
- **Session Save/Load** — save entire monitoring session to compressed binary file (`.jvmsession.gz`), reload for offline replay across all panels
- **CSV Export** — right-click context menu on all tables for CSV export with file chooser
- **HTML Session Report** — comprehensive export including all collected metrics, charts summary, thread states, GC stats, exceptions, network, locks, CPU, alarms, diagnostics

### Charts
- Time-series line/area charts with gradient fills and tooltips (colored bullets, cursor line + dots on hover)
- Stacked area charts with tooltips
- Horizontal bar charts for rankings
- Flame graph for CPU profiling (live update during recording)
- Sparkline mini-charts for dashboard
- All charts show HH:mm:ss time labels on X axis
- Right-aligned numbers, left-aligned text in all tables (AlignedCellRenderer)

## Quick Start

### Build

```bash
# Prerequisites: gcc, mingw (for Windows cross-compile), JDK 8+, Maven 3+
sudo apt-get install -y build-essential gcc-mingw-w64-x86-64 default-jdk maven

make        # Builds dist/linux/jvmmonitor.so + dist/windows/jvmmonitor.dll + dist/jvmmonitor.jar
```

### Run

```bash
# Option 1: Start agent with application
java -agentpath:dist/linux/jvmmonitor.so=port=9090 -jar your-app.jar

# Option 2: Inject agent at runtime
java -jar dist/jvmmonitor.jar attach <PID> --port 9090

# Option 3: Swing GUI (default)
java -jar dist/jvmmonitor.jar
java -jar dist/jvmmonitor.jar gui

# Option 4: CLI (interactive)
java -jar dist/jvmmonitor.jar cli
java -jar dist/jvmmonitor.jar connect 127.0.0.1 9090

# Demo mode (no real JVM needed)
java -cp dist/jvmmonitor.jar it.denzosoft.jvmmonitor.demo.DemoSession
```

### Agent Options

Comma-separated in `-agentpath` or attach:

| Option | Default | Description |
|---|---|---|
| `port` | 9090 | TCP port the agent listens on |
| `loglevel` | info | error, info, support, debug |
| `logfile` | jvmmonitor-agent.log | Agent log file path |
| `interval` | 10 | CPU sampling interval (ms) |
| `monitor_interval` | 1000 | Memory/thread polling interval (ms) |

## GUI Tabs (15)

| Tab | Description |
|---|---|
| **Dashboard** | 6 live mini-charts (threads stacked, CPU, alloc rate, disk I/O, network bytes delta, heap with live set), alarms, event counters |
| **Memory** | Heap/non-heap area charts, alloc rate, live data set. Sub-tabs: Histogram (manual request, delta columns), Old Gen, Leak Suspects, Memory Pools, Big Objects, Allocation Recording |
| **GC Analysis** | 2x2 grid: rectangle chart, CPU vs GC correlation, promotion rate, throughput. Bottom tabs: GC Events, GC Collectors |
| **Threads** | State stacked area (BLOCKED→RUNNABLE) + thread activity by layer (DB, Network, Messaging, etc.) + thread table with color-coded states |
| **Exceptions** | Rate chart + hotspots (code location) + events with stack trace |
| **Network** | Per-connection traffic (bytes in/out, TX/RX queue), service detection, connection leak/stuck warnings |
| **Integration** | External systems by IP, protocol auto-detection (HTTP, DB, Cache, Mail, FTP, LDAP), calls/min, bytes in/out |
| **Messaging** | Queue depth/rates, consumer lag, alerts for backlog/stale messages (JMS, Kafka, RabbitMQ) |
| **Locks** | Contention hotspots (code location), lock events with owner/waiter, longest wait events |
| **CPU Usage** | System/JVM CPU with 5-period moving avg + thread CPU delta + hot threads + spike auto-capture log |
| **CPU Profiler** | Start/stop recording, flame graph (live update every 2s), call tree (TraceTreeTable), hot methods |
| **Instrumentation** | 8 probes (+ Disk I/O, Socket I/O off by default), method profiler, request tracer (TraceTreeTable), JDBC monitor (3 sub-tabs), HTTP profiler, Disk I/O, Socket I/O, all events |
| **System** | OS charts, processes, NMT, classloaders, JIT compiler |
| **Debugger** | Enable/disable toggle, conditional breakpoints, stepping, variable watch, decompiled source viewer with syntax highlighting |
| **Tools** | 17 sub-tabs: field watch, thread dump, deadlock detection, GC roots, JVM config, JMX browser, heap dump, thread control, monitor map, object lifetime, hot swap, agent modules, auto-diagnostics, alarm config, session |

## CLI Commands

The CLI provides full feature parity with the GUI. Interactive prompt with `jvm-monitor>`:

### Connection
| Command | Description |
|---|---|
| `list` | List available JVMs (via Attach API) |
| `attach <pid> [--port <port>]` | Inject agent into running JVM and connect |
| `connect <host> <port>` | Connect to an agent already listening |
| `disconnect` | Disconnect from agent |

### Monitoring
| Command | Description |
|---|---|
| `status` | Overview: heap, events, alarms |
| `memory` | Heap/non-heap usage + growth rate |
| `gc` | GC frequency, pauses, throughput |
| `gcdetail` | Per-collector GC details |
| `threads` | Thread states table |
| `cpu` | CPU system/JVM % + per-thread hot threads |
| `exceptions` | Recent exceptions + throw locations |
| `network` | TCP connections, bytes in/out, services |
| `locks` | Lock contention hotspots + events |
| `queues` | Message queue depth, rates, consumer lag |
| `integration` | External systems aggregated by IP |
| `os` | RSS, FDs, TCP states, context switches |
| `processes` | OS process list (CPU%, RSS, threads) |
| `jit` | JIT compilation events |
| `classloaders` | Classloader breakdown |
| `nativemem` | Native memory (NMT) |
| `safepoints` | Safepoint statistics |
| `histogram` | Class histogram |

### Profiling
| Command | Description |
|---|---|
| `profiler start\|stop\|hotmethods` | CPU sampling profiler with top methods |
| `instrument start [pkgs] [probes]` | Start method instrumentation |
| `instrument stop\|events\|jdbc\|http` | View instrumentation results |

### Analysis & Alarms
| Command | Description |
|---|---|
| `diagnose` | Run all diagnostic rules |
| `alarms` | Show active alarms |
| `threshold show` | Display all configurable thresholds |
| `threshold set <key> <value>` | Change a threshold value |
| `threshold save <file>` | Save thresholds to file |
| `threshold load <file>` | Load thresholds from file |

### Session & Tools
| Command | Description |
|---|---|
| `save [file]` | Save session to `.jvmsession.gz` |
| `load <file>` | Load session for replay |
| `export [file]` | Export HTML report |
| `watch <cmd> [sec]` | Auto-refresh mode (e.g. `watch cpu 2`) |
| `enable <module> <level>` | Activate agent module |
| `disable <module>` | Deactivate agent module |
| `modules` | List agent modules and status |

## Architecture

```
JVM (JVMTI) → Agent modules → ring_buffer → TCP → Collector → EventStore → CLI / GUI / DiagnosisEngine
```

- **Agent** = C native JVMTI library (jvmmonitor.so / .dll), TCP server
- **Collector** = Java application (jvmmonitor.jar), TCP client
- **Protocol** = custom big-endian binary, 10-byte header (magic `JVMM`)
- **Decompiler** = integrated DenzoSOFT Java Decompiler (Java 1.0-25)
- **Java compatibility** = source 1.6, runs on JDK 6+
- **Dependencies** = zero external runtime dependencies
- **Crash handler** = auto-saves diagnostic dump on VMDeath/SIGSEGV/SIGABRT/SIGBUS

### Production Hardening
- Thread-safe rate limiter with atomic CAS operations
- Async-signal-safe crash handler (write() only, no malloc/fprintf)
- Graceful JVMTI capability degradation (full → basic fallback)
- Socket cleanup on connect error, stream close on disconnect
- Method name cache capped at 10,000 entries (OOM prevention)
- GUI refresh wrapped in try-catch (EDT crash prevention)
- Histogram access synchronized (TOCTOU race prevention)
- Transport idle polling at 100 Hz (was 1000 Hz)

## Performance Impact

### Core overhead (always on, no modules enabled)

| Component | What it does | Overhead |
|---|---|---|
| CPU Sampler | SIGPROF every 10ms + AsyncGetCallTrace | <1% CPU |
| GC Listener | GC start/finish callbacks | ~0% |
| Thread Monitor | Polls thread states every 1000ms | <0.5% CPU |
| Memory Monitor | Reads heap via JNI every 1000ms | ~0% |
| Ring Buffer | Lock-free SPSC push per event | ~0% |
| Transport | TCP send thread, 100 Hz idle poll | <0.2% CPU |
| Crash Handler | Signal handlers installed | 0% until signal |

**Estimated total: ~1-2% CPU, ~2-5 MB RSS overhead** (comparable to JFR default configuration).

No collector connected = agent drains ring buffer silently with near-zero impact.

### On-demand module overhead (only when user enables)

| Module | Overhead | Notes |
|---|---|---|
| `exceptions` | 2-5% | JVMTI callback per exception thrown |
| `os` | <0.5% | Reads /proc every 5s |
| `jit` | <1% | Callback per method compilation |
| `histogram` | **Pause: 0.5-5s** | Walks entire heap — one-shot, not continuous |
| `locks` | 2-10% under contention | Callback per lock contention event |
| `network` | <0.5% | Reads /proc/self/net/tcp every 5s |
| Instrumentation | 5-15% | JVMTI MethodEntry/Exit on every instrumented call |
| Debugger | **Pauses thread** | Stops execution at breakpoint — no overhead otherwise |

### Design principle

The agent starts in **CORE mode** with only the 6 always-on components. All profiling modules start at level 0 (off) and are activated on-demand from the collector. This ensures minimal impact on production workloads until the operator explicitly enables deeper analysis.

## Build Outputs

```
dist/
  linux/jvmmonitor.so       # Native agent for Linux (~103 KB)
  windows/jvmmonitor.dll    # Native agent for Windows (~416 KB)
  jvmmonitor.jar            # Collector JAR (~830 KB, includes decompiler)
```

## Testing

```bash
make test         # Run all tests (36 C + 120 Java)
make test-c       # C agent tests only
make test-java    # Java collector tests only
```

## Source Statistics

- 98 Java source files (JVMMonitor collector)
- 139 Java source files (DenzoSOFT Java Decompiler, integrated)
- 29 C source files (native agent)
- 30 C header files
- Total: ~296 source files, ~830 KB JAR

## License

Copyright (c) DenzoSOFT. All rights reserved.
