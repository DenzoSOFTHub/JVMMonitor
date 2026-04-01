package it.denzosoft.jvmmonitor.storage;

import it.denzosoft.jvmmonitor.model.*;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public class InMemoryEventStoreTest {

    private InMemoryEventStore store;

    @Before
    public void setUp() {
        store = new InMemoryEventStore(100);
    }

    @Test
    public void testStoreAndRetrieveGcEvents() {
        long now = System.currentTimeMillis();
        store.storeGcEvent(new GcEvent(now - 5000, 1, 8000000L, 10, 1));
        store.storeGcEvent(new GcEvent(now - 3000, 1, 12000000L, 11, 1));
        store.storeGcEvent(new GcEvent(now - 1000, 3, 300000000L, 12, 2));

        List<GcEvent> events = store.getGcEvents(now - 10000, now);
        assertEquals(3, events.size());
        assertEquals(1, events.get(0).getGcType());
        assertEquals(3, events.get(2).getGcType());
    }

    @Test
    public void testGcEventsTimeFilter() {
        long now = System.currentTimeMillis();
        store.storeGcEvent(new GcEvent(now - 60000, 1, 5000000L, 1, 0));
        store.storeGcEvent(new GcEvent(now - 1000, 1, 8000000L, 2, 0));

        List<GcEvent> recent = store.getGcEvents(now - 5000, now);
        assertEquals(1, recent.size());
        assertEquals(2, recent.get(0).getGcCount());
    }

    @Test
    public void testStoreAndRetrieveMemorySnapshots() {
        long now = System.currentTimeMillis();
        store.storeMemorySnapshot(new MemorySnapshot(now, 512 * 1024 * 1024L, 1024 * 1024 * 1024L, 64 * 1024 * 1024L, 256 * 1024 * 1024L));

        MemorySnapshot latest = store.getLatestMemorySnapshot();
        assertNotNull(latest);
        assertEquals(512 * 1024 * 1024L, latest.getHeapUsed());
        assertEquals(1024 * 1024 * 1024L, latest.getHeapMax());
    }

    @Test
    public void testLatestMemorySnapshotReturnsLast() {
        long now = System.currentTimeMillis();
        store.storeMemorySnapshot(new MemorySnapshot(now - 2000, 100L, 1000L, 10L, 100L));
        store.storeMemorySnapshot(new MemorySnapshot(now - 1000, 200L, 1000L, 20L, 100L));
        store.storeMemorySnapshot(new MemorySnapshot(now, 300L, 1000L, 30L, 100L));

        MemorySnapshot latest = store.getLatestMemorySnapshot();
        assertEquals(300L, latest.getHeapUsed());
    }

    @Test
    public void testStoreThreadInfo() {
        store.storeThreadInfo(new ThreadInfo(System.currentTimeMillis(), 1L, "main", 1, false));
        store.storeThreadInfo(new ThreadInfo(System.currentTimeMillis(), 2L, "worker-1", 2, true));

        List<ThreadInfo> threads = store.getLatestThreadInfo();
        assertEquals(2, threads.size());
    }

    @Test
    public void testThreadInfoUpdatesLatest() {
        long now = System.currentTimeMillis();
        store.storeThreadInfo(new ThreadInfo(now - 1000, 1L, "main", 1, false));
        store.storeThreadInfo(new ThreadInfo(now, 1L, "main", 2, false)); /* same thread, new state */

        List<ThreadInfo> threads = store.getLatestThreadInfo();
        assertEquals(1, threads.size());
        assertEquals(2, threads.get(0).getState()); /* updated to BLOCKED */
    }

    @Test
    public void testStoreAlarms() {
        long now = System.currentTimeMillis();
        store.storeAlarm(new AlarmEvent(now, 2, 1, 250.0, 200.0, "GC pause > 200ms"));

        List<AlarmEvent> alarms = store.getActiveAlarms();
        assertEquals(1, alarms.size());
        assertEquals("GC pause > 200ms", alarms.get(0).getMessage());
    }

    @Test
    public void testOldAlarmsNotActive() {
        long tenMinAgo = System.currentTimeMillis() - 10 * 60 * 1000;
        store.storeAlarm(new AlarmEvent(tenMinAgo, 2, 1, 250.0, 200.0, "Old alarm"));

        List<AlarmEvent> active = store.getActiveAlarms();
        assertEquals(0, active.size());
    }

    @Test
    public void testCircularBufferOverwrite() {
        InMemoryEventStore smallStore = new InMemoryEventStore(5);
        long now = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            smallStore.storeGcEvent(new GcEvent(now + i, 1, (long) i * 1000000, i, 0));
        }
        assertEquals(5, smallStore.getGcEventCount());
        List<GcEvent> events = smallStore.getGcEvents(0, Long.MAX_VALUE);
        /* Should have the last 5 events (indices 5-9) */
        assertEquals(5, events.size());
    }

    @Test
    public void testStoreCpuSamples() {
        CpuSample.StackFrame[] frames = new CpuSample.StackFrame[]{
            new CpuSample.StackFrame(12345L, 42),
            new CpuSample.StackFrame(67890L, 100)
        };
        store.storeCpuSample(new CpuSample(System.currentTimeMillis(), 1L, frames));

        assertEquals(1, store.getCpuSampleCount());
        List<CpuSample> samples = store.getCpuSamples(0, Long.MAX_VALUE);
        assertEquals(1, samples.size());
        assertEquals(2, samples.get(0).getDepth());
    }

    @Test
    public void testEmptyStoreReturnsEmpty() {
        assertNull(store.getLatestMemorySnapshot());
        assertTrue(store.getLatestThreadInfo().isEmpty());
        assertTrue(store.getActiveAlarms().isEmpty());
        assertEquals(0, store.getCpuSampleCount());
        assertEquals(0, store.getGcEventCount());
    }
}
