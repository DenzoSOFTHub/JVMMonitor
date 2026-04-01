# JVMMonitor Demo Mode Guide

## Introduction

JVMMonitor includes a built-in demo mode that simulates a real JVM application without needing an actual agent or target JVM. The demo agent generates realistic monitoring data including memory snapshots, GC events, thread states, exceptions, network connections, lock contention, instrumentation events, and more.

This is useful for:
- **Evaluation** — explore all JVMMonitor features without a real application
- **Training** — teach team members how to use the tool
- **Development** — test GUI/CLI changes with realistic data
- **Screenshots** — generate documentation images

---

## Demo Components

JVMMonitor provides three demo entry points:

| Component | Purpose | Headless? |
|---|---|---|
| `DemoSwingSession` | Launches GUI + internal demo agent, captures screenshots | No (requires display) |
| `DemoSession` | CLI demo — connects to internal demo agent, prints metrics | Yes |
| `DemoAgent` | Standalone demo agent — simulates a JVM on a TCP port | Yes |

---

## DemoSwingSession (GUI Demo)

Launches the full Swing GUI connected to an internal demo agent. Optionally captures screenshots of all 15 tabs.

### Basic usage

```bash
java -cp jvmmonitor.jar it.denzosoft.jvmmonitor.demo.DemoSwingSession
```

This opens the GUI, starts an internal demo agent on port 19999, and auto-connects. The GUI updates with live data for 3 minutes, then closes.

> **What you will see:** The Dashboard immediately starts populating with live charts — thread states, CPU usage, heap growth, network traffic, allocation rate. Switch between tabs to see all features. The data is realistic: heap grows over time, GC events fire periodically, threads change state, exceptions occur.

### With screenshot capture

```bash
java -cp jvmmonitor.jar it.denzosoft.jvmmonitor.demo.DemoSwingSession /path/to/screenshots
```

This captures screenshots of all 15 tabs across 4 rounds (15 seconds apart), producing 60 PNG files:

```
/path/to/screenshots/
  round1_dashboard.png
  round1_memory.png
  round1_gc_analysis.png
  round1_threads.png
  round1_exceptions.png
  round1_network.png
  round1_integration.png
  round1_messaging.png
  round1_locks.png
  round1_cpu_usage.png
  round1_cpu_profiler.png
  round1_instrumentation.png
  round1_system.png
  round1_debugger.png
  round1_tools.png
  round2_dashboard.png
  ... (4 rounds x 15 tabs = 60 files)
```

> **Tip:** Round 4 images have the most data (charts fully populated). Use those for documentation.

### With virtual display (headless server)

On a server without a display, use Xvfb:

```bash
sudo apt-get install -y xvfb
xvfb-run -a java -cp jvmmonitor.jar it.denzosoft.jvmmonitor.demo.DemoSwingSession /tmp/screenshots
```

> **Why Xvfb?** The Swing GUI requires a display to render. Xvfb (X Virtual Framebuffer) provides a virtual display in memory. The screenshots are still captured correctly.

---

## DemoSession (CLI Demo)

Runs a CLI-based demo that prints metrics to the console.

### Basic usage

```bash
java -cp jvmmonitor.jar it.denzosoft.jvmmonitor.demo.DemoSession
```

Default: runs for 60 seconds, refreshing every 5 seconds.

### Custom duration and refresh

```bash
# Run for 180 seconds, refresh every 2 seconds
java -cp jvmmonitor.jar it.denzosoft.jvmmonitor.demo.DemoSession 180 2
```

### Output

```
=== JVMMonitor Demo Session ===
Connecting to demo agent on port 19999...
Connected to PID 12345 @ demo-host (OpenJDK 17.0.9 (Demo))

[10s] Heap: 265 MB / 1024 MB (25.9%) | CPU: 42% sys / 29% jvm | Threads: 22 | GC: 3 | Exc: 45
  GC: 3.0/min, avg pause 15.2ms, throughput 99.2%
  Threads: 12 RUNNABLE, 2 BLOCKED, 6 WAITING, 2 TIMED_WAITING

[20s] Heap: 301 MB / 1024 MB (29.4%) | CPU: 38% sys / 25% jvm | Threads: 22 | GC: 8 | Exc: 85
  GC: 4.0/min, avg pause 18.3ms, throughput 98.5%
  ...
```

> **What the demo simulates:** A Spring Boot application with a PostgreSQL database, Redis cache, Kafka messaging, and HTTP clients. Thread names include realistic patterns (http-nio, HikariPool, kafka-consumer, scheduler, GC-worker). Memory grows gradually simulating allocation. GC events fire with varying pause times. Exceptions occur periodically.

---

## DemoAgent (Standalone)

Runs a standalone demo agent that listens on a TCP port. Any JVMMonitor client (GUI or CLI) can connect to it.

### Basic usage

```bash
# Start demo agent on port 9090
java -cp jvmmonitor.jar it.denzosoft.jvmmonitor.demo.DemoAgent 9090
```

Output:
```
=== JVMMonitor Demo Agent ===
Listening on port 9090
Connect with: java -jar jvmmonitor.jar connect 127.0.0.1 9090
Press Ctrl+C to stop.

Waiting for collector connection...
```

### Connect with the GUI

In another terminal:
```bash
java -jar jvmmonitor.jar gui
```

Then in the GUI: Click **Connect** > Enter `127.0.0.1` and `9090` > Click OK.

> **What happens:** The GUI connects to the demo agent and starts receiving simulated data. All tabs populate with realistic metrics. You can interact with the GUI exactly as you would with a real application.

### Connect with the CLI

```bash
java -jar jvmmonitor.jar connect 127.0.0.1 9090
```

Then use any CLI command:
```
jvm-monitor> status
jvm-monitor> memory
jvm-monitor> threads
jvm-monitor> cpu
jvm-monitor> network
jvm-monitor> diagnose
```

> **Tip:** The demo agent runs indefinitely (until Ctrl+C). You can disconnect and reconnect multiple times. Each connection receives fresh data.

---

## What the Demo Agent Simulates

The demo agent generates realistic data for all 20+ metric types:

### Memory
- **Heap:** Starts at ~250 MB, grows to ~550 MB over time (simulating allocation)
- **Max heap:** 1024 MB
- **Non-heap:** ~80 MB (stable)
- **GC events:** Young GC every ~3 seconds (5-30ms pause), occasional Full GC (100-300ms)

> **The heap growth pattern:** The demo simulates a moderate allocation rate. Heap grows between GCs, then drops when Young GC fires. Over time the baseline increases slightly, similar to a real application with some long-lived objects.

### Threads (22 simulated threads)

| Thread name | State pattern |
|---|---|
| `main` | Always RUNNABLE |
| `http-nio-8080-exec-1..5` | Cycles between RUNNABLE, WAITING, BLOCKED |
| `scheduler-1..2` | RUNNABLE or TIMED_WAITING |
| `HikariPool-1-connection-1..3` | TIMED_WAITING or RUNNABLE |
| `kafka-consumer-1` | RUNNABLE or TIMED_WAITING |
| `kafka-producer-1` | RUNNABLE |
| `lettuce-epollEventLoop-1` | TIMED_WAITING |
| `AsyncHttpClient-1..2` | RUNNABLE |
| `GC-worker-1..2` | WAITING |
| `Signal Dispatcher` | WAITING |
| `Finalizer` | WAITING |
| `Reference Handler` | WAITING |

> **Thread state cycling:** The demo varies thread states over time to simulate realistic contention patterns. Occasionally, multiple HTTP threads enter BLOCKED state simultaneously, simulating lock contention spikes.

### Exceptions
- Rate: ~5-10 exceptions/second
- Types: NullPointerException, IllegalArgumentException, SocketTimeoutException, ClassNotFoundException, NumberFormatException
- Mix of caught and uncaught exceptions
- Thrown from realistic locations (service classes, DAO classes, controllers)

### Network (18 simulated sockets)
- 3 LISTEN sockets (ports 8080, 8443, 9090)
- 4 inbound connections from clients
- 5 outbound connections to databases (PostgreSQL, MySQL)
- 3 outbound connections to cache (Redis)
- 2 outbound connections to messaging (Kafka)
- 1 connection in SYN_SENT (simulating a hanging connect)
- Traffic growing over time (bytes in/out)
- Retransmissions and errors for realism

### Instrumentation (when recording)
The demo generates request traces simulating:
- **Controller.handleRequest** → **Service.getOrders** → **DAO.findOrders** → **PreparedStatement.executeQuery**
- Cache lookups (LruCache.get, RedisClient.get)
- Occasional exceptions in payment service
- JDBC connection open/close (with occasional leak every 20 ticks)
- Disk I/O events (file read/write with paths and sizes)
- Socket I/O events (TCP read/write with remote addresses and bytes)

### Other metrics
- **CPU usage:** System 30-55%, JVM 15-30%
- **OS metrics:** RSS, FDs, TCP states, context switches
- **JIT events:** Compilation events with class.method and code size
- **Lock events:** Periodic contention on simulated locks
- **Queue stats:** JMS and Kafka queues with depth, rates, consumer lag
- **Allocation events:** Object allocations from various code paths
- **Classloader stats:** 3 classloaders with class counts
- **Safepoint events:** Periodic safepoint stats

---

## Demo Scenarios

### Scenario 1: Feature exploration

Goal: See what JVMMonitor can do.

```bash
# Terminal 1: Start demo agent
java -cp jvmmonitor.jar it.denzosoft.jvmmonitor.demo.DemoAgent 9090

# Terminal 2: Start GUI and connect
java -jar jvmmonitor.jar gui
# Connect to 127.0.0.1:9090
```

Walk through all 15 tabs. Try:
- Dashboard — watch charts update in real time
- Memory — click "Request Histogram"
- Threads — switch between Thread States and Thread Activity
- Instrumentation — click "Start Instrumentation", then check JDBC Monitor
- Tools — run Auto-Diagnostics, try Session Save/Load

### Scenario 2: CLI training

```bash
java -cp jvmmonitor.jar it.denzosoft.jvmmonitor.demo.DemoAgent 9090 &
java -jar jvmmonitor.jar connect 127.0.0.1 9090
```

Practice commands:
```
jvm-monitor> status
jvm-monitor> memory
jvm-monitor> gc
jvm-monitor> threads
jvm-monitor> cpu
jvm-monitor> network
jvm-monitor> exceptions
jvm-monitor> locks
jvm-monitor> queues
jvm-monitor> diagnose
jvm-monitor> profiler start
jvm-monitor> profiler hotmethods
jvm-monitor> instrument start com.myapp jdbc,spring,http
jvm-monitor> instrument jdbc
jvm-monitor> watch cpu 2
jvm-monitor> save demo-session.jvmsession.gz
jvm-monitor> threshold show
```

### Scenario 3: Screenshot generation for documentation

```bash
xvfb-run -a java -cp jvmmonitor.jar it.denzosoft.jvmmonitor.demo.DemoSwingSession ./screenshots
```

Use `round4_*.png` images (most populated) for documentation.

### Scenario 4: Testing alarm thresholds

Lower thresholds to trigger alarms with demo data:

```bash
java -cp jvmmonitor.jar it.denzosoft.jvmmonitor.demo.DemoAgent 9090 &
java -jar jvmmonitor.jar connect 127.0.0.1 9090
```

```
jvm-monitor> threshold set cpu.warnPct 20
jvm-monitor> threshold set gc.throughputWarnPct 99.5
jvm-monitor> threshold set exceptions.highRate 5
jvm-monitor> diagnose
# Should now show warnings for CPU, exceptions, etc.
```
