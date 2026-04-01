package it.denzosoft.jvmmonitor.analysis.rules;

import it.denzosoft.jvmmonitor.analysis.AlarmThresholds;
import it.denzosoft.jvmmonitor.analysis.AnalysisContext;
import it.denzosoft.jvmmonitor.model.ClassloaderStats;
import it.denzosoft.jvmmonitor.model.Diagnosis;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ClassloaderLeakRule implements DiagnosticRule {

    public String getName() {
        return "Classloader Leak";
    }

    public List<Diagnosis> evaluate(AnalysisContext ctx) {
        ClassloaderStats stats = ctx.getLatestClassloaderStats();
        if (stats == null || stats.getLoaderCount() < 3) {
            return Collections.emptyList();
        }

        int loaderCount = stats.getLoaderCount();
        int totalClasses = stats.getTotalClassCount();

        AlarmThresholds t = ctx.getThresholds();
        if (loaderCount < t.classloaderWarn) {
            return Collections.emptyList();
        }

        int severity = loaderCount > t.classloaderCrit ? 2 : 1;

        List<Diagnosis> results = new ArrayList<Diagnosis>();
        results.add(Diagnosis.builder()
            .timestamp(System.currentTimeMillis())
            .category("Classloader Leak")
            .severity(severity)
            .summary(String.format(
                "%d classloaders with %d total classes — possible classloader leak (e.g. hot-deploy / plugin reloads)",
                loaderCount, totalClasses))
            .evidence(String.format(
                "%d classloaders detected, %d total loaded classes",
                loaderCount, totalClasses))
            .fix("Ensure old classloaders are garbage collected after redeploy; check for static references holding old loaders")
            .suggestedAction("enable classloaders 1")
            .build());

        return results;
    }
}
