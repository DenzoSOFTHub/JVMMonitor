# Troubleshooting Java Applications with JVMMonitor

## Introduction

This guide covers how to diagnose and resolve common Java application problems using JVMMonitor. Each section presents a problem, explains how to identify it, and provides step-by-step instructions for both the Swing GUI and the CLI.

---

## 1. Application is Slow or Unresponsive

### Symptoms
- HTTP requests take longer than expected
- Users report timeouts or long waits
- Backend batch jobs miss their SLA

### Step 1: Check CPU

**GUI:** Open the **Dashboard** tab. Look at the CPU Usage chart. If JVM CPU is above 80% sustained, the application is CPU-bound.

**CLI:**
```
jvm-monitor> cpu
=== CPU Usage ===
System CPU:  92.0%
JVM CPU:     85.3%
```

If JVM CPU is high, check the per-thread breakdown to find which thread is consuming CPU:

**GUI:** Open the **CPU Usage** tab. The Thread CPU Table shows per-thread CPU% sorted descending. Red-highlighted rows are hot threads.

**CLI:**
```
jvm-monitor> cpu
TID    NAME                           CPU%     TIME STATE
2      http-nio-8080-exec-1            45.2%  8450ms RUNNABLE
7      G1 Concurrent Refinement        12.1%  5200ms RUNNABLE
```

### Step 2: Check if CPU is caused by GC

**GUI:** Open **GC Analysis** tab. Check the GC Throughput chart. If throughput is below 95%, GC is consuming too much CPU.

**CLI:**
```
jvm-monitor> gc
=== GC (last 60s) ===
Throughput: 82.5%    <-- GC is consuming 17.5% of CPU
```

If GC is the problem, see Section 3 (Memory Pressure / GC Thrashing).

### Step 3: Check for lock contention

If CPU is low but the application is still slow, threads may be blocked waiting for locks.

**GUI:** Open the **Threads** tab. Check the stacked area chart — if the red area (BLOCKED) is significant, there is lock contention. Then open the **Locks** tab to see which locks are contended. If the locks module is not enabled, a yellow bar appears at the top with an "Enable" button -- click it to activate the module directly from the panel.

**CLI:**
```
jvm-monitor> threads
ID     NAME                           STATE           DAEMON
2      http-nio-8080-exec-1           BLOCKED         yes
3      http-nio-8080-exec-2           BLOCKED         yes
4      http-nio-8080-exec-3           RUNNABLE        yes

jvm-monitor> locks
=== Lock Contention (last 60s: 142 events) ===
LOCK                                               CONTENTIONS
com.myapp.cache.SessionCache@3a2f1c                89
java.util.concurrent.ConcurrentHashMap@7b1d2e      53
```

### Step 4: Check for slow database queries

**GUI:** Open the **Instrumentation** tab. Start instrumentation with JDBC probe enabled. After some traffic, check the **JDBC Monitor > SQL Statistics** sub-tab for queries with high average time.

**CLI:**
```
jvm-monitor> instrument start com.myapp jdbc
Instrumentation started.

# Wait for some traffic, then:
jvm-monitor> instrument jdbc
=== JDBC Queries (145 queries) ===
SQL                                                          COUNT    AVG(ms)    MAX(ms)
SELECT o.* FROM orders o WHERE o.user_id = ? ORDER BY ...      45      250.3     1200.0
```

### Step 5: Run automatic diagnostics

**GUI:** Open **Tools > Auto-Diagnostics**. The engine will identify the root cause.

**CLI:**
```
jvm-monitor> diagnose
--- WARNING --- Response Time Degradation ---
PROBLEM: OrderService.getOrders: avg response time 450ms (was 120ms) — 3.8x slower.
ACTION:  Check if degradation correlates with GC pauses, CPU load, or lock contention.

--- WARNING --- Slow JDBC Queries ---
PROBLEM: 12 queries > 1s in the last minute. Slowest: 1200ms.
ACTION:  Check SQL Statistics in Instrumentation tab. Look for missing indexes.
```

---

## 2. OutOfMemoryError

### Symptoms
- Application crashes with `java.lang.OutOfMemoryError: Java heap space`
- Application crashes with `java.lang.OutOfMemoryError: Metaspace`
- Application becomes progressively slower before crashing

### Step 1: Check current heap state

**GUI:** Open the **Dashboard**. Look at the Heap Usage chart. If Used is close to Max, the heap is full.

**CLI:**
```
jvm-monitor> memory
=== Memory ===
Heap:     980.2 MB / 1024.0 MB (95.7%)    <-- critically full
Non-Heap: 85.3 MB / 256.0 MB
Growth:   45.0 MB/h (last 5 min trend)
```

### Step 2: Determine if it is a leak or undersized heap

**GUI:** Open the **Memory** tab. Check the **Live Data Set** chart (bottom-right). If it is flat or oscillating, the heap is simply too small. If it is rising, there is a memory leak.

**CLI:**
```
jvm-monitor> diagnose
--- CRITICAL --- Memory Leak ---
PROBLEM: Live set (heap after Full GC) growing: 400 MB -> 820 MB across 5 Full GCs.
         Growth rate: 120 MB/h. Estimated OOM in 1.7 hours.
ACTION:  Take two class histograms 5 min apart and compare.
```

Key distinction:
- **Live set flat, heap full** → Increase `-Xmx` or optimize object usage
- **Live set rising** → Memory leak. Objects accumulate and are never freed

### Step 3: Find the leaking class

**GUI:** Open the **Memory** tab, click **Histogram** sub-tab. Press "Request Histogram". Wait 5 minutes, press again. Compare the **Delta Instances** column — classes with a large positive delta are leak candidates.

**CLI:**
```
jvm-monitor> enable histogram 1
jvm-monitor> histogram
=== Class Histogram (took 1250.3ms) ===
CLASS                                              INSTANCES         SIZE
byte[]                                                45230     125.3 MB
com.myapp.model.Order                                 38100      12.5 MB

# Wait 5 minutes, then:
jvm-monitor> histogram
# Compare: if com.myapp.model.Order grew from 38100 to 52000, that's the leak
```

**GUI:** Also check **Leak Suspects** sub-tab for automated analysis and **Old Gen Objects** for large objects stuck in old generation.

### Step 4: Find where the leak occurs

**GUI:** Enable **Allocation Recording** in the Memory tab to see which code paths create the leaking objects. Check **GC Root Analysis** in Tools to trace the reference chain keeping objects alive.

### Step 5: Metaspace leak

If the error is `OutOfMemoryError: Metaspace`:

**GUI:** Open **System** tab, check classloader breakdown. Many classloaders suggest a hot-deploy/plugin leak.

**CLI:**
```
jvm-monitor> classloaders
=== Classloaders ===
CLASSLOADER                                        CLASSES
sun.misc.Launcher$AppClassLoader                   4500
org.springframework.boot.loader.LaunchedURLCL...   3200
com.myapp.plugins.PluginClassLoader@1a2b3c         1200    <-- leaked?
com.myapp.plugins.PluginClassLoader@4d5e6f          800    <-- leaked?
Total: 45 loaders, 9700 classes
```

---

## 3. GC Pauses / GC Thrashing

### Symptoms
- Application freezes periodically (latency spikes)
- High CPU usage caused by GC
- GC throughput below 95%

### Step 1: Analyze GC behavior

**GUI:** Open the **GC Analysis** tab. The four charts show:
- Rectangle chart: width = pause duration, height = freed memory. Many tall narrow rectangles = frequent short GCs. Wide rectangles = long pauses.
- CPU vs GC correlation: checks if CPU spikes align with GC pauses.
- Promotion rate: how fast objects move from Eden to Old Gen.
- Throughput: percentage of time in application code.

**CLI:**
```
jvm-monitor> gc
=== GC (last 60s) ===
Frequency:  45 GC/min         <-- very high
Avg Pause:  25.3 ms
Max Pause:  850.0 ms          <-- concerning
Throughput: 85.2%             <-- below 95% threshold

jvm-monitor> gcdetail
=== GC Collectors ===
G1 Young Generation          collections=42  time=950ms
G1 Old Generation            collections=3   time=2100ms    <-- Full GC taking 700ms each
```

### Step 2: Identify the cause

| Observation | Likely cause | Solution |
|---|---|---|
| High Young GC frequency | High allocation rate | Profile allocations, reduce temporary objects |
| Frequent Full GC | Old Gen filling up | Check for memory leak (Section 2) or increase `-Xmx` |
| Long GC pauses | Large heap with stop-the-world collector | Switch to G1/ZGC/Shenandoah, tune pause target |
| High promotion rate | Objects surviving Young GC too often | Increase Eden size (`-Xmn`), check object lifetimes |

### Step 3: Check allocation rate

**GUI:** Dashboard shows Allocation Rate chart. Memory tab shows detailed allocation rate over time.

**CLI:**
```
jvm-monitor> diagnose
--- WARNING --- Allocation Pressure ---
PROBLEM: Allocation rate (450 MB/s) exceeds GC reclaim rate (300 MB/s) by 50%.
ACTION:  Reduce allocation rate: check allocation recording for top allocators.
```

**GUI:** Start **Allocation Recording** in the Memory tab to find which code paths allocate the most.

---

## 4. Thread Deadlock

### Symptoms
- Application hangs completely or partially
- Specific functionality stops responding while other parts work
- Thread count stays stable but work is not progressing

### Step 1: Detect the deadlock

**GUI:** Open **Tools > Deadlock Detection**. If a deadlock exists, the chain is displayed showing which threads hold which locks and who is waiting for whom.

**CLI:**
```
jvm-monitor> diagnose
--- CRITICAL --- Deadlock Detected ---
PROBLEM: Deadlock detected! Threads are permanently blocked waiting for each other.
ACTION:  Use Deadlock Detection in Tools tab to see the full chain.
```

### Step 2: Get a thread dump

**GUI:** Open **Tools > Thread Dump** to capture a full stack dump. Save to file for analysis.

**CLI:**
```
jvm-monitor> threads
ID     NAME                           STATE           DAEMON
10     worker-1                       BLOCKED         yes
11     worker-2                       BLOCKED         yes
```

### Step 3: Fix lock ordering

Deadlocks are caused by threads acquiring locks in different orders. The deadlock chain shows the exact lock objects and thread names. Fix by ensuring all code acquires locks in a consistent global order.

---

## 5. Connection Leaks

### Symptoms
- Application runs out of database connections
- `getConnection()` calls start timing out
- CLOSE_WAIT connections accumulate over time

### Step 1: Check network connections

**GUI:** Open the **Network** tab. If the network module is not enabled, a yellow bar appears at the top with an "Enable" button -- click it to activate the module. Look for growing CLOSE_WAIT count in the summary bar. The Connection Warnings section at the bottom flags suspicious connections.

**CLI:**
```
jvm-monitor> network
=== Network ===
Established: 45  Close_Wait: 23  Time_Wait: 5  Listen: 2
```

A growing CLOSE_WAIT count means the application is not closing connections.

### Step 2: Check JDBC connection pool

**GUI:** Open **Instrumentation** tab, start with JDBC probe, then check **JDBC Monitor > Connection Monitor** sub-tab. It shows open connections with thread name, open time, and duration. Connections open for a long time without being closed are leaks.

**CLI:**
```
jvm-monitor> instrument start com.myapp jdbc
# Wait for traffic
jvm-monitor> instrument events
```

### Step 3: Run diagnostics

**CLI:**
```
jvm-monitor> diagnose
--- WARNING --- TCP Connection Leak ---
PROBLEM: 23 CLOSE_WAIT connections and growing — application is not closing sockets.
ACTION:  Enable Socket I/O probe to identify which threads open connections without closing.

--- WARNING --- JDBC Connection Pool Exhaustion ---
PROBLEM: 8 getConnection() calls took > 500ms in the last minute.
ACTION:  Check Connection Monitor for leaked connections. Fix missing close() in finally blocks.
```

### Step 4: Find the leak in code

Enable the **Socket I/O** probe in Instrumentation to trace every socket connect/close by thread:

**GUI:** Instrumentation tab > check "Socket I/O" checkbox > Start. Then check the **Socket I/O** sub-tab.

**CLI:**
```
jvm-monitor> instrument start com.myapp jdbc,socket_io
```

---

## 6. High Exception Rate

### Symptoms
- Error logs growing rapidly
- Application returning 500 errors to clients
- Performance degradation caused by exception overhead

### Step 1: Check exception rate and hotspots

**GUI:** Open the **Exceptions** tab. If the exceptions module is not enabled, a yellow bar appears at the top with an "Enable" button -- click it to activate the module. The rate chart shows exceptions/minute. The **Exception Hotspots** sub-tab identifies the exact code locations throwing the most exceptions.

**CLI:**
```
jvm-monitor> exceptions
=== Exceptions (last 60s) ===
Total thrown:  1250
Total caught:  1200
Rate:          1250/min
EXCEPTION                                THROWN AT                      CAUGHT?
NullPointerException                     OrderService.getOrder          yes
SocketTimeoutException                   HttpClient.execute             NO
```

### Step 2: Identify the pattern

**CLI:**
```
jvm-monitor> diagnose
--- WARNING --- Exception Storm ---
PROBLEM: Exception rate spike: 1250/min (was 80/min baseline) — 15.6x increase.
ACTION:  Check Exceptions tab for the new exception types causing the spike.

--- CRITICAL --- Uncaught Exceptions ---
PROBLEM: 50 uncaught exception(s) in the last minute — threads are dying.
ACTION:  Uncaught exceptions kill threads. Fix the root cause.

--- WARNING --- Recurring Bug ---
PROBLEM: NullPointerException thrown 450 times from OrderService.getOrder — likely a bug.
ACTION:  Fix the root cause at this code location.
```

### Step 3: Examine stack traces

**GUI:** In the Exceptions tab, click **Recent Events** sub-tab. Click on a row to see the full stack trace and identify the exact line of code.

---

## 7. Network Issues

### Symptoms
- Slow responses when calling external services
- Connection timeouts
- High retransmission rate

### Step 1: Check network overview

**GUI:** Open the **Network** tab. Check the Retransmissions & Errors chart. A rising retransmission line indicates network problems.

**CLI:**
```
jvm-monitor> network
=== Network ===
Segments in: 45230  out: 38100  retrans: 1250  errors: 15

jvm-monitor> diagnose
--- WARNING --- Network Degradation ---
PROBLEM: TCP retransmit rate 3.3% — network is losing packets.
ACTION:  Check network infrastructure.
```

### Step 2: Identify affected external systems

**GUI:** Open the **Integration** tab to see all external systems grouped by IP with traffic volume and latency.

**CLI:**
```
jvm-monitor> integration
=== External Systems ===
SYSTEM                     CONNS     BYTES_IN    BYTES_OUT
192.168.0.10                   3       4.5 MB      1.2 MB
api.external.com               1       89 KB       12 KB    <-- slow?
```

### Step 3: Check per-connection details

**GUI:** Network tab shows per-socket details including TX/RX queue backlog. A high TX Queue means the remote is not reading data fast enough.

---

## 8. Classloader Leak (Metaspace Growth)

### Symptoms
- Metaspace grows after each deployment/redeploy
- `OutOfMemoryError: Metaspace` after multiple hot-deploys
- Class count keeps increasing

### Step 1: Check classloader state

**GUI:** Open the **System** tab, check classloader breakdown.

**CLI:**
```
jvm-monitor> classloaders
Total: 45 loaders, 9700 classes

jvm-monitor> diagnose
--- WARNING --- Classloader Leak ---
PROBLEM: 45 classloaders with 9700 total classes — possible classloader leak.
ACTION:  Ensure old classloaders are garbage collected after redeploy.
```

### Step 2: Identify the leaked loader

Multiple classloaders with the same class name pattern (e.g., `PluginClassLoader@1a2b3c`, `PluginClassLoader@4d5e6f`) suggest the old loaders are not being garbage collected after unloading.

### Step 3: Find what holds the old classloader alive

**GUI:** Use **Tools > GC Root Analysis**. Enter the classloader class name to trace the reference chain keeping it alive. Common causes: static fields, thread-local variables, JDBC driver registration.

---

## 9. Saving Evidence for Later Analysis

When investigating a production incident, always save the session:

**GUI:** **Tools > Session > Save Session** — saves all collected data to a compressed `.jvmsession.gz` file.

**CLI:**
```
jvm-monitor> save incident-2026-04-01.jvmsession.gz
Session saved to incident-2026-04-01.jvmsession.gz (245.3 KB, 120ms)
```

Later, load the session for offline analysis:

**GUI:** **Tools > Session > Load Session**

**CLI:**
```
jvm-monitor> load incident-2026-04-01.jvmsession.gz
Session loaded. Use 'status', 'memory', 'threads' etc. to view loaded data.
jvm-monitor> diagnose
```

Export an HTML report for sharing with the team:

**GUI:** **Tools > Session > Export HTML Report**

**CLI:**
```
jvm-monitor> export incident-report.html
```
