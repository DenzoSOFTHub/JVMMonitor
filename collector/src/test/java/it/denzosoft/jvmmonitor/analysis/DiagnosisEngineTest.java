package it.denzosoft.jvmmonitor.analysis;

import it.denzosoft.jvmmonitor.model.*;
import it.denzosoft.jvmmonitor.storage.InMemoryEventStore;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public class DiagnosisEngineTest {

    private InMemoryEventStore store;
    private AnalysisContext ctx;
    private DiagnosisEngine engine;

    @Before
    public void setUp() {
        store = new InMemoryEventStore();
        ctx = new AnalysisContext(store);
        engine = new DiagnosisEngine(ctx);
    }

    @Test
    public void testNoDiagnosesOnEmptyStore() {
        List<Diagnosis> results = engine.runDiagnostics();
        assertTrue(results.isEmpty());
    }

    @Test
    public void testGcPressureDetected() {
        long now = System.currentTimeMillis();
        /* Simulate heavy GC: 60 GC events in 60 seconds, each 200ms → 12s pause / 60s = 80% throughput */
        for (int i = 0; i < 60; i++) {
            store.storeGcEvent(new GcEvent(now - 60000 + i * 1000, 1,
                    200000000L, i, 0)); /* 200ms each */
        }

        List<Diagnosis> results = engine.runDiagnostics();
        boolean foundGcPressure = false;
        for (int i = 0; i < results.size(); i++) {
            if ("GC Pressure".equals(results.get(i).getCategory())) {
                foundGcPressure = true;
                break;
            }
        }
        assertTrue("Should detect GC pressure", foundGcPressure);
    }

    @Test
    public void testLongGcPauseDetected() {
        long now = System.currentTimeMillis();
        /* One very long GC pause */
        store.storeGcEvent(new GcEvent(now - 5000, 3,
                2000000000L, 1, 1)); /* 2 seconds! */

        List<Diagnosis> results = engine.runDiagnostics();
        boolean foundLongPause = false;
        for (int i = 0; i < results.size(); i++) {
            if ("Long GC Pause".equals(results.get(i).getCategory())) {
                foundLongPause = true;
                assertEquals(2, results.get(i).getSeverity()); /* CRITICAL */
                break;
            }
        }
        assertTrue("Should detect long GC pause", foundLongPause);
    }

    @Test
    public void testHeapGrowthDetected() {
        long now = System.currentTimeMillis();
        long maxHeap = 4L * 1024 * 1024 * 1024; /* 4GB */

        /* Simulate linear heap growth: 100MB → 600MB in 5 minutes */
        for (int i = 0; i < 300; i++) {
            long heapUsed = 100L * 1024 * 1024 + (long) i * 1700000L; /* ~500MB growth over 5min */
            store.storeMemorySnapshot(new MemorySnapshot(
                    now - 300000 + i * 1000, heapUsed, maxHeap, 0, 0));
        }

        /* Simulate growing live set via Full GC events (heap after GC growing) */
        for (int i = 0; i < 5; i++) {
            long heapBefore = (300 + i * 50) * 1024L * 1024L;
            long heapAfter = (100 + i * 40) * 1024L * 1024L;  /* growing after each Full GC */
            store.storeGcEvent(new GcEvent(now - 600000 + i * 120000L, GcEvent.TYPE_FULL,
                    200000000L, i + 1, i + 1, heapBefore, heapAfter, 0, 0, 0, 0, "Ergonomics", 0));
        }

        List<Diagnosis> results = engine.runDiagnostics();
        boolean foundHeapGrowth = false;
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).getCategory().contains("Memory Leak")
                || results.get(i).getCategory().contains("Heap Growth")
                || results.get(i).getCategory().contains("Old Gen")) {
                foundHeapGrowth = true;
                break;
            }
        }
        assertTrue("Should detect heap growth", foundHeapGrowth);
    }

    @Test
    public void testThreadContentionDetected() {
        long now = System.currentTimeMillis();
        /* 10 threads, 6 of them BLOCKED (60%) */
        for (int i = 0; i < 10; i++) {
            int state = i < 6 ? ThreadInfo.STATE_BLOCKED : ThreadInfo.STATE_RUNNABLE;
            store.storeThreadInfo(new ThreadInfo(now, (long) i,
                    "worker-" + i, state, false));
        }

        List<Diagnosis> results = engine.runDiagnostics();
        boolean foundContention = false;
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).getCategory().contains("Lock Contention")
                || results.get(i).getCategory().contains("Thread Contention")) {
                foundContention = true;
                assertEquals(2, results.get(i).getSeverity()); /* CRITICAL for > 50% */
                break;
            }
        }
        assertTrue("Should detect thread contention", foundContention);
    }

    @Test
    public void testNoFalsePositivesOnHealthySystem() {
        long now = System.currentTimeMillis();

        /* Light GC: 2 events, short pause */
        store.storeGcEvent(new GcEvent(now - 30000, 1, 5000000L, 1, 0));
        store.storeGcEvent(new GcEvent(now - 15000, 1, 3000000L, 2, 0));

        /* Stable memory */
        for (int i = 0; i < 10; i++) {
            store.storeMemorySnapshot(new MemorySnapshot(
                    now - 300000 + i * 30000, 200 * 1024 * 1024L, 1024 * 1024 * 1024L, 0, 0));
        }

        /* All threads RUNNABLE */
        for (int i = 0; i < 10; i++) {
            store.storeThreadInfo(new ThreadInfo(now, (long) i,
                    "worker-" + i, ThreadInfo.STATE_RUNNABLE, false));
        }

        List<Diagnosis> results = engine.runDiagnostics();
        assertTrue("Healthy system should have no diagnoses", results.isEmpty());
    }

    @Test
    public void testDiagnosisSuggestsAction() {
        long now = System.currentTimeMillis();
        store.storeGcEvent(new GcEvent(now - 1000, 3, 2000000000L, 1, 1));

        List<Diagnosis> results = engine.runDiagnostics();
        assertFalse(results.isEmpty());
        for (int i = 0; i < results.size(); i++) {
            Diagnosis d = results.get(i);
            if (d.getSuggestedAction() != null) {
                assertTrue("Should suggest enabling a module",
                        d.getSuggestedAction().startsWith("enable "));
                return;
            }
        }
    }

    @Test
    public void testDiagnosisToStringContainsAllFields() {
        Diagnosis d = Diagnosis.builder()
                .timestamp(System.currentTimeMillis())
                .category("Test Category")
                .severity(2)
                .location("com.app.Foo.bar():42")
                .summary("Something bad happened")
                .evidence("Lots of evidence")
                .fix("Do this to fix it")
                .estimatedImpact("50% improvement")
                .suggestedAction("enable alloc 2")
                .build();

        String s = d.toString();
        assertTrue(s.contains("CRITICAL"));
        assertTrue(s.contains("Test Category"));
        assertTrue(s.contains("com.app.Foo.bar():42"));
        assertTrue(s.contains("Something bad happened"));
        assertTrue(s.contains("Lots of evidence"));
        assertTrue(s.contains("Do this to fix it"));
        assertTrue(s.contains("50% improvement"));
        assertTrue(s.contains("enable alloc 2"));
    }
}
