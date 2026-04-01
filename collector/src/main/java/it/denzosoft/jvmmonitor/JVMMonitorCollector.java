package it.denzosoft.jvmmonitor;

import it.denzosoft.jvmmonitor.analysis.AlarmThresholds;
import it.denzosoft.jvmmonitor.analysis.AnalysisContext;
import it.denzosoft.jvmmonitor.analysis.DiagnosisEngine;
import it.denzosoft.jvmmonitor.net.AgentConnection;
import it.denzosoft.jvmmonitor.storage.EventStore;
import it.denzosoft.jvmmonitor.storage.InMemoryEventStore;

import java.io.IOException;

public class JVMMonitorCollector {

    private final EventStore store;
    private final AlarmThresholds thresholds;
    private final AnalysisContext analysisContext;
    private final DiagnosisEngine diagnosisEngine;
    private AgentConnection connection;

    public JVMMonitorCollector() {
        this.store = new InMemoryEventStore();
        this.thresholds = new AlarmThresholds();
        this.analysisContext = new AnalysisContext(store, thresholds);
        this.diagnosisEngine = new DiagnosisEngine(analysisContext);
    }

    /**
     * Connect to an agent already listening on host:port.
     */
    public void connect(String host, int port) throws IOException {
        if (connection != null) {
            connection.disconnect();
        }
        connection = new AgentConnection(host, port, store);
        connection.connect();
    }

    public void disconnect() {
        if (connection != null) {
            connection.disconnect();
            connection = null;
        }
    }

    public EventStore getStore() { return store; }
    public AgentConnection getConnection() { return connection; }
    public AnalysisContext getAnalysisContext() { return analysisContext; }
    public DiagnosisEngine getDiagnosisEngine() { return diagnosisEngine; }
    public AlarmThresholds getThresholds() { return thresholds; }
}
