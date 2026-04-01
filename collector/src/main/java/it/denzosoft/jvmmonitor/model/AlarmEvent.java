package it.denzosoft.jvmmonitor.model;

public final class AlarmEvent {

    private final long timestamp;
    private final int alarmType;
    private final int severity;
    private final double value;
    private final double threshold;
    private final String message;

    public AlarmEvent(long timestamp, int alarmType, int severity,
                      double value, double threshold, String message) {
        this.timestamp = timestamp;
        this.alarmType = alarmType;
        this.severity = severity;
        this.value = value;
        this.threshold = threshold;
        this.message = message;
    }

    public long getTimestamp() { return timestamp; }
    public int getAlarmType() { return alarmType; }
    public int getSeverity() { return severity; }
    public double getValue() { return value; }
    public double getThreshold() { return threshold; }
    public String getMessage() { return message; }

    public static String severityToString(int severity) {
        switch (severity) {
            case 0: return "INFO";
            case 1: return "WARNING";
            case 2: return "CRITICAL";
            default: return "UNKNOWN";
        }
    }

    public String getAlarmTypeName() {
        switch (alarmType) {
            case 1: return "GC_FREQUENCY";
            case 2: return "GC_PAUSE";
            case 3: return "HEAP_USAGE";
            case 4: return "HEAP_GROWTH";
            case 5: return "THREAD_BLOCKED";
            case 6: return "CPU_HIGH";
            default: return "UNKNOWN(" + alarmType + ")";
        }
    }
}
