package it.denzosoft.jvmmonitor.analysis;

import it.denzosoft.jvmmonitor.analysis.rules.*;
import it.denzosoft.jvmmonitor.model.Diagnosis;
import java.util.ArrayList;
import java.util.List;

public class DiagnosisEngine {

    private final List<DiagnosticRule> rules = new ArrayList<DiagnosticRule>();
    private final AnalysisContext ctx;
    private List<Diagnosis> lastDiagnoses = new ArrayList<Diagnosis>();

    public DiagnosisEngine(AnalysisContext ctx) {
        this.ctx = ctx;
        rules.add(new GcPressureRule());
        rules.add(new HeapGrowthRule());
        rules.add(new ThreadContentionRule());
        rules.add(new ExceptionRateRule());
        rules.add(new ClassloaderLeakRule());
        rules.add(new SafepointPauseRule());
        rules.add(new CpuSaturationRule());
        rules.add(new ConnectionLeakRule());
        rules.add(new ResponseDegradationRule());
    }

    public void addRule(DiagnosticRule rule) {
        rules.add(rule);
    }

    public List<Diagnosis> runDiagnostics() {
        List<Diagnosis> all = new ArrayList<Diagnosis>();
        for (int i = 0; i < rules.size(); i++) {
            DiagnosticRule rule = rules.get(i);
            try {
                List<Diagnosis> results = rule.evaluate(ctx);
                if (results != null) {
                    all.addAll(results);
                }
            } catch (Exception e) {
                /* Rule failed — skip silently */
            }
        }
        lastDiagnoses = all;
        return all;
    }

    public List<Diagnosis> getLastDiagnoses() {
        return lastDiagnoses;
    }
}
