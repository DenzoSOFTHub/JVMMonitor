# JVMMonitor CLI Manual

## Overview

The JVMMonitor CLI provides a full-featured interactive command-line interface for monitoring, profiling, and diagnosing JVM applications. It offers complete feature parity with the Swing GUI, suitable for headless servers, SSH sessions, and scripting.

## Starting the CLI

```bash
# Connect to a running agent
java -jar jvmmonitor.jar connect <host> <port>

# Inject agent into a running JVM and connect
java -jar jvmmonitor.jar attach <pid> [--port <port>]

# List available JVMs
java -jar jvmmonitor.jar list

# Start without connecting (interactive mode)
java -jar jvmmonitor.jar
```

Once started, the CLI presents an interactive prompt:

```
JVMMonitor v1.0.0
Type 'help' for available commands.

jvm-monitor>
```

## Connection

### list

List all JVMs visible on this machine via the Java Attach API.

```
jvm-monitor> list
PID      NAME
12345    com.myapp.Application
67890    org.apache.catalina.startup.Bootstrap
```

Requires a JDK (not JRE). On JDK 6-8, `tools.jar` is loaded automatically.

### attach

Inject the native agent into a running JVM and connect.

```
jvm-monitor> attach 12345
jvm-monitor> attach 12345 --port 9090
```

The CLI searches for `jvmmonitor.so` (Linux) or `jvmmonitor.dll` (Windows) in:
1. Current directory
2. `dist/linux/` or `dist/windows/`
3. `/usr/local/lib/`

After injection, the agent starts listening on the specified port (default 9090) and the CLI connects automatically.

### connect / disconnect

```
jvm-monitor> connect 192.168.1.10 9090
Connected to agent PID 12345 @ myhost (OpenJDK 17.0.9)

jvm-monitor> disconnect
Disconnected.
```

## Monitoring Commands

### status

Overview of the connected JVM: heap usage, event counts, active alarms.

```
jvm-monitor> status
=== JVMMonitor Status ===
Agent:  CONNECTED (PID 12345 @ myhost)
JVM:    OpenJDK 17.0.9
Events: CPU=5420 GC=35 Mem=180 Exc=267 JIT=89
Heap:   429.5 MB / 1024.0 MB (41.9%)
Alarms: none
```

### memory

Heap and non-heap usage with growth rate trend.

```
jvm-monitor> memory
=== Memory ===
Heap:     429.5 MB / 1024.0 MB (41.9%)
Non-Heap: 85.3 MB / 256.0 MB
Growth:   12.0 MB/h (last 5 min trend)
```

A positive growth rate over time suggests a potential memory leak. Compare with the `diagnose` command for intelligent analysis.

### gc

GC statistics for the last 60 seconds.

```
jvm-monitor> gc
=== GC (last 60s) ===
Frequency:  8 GC/min
Avg Pause:  12.5 ms
Max Pause:  45.2 ms
Throughput: 98.5%
```

**Throughput** is the percentage of time spent in application code (not GC). Below 95% indicates GC pressure.

### gcdetail

Per-collector GC details with collection counts, cumulative time, and managed memory pools.

```
jvm-monitor> gcdetail
=== GC Collectors ===
G1 Young Generation          collections=28  time=312ms
  Pools: G1 Eden Space, G1 Survivor Space
G1 Old Generation            collections=2   time=89ms
  Pools: G1 Old Gen
```

### threads

Thread states table showing all live threads.

```
jvm-monitor> threads
ID     NAME                           STATE           DAEMON
1      main                           RUNNABLE        no
2      http-nio-8080-exec-1           RUNNABLE        yes
3      http-nio-8080-exec-2           BLOCKED         yes
4      HikariPool-1-connection-1      TIMED_WAITING   yes
...
```

### cpu

CPU usage with per-thread breakdown showing the hottest threads.

```
jvm-monitor> cpu
=== CPU Usage ===
System CPU:  42.0%
JVM CPU:     28.5%
Available:   71.5%
Processors:  8
User time:   145320 ms
System time: 12450 ms

TID    NAME                           CPU%     TIME STATE
2      http-nio-8080-exec-1            12.3%  8450ms RUNNABLE
7      G1 Concurrent Refinement        8.1%   5200ms RUNNABLE
3      http-nio-8080-exec-2            6.4%   4100ms RUNNABLE
...
```

### exceptions

Recent exceptions with throw location and caught/uncaught status.

```
jvm-monitor> exceptions
=== Exceptions (last 60s) ===
Total thrown:  267
Total caught:  265
Rate:          16/min
EXCEPTION                                THROWN AT                      CAUGHT?
NullPointerException                     OrderService.getOrder          yes
IllegalArgumentException                 Validator.check                yes
SocketTimeoutException                   HttpClient.execute             NO
...
```

### network

TCP connections with per-socket details: direction, destination, state, traffic.

```
jvm-monitor> network
=== Network ===
Established: 12  Close_Wait: 0  Time_Wait: 3  Listen: 2
Segments in: 45230  out: 38100  retrans: 12  errors: 0

DIR  DESTINATION               STATE        REQS   BYTES_IN   BYTES_OUT SERVICE
OUT  192.168.0.10:5432         ESTABLISHED     45    2.1 MB     512 KB  PostgreSQL
OUT  10.0.1.50:6379            ESTABLISHED    120    89 KB      45 KB   Redis
IN   0.0.0.0:8080              LISTEN           0      0 B       0 B
...
```

### locks

Lock contention hotspots and recent contention events.

```
jvm-monitor> locks
=== Lock Contention (last 60s: 42 events) ===
LOCK                                               CONTENTIONS
java.util.concurrent.ConcurrentHashMap@3a2f1c      18
com.myapp.cache.LruCache@7b1d2e                    12

THREAD               LOCK                           OWNER                WAITERS
http-exec-2          ConcurrentHashMap@3a2f1c       http-exec-1          3
scheduler-1          LruCache@7b1d2e                http-exec-3          1
```

### queues

Message queue monitoring: depth, enqueue/dequeue rates, consumer lag.

```
jvm-monitor> queues
=== Message Queues ===
QUEUE                     TYPE       DEPTH    ENQ/s    DEQ/s   CONSUM      LAG  AGE(ms)
orders.queue              JMS          45       12        8        2       120     3500
events.topic              Kafka       230       50       48        4      1200    12000
```

### integration

External systems aggregated by IP with connection count and traffic.

```
jvm-monitor> integration
=== External Systems ===
SYSTEM                     CONNS     BYTES_IN    BYTES_OUT
192.168.0.10                   3       4.5 MB      1.2 MB
10.0.1.50                      2      120 KB       56 KB
api.external.com               1       89 KB       12 KB
```

### os

OS-level metrics: memory, file descriptors, TCP states, context switches.

```
jvm-monitor> os
=== OS Metrics ===
RSS:          512.3 MB
VM Size:      2048.0 MB
Open FDs:     245
OS Threads:   48
TCP:          12 established, 0 close_wait, 3 time_wait
Ctx Switches: 45000 voluntary, 1200 involuntary
```

### processes

OS process list with CPU, memory, and thread count.

```
jvm-monitor> processes
=== System Processes ===
System memory: 16384 MB total, 4096 MB free

PID      NAME                           CPU%    RSS(MB)  THREADS
12345    java                            28.5%     512.3       48
1        systemd                          0.1%      12.0        1
890      nginx                            2.3%      45.0        4
```

### histogram

Class histogram showing top classes by memory usage.

```
jvm-monitor> histogram
=== Class Histogram (took 1250.3ms) ===
CLASS                                              INSTANCES         SIZE
[B                                                    45230     125.3 MB
java.lang.String                                      38100      12.5 MB
java.util.HashMap$Node                                12400       3.8 MB
...
```

**Note:** Requesting a histogram pauses the JVM for 0.5-5 seconds. Use in production with caution.

### Other monitoring commands

| Command | Description |
|---|---|
| `jit` | JIT compilation events (compiled/deoptimized) |
| `classloaders` | Classloader breakdown with class counts |
| `nativemem` | Native Memory Tracking (NMT) output |
| `safepoints` | Safepoint count, total/sync time, averages |
| `alarms` | Active alarms from the agent |

## Profiling

### CPU Profiler

```bash
jvm-monitor> profiler start        # Start collecting CPU samples
jvm-monitor> profiler status       # Check sample count and duration
jvm-monitor> profiler hotmethods   # Show top 20 methods (keeps running)
jvm-monitor> profiler stop         # Show results and stop
```

Output:

```
=== CPU Profiler (5420 samples, 120s) ===
METHOD                                             SAMPLES      %
com.myapp.dao.OrderDao.findOrders                     892  16.5%
java.sql.PreparedStatement.executeQuery               654  12.1%
com.myapp.service.OrderService.getOrders              421   7.8%
com.myapp.cache.LruCache.get                          312   5.8%
...
```

### Instrumentation

Start method-level tracing with configurable packages and probes:

```bash
# Start with defaults (com.myapp, all probes)
jvm-monitor> instrument start

# Start with specific packages and probes
jvm-monitor> instrument start com.myapp,com.lib jdbc,spring,http

# View aggregated method statistics
jvm-monitor> instrument events

# View JDBC query statistics
jvm-monitor> instrument jdbc

# View HTTP request statistics
jvm-monitor> instrument http

# Stop instrumentation
jvm-monitor> instrument stop
```

Available probes: `jdbc`, `spring`, `http`, `messaging`, `mail`, `cache`, `disk_io`, `socket_io`

**Disk I/O** and **Socket I/O** probes are disabled by default (5-15% overhead). Enable them explicitly when investigating per-thread I/O.

Example JDBC output:

```
=== JDBC Queries (145 queries) ===
SQL                                                          COUNT    AVG(ms)    MAX(ms)
SELECT o.* FROM orders o WHERE o.user_id = ? ORDER BY c...     45       12.3       89.0
INSERT INTO audit_log (action, user_id, ts) VALUES (?, ...     38        2.1        8.5
UPDATE orders SET status = ? WHERE id = ? AND version =...     22       15.7      120.0
```

## Diagnostics & Alarms

### diagnose

Run all diagnostic rules and show findings:

```
jvm-monitor> diagnose
--- WARNING --- Memory Leak ---
WHERE:    Heap Old Generation
PROBLEM:  Live set (heap after Full GC) growing: 120 MB -> 280 MB across 5 Full GCs. Growth rate: 96 MB/h. Estimated OOM in 8.3 hours.
EVIDENCE: Live set trend: 5 Full GCs, first=120 MB, last=280 MB, delta=+160 MB
ACTION:   Take two class histograms 5 min apart and compare. Look for classes with growing instance count.

--- CRITICAL --- CPU Saturation ---
PROBLEM:  JVM CPU at 92% (30s avg). Application code is consuming most CPU.
EVIDENCE: Avg JVM CPU: 92%, GC throughput: 99%
ACTION:   Start CPU profiler to identify hot methods. Check thread CPU table for runaway threads.
```

The diagnosis engine is **contextual**: heap at 85% is not alarmed if Full GC can reclaim it. CPU at 80% is always a warning. Response time degradation is detected by comparing recent vs baseline, not by fixed thresholds.

### Alarm thresholds

View, modify, save, and load alarm thresholds:

```bash
# Show all thresholds with current values
jvm-monitor> threshold show
PARAMETER                           VALUE
memory.liveSetMinFullGcs            3
memory.oldGenAfterFullGcPct         80.0
gc.throughputWarnPct                90.0
gc.pauseMaxMs                       1000.0
cpu.warnPct                         80.0
cpu.critPct                         95.0
threads.blockedWarnPct              20.0
exceptions.spikeMultiplier          3.0
response.jdbcSlowMs                 1000.0
...

# Change a threshold
jvm-monitor> threshold set cpu.warnPct 70
cpu.warnPct = 70

# Save to file
jvm-monitor> threshold save production.thresholds

# Load from file
jvm-monitor> threshold load production.thresholds
```

Threshold files are plain text (key=value), easy to version control and deploy across environments.

## Session Management

### save

Save the entire monitoring session (all events, all metrics) to a compressed binary file:

```
jvm-monitor> save
Session saved to jvmmonitor-session-20260401-170530.jvmsession.gz (245.3 KB, 120ms)

jvm-monitor> save /tmp/before-deploy.jvmsession.gz
Session saved to /tmp/before-deploy.jvmsession.gz (245.3 KB, 115ms)
```

### load

Load a previously saved session for offline analysis:

```
jvm-monitor> load before-deploy.jvmsession.gz
Session loaded from before-deploy.jvmsession.gz (115ms)
Saved at: 2026-04-01 15:30:00
Use 'status', 'memory', 'threads' etc. to view loaded data.
```

After loading, all monitoring commands (`memory`, `threads`, `gc`, `diagnose`, etc.) work on the loaded data.

### export

Export an HTML report:

```
jvm-monitor> export
Report exported to jvmmonitor-report-20260401-170530.html

jvm-monitor> export /tmp/incident-report.html
Report exported to /tmp/incident-report.html
```

## Agent Module Control

### enable / disable

Control agent profiling modules. Modules start at level 0 (off) and can be activated at levels 1-3.

```bash
# Enable exceptions module at level 1
jvm-monitor> enable exceptions 1

# Enable locks with surgical targeting
jvm-monitor> enable locks 2 --target com.myapp.CacheManager --duration 60

# Disable a module
jvm-monitor> disable exceptions
```

| Level | Description |
|---|---|
| 0 | Off |
| 1 | Statistical (low overhead, sampling) |
| 2 | Detailed (higher overhead, per-event) |
| 3 | Surgical (maximum detail, targeted) |

### modules

List all agent modules with their current status:

```
jvm-monitor> modules
MODULE               LEVEL      MAX        STATUS
alloc                0          3          off
locks                1          3          ACTIVE
memory               0          2          off
exceptions           1          2          ACTIVE
os                   1          1          ACTIVE
...
```

## Watch Mode

Auto-refresh any command at a configurable interval. Press Enter to stop.

```bash
# Watch CPU every 2 seconds
jvm-monitor> watch cpu 2

# Watch status every 5 seconds (default)
jvm-monitor> watch status

# Watch memory every 10 seconds
jvm-monitor> watch memory 10
```

Output refreshes in-place:

```
--- 17:05:30 ---
=== CPU Usage ===
System CPU:  42.0%
JVM CPU:     28.5%
...

--- 17:05:32 ---
=== CPU Usage ===
System CPU:  38.0%
JVM CPU:     25.1%
...
```

## Typical Workflows

### Production incident investigation

```bash
java -jar jvmmonitor.jar connect prod-server 9090
jvm-monitor> status              # Quick overview
jvm-monitor> diagnose            # Intelligent analysis
jvm-monitor> save incident.jvmsession.gz   # Save for later
jvm-monitor> cpu                 # Check CPU
jvm-monitor> memory              # Check memory
jvm-monitor> gc                  # Check GC health
jvm-monitor> threads             # Look for blocked threads
jvm-monitor> locks               # Identify lock contention
jvm-monitor> network             # Check connections
jvm-monitor> exceptions          # Check error rate
```

### Memory leak investigation

```bash
jvm-monitor> diagnose            # Should show "Memory Leak" if live set growing
jvm-monitor> enable histogram 1  # Enable class histogram
jvm-monitor> histogram           # First snapshot
# Wait 5 minutes
jvm-monitor> histogram           # Second snapshot — compare for growing classes
jvm-monitor> memory              # Check growth rate
```

### Performance profiling

```bash
jvm-monitor> profiler start
# Wait 60 seconds
jvm-monitor> profiler hotmethods   # See top CPU consumers
jvm-monitor> instrument start com.myapp jdbc,spring,http
# Generate some load
jvm-monitor> instrument events     # See method timings
jvm-monitor> instrument jdbc       # See slow queries
jvm-monitor> instrument http       # See slow endpoints
jvm-monitor> profiler stop
```

### Continuous monitoring

```bash
jvm-monitor> threshold load production.thresholds
jvm-monitor> watch status 10
# CLI will auto-print CRITICAL alarms in the background
```

## Exit

```
jvm-monitor> quit
```

Or `exit`, or Ctrl+D.
