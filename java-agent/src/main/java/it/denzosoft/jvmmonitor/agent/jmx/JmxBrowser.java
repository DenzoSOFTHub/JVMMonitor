package it.denzosoft.jvmmonitor.agent.jmx;

import it.denzosoft.jvmmonitor.agent.transport.MessageQueue;
import it.denzosoft.jvmmonitor.agent.transport.ProtocolWriter;
import it.denzosoft.jvmmonitor.agent.util.AgentLogger;

import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;
import java.lang.management.ManagementFactory;
import java.util.Set;

/**
 * JMX browser handler: replies to DIAG_CMD_JMX_LIST_MBEANS (0x33) and
 * DIAG_CMD_JMX_GET_ATTRS (0x34) using the platform MBeanServer.
 *
 * Responses use MSG_JMX_DATA (0xB0) with a leading subtype byte:
 *   0x01 = MBEAN_LIST
 *   0x02 = MBEAN_ATTRS
 *
 * CompositeData values are decomposed into multiple (name.subkey, value)
 * entries so the collector tree can show them as expandable groups.
 */
public final class JmxBrowser {

    private static final int MSG_JMX_DATA = 0xB0;
    private static final int SUBTYPE_MBEAN_LIST = 0x01;
    private static final int SUBTYPE_MBEAN_ATTRS = 0x02;

    /* Keep well under the 8KB protocol payload cap to leave room for headers */
    private static final int MAX_PAYLOAD_BUDGET = 7800;
    private static final int MAX_STRING_LEN = 500;

    private JmxBrowser() {}

    /** Respond to DIAG_CMD_JMX_LIST_MBEANS: send the list of all MBean names. */
    public static void handleListMBeans(MessageQueue queue) {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            Set<ObjectName> names = server.queryNames(null, null);

            ProtocolWriter.PayloadBuilder pb = ProtocolWriter.payload();
            pb.writeU8(SUBTYPE_MBEAN_LIST);
            pb.writeU64(System.currentTimeMillis());

            /* We don't know the count upfront for size budgeting, so cap at 500. */
            int cap = Math.min(names.size(), 500);
            pb.writeU16(cap);
            int sent = 0;
            for (ObjectName name : names) {
                if (sent >= cap) break;
                String s = name.toString();
                if (s.length() > MAX_STRING_LEN) s = s.substring(0, MAX_STRING_LEN);
                pb.writeString(s);
                sent++;
            }
            queue.enqueue(pb.buildMessage(MSG_JMX_DATA));
            AgentLogger.debug("JMX: sent " + sent + " MBean names");
        } catch (Exception e) {
            AgentLogger.error("JMX list failed: " + e.getMessage());
        }
    }

    /** Respond to DIAG_CMD_JMX_GET_ATTRS: send attributes for a specific MBean. */
    public static void handleGetAttrs(String mbeanName, MessageQueue queue) {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName objName = new ObjectName(mbeanName);
            MBeanInfo info = server.getMBeanInfo(objName);
            MBeanAttributeInfo[] attrInfos = info.getAttributes();

            ProtocolWriter.PayloadBuilder pb = ProtocolWriter.payload();
            pb.writeU8(SUBTYPE_MBEAN_ATTRS);
            pb.writeU64(System.currentTimeMillis());
            pb.writeString(mbeanName);

            /* Each entry: (key_string, value_string). Terminated with empty key. */
            int budget = MAX_PAYLOAD_BUDGET - mbeanName.length() - 32;

            for (int i = 0; i < attrInfos.length; i++) {
                MBeanAttributeInfo ai = attrInfos[i];
                if (!ai.isReadable()) continue;

                Object value;
                try {
                    value = server.getAttribute(objName, ai.getName());
                } catch (Throwable t) {
                    continue;
                }
                if (value == null) {
                    budget -= appendPair(pb, ai.getName(), "null");
                    if (budget < 200) break;
                    continue;
                }

                if (value instanceof CompositeData) {
                    /* Decompose: emit "attr.subkey" = subvalue for each composite field */
                    CompositeData cd = (CompositeData) value;
                    for (Object keyObj : cd.getCompositeType().keySet()) {
                        String k = keyObj.toString();
                        Object sub = cd.get(k);
                        budget -= appendPair(pb, ai.getName() + "." + k, formatValue(sub));
                        if (budget < 200) break;
                    }
                } else if (value instanceof TabularData) {
                    budget -= appendPair(pb, ai.getName(), "<TabularData rows=" + ((TabularData) value).size() + ">");
                } else if (value.getClass().isArray()) {
                    budget -= appendPair(pb, ai.getName(), formatArray(value));
                } else {
                    budget -= appendPair(pb, ai.getName(), formatValue(value));
                }

                if (budget < 200) break;
            }

            /* Terminator: empty key */
            pb.writeString("");
            pb.writeString("");

            queue.enqueue(pb.buildMessage(MSG_JMX_DATA));
            AgentLogger.debug("JMX: sent attrs for " + mbeanName);
        } catch (Exception e) {
            AgentLogger.debug("JMX get attrs failed for " + mbeanName + ": " + e.getMessage());
            /* Send an error response so the collector doesn't hang */
            try {
                ProtocolWriter.PayloadBuilder pb = ProtocolWriter.payload();
                pb.writeU8(SUBTYPE_MBEAN_ATTRS);
                pb.writeU64(System.currentTimeMillis());
                pb.writeString(mbeanName);
                pb.writeString("_error");
                pb.writeString(e.getClass().getSimpleName() + ": " + e.getMessage());
                pb.writeString("");
                pb.writeString("");
                queue.enqueue(pb.buildMessage(MSG_JMX_DATA));
            } catch (Exception ignored) { /* swallow */ }
        }
    }

    private static int appendPair(ProtocolWriter.PayloadBuilder pb, String key, String value)
            throws java.io.IOException {
        if (key == null) key = "";
        if (value == null) value = "null";
        if (key.length() > 200) key = key.substring(0, 200);
        if (value.length() > MAX_STRING_LEN) value = value.substring(0, MAX_STRING_LEN);
        pb.writeString(key);
        pb.writeString(value);
        return 4 + key.length() + value.length();
    }

    private static String formatValue(Object v) {
        if (v == null) return "null";
        if (v.getClass().isArray()) return formatArray(v);
        return v.toString();
    }

    private static String formatArray(Object arr) {
        if (arr instanceof Object[]) {
            Object[] a = (Object[]) arr;
            if (a.length == 0) return "[]";
            StringBuilder sb = new StringBuilder("[");
            int limit = Math.min(a.length, 10);
            for (int i = 0; i < limit; i++) {
                if (i > 0) sb.append(", ");
                sb.append(a[i] != null ? a[i].toString() : "null");
            }
            if (a.length > limit) sb.append(", ... (").append(a.length).append(" total)");
            sb.append("]");
            return sb.toString();
        }
        if (arr instanceof long[]) return java.util.Arrays.toString((long[]) arr);
        if (arr instanceof int[]) return java.util.Arrays.toString((int[]) arr);
        if (arr instanceof double[]) return java.util.Arrays.toString((double[]) arr);
        if (arr instanceof boolean[]) return java.util.Arrays.toString((boolean[]) arr);
        return arr.toString();
    }
}
