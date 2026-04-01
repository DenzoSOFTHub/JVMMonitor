package it.denzosoft.jvmmonitor.storage;

import it.denzosoft.jvmmonitor.model.*;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public class InMemoryEventStoreNewTypesTest {

    private InMemoryEventStore store;

    @Before
    public void setUp() {
        store = new InMemoryEventStore(100);
    }

    @Test
    public void testStoreAndRetrieveExceptions() {
        long now = System.currentTimeMillis();
        store.storeException(new ExceptionEvent(now, 10, 8, 2,
                "Ljava/lang/NullPointerException;", "com.example.Foo", "bar",
                42L, true, "com.example.Handler", "handle"));

        List<ExceptionEvent> events = store.getExceptions(now - 1000, now + 1000);
        assertEquals(1, events.size());
        assertEquals("java.lang.NullPointerException", events.get(0).getDisplayName());
        assertTrue(events.get(0).isCaught());
        assertEquals(10, events.get(0).getTotalThrown());
    }

    @Test
    public void testLatestException() {
        long now = System.currentTimeMillis();
        store.storeException(new ExceptionEvent(now - 1000, 1, 1, 0,
                "Ljava/io/IOException;", "A", "b", 0, false, "", ""));
        store.storeException(new ExceptionEvent(now, 2, 1, 0,
                "Ljava/lang/RuntimeException;", "C", "d", 0, true, "E", "f"));

        ExceptionEvent latest = store.getLatestException();
        assertNotNull(latest);
        assertEquals("java.lang.RuntimeException", latest.getDisplayName());
    }

    @Test
    public void testExceptionCount() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            store.storeException(new ExceptionEvent(now + i, i, 0, 0,
                    "Exc" + i, "", "", 0, false, "", ""));
        }
        assertEquals(5, store.getExceptionCount());
    }

    @Test
    public void testStoreOsMetrics() {
        long now = System.currentTimeMillis();
        store.storeOsMetrics(new OsMetrics(now, 256, 512 * 1024 * 1024L,
                2048L * 1024 * 1024, 1000, 50, 42, 3, 7, 120));

        OsMetrics os = store.getLatestOsMetrics();
        assertNotNull(os);
        assertEquals(256, os.getOpenFileDescriptors());
        assertEquals(512.0, os.getRssMB(), 0.1);
        assertEquals(42, os.getTcpEstablished());
        assertEquals(120, os.getOsThreadCount());
    }

    @Test
    public void testStoreJitEvents() {
        long now = System.currentTimeMillis();
        store.storeJitEvent(new JitEvent(now, JitEvent.COMPILED,
                "com.example.Foo", "bar", 1024, 0xDEADBEEFL, 42));

        List<JitEvent> events = store.getJitEvents(now - 1000, now + 1000);
        assertEquals(1, events.size());
        assertEquals("com.example.Foo", events.get(0).getClassName());
        assertEquals("COMPILED", events.get(0).getTypeName());
        assertEquals(42, events.get(0).getTotalCompiled());
    }

    @Test
    public void testJitEventCount() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            store.storeJitEvent(new JitEvent(now + i, JitEvent.COMPILED,
                    "C" + i, "m" + i, 100, 0, i));
        }
        assertEquals(3, store.getJitEventCount());
    }

    @Test
    public void testStoreClassHistogram() {
        ClassHistogram.Entry[] entries = new ClassHistogram.Entry[] {
            new ClassHistogram.Entry("[B", 50000, 10 * 1024 * 1024L),
            new ClassHistogram.Entry("Ljava/lang/String;", 30000, 5 * 1024 * 1024L),
        };
        store.storeClassHistogram(new ClassHistogram(System.currentTimeMillis(), 150000000L, entries));

        ClassHistogram histo = store.getLatestClassHistogram();
        assertNotNull(histo);
        assertEquals(2, histo.getEntryCount());
        assertEquals(150.0, histo.getElapsedMs(), 0.1);
        assertEquals(50000, histo.getEntries()[0].getInstanceCount());
    }

    @Test
    public void testStoreSafepoint() {
        store.storeSafepoint(new SafepointEvent(System.currentTimeMillis(), 1000, 5000, 500));

        SafepointEvent sp = store.getLatestSafepoint();
        assertNotNull(sp);
        assertTrue(sp.isAvailable());
        assertEquals(1000, sp.getSafepointCount());
        assertEquals(5000, sp.getTotalTimeMs());
        assertEquals(500, sp.getSyncTimeMs());
    }

    @Test
    public void testSafepointUnavailable() {
        store.storeSafepoint(new SafepointEvent(System.currentTimeMillis(), -1, -1, -1));

        SafepointEvent sp = store.getLatestSafepoint();
        assertNotNull(sp);
        assertFalse(sp.isAvailable());
    }

    @Test
    public void testStoreNativeMemory() {
        store.storeNativeMemory(new NativeMemoryStats(System.currentTimeMillis(),
                true, "Total: reserved=1234KB, committed=567KB"));

        NativeMemoryStats nms = store.getLatestNativeMemory();
        assertNotNull(nms);
        assertTrue(nms.isAvailable());
        assertTrue(nms.getRawOutput().contains("reserved=1234KB"));
    }

    @Test
    public void testStoreNativeMemoryUnavailable() {
        store.storeNativeMemory(new NativeMemoryStats(System.currentTimeMillis(),
                false, "NMT not available"));

        NativeMemoryStats nms = store.getLatestNativeMemory();
        assertNotNull(nms);
        assertFalse(nms.isAvailable());
    }

    @Test
    public void testStoreGcDetail() {
        GcDetail.CollectorInfo[] collectors = new GcDetail.CollectorInfo[] {
            new GcDetail.CollectorInfo("PS Scavenge", 100, 5000, new String[] {"PS Eden Space", "PS Survivor Space"}),
            new GcDetail.CollectorInfo("PS MarkSweep", 5, 2000, new String[] {"PS Old Gen"}),
        };
        store.storeGcDetail(new GcDetail(System.currentTimeMillis(), collectors));

        GcDetail detail = store.getLatestGcDetail();
        assertNotNull(detail);
        assertEquals(2, detail.getCollectorCount());
        assertEquals("PS Scavenge", detail.getCollectors()[0].getName());
        assertEquals(100, detail.getCollectors()[0].getCollectionCount());
        assertEquals(2, detail.getCollectors()[0].getMemoryPools().length);
    }

    @Test
    public void testStoreClassloaderStats() {
        ClassloaderStats.LoaderInfo[] loaders = new ClassloaderStats.LoaderInfo[] {
            new ClassloaderStats.LoaderInfo("bootstrap", 1500),
            new ClassloaderStats.LoaderInfo("Ljava/net/URLClassLoader;", 200),
        };
        store.storeClassloaderStats(new ClassloaderStats(System.currentTimeMillis(), loaders));

        ClassloaderStats stats = store.getLatestClassloaderStats();
        assertNotNull(stats);
        assertEquals(2, stats.getLoaderCount());
        assertEquals(1700, stats.getTotalClassCount());
        assertEquals("bootstrap", stats.getLoaders()[0].getLoaderClass());
    }

    @Test
    public void testStoreStringTableStats() {
        store.storeStringTableStats(new StringTableStats(System.currentTimeMillis(),
                true, "StringTable statistics:\n  Number of buckets: 60013"));

        StringTableStats sts = store.getLatestStringTableStats();
        assertNotNull(sts);
        assertTrue(sts.isAvailable());
        assertTrue(sts.getRawOutput().contains("60013"));
    }

    @Test
    public void testNewStoreMethodsReturnNullWhenEmpty() {
        assertNull(store.getLatestException());
        assertNull(store.getLatestOsMetrics());
        assertNull(store.getLatestClassHistogram());
        assertNull(store.getLatestSafepoint());
        assertNull(store.getLatestNativeMemory());
        assertNull(store.getLatestGcDetail());
        assertNull(store.getLatestClassloaderStats());
        assertNull(store.getLatestStringTableStats());
        assertEquals(0, store.getExceptionCount());
        assertEquals(0, store.getJitEventCount());
    }
}
