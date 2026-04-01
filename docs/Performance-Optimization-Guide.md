# Identifying Bottlenecks and Optimizing Java Applications with JVMMonitor

## Introduction

This guide explains how to systematically identify performance bottlenecks in a Java application and optimize it using JVMMonitor. The approach follows a top-down methodology: start with the high-level symptoms, narrow down to the specific component, and then drill into the code.

---

## Phase 1: Baseline Assessment

Before optimizing, establish a baseline of normal application behavior. This allows you to measure improvements and detect regressions.

### Capture a baseline session

Connect JVMMonitor to the application under normal load:

**GUI:**
1. Connect to the agent (toolbar > Connect)
2. Let data accumulate for 5-10 minutes under typical load
3. Save the session: **Tools > Session > Save Session** as `baseline.jvmsession.gz`

**CLI:**
```
jvm-monitor> connect prod-server 9090
jvm-monitor> watch status 10
# Let it run for 5-10 minutes under normal load
jvm-monitor> save baseline.jvmsession.gz
```

### Record key metrics

Note these baseline numbers:

| Metric | How to get | Healthy range |
|---|---|---|
| JVM CPU % | `cpu` or Dashboard | < 60% under normal load |
| Heap usage % | `memory` or Dashboard | < 70% with room for spikes |
| GC throughput | `gc` or GC Analysis tab | > 98% |
| GC pause avg | `gc` or GC Analysis tab | < 50ms for interactive apps |
| Thread count | `threads` or Dashboard | Stable, not growing |
| Blocked thread % | `threads` or Threads tab | < 5% |
| Exception rate | `exceptions` or Exceptions tab | < 10/min for healthy apps |
| Response time (instrumented) | `instrument events` | App-specific |

---

## Phase 2: Identify the Bottleneck Category

Every performance problem falls into one of these categories:

| Category | Key indicator | Primary tool |
|---|---|---|
| **CPU-bound** | JVM CPU > 80% | CPU Profiler, CPU Usage tab |
| **Memory-bound** | Frequent GC, high heap usage | Memory tab, GC Analysis |
| **I/O-bound** | Low CPU but slow responses | Instrumentation (JDBC, HTTP, Socket I/O) |
| **Lock-bound** | High BLOCKED thread % | Locks tab, Threads tab |
| **External dependency** | Slow calls to DB/API/cache | Integration tab, Network tab |

### Quick diagnosis

**GUI:** Open the **Dashboard**. In 10 seconds you can see:
- CPU chart high → CPU-bound
- Heap chart near max with frequent drops → GC-bound
- Thread chart with large red (BLOCKED) area → Lock-bound
- All charts normal but users report slowness → I/O-bound or external dependency

**CLI:**
```
jvm-monitor> diagnose
```

The diagnostic engine correlates all metrics and reports the most likely root cause.

---

## Phase 3: CPU Optimization

### Step 1: Profile CPU usage

**GUI:**
1. Open the **CPU Profiler** tab
2. Click "Start Recording"
3. Generate load on the application (or wait for natural traffic)
4. After 60-120 seconds, view results in the three sub-tabs

**CLI:**
```
jvm-monitor> profiler start
# Wait 60 seconds
jvm-monitor> profiler hotmethods
```

### Step 2: Analyze hot methods

The **Hot Methods** list shows which methods consume the most CPU time (by self sample count). Focus on the top 5-10 methods.

Common patterns:

| Pattern | Problem | Solution |
|---|---|---|
| `java.util.regex.Matcher.match` | Regex in hot path | Pre-compile patterns, use `String.contains()` if possible |
| `java.lang.String.format` | String formatting in loop | Use `StringBuilder` |
| `java.util.HashMap.get` with high count | Hash collisions or hot map | Check `hashCode()` distribution, consider `ConcurrentHashMap` |
| `com.myapp.*.serialize` | Serialization overhead | Use faster serializer (Jackson, Kryo), cache results |
| `java.security.MessageDigest.digest` | Crypto in hot path | Cache digests, reduce hashing frequency |
| Application method with high self time | Algorithmic issue | Review algorithm complexity, add caching |

### Step 3: Analyze the call tree

**GUI:** Switch to the **Call Tree** sub-tab (TraceTreeTable). Expand nodes to trace which callers contribute the most to a hot method. This answers "why is this method called so often?"

**GUI:** The **Flame Graph** provides a visual overview. Wide bars at the bottom are the entry points consuming the most CPU. Narrow spikes are deep call chains.

### Step 4: Check for GC-induced CPU

If GC Throughput < 95%, CPU is being consumed by garbage collection, not application code. See Phase 4 (Memory Optimization) instead.

**CLI:**
```
jvm-monitor> gc
Throughput: 88.5%    <-- 11.5% of CPU spent on GC
```

---

## Phase 4: Memory Optimization

### Step 1: Understand memory usage

**GUI:** Open the **Memory** tab. The four charts show heap, non-heap, allocation rate, and live data set.

Key question: **Is the heap too small, or is the application wasting memory?**

- Live Data Set flat at 60% of max → heap correctly sized, 40% headroom
- Live Data Set growing → memory leak (fix the leak, not the symptoms)
- Live Data Set flat at 30% but frequent GC → allocation rate too high

### Step 2: Reduce allocation rate

High allocation rate causes frequent Young GC. Each GC pauses the application.

**GUI:** Start **Allocation Recording** in the Memory tab. After some load, stop recording. The results show which classes are allocated most frequently and from which code paths.

Common high-allocation patterns:

| Pattern | Problem | Solution |
|---|---|---|
| `byte[]` from serialization | Creating byte arrays for every request | Pool buffers, use direct buffers |
| `String` from concatenation | String + String in loops | Use `StringBuilder` |
| `HashMap$Node` | Creating maps for every request | Reuse maps, use object pooling |
| `ArrayList` with small size | Default capacity (10) wastes space | Pre-size: `new ArrayList(expectedSize)` |
| Autoboxing (`Integer`, `Long`) | Primitive boxing in hot path | Use primitive collections (Eclipse Collections, HPPC) |

### Step 3: Optimize GC

**GUI:** Open the **GC Analysis** tab.

| Observation | Optimization |
|---|---|
| Many short Young GCs | Increase Eden size (`-Xmn`) to reduce frequency |
| Long Full GC pauses | Switch to G1 (`-XX:+UseG1GC`) or ZGC (`-XX:+UseZGC` on JDK 15+) |
| High promotion rate | Objects survive Young GC because Eden is too small. Increase `-Xmn` |
| Throughput < 95% | Increase heap (`-Xmx`), tune GC pause target (`-XX:MaxGCPauseMillis`) |

### Step 4: Find memory leaks

If the Live Data Set is growing:

1. Take a class histogram (Memory > Histogram > Request Histogram)
2. Wait 5 minutes under load
3. Take another histogram
4. Compare Delta Instances — classes with growing count are leak candidates
5. Use **GC Root Analysis** (Tools tab) to find what holds the objects alive

---

## Phase 5: I/O and External Dependency Optimization

### Step 1: Identify slow external calls

**GUI:** Start instrumentation with all probes:
1. Open **Instrumentation** tab
2. Check JDBC, Spring, HTTP, Messaging probes
3. Click "Start Instrumentation"
4. After some traffic, check results

**CLI:**
```
jvm-monitor> instrument start com.myapp jdbc,spring,http,messaging
# Wait for traffic
jvm-monitor> instrument events
```

### Step 2: Analyze JDBC performance

**GUI:** Open **JDBC Monitor > SQL Statistics**. Sort by Avg Time or Total Time.

| Pattern | Problem | Solution |
|---|---|---|
| SELECT with avg > 100ms | Slow query | Add index, optimize query, use EXPLAIN |
| Same SELECT repeated thousands of times | N+1 query problem | Use JOIN or batch fetch |
| INSERT/UPDATE with avg > 50ms | Slow writes | Batch writes, async writes |
| getConnection() > 500ms | Pool exhaustion | Increase pool size, fix connection leaks |

**GUI:** Check **Connection Monitor** sub-tab for leaked connections (open for a long time without close).

### Step 3: Analyze HTTP client performance

**GUI:** Open **HTTP Profiler** sub-tab.

| Pattern | Problem | Solution |
|---|---|---|
| External API call avg > 500ms | Slow remote service | Add timeout, circuit breaker, cache response |
| Same URL called repeatedly | Missing cache | Cache responses (TTL-based) |
| Many concurrent connections to same host | No connection pooling | Use HTTP connection pool |

### Step 4: Analyze per-thread I/O

Enable Disk I/O and Socket I/O probes to see which threads perform the most I/O:

**GUI:** Instrumentation tab > check "Disk I/O" and "Socket I/O" > Start. Check the **Disk I/O** and **Socket I/O** sub-tabs.

---

## Phase 6: Lock and Concurrency Optimization

### Step 1: Identify lock contention

**GUI:** Open the **Locks** tab. The Lock Hotspots chart shows which locks are most contended.

**CLI:**
```
jvm-monitor> locks
=== Lock Contention (last 60s: 342 events) ===
LOCK                                               CONTENTIONS
com.myapp.cache.SessionCache@3a2f1c                189
java.io.PrintStream@7b1d2e                          85
```

### Step 2: Optimize locking

| Pattern | Problem | Solution |
|---|---|---|
| Single lock with many contentions | Coarse-grained lock | Use finer-grained locks, partition data |
| `synchronized(this)` on service class | Entire object locked | Lock only the critical section |
| `Collections.synchronizedMap` | Lock on every operation | Use `ConcurrentHashMap` |
| Database row lock contention | SELECT FOR UPDATE | Optimize transaction scope, use optimistic locking |
| `PrintStream` contention | `System.out.println` in hot path | Use async logging (Logback, Log4j2) |

### Step 3: Check for thread pool sizing

**GUI:** Open the **Threads** tab. Check the Thread Activity sub-tab.

- Many threads in WAITING state → thread pool too large (wasting memory)
- Many threads in BLOCKED/RUNNABLE with queue building up → thread pool too small
- Thread names with high numbers (pool-1-thread-999) → thread leak

---

## Phase 7: Measure Improvement

After applying optimizations:

1. Load the baseline session: **Tools > Session > Load Session** (`baseline.jvmsession.gz`)
2. Note the baseline metrics
3. Connect to the optimized application
4. Run the same load pattern
5. Compare metrics:

| Metric | Before | After | Improvement |
|---|---|---|---|
| Avg response time | 450ms | 120ms | 3.75x faster |
| GC throughput | 88% | 99% | GC no longer a bottleneck |
| CPU usage | 85% | 45% | 47% reduction |
| P95 latency | 1200ms | 250ms | 4.8x improvement |

6. Save the new session: `optimized.jvmsession.gz`

---

## Quick Reference: Optimization Checklist

- [ ] CPU profiler shows no single method > 10% self time
- [ ] GC throughput > 98%
- [ ] GC max pause < 100ms (for interactive apps)
- [ ] No memory leak (live set stable)
- [ ] Heap usage < 70% under peak load
- [ ] No JDBC query > 500ms average
- [ ] No connection leaks (CLOSE_WAIT = 0)
- [ ] Blocked threads < 5%
- [ ] No deadlocks
- [ ] Exception rate < 10/min
- [ ] All alarm thresholds passing (diagnose shows no findings)
