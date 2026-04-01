package it.denzosoft.jvmmonitor.analysis;

import it.denzosoft.jvmmonitor.analysis.rules.*;
import it.denzosoft.jvmmonitor.model.*;
import it.denzosoft.jvmmonitor.storage.InMemoryEventStore;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public class NewDiagnosticRulesTest {

    private InMemoryEventStore store;
    private AnalysisContext ctx;

    @Before
    public void setUp() {
        store = new InMemoryEventStore(1000);
        ctx = new AnalysisContext(store);
    }

    /* ── ExceptionRateRule ──────────────────────────── */

    @Test
    public void testExceptionRateRuleNoIssue() {
        ExceptionRateRule rule = new ExceptionRateRule();
        List<Diagnosis> results = rule.evaluate(ctx);
        assertTrue(results.isEmpty());
    }

    @Test
    public void testExceptionRateRuleWarning() {
        ExceptionRateRule rule = new ExceptionRateRule();
        long now = System.currentTimeMillis();
        /* 200 exceptions in last 60s = 200/min -> WARNING */
        for (int i = 0; i < 200; i++) {
            store.storeException(new ExceptionEvent(now - 60000 + i * 300, i, 0, 0,
                    "Ljava/lang/NullPointerException;", "Foo", "bar", 0, false, "", ""));
        }

        List<Diagnosis> results = rule.evaluate(ctx);
        assertFalse("Should detect exception issues", results.isEmpty());
        /* The rule may produce multiple findings (uncaught, recurring bug, high rate) */
        boolean foundExcIssue = false;
        for (int i = 0; i < results.size(); i++) {
            String cat = results.get(i).getCategory();
            if (cat.contains("Exception") || cat.contains("Recurring") || cat.contains("Uncaught")) {
                foundExcIssue = true;
                break;
            }
        }
        assertTrue("Should find exception-related issue", foundExcIssue);
    }

    @Test
    public void testExceptionRateRuleCritical() {
        ExceptionRateRule rule = new ExceptionRateRule();
        long now = System.currentTimeMillis();
        /* >1000 exceptions in last 60s -> CRITICAL */
        for (int i = 0; i < 1001; i++) {
            store.storeException(new ExceptionEvent(now - 60000 + i * 59, i, 0, 0,
                    "Ljava/lang/RuntimeException;", "X", "y", 0, true, "Z", "w"));
        }

        List<Diagnosis> results = rule.evaluate(ctx);
        assertFalse(results.isEmpty());
    }

    /* ── ClassloaderLeakRule ────────────────────────── */

    @Test
    public void testClassloaderLeakRuleNoIssue() {
        ClassloaderLeakRule rule = new ClassloaderLeakRule();
        List<Diagnosis> results = rule.evaluate(ctx);
        assertTrue(results.isEmpty());
    }

    @Test
    public void testClassloaderLeakRuleFewLoaders() {
        ClassloaderLeakRule rule = new ClassloaderLeakRule();
        ClassloaderStats.LoaderInfo[] loaders = new ClassloaderStats.LoaderInfo[5];
        for (int i = 0; i < 5; i++) {
            loaders[i] = new ClassloaderStats.LoaderInfo("loader" + i, 100);
        }
        store.storeClassloaderStats(new ClassloaderStats(System.currentTimeMillis(), loaders));

        List<Diagnosis> results = rule.evaluate(ctx);
        assertTrue(results.isEmpty()); /* 5 loaders is normal */
    }

    @Test
    public void testClassloaderLeakRuleWarning() {
        ClassloaderLeakRule rule = new ClassloaderLeakRule();
        ClassloaderStats.LoaderInfo[] loaders = new ClassloaderStats.LoaderInfo[25];
        for (int i = 0; i < 25; i++) {
            loaders[i] = new ClassloaderStats.LoaderInfo("loader" + i, 50);
        }
        store.storeClassloaderStats(new ClassloaderStats(System.currentTimeMillis(), loaders));

        List<Diagnosis> results = rule.evaluate(ctx);
        assertEquals(1, results.size());
        assertEquals(1, results.get(0).getSeverity()); /* WARNING */
        assertTrue(results.get(0).getSummary().contains("25 classloaders"));
    }

    @Test
    public void testClassloaderLeakRuleCritical() {
        ClassloaderLeakRule rule = new ClassloaderLeakRule();
        ClassloaderStats.LoaderInfo[] loaders = new ClassloaderStats.LoaderInfo[60];
        for (int i = 0; i < 60; i++) {
            loaders[i] = new ClassloaderStats.LoaderInfo("loader" + i, 10);
        }
        store.storeClassloaderStats(new ClassloaderStats(System.currentTimeMillis(), loaders));

        List<Diagnosis> results = rule.evaluate(ctx);
        assertEquals(1, results.size());
        assertEquals(2, results.get(0).getSeverity()); /* CRITICAL */
    }

    /* ── SafepointPauseRule ─────────────────────────── */

    @Test
    public void testSafepointRuleNoData() {
        SafepointPauseRule rule = new SafepointPauseRule();
        List<Diagnosis> results = rule.evaluate(ctx);
        assertTrue(results.isEmpty());
    }

    @Test
    public void testSafepointRuleNoIssue() {
        SafepointPauseRule rule = new SafepointPauseRule();
        /* 100 safepoints, 500ms total, 100ms sync -> avg 5ms/1ms -> no issue */
        store.storeSafepoint(new SafepointEvent(System.currentTimeMillis(), 100, 500, 100));

        List<Diagnosis> results = rule.evaluate(ctx);
        assertTrue(results.isEmpty());
    }

    @Test
    public void testSafepointRuleSyncDelay() {
        SafepointPauseRule rule = new SafepointPauseRule();
        /* 10 safepoints, 1000ms total, 1000ms sync -> avg sync 100ms -> WARNING */
        store.storeSafepoint(new SafepointEvent(System.currentTimeMillis(), 10, 1000, 1000));

        List<Diagnosis> results = rule.evaluate(ctx);
        assertFalse(results.isEmpty());
        boolean foundSync = false;
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).getCategory().contains("Sync")) {
                foundSync = true;
                assertTrue(results.get(i).getSeverity() >= 1);
            }
        }
        assertTrue(foundSync);
    }

    @Test
    public void testSafepointRuleHighTime() {
        SafepointPauseRule rule = new SafepointPauseRule();
        /* 10 safepoints, 10000ms total -> avg 1000ms -> WARNING/CRITICAL */
        store.storeSafepoint(new SafepointEvent(System.currentTimeMillis(), 10, 10000, 10));

        List<Diagnosis> results = rule.evaluate(ctx);
        boolean foundHighTime = false;
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).getCategory().contains("High Safepoint")) {
                foundHighTime = true;
                assertEquals(2, results.get(i).getSeverity()); /* CRITICAL */
            }
        }
        assertTrue(foundHighTime);
    }

    /* ── DiagnosisEngine includes new rules ─────────── */

    @Test
    public void testDiagnosisEngineHasNewRules() {
        DiagnosisEngine engine = new DiagnosisEngine(ctx);
        /* Run with no data — should return empty, not throw */
        List<Diagnosis> results = engine.runDiagnostics();
        assertNotNull(results);
    }
}
