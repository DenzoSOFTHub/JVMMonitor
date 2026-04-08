package it.denzosoft.jvmmonitor.agent;

import it.denzosoft.jvmmonitor.agent.collector.*;
import it.denzosoft.jvmmonitor.agent.module.ModuleRegistry;
import it.denzosoft.jvmmonitor.agent.transport.TransportServer;
import it.denzosoft.jvmmonitor.agent.util.AgentLogger;

import java.lang.instrument.Instrumentation;

/**
 * Pure Java agent for JVMMonitor.
 * Provides portability across all JVM platforms (macOS, AIX, Solaris, etc.)
 * without native code. Uses java.lang.management MXBeans for metrics.
 *
 * Usage:
 *   java -javaagent:jvmmonitor-agent.jar=port=9090 -jar your-app.jar
 *
 * Or attach at runtime via the Attach API.
 */
public class JvmMonitorAgent {

    private static volatile TransportServer transport;
    private static volatile ModuleRegistry modules;
    private static volatile Instrumentation instrumentation;
    private static Thread shutdownHook;

    /** Called when loaded via -javaagent at startup. */
    public static void premain(String args, Instrumentation inst) {
        instrumentation = inst;
        start(args);
    }

    /** Called when attached at runtime via Attach API. */
    public static void agentmain(String args, Instrumentation inst) {
        instrumentation = inst;
        start(args);
    }

    private static synchronized void start(String args) {
        if (transport != null) {
            AgentLogger.info("Agent already running, ignoring duplicate attach");
            return;
        }
        /* Agent was previously shutdown — restart is allowed */

        AgentConfig config = new AgentConfig(args);
        AgentLogger.setLevel(config.getLogLevel());

        AgentLogger.info("JVMMonitor Java Agent v1.1.0 starting (port=" + config.getPort() + ")");

        /* Create transport server */
        transport = new TransportServer(config);

        /* Create module registry with all collectors */
        modules = new ModuleRegistry(transport.getMessageQueue());

        /* Core collectors (always on) */
        modules.register(new MemoryCollector(transport.getMessageQueue(), config));
        modules.register(new GcCollector(transport.getMessageQueue()));
        modules.register(new ThreadCollector(transport.getMessageQueue(), config));
        modules.register(new JvmConfigCollector(transport.getMessageQueue()));

        /* On-demand collectors */
        modules.register(new CpuUsageCollector(transport.getMessageQueue(), config));
        modules.register(new ThreadCpuCollector(transport.getMessageQueue(), config));
        modules.register(new OsMetricsCollector(transport.getMessageQueue(), config));
        modules.register(new ClassloaderCollector(transport.getMessageQueue(), config));
        modules.register(new JitCollector(transport.getMessageQueue(), config));
        modules.register(new CpuSampleCollector(transport.getMessageQueue(), config));
        if (instrumentation != null) {
            modules.register(new ClassHistoCollector(transport.getMessageQueue(), instrumentation));
            modules.register(new InstrumentationCollector(transport.getMessageQueue(), instrumentation));
            modules.register(new WebProbeCollector(transport.getMessageQueue(), instrumentation));
        }
        modules.register(new DeadlockDetector(transport.getMessageQueue()));

        /* Wire command handling */
        transport.setModuleRegistry(modules);

        /* Start transport (TCP server) */
        transport.start();

        /* Activate core collectors */
        modules.activateCore();

        AgentLogger.info("Agent started. Listening on port " + config.getPort());

        /* Shutdown hook — register only once to prevent accumulation on re-attach */
        if (shutdownHook == null) {
            shutdownHook = new Thread(new Runnable() {
                public void run() {
                    shutdown();
                }
            }, "jvmmonitor-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }
    }

    /**
     * Full shutdown: stop all modules, close transport, remove instrumentation.
     * The agent becomes dormant (zero overhead). Can be restarted via re-attach.
     */
    public static synchronized void shutdown() {
        if (transport == null) return;
        AgentLogger.info("Agent shutting down — stopping all modules");
        if (modules != null) modules.deactivateAll();
        if (transport != null) transport.stop();
        transport = null;
        modules = null;
        AgentLogger.info("Agent shutdown complete — dormant (zero overhead). Re-attach to restart.");
    }

    /** Check if agent is currently running. */
    public static boolean isRunning() {
        return transport != null;
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }
}
