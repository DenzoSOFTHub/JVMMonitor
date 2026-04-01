# Continuous Monitoring Guide with JVMMonitor

## Introduction

This guide explains how to set up and use JVMMonitor for continuous monitoring of Java applications in production. The goal is to detect problems before they become critical: memory leaks before OOM, GC degradation before latency spikes, connection leaks before pool exhaustion.

---

## Setup

### 1. Deploy the agent

Add the agent to your application's startup command:

```bash
java -agentpath:/opt/jvmmonitor/jvmmonitor.so=port=9090 -jar your-app.jar
```

Or inject at runtime without restart:

```bash
java -jar jvmmonitor.jar attach <PID> --port 9090
```

The agent starts in CORE mode with minimal overhead (~1-2% CPU, ~2-5 MB RSS). No profiling modules are active until explicitly enabled.

### 2. Configure alarm thresholds

Create a threshold file tailored to your application:

**CLI:**
```
jvm-monitor> connect prod-server 9090
jvm-monitor> threshold set cpu.warnPct 70
jvm-monitor> threshold set gc.throughputWarnPct 95
jvm-monitor> threshold set response.jdbcSlowMs 500
jvm-monitor> threshold set exceptions.highRate 50
jvm-monitor> threshold save production.thresholds
```

**GUI:** Open **Tools > Alarm Config**, edit values in the table, click "Save to File".

Deploy the same `.thresholds` file to all environments for consistent alerting.

### 3. Enable monitoring modules

Enable the modules you need for continuous monitoring:

```
jvm-monitor> enable exceptions 1
jvm-monitor> enable os 1
jvm-monitor> enable network 1
jvm-monitor> enable locks 1
```

These modules add minimal overhead (< 5% total) and provide essential visibility.

---

## What to Monitor

### Health Dashboard

**GUI:** The **Dashboard** tab provides a single-screen overview. Check it periodically or use `watch`:

**CLI:**
```
jvm-monitor> watch status 30
```

### Key metrics and healthy ranges

| Metric | Check | Healthy | Warning | Critical |
|---|---|---|---|---|
| **Heap usage** | `memory` | < 70% | 70-85% | > 85% with rising live set |
| **GC throughput** | `gc` | > 98% | 95-98% | < 95% |
| **GC max pause** | `gc` | < 50ms | 50-200ms | > 200ms |
| **JVM CPU** | `cpu` | < 50% | 50-80% | > 80% sustained |
| **Blocked threads** | `threads` | < 3% | 3-20% | > 20% |
| **Exception rate** | `exceptions` | < 10/min | 10-100/min | > 100/min or spike |
| **CLOSE_WAIT** | `network` | 0 | 1-10 | > 10 growing |
| **Retransmit rate** | `network` | < 1% | 1-5% | > 5% |
| **Thread count** | `threads` | Stable | Growing slowly | > 500 or growing fast |
| **Live data set** | `diagnose` | Flat | Slight growth | Growing across Full GCs |

---

## Daily Monitoring Routine

### Morning check (2 minutes)

**GUI:**
1. Open Dashboard — scan all 6 charts for anomalies
2. Check Active Alarms at the bottom — should be empty
3. Switch to Memory tab — verify live data set is flat

**CLI:**
```
jvm-monitor> status
jvm-monitor> diagnose
jvm-monitor> gc
```

If `diagnose` reports no findings, the application is healthy.

### Periodic check (every 4-8 hours)

**GUI:**
1. Dashboard — look for trends (heap climbing, CPU increasing)
2. Exceptions tab — check for new exception types
3. Network tab — check for CLOSE_WAIT growth
4. GC Analysis — check throughput trend

**CLI:**
```
jvm-monitor> status
jvm-monitor> memory
jvm-monitor> gc
jvm-monitor> exceptions
jvm-monitor> network
jvm-monitor> diagnose
```

### Weekly deep check

1. Run class histogram and compare with previous week
2. Check classloader count for growth
3. Review lock contention hotspots
4. Save session for historical comparison

**CLI:**
```
jvm-monitor> enable histogram 1
jvm-monitor> histogram
jvm-monitor> classloaders
jvm-monitor> locks
jvm-monitor> save weekly-2026-04-01.jvmsession.gz
```

---

## Automated Alerting

### Diagnosis loop

The CLI runs a background diagnosis loop every 10 seconds. CRITICAL findings are printed automatically:

```
jvm-monitor> connect prod-server 9090

! Memory Leak: Live set growing: 400 MB -> 620 MB across 4 Full GCs.
  -> Take two class histograms 5 min apart and compare.
jvm-monitor>
```

### Watch mode for continuous display

```
jvm-monitor> watch status 10
```

This refreshes the status every 10 seconds, showing heap, CPU, threads, GC count, exceptions, and alarms. Press Enter to stop.

---

## Detecting Problems Early

### Memory leak detection

A memory leak does not cause OOM immediately. JVMMonitor detects it early by monitoring the **live data set** — the heap used after Full GC. If this floor is rising, objects are accumulating.

**GUI:** Memory tab > Live Data Set chart (bottom-right). A flat line is healthy. A rising line is a leak.

The diagnostic engine reports:
```
--- WARNING --- Memory Leak ---
Live set growing: 200 MB -> 350 MB across 5 Full GCs. Growth rate: 60 MB/h.
Estimated OOM in 11.2 hours.
```

This gives you hours of warning before the application crashes.

### GC degradation detection

Before GC causes visible latency problems, throughput starts dropping:

```
--- WARNING --- GC Pressure ---
GC throughput 93.5% — losing 6.5% of time to GC.
```

This early warning appears when throughput drops below the configured threshold (default 90%, recommended 95% for production).

### Connection leak detection

Connection leaks are silent — the pool works fine until it is exhausted. JVMMonitor detects them by monitoring CLOSE_WAIT trend:

```
--- WARNING --- TCP Connection Leak ---
15 CLOSE_WAIT connections and growing.
```

And through the JDBC Connection Monitor (if instrumentation is enabled), which shows connections open for abnormally long periods.

### Thread leak detection

Thread leaks consume memory (each thread uses ~1 MB stack space) and eventually cause OOM or OS thread limit errors:

```
--- WARNING --- High Thread Count ---
650 threads — possible thread leak.
```

**GUI:** Threads tab — look for thread names with incrementing counters (e.g., `pool-1-thread-650`).

### Exception spike detection

A sudden increase in exception rate often indicates a new bug introduced by a deployment or a downstream service failure:

```
--- WARNING --- Exception Storm ---
Exception rate spike: 500/min (was 30/min baseline) — 16.7x increase.
```

The engine compares the last minute against the 5-minute baseline, so it detects spikes even if the absolute rate is not high.

### Response time degradation

Instrumented methods are compared against their recent baseline:

```
--- WARNING --- Response Time Degradation ---
OrderService.getOrders: avg response time 450ms (was 120ms) — 3.8x slower.
```

This detects degradation even if no single request times out.

---

## Responding to Alarms

### When a CRITICAL alarm fires

1. **Save the session immediately**: `save incident.jvmsession.gz`
2. **Run full diagnostics**: `diagnose`
3. **Check the specific area** indicated by the alarm
4. **Export HTML report** for documentation: `export incident-report.html`

### When a WARNING alarm fires

1. **Note the trend** — is it getting worse or stable?
2. **Check if it correlates** with a recent deployment or load change
3. **Schedule investigation** if the trend is worsening
4. **Adjust thresholds** if the warning is a false positive for your application

### False positive tuning

Some applications have legitimately different profiles:

| Situation | Threshold adjustment |
|---|---|
| Batch processing app with high CPU | `threshold set cpu.warnPct 90` |
| App with intentional long-lived caches | `threshold set memory.oldGenAfterFullGcPct 90` |
| App that throws exceptions for flow control | `threshold set exceptions.highRate 500` |
| Microservice with many outbound connections | `threshold set network.establishedWarn 1000` |

Save adjusted thresholds: `threshold save myapp-production.thresholds`

---

## Session Comparison

Compare sessions to detect regressions after deployments:

1. Save a session before deployment: `save before-deploy.jvmsession.gz`
2. Deploy the new version
3. Save a session after deployment: `save after-deploy.jvmsession.gz`
4. Load each session and compare key metrics:

```
jvm-monitor> load before-deploy.jvmsession.gz
jvm-monitor> memory
Heap: 350 MB / 1024 MB (34.2%)
Growth: 5.0 MB/h

jvm-monitor> load after-deploy.jvmsession.gz
jvm-monitor> memory
Heap: 480 MB / 1024 MB (46.9%)    <-- higher baseline
Growth: 25.0 MB/h                  <-- faster growth = possible new leak
```

---

## Multi-Environment Monitoring

Use the same threshold file across environments with environment-specific overrides:

```
# base.thresholds — shared across all environments
cpu.warnPct=80
gc.throughputWarnPct=95
exceptions.highRate=50

# production.thresholds — stricter for production
cpu.warnPct=70
gc.throughputWarnPct=98
exceptions.highRate=20

# staging.thresholds — relaxed for staging
cpu.warnPct=90
exceptions.highRate=200
```

---

## Monitoring Checklist

### Daily
- [ ] Dashboard shows no active alarms
- [ ] `diagnose` returns no findings
- [ ] Heap usage below 70%
- [ ] GC throughput above 98%
- [ ] Exception rate below threshold

### Weekly
- [ ] Class histogram compared with previous week
- [ ] Classloader count stable
- [ ] Thread count stable
- [ ] CLOSE_WAIT count at 0
- [ ] Session saved for historical record

### After each deployment
- [ ] Session saved before and after
- [ ] Key metrics compared (heap, CPU, GC, response time)
- [ ] `diagnose` run on new version
- [ ] Exception rate monitored for 30 minutes post-deploy
- [ ] No new alarm types appearing
