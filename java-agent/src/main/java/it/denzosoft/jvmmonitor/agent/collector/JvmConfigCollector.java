package it.denzosoft.jvmmonitor.agent.collector;

import it.denzosoft.jvmmonitor.agent.module.Module;
import it.denzosoft.jvmmonitor.agent.transport.MessageQueue;
import it.denzosoft.jvmmonitor.agent.transport.ProtocolWriter;
import it.denzosoft.jvmmonitor.agent.util.AgentLogger;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.List;
import java.util.Properties;

/**
 * Sends the JVM startup configuration (vm arguments, java version, key system
 * properties) once when the module is activated. All data comes from JMX
 * RuntimeMXBean which is always available without any special flags.
 *
 * This is a core collector so the collector-side JvmFlagsRule can run
 * without user intervention.
 */
public class JvmConfigCollector implements Module {

    private static final int MSG_JVM_CONFIG = 0xD3;

    /* System properties we care about for flag analysis. Keep short to stay
     * within the 8KB protocol payload limit. */
    private static final String[] RELEVANT_PROPS = {
            "java.vm.name", "java.vm.vendor", "java.vm.version", "java.version",
            "java.runtime.version", "os.name", "os.arch", "os.version",
            "user.timezone", "file.encoding", "java.io.tmpdir"
    };

    private final MessageQueue queue;
    private volatile boolean active;

    public JvmConfigCollector(MessageQueue queue) {
        this.queue = queue;
    }

    public String getName() { return "jvmconfig"; }
    public int getMaxLevel() { return 1; }
    public boolean isCore() { return true; }
    public boolean isActive() { return active; }

    public void activate(int level) {
        if (active) return;
        active = true;
        sendConfig();
    }

    public void deactivate() {
        active = false;
    }

    private void sendConfig() {
        try {
            RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
            List<String> vmArgs = rt.getInputArguments();
            Properties sys = System.getProperties();

            String javaVersion = sys.getProperty("java.version", "");
            String vmName = sys.getProperty("java.vm.name", "");
            String vmVendor = sys.getProperty("java.vm.vendor", "");
            String javaHome = sys.getProperty("java.home", "");

            ProtocolWriter.PayloadBuilder pb = ProtocolWriter.payload();
            pb.writeU64(System.currentTimeMillis());
            pb.writeString(javaVersion);
            pb.writeString(vmName);
            pb.writeString(vmVendor);
            pb.writeString(javaHome);
            pb.writeU64(rt.getStartTime());
            pb.writeU64(rt.getUptime());
            pb.writeI32(Runtime.getRuntime().availableProcessors());

            int argCount = vmArgs != null ? vmArgs.size() : 0;
            /* Cap at 256 args and truncate each to 512 chars to avoid overflow */
            if (argCount > 256) argCount = 256;
            pb.writeU16(argCount);
            for (int i = 0; i < argCount; i++) {
                String a = vmArgs.get(i);
                if (a == null) a = "";
                if (a.length() > 512) a = a.substring(0, 512);
                pb.writeString(a);
            }

            pb.writeU16(RELEVANT_PROPS.length);
            for (int i = 0; i < RELEVANT_PROPS.length; i++) {
                String k = RELEVANT_PROPS[i];
                String v = sys.getProperty(k, "");
                if (v.length() > 256) v = v.substring(0, 256);
                pb.writeString(k);
                pb.writeString(v);
            }

            queue.enqueue(pb.buildMessage(MSG_JVM_CONFIG));
            AgentLogger.info("JvmConfig sent: " + argCount + " vm args");
        } catch (Exception e) {
            AgentLogger.error("JvmConfig send failed: " + e.getMessage());
        }
    }
}
