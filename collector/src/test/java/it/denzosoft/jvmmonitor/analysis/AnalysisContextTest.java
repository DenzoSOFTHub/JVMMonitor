package it.denzosoft.jvmmonitor.analysis;

import it.denzosoft.jvmmonitor.model.*;
import it.denzosoft.jvmmonitor.storage.InMemoryEventStore;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class AnalysisContextTest {

    private InMemoryEventStore store;
    private AnalysisContext ctx;

    @Before
    public void setUp() {
        store = new InMemoryEventStore();
        ctx = new AnalysisContext(store);
    }

    @Test
    public void testGcFrequencyCalculation() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 30; i++) {
            store.storeGcEvent(new GcEvent(now - 60000 + i * 2000, 1, 5000000L, i, 0));
        }
        double freq = ctx.getGcFrequencyPerMinute(60);
        assertEquals(30.0, freq, 1.0); /* ~30 GC/min */
    }

    @Test
    public void testGcFrequencyEmpty() {
        assertEquals(0.0, ctx.getGcFrequencyPerMinute(60), 0.001);
    }

    @Test
    public void testAvgGcPause() {
        long now = System.currentTimeMillis();
        store.storeGcEvent(new GcEvent(now - 3000, 1, 10000000L, 1, 0)); /* 10ms */
        store.storeGcEvent(new GcEvent(now - 2000, 1, 20000000L, 2, 0)); /* 20ms */
        store.storeGcEvent(new GcEvent(now - 1000, 1, 30000000L, 3, 0)); /* 30ms */

        assertEquals(20.0, ctx.getAvgGcPauseMs(60), 0.1); /* avg = 20ms */
    }

    @Test
    public void testMaxGcPause() {
        long now = System.currentTimeMillis();
        store.storeGcEvent(new GcEvent(now - 3000, 1, 10000000L, 1, 0));
        store.storeGcEvent(new GcEvent(now - 2000, 3, 500000000L, 2, 1)); /* 500ms */
        store.storeGcEvent(new GcEvent(now - 1000, 1, 20000000L, 3, 1));

        assertEquals(500.0, ctx.getMaxGcPauseMs(60), 0.1);
    }

    @Test
    public void testGcThroughput() {
        long now = System.currentTimeMillis();
        /* 10 GC events, each 100ms, over 60s → ~1s pause / 60s = ~98.3% throughput */
        for (int i = 0; i < 10; i++) {
            store.storeGcEvent(new GcEvent(now - 60000 + i * 6000, 1,
                    100000000L, i, 0)); /* 100ms */
        }
        double throughput = ctx.getGcThroughputPercent(60);
        assertTrue("Throughput should be > 95%", throughput > 95.0);
        assertTrue("Throughput should be < 100%", throughput < 100.0);
    }

    @Test
    public void testHeapGrowthRate() {
        long now = System.currentTimeMillis();
        /* 100MB at start, 200MB at end, over 5 minutes → 1200 MB/h */
        store.storeMemorySnapshot(new MemorySnapshot(
                now - 300000, 100 * 1024 * 1024L, 1024 * 1024 * 1024L, 0, 0));
        store.storeMemorySnapshot(new MemorySnapshot(
                now, 200 * 1024 * 1024L, 1024 * 1024 * 1024L, 0, 0));

        double rate = ctx.getHeapGrowthRateMBPerHour(5);
        assertEquals(1200.0, rate, 10.0);
    }

    @Test
    public void testHeapGrowthRateStable() {
        long now = System.currentTimeMillis();
        /* Same memory at start and end */
        store.storeMemorySnapshot(new MemorySnapshot(
                now - 300000, 500 * 1024 * 1024L, 1024 * 1024 * 1024L, 0, 0));
        store.storeMemorySnapshot(new MemorySnapshot(
                now, 500 * 1024 * 1024L, 1024 * 1024 * 1024L, 0, 0));

        double rate = ctx.getHeapGrowthRateMBPerHour(5);
        assertEquals(0.0, rate, 1.0);
    }

    @Test
    public void testBlockedThreadPercent() {
        long now = System.currentTimeMillis();
        store.storeThreadInfo(new ThreadInfo(now, 1L, "t1", ThreadInfo.STATE_RUNNABLE, false));
        store.storeThreadInfo(new ThreadInfo(now, 2L, "t2", ThreadInfo.STATE_BLOCKED, false));
        store.storeThreadInfo(new ThreadInfo(now, 3L, "t3", ThreadInfo.STATE_BLOCKED, false));
        store.storeThreadInfo(new ThreadInfo(now, 4L, "t4", ThreadInfo.STATE_WAITING, false));

        assertEquals(2, ctx.getBlockedThreadCount());
        assertEquals(50.0, ctx.getBlockedThreadPercent(), 0.1);
    }

    @Test
    public void testActiveAlarms() {
        long now = System.currentTimeMillis();
        store.storeAlarm(new AlarmEvent(now, 2, 1, 250.0, 200.0, "GC pause warning"));
        assertEquals(1, ctx.getActiveAlarms().size());
    }
}
