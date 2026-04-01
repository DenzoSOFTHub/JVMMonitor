package it.denzosoft.jvmmonitor.storage;

import it.denzosoft.jvmmonitor.model.*;
import java.util.*;

public class InMemoryEventStore implements EventStore {

    private static final int DEFAULT_CAPACITY = 50000;

    private final CpuSample[] cpuSamples;
    private int cpuWriteIdx = 0;
    private int cpuCount = 0;

    private final GcEvent[] gcEvents;
    private int gcWriteIdx = 0;
    private int gcCount = 0;

    private final MemorySnapshot[] memSnapshots;
    private int memWriteIdx = 0;
    private int memCount = 0;

    private final AlarmEvent[] alarms;
    private int alarmWriteIdx = 0;
    private int alarmCount = 0;

    private final ExceptionEvent[] exceptions;
    private int excWriteIdx = 0;
    private int excCount = 0;

    private final JitEvent[] jitEvents;
    private int jitWriteIdx = 0;
    private int jitCount = 0;

    private final Map<Long, ThreadInfo> latestThreads =
            Collections.synchronizedMap(new LinkedHashMap<Long, ThreadInfo>());

    private static final int SNAPSHOT_CAPACITY = 1000;

    private final OsMetrics[] osMetricsHistory;
    private int osWriteIdx = 0;
    private int osCount = 0;

    private final SafepointEvent[] safepointHistory;
    private int spWriteIdx = 0;
    private int spCount = 0;

    private final List<ClassHistogram> histogramHistory =
            Collections.synchronizedList(new ArrayList<ClassHistogram>());
    private static final int MAX_HISTOGRAMS = 20;
    private volatile NativeMemoryStats latestNativeMemory;
    private volatile GcDetail latestGcDetail;
    private volatile ClassloaderStats latestClassloaderStats;
    private volatile StringTableStats latestStringTableStats;

    private final NetworkSnapshot[] networkHistory;
    private int netWriteIdx = 0;
    private int netCount = 0;

    private final LockEvent[] lockEvents;
    private int lockWriteIdx = 0;
    private int lockCount = 0;

    private volatile ProcessInfo latestProcessInfo;

    private final AllocationEvent[] allocEvents;
    private int allocWriteIdx = 0;
    private int allocCount = 0;

    private final QueueStats[] queueStatsHistory;
    private int qsWriteIdx = 0;
    private int qsCount = 0;

    private final InstrumentationEvent[] instrEvents;
    private int instrWriteIdx = 0;
    private int instrCount = 0;

    private final CpuUsageSnapshot[] cpuUsageHistory;
    private int cpuUWriteIdx = 0;
    private int cpuUCount = 0;

    private final int capacity;

    public InMemoryEventStore() {
        this(DEFAULT_CAPACITY);
    }

    public InMemoryEventStore(int capacity) {
        this.capacity = capacity;
        this.cpuSamples = new CpuSample[capacity];
        this.gcEvents = new GcEvent[capacity];
        this.memSnapshots = new MemorySnapshot[capacity];
        this.alarms = new AlarmEvent[capacity];
        this.exceptions = new ExceptionEvent[capacity];
        this.jitEvents = new JitEvent[capacity];
        this.networkHistory = new NetworkSnapshot[SNAPSHOT_CAPACITY];
        this.lockEvents = new LockEvent[capacity];
        this.queueStatsHistory = new QueueStats[SNAPSHOT_CAPACITY];
        this.allocEvents = new AllocationEvent[capacity];
        this.instrEvents = new InstrumentationEvent[capacity];
        this.cpuUsageHistory = new CpuUsageSnapshot[SNAPSHOT_CAPACITY];
        this.osMetricsHistory = new OsMetrics[SNAPSHOT_CAPACITY];
        this.safepointHistory = new SafepointEvent[SNAPSHOT_CAPACITY];
    }

    public synchronized void storeCpuSample(CpuSample sample) {
        cpuSamples[cpuWriteIdx] = sample;
        cpuWriteIdx = (cpuWriteIdx + 1) % capacity;
        if (cpuCount < capacity) cpuCount++;
    }

    public synchronized void storeGcEvent(GcEvent event) {
        gcEvents[gcWriteIdx] = event;
        gcWriteIdx = (gcWriteIdx + 1) % capacity;
        if (gcCount < capacity) gcCount++;
    }

    public void storeThreadInfo(ThreadInfo info) {
        latestThreads.put(Long.valueOf(info.getThreadId()), info);
    }

    public synchronized void storeMemorySnapshot(MemorySnapshot snapshot) {
        memSnapshots[memWriteIdx] = snapshot;
        memWriteIdx = (memWriteIdx + 1) % capacity;
        if (memCount < capacity) memCount++;
    }

    public synchronized void storeAlarm(AlarmEvent alarm) {
        alarms[alarmWriteIdx] = alarm;
        alarmWriteIdx = (alarmWriteIdx + 1) % capacity;
        if (alarmCount < capacity) alarmCount++;
    }

    public synchronized void storeException(ExceptionEvent event) {
        exceptions[excWriteIdx] = event;
        excWriteIdx = (excWriteIdx + 1) % capacity;
        if (excCount < capacity) excCount++;
    }

    public synchronized void storeOsMetrics(OsMetrics metrics) {
        osMetricsHistory[osWriteIdx] = metrics;
        osWriteIdx = (osWriteIdx + 1) % SNAPSHOT_CAPACITY;
        if (osCount < SNAPSHOT_CAPACITY) osCount++;
    }

    public synchronized void storeJitEvent(JitEvent event) {
        jitEvents[jitWriteIdx] = event;
        jitWriteIdx = (jitWriteIdx + 1) % capacity;
        if (jitCount < capacity) jitCount++;
    }

    public void storeClassHistogram(ClassHistogram histogram) {
        histogramHistory.add(histogram);
        if (histogramHistory.size() > MAX_HISTOGRAMS) {
            histogramHistory.remove(0);
        }
    }

    public synchronized void storeSafepoint(SafepointEvent event) {
        safepointHistory[spWriteIdx] = event;
        spWriteIdx = (spWriteIdx + 1) % SNAPSHOT_CAPACITY;
        if (spCount < SNAPSHOT_CAPACITY) spCount++;
    }

    public void storeNativeMemory(NativeMemoryStats stats) {
        latestNativeMemory = stats;
    }

    public void storeGcDetail(GcDetail detail) {
        latestGcDetail = detail;
    }

    public void storeClassloaderStats(ClassloaderStats stats) {
        latestClassloaderStats = stats;
    }

    public void storeStringTableStats(StringTableStats stats) {
        latestStringTableStats = stats;
    }

    public synchronized void storeNetworkSnapshot(NetworkSnapshot snapshot) {
        networkHistory[netWriteIdx] = snapshot;
        netWriteIdx = (netWriteIdx + 1) % SNAPSHOT_CAPACITY;
        if (netCount < SNAPSHOT_CAPACITY) netCount++;
    }

    public void storeProcessInfo(ProcessInfo info) {
        latestProcessInfo = info;
    }

    public synchronized void storeAllocationEvent(AllocationEvent event) {
        allocEvents[allocWriteIdx] = event;
        allocWriteIdx = (allocWriteIdx + 1) % capacity;
        if (allocCount < capacity) allocCount++;
    }

    public synchronized List<AllocationEvent> getAllocationEvents(long from, long to) {
        List<AllocationEvent> result = new ArrayList<AllocationEvent>();
        int start = allocCount < capacity ? 0 : allocWriteIdx;
        for (int i = 0; i < allocCount; i++) {
            AllocationEvent e = allocEvents[(start + i) % capacity];
            if (e != null && e.getTimestamp() >= from && e.getTimestamp() <= to) {
                result.add(e);
            }
        }
        return result;
    }

    public synchronized int getAllocationEventCount() { return allocCount; }

    public synchronized void storeInstrumentationEvent(InstrumentationEvent event) {
        instrEvents[instrWriteIdx] = event;
        instrWriteIdx = (instrWriteIdx + 1) % capacity;
        if (instrCount < capacity) instrCount++;
    }

    public synchronized List<InstrumentationEvent> getInstrumentationEvents(long from, long to) {
        List<InstrumentationEvent> result = new ArrayList<InstrumentationEvent>();
        int start = instrCount < capacity ? 0 : instrWriteIdx;
        for (int i = 0; i < instrCount; i++) {
            InstrumentationEvent e = instrEvents[(start + i) % capacity];
            if (e != null && e.getTimestamp() >= from && e.getTimestamp() <= to) {
                result.add(e);
            }
        }
        return result;
    }

    public synchronized int getInstrumentationEventCount() { return instrCount; }

    public synchronized void storeQueueStats(QueueStats stats) {
        queueStatsHistory[qsWriteIdx] = stats;
        qsWriteIdx = (qsWriteIdx + 1) % SNAPSHOT_CAPACITY;
        if (qsCount < SNAPSHOT_CAPACITY) qsCount++;
    }

    public synchronized QueueStats getLatestQueueStats() {
        if (qsCount == 0) return null;
        int idx = (qsWriteIdx - 1 + SNAPSHOT_CAPACITY) % SNAPSHOT_CAPACITY;
        return queueStatsHistory[idx];
    }

    public synchronized List<QueueStats> getQueueStatsHistory(long from, long to) {
        List<QueueStats> result = new ArrayList<QueueStats>();
        int start = qsCount < SNAPSHOT_CAPACITY ? 0 : qsWriteIdx;
        for (int i = 0; i < qsCount; i++) {
            QueueStats s = queueStatsHistory[(start + i) % SNAPSHOT_CAPACITY];
            if (s != null && s.getTimestamp() >= from && s.getTimestamp() <= to) {
                result.add(s);
            }
        }
        return result;
    }

    public ProcessInfo getLatestProcessInfo() {
        return latestProcessInfo;
    }

    public synchronized void storeCpuUsage(CpuUsageSnapshot snapshot) {
        cpuUsageHistory[cpuUWriteIdx] = snapshot;
        cpuUWriteIdx = (cpuUWriteIdx + 1) % SNAPSHOT_CAPACITY;
        if (cpuUCount < SNAPSHOT_CAPACITY) cpuUCount++;
    }

    public synchronized void storeLockEvent(LockEvent event) {
        lockEvents[lockWriteIdx] = event;
        lockWriteIdx = (lockWriteIdx + 1) % capacity;
        if (lockCount < capacity) lockCount++;
    }

    public synchronized List<CpuSample> getCpuSamples(long from, long to) {
        List<CpuSample> result = new ArrayList<CpuSample>();
        int start = cpuCount < capacity ? 0 : cpuWriteIdx;
        for (int i = 0; i < cpuCount; i++) {
            CpuSample s = cpuSamples[(start + i) % capacity];
            if (s != null && s.getTimestamp() >= from && s.getTimestamp() <= to) {
                result.add(s);
            }
        }
        return result;
    }

    public synchronized List<GcEvent> getGcEvents(long from, long to) {
        List<GcEvent> result = new ArrayList<GcEvent>();
        int start = gcCount < capacity ? 0 : gcWriteIdx;
        for (int i = 0; i < gcCount; i++) {
            GcEvent e = gcEvents[(start + i) % capacity];
            if (e != null && e.getTimestamp() >= from && e.getTimestamp() <= to) {
                result.add(e);
            }
        }
        return result;
    }

    public List<ThreadInfo> getLatestThreadInfo() {
        return new ArrayList<ThreadInfo>(latestThreads.values());
    }

    public synchronized List<MemorySnapshot> getMemorySnapshots(long from, long to) {
        List<MemorySnapshot> result = new ArrayList<MemorySnapshot>();
        int start = memCount < capacity ? 0 : memWriteIdx;
        for (int i = 0; i < memCount; i++) {
            MemorySnapshot s = memSnapshots[(start + i) % capacity];
            if (s != null && s.getTimestamp() >= from && s.getTimestamp() <= to) {
                result.add(s);
            }
        }
        return result;
    }

    public synchronized MemorySnapshot getLatestMemorySnapshot() {
        if (memCount == 0) return null;
        int idx = (memWriteIdx - 1 + capacity) % capacity;
        return memSnapshots[idx];
    }

    public synchronized List<AlarmEvent> getAlarms(long from, long to) {
        List<AlarmEvent> result = new ArrayList<AlarmEvent>();
        int start = alarmCount < capacity ? 0 : alarmWriteIdx;
        for (int i = 0; i < alarmCount; i++) {
            AlarmEvent a = alarms[(start + i) % capacity];
            if (a != null && a.getTimestamp() >= from && a.getTimestamp() <= to) {
                result.add(a);
            }
        }
        return result;
    }

    public synchronized List<AlarmEvent> getActiveAlarms() {
        long fiveMinAgo = System.currentTimeMillis() - 5 * 60 * 1000;
        return getAlarms(fiveMinAgo, Long.MAX_VALUE);
    }

    public synchronized List<ExceptionEvent> getExceptions(long from, long to) {
        List<ExceptionEvent> result = new ArrayList<ExceptionEvent>();
        int start = excCount < capacity ? 0 : excWriteIdx;
        for (int i = 0; i < excCount; i++) {
            ExceptionEvent e = exceptions[(start + i) % capacity];
            if (e != null && e.getTimestamp() >= from && e.getTimestamp() <= to) {
                result.add(e);
            }
        }
        return result;
    }

    public synchronized ExceptionEvent getLatestException() {
        if (excCount == 0) return null;
        int idx = (excWriteIdx - 1 + capacity) % capacity;
        return exceptions[idx];
    }

    public synchronized OsMetrics getLatestOsMetrics() {
        if (osCount == 0) return null;
        int idx = (osWriteIdx - 1 + SNAPSHOT_CAPACITY) % SNAPSHOT_CAPACITY;
        return osMetricsHistory[idx];
    }

    public synchronized List<OsMetrics> getOsMetricsHistory(long from, long to) {
        List<OsMetrics> result = new ArrayList<OsMetrics>();
        int start = osCount < SNAPSHOT_CAPACITY ? 0 : osWriteIdx;
        for (int i = 0; i < osCount; i++) {
            OsMetrics m = osMetricsHistory[(start + i) % SNAPSHOT_CAPACITY];
            if (m != null && m.getTimestamp() >= from && m.getTimestamp() <= to) {
                result.add(m);
            }
        }
        return result;
    }

    public synchronized List<JitEvent> getJitEvents(long from, long to) {
        List<JitEvent> result = new ArrayList<JitEvent>();
        int start = jitCount < capacity ? 0 : jitWriteIdx;
        for (int i = 0; i < jitCount; i++) {
            JitEvent e = jitEvents[(start + i) % capacity];
            if (e != null && e.getTimestamp() >= from && e.getTimestamp() <= to) {
                result.add(e);
            }
        }
        return result;
    }

    public ClassHistogram getLatestClassHistogram() {
        synchronized (histogramHistory) {
            if (histogramHistory.isEmpty()) return null;
            return histogramHistory.get(histogramHistory.size() - 1);
        }
    }

    public List<ClassHistogram> getClassHistogramHistory() {
        synchronized (histogramHistory) {
            return new ArrayList<ClassHistogram>(histogramHistory);
        }
    }

    public synchronized SafepointEvent getLatestSafepoint() {
        if (spCount == 0) return null;
        int idx = (spWriteIdx - 1 + SNAPSHOT_CAPACITY) % SNAPSHOT_CAPACITY;
        return safepointHistory[idx];
    }

    public synchronized List<SafepointEvent> getSafepointHistory(long from, long to) {
        List<SafepointEvent> result = new ArrayList<SafepointEvent>();
        int start = spCount < SNAPSHOT_CAPACITY ? 0 : spWriteIdx;
        for (int i = 0; i < spCount; i++) {
            SafepointEvent s = safepointHistory[(start + i) % SNAPSHOT_CAPACITY];
            if (s != null && s.getTimestamp() >= from && s.getTimestamp() <= to) {
                result.add(s);
            }
        }
        return result;
    }

    public NativeMemoryStats getLatestNativeMemory() {
        return latestNativeMemory;
    }

    public GcDetail getLatestGcDetail() {
        return latestGcDetail;
    }

    public ClassloaderStats getLatestClassloaderStats() {
        return latestClassloaderStats;
    }

    public StringTableStats getLatestStringTableStats() {
        return latestStringTableStats;
    }

    public synchronized NetworkSnapshot getLatestNetworkSnapshot() {
        if (netCount == 0) return null;
        int idx = (netWriteIdx - 1 + SNAPSHOT_CAPACITY) % SNAPSHOT_CAPACITY;
        return networkHistory[idx];
    }

    public synchronized List<NetworkSnapshot> getNetworkHistory(long from, long to) {
        List<NetworkSnapshot> result = new ArrayList<NetworkSnapshot>();
        int start = netCount < SNAPSHOT_CAPACITY ? 0 : netWriteIdx;
        for (int i = 0; i < netCount; i++) {
            NetworkSnapshot n = networkHistory[(start + i) % SNAPSHOT_CAPACITY];
            if (n != null && n.getTimestamp() >= from && n.getTimestamp() <= to) {
                result.add(n);
            }
        }
        return result;
    }

    public synchronized CpuUsageSnapshot getLatestCpuUsage() {
        if (cpuUCount == 0) return null;
        int idx = (cpuUWriteIdx - 1 + SNAPSHOT_CAPACITY) % SNAPSHOT_CAPACITY;
        return cpuUsageHistory[idx];
    }

    public synchronized List<CpuUsageSnapshot> getCpuUsageHistory(long from, long to) {
        List<CpuUsageSnapshot> result = new ArrayList<CpuUsageSnapshot>();
        int start = cpuUCount < SNAPSHOT_CAPACITY ? 0 : cpuUWriteIdx;
        for (int i = 0; i < cpuUCount; i++) {
            CpuUsageSnapshot s = cpuUsageHistory[(start + i) % SNAPSHOT_CAPACITY];
            if (s != null && s.getTimestamp() >= from && s.getTimestamp() <= to) {
                result.add(s);
            }
        }
        return result;
    }

    public synchronized List<LockEvent> getLockEvents(long from, long to) {
        List<LockEvent> result = new ArrayList<LockEvent>();
        int start = lockCount < capacity ? 0 : lockWriteIdx;
        for (int i = 0; i < lockCount; i++) {
            LockEvent e = lockEvents[(start + i) % capacity];
            if (e != null && e.getTimestamp() >= from && e.getTimestamp() <= to) {
                result.add(e);
            }
        }
        return result;
    }

    public synchronized int getLockEventCount() { return lockCount; }
    public synchronized int getCpuSampleCount() { return cpuCount; }
    public synchronized int getGcEventCount() { return gcCount; }
    public synchronized int getMemorySnapshotCount() { return memCount; }
    public synchronized int getExceptionCount() { return excCount; }
    public synchronized int getJitEventCount() { return jitCount; }
}
