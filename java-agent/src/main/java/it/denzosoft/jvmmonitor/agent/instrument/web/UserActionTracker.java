package it.denzosoft.jvmmonitor.agent.instrument.web;

import it.denzosoft.jvmmonitor.agent.transport.MessageQueue;
import it.denzosoft.jvmmonitor.agent.transport.ProtocolWriter;
import it.denzosoft.jvmmonitor.agent.util.AgentLogger;

import java.io.IOException;

/**
 * Receives beacon data from the injected JS and sends it as protocol messages.
 * Each user action becomes a MSG_INSTR_EVENT with a special event type.
 */
public final class UserActionTracker {

    /* Event type for user actions (re-uses INSTR_EVENT message type 0xC1) */
    public static final int TYPE_USER_ACTION = 20;

    private static volatile MessageQueue queue;
    private static volatile boolean enabled = false;

    private UserActionTracker() {}

    public static void init(MessageQueue q) { queue = q; }
    public static void setEnabled(boolean e) { enabled = e; }
    public static boolean isEnabled() { return enabled; }

    /**
     * Process a beacon JSON payload from the browser.
     * @param json the raw JSON string from the POST body
     */
    public static void processBeacon(String json) {
        if (!enabled || queue == null || json == null) return;

        try {
            /* Parse minimal JSON manually (no dependency on JSON library) */
            String type = extractJsonString(json, "\"t\"");
            long ts = extractJsonLong(json, "\"ts\"");
            String sid = extractJsonString(json, "\"sid\"");
            String url = extractJsonString(json, "\"url\"");
            String dataBlock = extractJsonObject(json, "\"d\"");

            /* Build context string from the action data */
            String context = buildContext(type, dataBlock);

            ProtocolWriter.PayloadBuilder pb = ProtocolWriter.payload();
            pb.writeU64(ts > 0 ? ts : System.currentTimeMillis());
            pb.writeU8(TYPE_USER_ACTION);
            pb.writeU64(0);                    /* threadId (0 = browser) */
            pb.writeString("browser:" + (sid != null ? sid : "?"));  /* threadName = session */
            pb.writeString(type != null ? type : "unknown");         /* className = action type */
            pb.writeString("");                /* methodName */
            pb.writeU64(0);                    /* durationNanos */
            pb.writeString(context);           /* context = action details */
            pb.writeU64(0);                    /* traceId */
            pb.writeU64(0);                    /* parentTraceId */
            pb.writeI32(0);                    /* depth */
            pb.writeU8(type != null && type.contains("error") ? 1 : 0); /* isException */
            pb.writeString("");                /* paramsJson */
            pb.writeString("");                /* returnValueJson */

            queue.enqueue(pb.buildMessage(0xC1));  /* MSG_INSTR_EVENT */
        } catch (Exception e) {
            /* Silently ignore malformed beacons */
        }
    }

    private static String buildContext(String type, String data) {
        if (type == null || data == null) return "";

        if ("click".equals(type)) {
            String tag = extractJsonString(data, "\"tag\"");
            String id = extractJsonString(data, "\"id\"");
            String text = extractJsonString(data, "\"text\"");
            String href = extractJsonString(data, "\"href\"");
            StringBuilder sb = new StringBuilder("CLICK ");
            sb.append(tag != null ? tag : "?");
            if (id != null && id.length() > 0) sb.append("#").append(id);
            if (text != null && text.length() > 0) sb.append(" \"").append(text).append("\"");
            if (href != null && href.length() > 0) sb.append(" -> ").append(href);
            return sb.toString();
        }
        if ("ajax".equals(type) || "fetch".equals(type)) {
            String method = extractJsonString(data, "\"method\"");
            String url = extractJsonString(data, "\"url\"");
            long status = extractJsonLong(data, "\"status\"");
            long dur = extractJsonLong(data, "\"dur\"");
            return (method != null ? method : "GET") + " " +
                   (url != null ? url : "?") + " " + status + " " + dur + "ms";
        }
        if ("pageload".equals(type)) {
            long ttfb = extractJsonLong(data, "\"ttfb\"");
            long dom = extractJsonLong(data, "\"dom\"");
            long load = extractJsonLong(data, "\"load\"");
            String title = extractJsonString(data, "\"title\"");
            return "PAGE " + (title != null ? title : "?") +
                   " ttfb=" + ttfb + "ms dom=" + dom + "ms load=" + load + "ms";
        }
        if ("jserror".equals(type)) {
            String msg = extractJsonString(data, "\"msg\"");
            String file = extractJsonString(data, "\"file\"");
            long line = extractJsonLong(data, "\"line\"");
            return "JS ERROR: " + (msg != null ? msg : "?") +
                   " at " + (file != null ? file : "?") + ":" + line;
        }
        if ("navigate".equals(type)) {
            String from = extractJsonString(data, "\"from\"");
            String to = extractJsonString(data, "\"to\"");
            return "NAVIGATE " + (from != null ? from : "?") + " -> " + (to != null ? to : "?");
        }
        if ("submit".equals(type)) {
            String action = extractJsonString(data, "\"action\"");
            String method = extractJsonString(data, "\"method\"");
            return "FORM SUBMIT " + (method != null ? method : "POST") + " " + (action != null ? action : "?");
        }
        return data;
    }

    /* Simple JSON string extraction (no external JSON library needed) */
    private static String extractJsonString(String json, String key) {
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        idx = json.indexOf(':', idx + key.length());
        if (idx < 0) return null;
        idx++; /* skip ':' */
        while (idx < json.length() && json.charAt(idx) == ' ') idx++;
        if (idx >= json.length()) return null;
        if (json.charAt(idx) == '"') {
            /* Find closing quote, skipping escaped quotes */
            int start = idx + 1;
            int end = start;
            while (end < json.length()) {
                char c = json.charAt(end);
                if (c == '\\') {
                    end += 2; /* skip escaped character */
                    if (end > json.length()) break; /* guard trailing backslash */
                    continue;
                }
                if (c == '"') break;
                end++;
            }
            if (end >= json.length()) return null;
            return json.substring(start, end);
        }
        return null;
    }

    private static long extractJsonLong(String json, String key) {
        int idx = json.indexOf(key);
        if (idx < 0) return 0;
        idx = json.indexOf(':', idx + key.length());
        if (idx < 0) return 0;
        idx++;
        while (idx < json.length() && json.charAt(idx) == ' ') idx++;
        int end = idx;
        while (end < json.length() && (json.charAt(end) >= '0' && json.charAt(end) <= '9')) end++;
        if (end == idx) return 0;
        try { return Long.parseLong(json.substring(idx, end)); } catch (Exception e) { return 0; }
    }

    private static String extractJsonObject(String json, String key) {
        int idx = json.indexOf(key);
        if (idx < 0) return "";
        idx = json.indexOf('{', idx);
        if (idx < 0) return "";
        int depth = 0;
        int end = idx;
        while (end < json.length()) {
            if (json.charAt(end) == '{') depth++;
            else if (json.charAt(end) == '}') { depth--; if (depth == 0) { end++; break; } }
            end++;
        }
        return json.substring(idx, end);
    }
}
