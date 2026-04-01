package it.denzosoft.jvmmonitor.model;

/**
 * GC root analysis result: reference chain from a GC root to an object.
 * Shows WHY an object cannot be garbage collected.
 */
public final class GcRootPath {

    private final String targetClass;
    private final String targetField;
    private final ReferenceNode[] path;

    public GcRootPath(String targetClass, String targetField, ReferenceNode[] path) {
        this.targetClass = targetClass;
        this.targetField = targetField;
        this.path = path;
    }

    public String getTargetClass() { return targetClass; }
    public String getTargetField() { return targetField; }
    public ReferenceNode[] getPath() { return path; }

    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("GC Root Path for ").append(targetClass).append(".").append(targetField).append(":\n");
        if (path != null) {
            for (int i = 0; i < path.length; i++) {
                for (int j = 0; j < i; j++) sb.append("  ");
                ReferenceNode n = path[i];
                if (i == 0) {
                    sb.append("[GC ROOT] ").append(n.rootType).append(": ");
                } else {
                    sb.append("-> ");
                }
                sb.append(n.className).append(".").append(n.fieldName);
                sb.append(" (").append(n.referenceType).append(")");
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    public static final class ReferenceNode {
        public final String className;
        public final String fieldName;
        public final String referenceType;  /* strong, weak, soft, phantom */
        public final String rootType;       /* thread, static, jni, etc. (only for root) */

        public ReferenceNode(String className, String fieldName, String referenceType, String rootType) {
            this.className = className;
            this.fieldName = fieldName;
            this.referenceType = referenceType;
            this.rootType = rootType != null ? rootType : "";
        }
    }
}
