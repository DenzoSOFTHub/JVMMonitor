package it.denzosoft.jvmmonitor.agent.module;

import it.denzosoft.jvmmonitor.agent.transport.MessageQueue;
import it.denzosoft.jvmmonitor.agent.transport.ProtocolWriter;
import it.denzosoft.jvmmonitor.agent.util.AgentLogger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Manages all collector modules. Thread-safe: all access synchronized. */
public class ModuleRegistry {

    private final Map<String, Module> modules =
            Collections.synchronizedMap(new LinkedHashMap<String, Module>());
    private final MessageQueue queue;

    public ModuleRegistry(MessageQueue queue) {
        this.queue = queue;
    }

    public void register(Module module) {
        modules.put(module.getName(), module);
    }

    public void activateCore() {
        for (Module m : modules.values()) {
            if (m.isCore()) {
                m.activate(1);
                AgentLogger.info("Core module activated: " + m.getName());
            }
        }
    }

    public void deactivateAll() {
        for (Module m : modules.values()) {
            if (m.isActive()) m.deactivate();
        }
    }

    public void enable(String name, int level) {
        Module m = modules.get(name);
        if (m != null) {
            if (level > m.getMaxLevel()) level = m.getMaxLevel();
            m.activate(level);
            AgentLogger.info("Module enabled: " + name + " level=" + level);
            sendModuleEvent(name, 0, level, m.getMaxLevel());
        } else {
            AgentLogger.error("Unknown module: " + name);
        }
    }

    public void disable(String name) {
        Module m = modules.get(name);
        if (m != null) {
            int oldLevel = m.isActive() ? 1 : 0;
            m.deactivate();
            AgentLogger.info("Module disabled: " + name);
            sendModuleEvent(name, oldLevel, 0, m.getMaxLevel());
        }
    }

    /** Build and enqueue MSG_COMMAND_RESP with module list. */
    public void sendModuleList() {
        try {
            ProtocolWriter.PayloadBuilder pb = ProtocolWriter.payload();
            pb.writeU64(System.currentTimeMillis());
            synchronized (modules) {
                pb.writeU16(modules.size());
                for (Map.Entry<String, Module> entry : modules.entrySet()) {
                    Module m = entry.getValue();
                    pb.writeString(m.getName());
                    pb.writeU8(m.isActive() ? 1 : 0);
                    pb.writeU8(m.getMaxLevel());
                }
            }
            queue.enqueue(pb.buildMessage(0x04)); /* MSG_COMMAND_RESP */
        } catch (IOException e) {
            AgentLogger.error("Failed to build module list: " + e.getMessage());
        }
    }

    /** Configure instrumentation probes. */
    public void configureInstrumentation(String[] packages, String[] probes) {
        Module m = modules.get("instrumentation");
        if (m != null && m instanceof it.denzosoft.jvmmonitor.agent.collector.InstrumentationCollector) {
            ((it.denzosoft.jvmmonitor.agent.collector.InstrumentationCollector) m).configure(packages, probes);
        } else {
            AgentLogger.error("Instrumentation module not available (no Instrumentation API)");
        }
    }

    /** Configure param capture options for instrumentation. */
    public void setInstrumentationCapture(boolean captureParams, int maxValueLength) {
        Module m = modules.get("instrumentation");
        if (m != null && m instanceof it.denzosoft.jvmmonitor.agent.collector.InstrumentationCollector) {
            it.denzosoft.jvmmonitor.agent.collector.InstrumentationCollector ic =
                    (it.denzosoft.jvmmonitor.agent.collector.InstrumentationCollector) m;
            ic.setCaptureParams(captureParams);
            ic.setMaxValueLength(maxValueLength);
        }
    }

    private void sendModuleEvent(String name, int oldLevel, int newLevel, int maxLevel) {
        try {
            byte[] msg = ProtocolWriter.payload()
                    .writeU64(System.currentTimeMillis())
                    .writeString(name)
                    .writeU8(oldLevel)
                    .writeU8(newLevel)
                    .writeU8(maxLevel)
                    .buildMessage(0x70); /* MSG_MODULE_EVENT */
            queue.enqueue(msg);
        } catch (IOException e) {
            AgentLogger.error("Failed to send module event: " + e.getMessage());
        }
    }
}
