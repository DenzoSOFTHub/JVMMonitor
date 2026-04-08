package it.denzosoft.jvmmonitor.agent.collector;

import it.denzosoft.jvmmonitor.agent.module.Module;
import it.denzosoft.jvmmonitor.agent.instrument.InstrumentationRecorder;
import it.denzosoft.jvmmonitor.agent.instrument.ParamSerializer;
import it.denzosoft.jvmmonitor.agent.instrument.ProbeTransformer;
import it.denzosoft.jvmmonitor.agent.transport.MessageQueue;
import it.denzosoft.jvmmonitor.agent.util.AgentLogger;

import java.lang.instrument.Instrumentation;
import java.util.*;

/**
 * Manages bytecode instrumentation via Javassist.
 * Activated by the collector's INSTR_CMD_CONFIGURE + INSTR_CMD_START commands.
 * Configures probes and application packages, then installs a ClassFileTransformer.
 */
public class InstrumentationCollector implements Module {

    private final MessageQueue queue;
    private final Instrumentation instrumentation;
    private volatile boolean active;
    private ProbeTransformer transformer;
    private volatile Set activeProbes = new HashSet();
    private volatile Set appPackages = new HashSet();

    public InstrumentationCollector(MessageQueue queue, Instrumentation instrumentation) {
        this.queue = queue;
        this.instrumentation = instrumentation;
        InstrumentationRecorder.init(queue);
    }

    public String getName() { return "instrumentation"; }
    public int getMaxLevel() { return 3; }
    public boolean isCore() { return false; }
    public boolean isActive() { return active; }

    /**
     * Configure which probes and packages to instrument.
     * Called before activate().
     */
    public void configure(String[] packages, String[] probes) {
        /* Build new sets and swap atomically to avoid concurrent read seeing empty state */
        Set newPackages = new HashSet();
        Set newProbes = new HashSet();
        for (int i = 0; i < packages.length; i++) {
            String pkg = packages[i].trim();
            if (pkg.length() > 0) newPackages.add(pkg);
        }
        for (int i = 0; i < probes.length; i++) {
            String probe = probes[i].trim().toLowerCase();
            if (probe.length() > 0) newProbes.add(probe);
        }
        appPackages = newPackages;
        activeProbes = newProbes;
        AgentLogger.info("Instrumentation configured: packages=" + appPackages + " probes=" + activeProbes);
    }

    /** Enable/disable parameter capture. */
    public void setCaptureParams(boolean enabled) {
        ParamSerializer.setEnabled(enabled);
        AgentLogger.info("Parameter capture " + (enabled ? "enabled" : "disabled"));
    }

    /** Set max length for serialized values. -1 = unlimited. */
    public void setMaxValueLength(int maxLen) {
        ParamSerializer.setMaxValueLength(maxLen);
        AgentLogger.info("Max value length: " + (maxLen < 0 ? "unlimited" : maxLen));
    }

    public void activate(int level) {
        if (active) return;
        if (instrumentation == null) {
            AgentLogger.error("Instrumentation not available (not loaded as java agent)");
            return;
        }
        if (activeProbes.isEmpty()) {
            AgentLogger.error("No probes configured. Call configure() first.");
            return;
        }

        active = true;
        InstrumentationRecorder.setRecording(true);

        /* Install ClassFileTransformer */
        transformer = new ProbeTransformer(activeProbes, appPackages);
        instrumentation.addTransformer(transformer, true);

        AgentLogger.info("Instrumentation activated with " + activeProbes.size() + " probes");

        /* Retransform already loaded classes that match probe targets */
        retransformLoadedClasses();
    }

    public void deactivate() {
        active = false;
        InstrumentationRecorder.setRecording(false);
        if (transformer != null && instrumentation != null) {
            instrumentation.removeTransformer(transformer);
            AgentLogger.info("Instrumentation deactivated (" + transformer.getTransformedCount() +
                    " classes were instrumented)");
            transformer = null;
        }
    }

    private void retransformLoadedClasses() {
        if (instrumentation == null) return;

        Class[] loaded = instrumentation.getAllLoadedClasses();
        Set targets = it.denzosoft.jvmmonitor.agent.instrument.ProbeRegistry
                .getTargetClassNames(activeProbes);

        int retransformed = 0;
        for (int i = 0; i < loaded.length; i++) {
            String internalName = loaded[i].getName().replace('.', '/');
            boolean isTarget = targets.contains(internalName);

            /* Check app packages */
            boolean isAppClass = false;
            if (!isTarget && activeProbes.contains("spring")) {
                String dotName = loaded[i].getName();
                Iterator it = appPackages.iterator();
                while (it.hasNext()) {
                    if (dotName.startsWith((String) it.next())) {
                        isAppClass = true;
                        break;
                    }
                }
            }

            if ((isTarget || isAppClass) && instrumentation.isModifiableClass(loaded[i])) {
                try {
                    instrumentation.retransformClasses(loaded[i]);
                    retransformed++;
                } catch (Exception e) {
                    AgentLogger.debug("Cannot retransform " + loaded[i].getName() + ": " + e.getMessage());
                }
            }
        }

        AgentLogger.info("Retransformed " + retransformed + " already-loaded classes");
    }
}
