package it.denzosoft.jvmmonitor.agent.command;

import it.denzosoft.jvmmonitor.agent.collector.InstrumentationCollector;
import it.denzosoft.jvmmonitor.agent.module.ModuleRegistry;
import it.denzosoft.jvmmonitor.agent.transport.MessageQueue;
import it.denzosoft.jvmmonitor.agent.util.AgentLogger;

import java.io.DataInputStream;
import java.io.ByteArrayInputStream;

/**
 * Decodes incoming commands from the collector and dispatches to modules.
 * Handles both module commands and instrumentation commands.
 */
public final class CommandDispatcher {

    /* Command message type */
    private static final int MSG_COMMAND = 0x03;

    /* Command subtypes */
    private static final int CMD_ENABLE_MODULE = 0x01;
    private static final int CMD_DISABLE_MODULE = 0x02;
    private static final int CMD_LIST_MODULES = 0x04;
    private static final int CMD_DETACH = 0xFF;

    /* Instrumentation command subtypes */
    private static final int INSTR_CMD_CONFIGURE = 0x10;
    private static final int INSTR_CMD_START = 0x11;
    private static final int INSTR_CMD_STOP = 0x12;

    /* Diagnostic command subtypes */
    private static final int DIAG_CMD_JMX_LIST_MBEANS = 0x33;
    private static final int DIAG_CMD_JMX_GET_ATTRS   = 0x34;

    private CommandDispatcher() {}

    public static void dispatch(int msgType, byte[] payload, ModuleRegistry modules, MessageQueue queue) {
        if (msgType != MSG_COMMAND) return;
        if (payload == null || payload.length < 1) return;

        int subType = payload[0] & 0xFF;

        try {
            int remaining = payload.length - 1;
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload, 1, remaining));

            switch (subType) {
                case CMD_ENABLE_MODULE: {
                    /* Minimum: 2(nameLen) + 1(name) + 1(level) = 4 bytes */
                    if (remaining < 4) {
                        AgentLogger.debug("CMD_ENABLE_MODULE: payload too short (" + remaining + " bytes)");
                        return;
                    }
                    int nameLen = in.readUnsignedShort();
                    if (nameLen <= 0 || nameLen > remaining - 3) {
                        AgentLogger.debug("CMD_ENABLE_MODULE: invalid nameLen " + nameLen);
                        return;
                    }
                    byte[] nameBytes = new byte[nameLen];
                    in.readFully(nameBytes);
                    String name = new String(nameBytes, "UTF-8");
                    int level = in.readUnsignedByte();
                    modules.enable(name, level);
                    break;
                }
                case CMD_DISABLE_MODULE: {
                    /* Minimum: 2(nameLen) + 1(name) = 3 bytes */
                    if (remaining < 3) {
                        AgentLogger.debug("CMD_DISABLE_MODULE: payload too short (" + remaining + " bytes)");
                        return;
                    }
                    int nameLen = in.readUnsignedShort();
                    if (nameLen <= 0 || nameLen > remaining - 2) {
                        AgentLogger.debug("CMD_DISABLE_MODULE: invalid nameLen " + nameLen);
                        return;
                    }
                    byte[] nameBytes = new byte[nameLen];
                    in.readFully(nameBytes);
                    String name = new String(nameBytes, "UTF-8");
                    modules.disable(name);
                    break;
                }
                case CMD_LIST_MODULES: {
                    modules.sendModuleList();
                    break;
                }
                case CMD_DETACH: {
                    AgentLogger.info("Received DETACH command — shutting down agent");
                    it.denzosoft.jvmmonitor.agent.JvmMonitorAgent.shutdown();
                    break;
                }
                case INSTR_CMD_CONFIGURE: {
                    /* Minimum: 2(pkgCount) + 2(probeCount) = 4 bytes */
                    if (remaining < 4) {
                        AgentLogger.debug("INSTR_CMD_CONFIGURE: payload too short (" + remaining + " bytes)");
                        return;
                    }
                    /* Parse packages and probes */
                    int pkgCount = in.readUnsignedShort();
                    String[] packages = new String[pkgCount];
                    for (int i = 0; i < pkgCount; i++) {
                        int len = in.readUnsignedShort();
                        byte[] b = new byte[len];
                        in.readFully(b);
                        packages[i] = new String(b, "UTF-8");
                    }
                    int probeCount = in.readUnsignedShort();
                    String[] probes = new String[probeCount];
                    for (int i = 0; i < probeCount; i++) {
                        int len = in.readUnsignedShort();
                        byte[] b = new byte[len];
                        in.readFully(b);
                        probes[i] = new String(b, "UTF-8");
                    }
                    /* Optional: capture params flag + max value length (v1.1.0) */
                    boolean captureParams = false;
                    int maxValueLength = 500;
                    try {
                        if (in.available() > 0) {
                            captureParams = in.readUnsignedByte() != 0;
                            maxValueLength = in.readInt();
                        }
                    } catch (Exception ignored) { /* old protocol version, no capture fields */ }

                    modules.configureInstrumentation(packages, probes);
                    modules.setInstrumentationCapture(captureParams, maxValueLength);
                    break;
                }
                case INSTR_CMD_START: {
                    modules.enable("instrumentation", 1);
                    break;
                }
                case INSTR_CMD_STOP: {
                    modules.disable("instrumentation");
                    break;
                }
                case DIAG_CMD_JMX_LIST_MBEANS: {
                    it.denzosoft.jvmmonitor.agent.jmx.JmxBrowser.handleListMBeans(queue);
                    break;
                }
                case DIAG_CMD_JMX_GET_ATTRS: {
                    /* Minimum: 2(nameLen) + 1(name) = 3 bytes */
                    if (remaining < 3) {
                        AgentLogger.debug("DIAG_CMD_JMX_GET_ATTRS: payload too short (" + remaining + " bytes)");
                        return;
                    }
                    int nameLen = in.readUnsignedShort();
                    byte[] nameBytes = new byte[nameLen];
                    in.readFully(nameBytes);
                    String mbeanName = new String(nameBytes, "UTF-8");
                    it.denzosoft.jvmmonitor.agent.jmx.JmxBrowser.handleGetAttrs(mbeanName, queue);
                    break;
                }
                default:
                    AgentLogger.debug("Unknown command subtype: 0x" + Integer.toHexString(subType));
                    break;
            }
        } catch (Exception e) {
            AgentLogger.error("Command dispatch error: " + e.getMessage());
        }
    }
}
