package it.denzosoft.jvmmonitor.agent.instrument;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Map;

/**
 * Lightweight JSON serializer for method parameters and return values.
 * Handles: primitives, strings, arrays, collections, maps, and objects (via toString).
 * Each value is truncated to maxLength characters (-1 = no limit).
 *
 * Thread-safe: no mutable state.
 */
public final class ParamSerializer {

    private static volatile int maxValueLength = 500;
    private static volatile boolean enabled = false;

    private ParamSerializer() {}

    public static void setEnabled(boolean e) { enabled = e; }
    public static boolean isEnabled() { return enabled; }

    /** Set max length for each serialized value. -1 = unlimited. Default: 500. */
    public static void setMaxValueLength(int len) { maxValueLength = len; }
    public static int getMaxValueLength() { return maxValueLength; }

    /**
     * Serialize method parameters to a JSON array string.
     * @param args the Object[] from Javassist $args
     * @return JSON like ["value1","value2",123] or null if disabled
     */
    public static String serializeParams(Object[] args) {
        if (!enabled || args == null || args.length == 0) return null;

        StringBuilder sb = new StringBuilder(64);
        sb.append('[');
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(',');
            serializeValue(sb, args[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * Serialize a return value to a JSON string.
     * @param value the return value (or boxed primitive)
     * @return JSON representation or null if disabled
     */
    public static String serializeReturnValue(Object value) {
        if (!enabled) return null;
        StringBuilder sb = new StringBuilder(32);
        serializeValue(sb, value);
        return sb.toString();
    }

    private static void serializeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
            return;
        }

        Class cls = value.getClass();

        /* Primitives and wrappers */
        if (cls == Boolean.class) {
            sb.append(value);
            return;
        }
        if (cls == Byte.class || cls == Short.class || cls == Integer.class || cls == Long.class) {
            sb.append(value);
            return;
        }
        if (cls == Float.class || cls == Double.class) {
            sb.append(value);
            return;
        }
        if (cls == Character.class) {
            appendString(sb, value.toString());
            return;
        }

        /* String */
        if (value instanceof String) {
            appendString(sb, (String) value);
            return;
        }

        /* Array */
        if (cls.isArray()) {
            serializeArray(sb, value);
            return;
        }

        /* Collection */
        if (value instanceof Collection) {
            serializeCollection(sb, (Collection) value);
            return;
        }

        /* Map */
        if (value instanceof Map) {
            serializeMap(sb, (Map) value);
            return;
        }

        /* Fallback: toString */
        String str;
        try {
            str = value.toString();
        } catch (Exception e) {
            str = cls.getName() + "@" + Integer.toHexString(System.identityHashCode(value));
        }
        appendString(sb, str);
    }

    private static void serializeArray(StringBuilder sb, Object arr) {
        int len = Array.getLength(arr);
        int show = Math.min(len, 20);
        sb.append('[');
        for (int i = 0; i < show; i++) {
            if (i > 0) sb.append(',');
            try {
                serializeValue(sb, Array.get(arr, i));
            } catch (Exception e) {
                sb.append("\"?\"");
            }
        }
        if (len > show) {
            sb.append(",\"...(").append(len).append(" total)\"");
        }
        sb.append(']');
    }

    private static void serializeCollection(StringBuilder sb, Collection col) {
        sb.append('[');
        int i = 0;
        java.util.Iterator it = col.iterator();
        while (it.hasNext() && i < 20) {
            if (i > 0) sb.append(',');
            try {
                serializeValue(sb, it.next());
            } catch (Exception e) {
                sb.append("\"?\"");
            }
            i++;
        }
        if (it.hasNext()) {
            sb.append(",\"...(").append(col.size()).append(" total)\"");
        }
        sb.append(']');
    }

    private static void serializeMap(StringBuilder sb, Map map) {
        sb.append('{');
        int i = 0;
        java.util.Iterator it = map.entrySet().iterator();
        while (it.hasNext() && i < 20) {
            if (i > 0) sb.append(',');
            Map.Entry entry = (Map.Entry) it.next();
            try {
                appendString(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                serializeValue(sb, entry.getValue());
            } catch (Exception e) {
                sb.append("\"?\":\"?\"");
            }
            i++;
        }
        if (it.hasNext()) {
            sb.append(",\"...\":\"(").append(map.size()).append(" entries)\"");
        }
        sb.append('}');
    }

    private static void appendString(StringBuilder sb, String s) {
        sb.append('"');
        String truncated = truncate(s);
        for (int i = 0; i < truncated.length(); i++) {
            char c = truncated.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append("\\u00").append(String.format("%02x", (int)c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    private static String truncate(String s) {
        if (s == null) return "null";
        int max = maxValueLength;
        if (max < 0 || s.length() <= max) return s;
        return s.substring(0, max) + "...(" + s.length() + " chars)";
    }
}
