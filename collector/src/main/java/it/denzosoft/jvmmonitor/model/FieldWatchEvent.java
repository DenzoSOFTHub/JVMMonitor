package it.denzosoft.jvmmonitor.model;

/**
 * Event fired when a watched field is accessed or modified.
 */
public final class FieldWatchEvent {

    public static final int ACCESS = 1;
    public static final int MODIFY = 2;

    private final long timestamp;
    private final int eventType;
    private final String className;
    private final String fieldName;
    private final String fieldType;
    private final String oldValue;
    private final String newValue;
    private final String threadName;
    private final String accessLocation;  /* class.method where access happened */

    public FieldWatchEvent(long timestamp, int eventType, String className, String fieldName,
                           String fieldType, String oldValue, String newValue,
                           String threadName, String accessLocation) {
        this.timestamp = timestamp;
        this.eventType = eventType;
        this.className = className;
        this.fieldName = fieldName;
        this.fieldType = fieldType;
        this.oldValue = oldValue != null ? oldValue : "";
        this.newValue = newValue != null ? newValue : "";
        this.threadName = threadName;
        this.accessLocation = accessLocation != null ? accessLocation : "";
    }

    public long getTimestamp() { return timestamp; }
    public int getEventType() { return eventType; }
    public String getClassName() { return className; }
    public String getFieldName() { return fieldName; }
    public String getFieldType() { return fieldType; }
    public String getOldValue() { return oldValue; }
    public String getNewValue() { return newValue; }
    public String getThreadName() { return threadName; }
    public String getAccessLocation() { return accessLocation; }
    public String getTypeName() { return eventType == ACCESS ? "READ" : "WRITE"; }
    public String getFullFieldName() { return className.replace('/', '.') + "." + fieldName; }
}
