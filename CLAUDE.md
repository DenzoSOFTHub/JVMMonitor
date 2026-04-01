# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

All builds run from Linux. Requires gcc, x86_64-w64-mingw32-gcc, JDK 8+, and Maven 3+.

```bash
make              # Build everything: Linux + Windows + Java
make linux        # Linux agent only (dist/linux/jvmmonitor.so)
make windows      # Windows agent only (dist/windows/jvmmonitor.dll)
make java         # Java collector only (dist/jvmmonitor.jar)
make clean        # Clean all build artifacts
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

### Agent (C) - TCP Server
- Version: 1.0.0. Entry points: `Agent_OnLoad` / `Agent_OnAttach` / `Agent_OnUnload`
- Lock-free SPSC ring buffer → TCP transport
- Wire protocol: big-endian binary, 10-byte header (magic `0x4A564D4D`, version, type, length)
- Protocol constants must stay in sync: `agent/include/jvmmon/protocol.h` ↔ `collector/.../protocol/ProtocolConstants.java`
- Crash handler: auto-saves diagnostic dump on VMDeath/signal. Signal handler uses ONLY async-signal-safe functions (write, open, close)
- Capabilities: graceful degradation (full → basic fallback if JVM doesn't support all)
- Rate limiter: atomic CAS for thread-safe exception throttling
- Transport: 100 Hz idle polling (10ms sleep)
- 15 activatable modules registered in module_registry

### Collector (Java) - TCP Client
- Package: `it.denzosoft.jvmmonitor`. Version: 1.0.0
- Source compatibility: Java 1.6 (no diamond, no lambda, no try-with-resources)
- Zero external runtime dependencies
- Includes DenzoSOFT Java Decompiler sources (`it.denzosoft.javadecompiler`, 139 files)
- Entry point: `it.denzosoft.jvmmonitor.Main`
- Production hardening: socket cleanup on error, method cache capped at 10K, EDT crash prevention via safeRefresh(), histogram TOCTOU fixed

### GUI Tabs (15)
Dashboard, Memory, GC Analysis, Threads, Exceptions, Network, Integration, Messaging, Locks, CPU Usage, CPU Profiler, Instrumentation, System, Debugger, Tools (17 sub-tabs)

### CLI Commands (30+)
Full feature parity with GUI: status, memory, gc, threads, cpu, exceptions, network, locks, queues, integration, processes, os, jit, classloaders, nativemem, safepoints, histogram, profiler, instrument, diagnose, alarms, threshold, save, load, export, watch, enable, disable, modules

### Key Packages
- `gui/` — Swing panels (15 main + sub-tabs)
- `gui/chart/` — TimeSeriesChart, StackedAreaChart, BarChart, SparklinePanel, FlameGraph, TraceTreeTable, AlignedCellRenderer, CsvExporter, ClassNameFormatter
- `model/` — 20+ data model classes
- `storage/` — EventStore interface + InMemoryEventStore (circular buffers)
- `analysis/` — CpuProfileAggregator, HeapAnalyzer, DiagnosisEngine + 9 rules, AlarmThresholds (configurable)
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
  linux/jvmmonitor.so       # ~103 KB
  windows/jvmmonitor.dll    # ~416 KB
  jvmmonitor.jar            # ~830 KB (includes decompiler)
```

### Demo Mode
```bash
java -cp dist/jvmmonitor.jar it.denzosoft.jvmmonitor.demo.DemoSession [durationSec] [refreshSec]
java -cp dist/jvmmonitor.jar it.denzosoft.jvmmonitor.demo.DemoSwingSession [screenshotDir]
java -cp dist/jvmmonitor.jar it.denzosoft.jvmmonitor.demo.DemoAgent [port]
```

## Code Style

- **All code, comments, Javadoc, documentation, and string literals must be in English.** No other languages.
- **Java 1.6 source compatibility**: No diamond `<>`, no lambda, no try-with-resources, no String switch, no multi-catch.
- **Numbers right-aligned, text left-aligned** in all tables (use `AlignedCellRenderer`).
- **All charts must have tooltips** showing values on hover, time labels on X axis, gradient fills.
- **Zero dependencies**: only JDK classes at runtime. JUnit 4 for tests.
- **Thread safety**: store methods are synchronized. GUI refresh wrapped in safeRefresh(). Volatile for snapshot-only fields.
- **Error handling**: all panel refresh() must be exception-safe. Log errors to stderr with `[JVMMonitor]` prefix.
