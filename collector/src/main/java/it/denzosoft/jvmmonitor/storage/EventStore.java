package it.denzosoft.jvmmonitor.storage;

import it.denzosoft.jvmmonitor.model.*;
import java.util.List;

public interface EventStore {

    void storeCpuSample(CpuSample sample);
    void storeGcEvent(GcEvent event);
    void storeThreadInfo(ThreadInfo info);
    void storeMemorySnapshot(MemorySnapshot snapshot);
    void storeAlarm(AlarmEvent alarm);
    void storeException(ExceptionEvent event);
    void storeOsMetrics(OsMetrics metrics);
    void storeJitEvent(JitEvent event);
    void storeClassHistogram(ClassHistogram histogram);
    void storeSafepoint(SafepointEvent event);
    void storeNativeMemory(NativeMemoryStats stats);
    void storeGcDetail(GcDetail detail);
    void storeClassloaderStats(ClassloaderStats stats);
    void storeStringTableStats(StringTableStats stats);
    void storeNetworkSnapshot(NetworkSnapshot snapshot);
    void storeLockEvent(LockEvent event);
    void storeCpuUsage(CpuUsageSnapshot snapshot);
    void storeProcessInfo(ProcessInfo info);
    void storeAllocationEvent(AllocationEvent event);
    void storeInstrumentationEvent(InstrumentationEvent event);
    void storeJvmConfig(JvmConfig config);
    JvmConfig getLatestJvmConfig();

    List<CpuSample> getCpuSamples(long fromTimestamp, long toTimestamp);
    List<GcEvent> getGcEvents(long fromTimestamp, long toTimestamp);
    List<ThreadInfo> getLatestThreadInfo();
    List<MemorySnapshot> getMemorySnapshots(long fromTimestamp, long toTimestamp);
    MemorySnapshot getLatestMemorySnapshot();
    List<AlarmEvent> getAlarms(long fromTimestamp, long toTimestamp);
    List<AlarmEvent> getActiveAlarms();
    List<ExceptionEvent> getExceptions(long fromTimestamp, long toTimestamp);
    ExceptionEvent getLatestException();
    OsMetrics getLatestOsMetrics();
    List<OsMetrics> getOsMetricsHistory(long fromTimestamp, long toTimestamp);
    List<JitEvent> getJitEvents(long fromTimestamp, long toTimestamp);
    ClassHistogram getLatestClassHistogram();
    List<ClassHistogram> getClassHistogramHistory();
    SafepointEvent getLatestSafepoint();
    List<SafepointEvent> getSafepointHistory(long fromTimestamp, long toTimestamp);
    NativeMemoryStats getLatestNativeMemory();
    GcDetail getLatestGcDetail();
    ClassloaderStats getLatestClassloaderStats();
    StringTableStats getLatestStringTableStats();
    NetworkSnapshot getLatestNetworkSnapshot();
    List<NetworkSnapshot> getNetworkHistory(long fromTimestamp, long toTimestamp);
    List<LockEvent> getLockEvents(long fromTimestamp, long toTimestamp);
    int getLockEventCount();
    CpuUsageSnapshot getLatestCpuUsage();
    List<CpuUsageSnapshot> getCpuUsageHistory(long fromTimestamp, long toTimestamp);
    List<AllocationEvent> getAllocationEvents(long fromTimestamp, long toTimestamp);
    int getAllocationEventCount();
    List<InstrumentationEvent> getInstrumentationEvents(long fromTimestamp, long toTimestamp);
    int getInstrumentationEventCount();
    void storeQueueStats(QueueStats stats);
    QueueStats getLatestQueueStats();
    List<QueueStats> getQueueStatsHistory(long fromTimestamp, long toTimestamp);
    ProcessInfo getLatestProcessInfo();

    int getCpuSampleCount();
    int getGcEventCount();
    int getMemorySnapshotCount();
    int getExceptionCount();
    int getJitEventCount();
}
