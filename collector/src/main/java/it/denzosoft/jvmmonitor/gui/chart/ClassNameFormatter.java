package it.denzosoft.jvmmonitor.gui.chart;

/**
 * Formats JVM internal class names to readable Java notation.
 * [B -> byte[], [C -> char[], Ljava/lang/String; -> java.lang.String, etc.
 */
public final class ClassNameFormatter {

    private ClassNameFormatter() {}

    public static String format(String name) {
        if (name == null || name.isEmpty()) return "?";

        /* Array types */
        if (name.startsWith("[")) {
            int dims = 0;
            while (dims < name.length() && name.charAt(dims) == '[') dims++;
            String base = name.substring(dims);
            StringBuilder suffix = new StringBuilder();
            for (int i = 0; i < dims; i++) suffix.append("[]");

            if ("B".equals(base)) return "byte" + suffix;
            if ("C".equals(base)) return "char" + suffix;
            if ("I".equals(base)) return "int" + suffix;
            if ("J".equals(base)) return "long" + suffix;
            if ("D".equals(base)) return "double" + suffix;
            if ("F".equals(base)) return "float" + suffix;
            if ("Z".equals(base)) return "boolean" + suffix;
            if ("S".equals(base)) return "short" + suffix;
            if (base.startsWith("L") && base.endsWith(";")) {
                return base.substring(1, base.length() - 1).replace('/', '.') + suffix;
            }
            return base + suffix;
        }

        /* Object type: Lfoo/bar/Baz; -> foo.bar.Baz */
        if (name.startsWith("L") && name.endsWith(";")) {
            return name.substring(1, name.length() - 1).replace('/', '.');
        }

        return name.replace('/', '.');
    }
}
