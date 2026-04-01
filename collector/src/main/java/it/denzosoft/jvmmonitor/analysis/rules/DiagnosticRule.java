package it.denzosoft.jvmmonitor.analysis.rules;

import it.denzosoft.jvmmonitor.analysis.AnalysisContext;
import it.denzosoft.jvmmonitor.model.Diagnosis;
import java.util.List;

public interface DiagnosticRule {

    String getName();

    List<Diagnosis> evaluate(AnalysisContext ctx);
}
