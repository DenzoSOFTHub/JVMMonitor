package it.denzosoft.jvmmonitor.model;

public final class ThreadInfo {

    public static final int STATE_NEW = 0;
    public static final int STATE_RUNNABLE = 1;
    public static final int STATE_BLOCKED = 2;
    public static final int STATE_WAITING = 3;
    public static final int STATE_TIMED_WAITING = 4;
    public static final int STATE_TERMINATED = 5;

    private final long timestamp;
    private final long threadId;
    private final String name;
    private final int state;
    private final boolean daemon;

    public ThreadInfo(long timestamp, long threadId, String name, int state, boolean daemon) {
        this.timestamp = timestamp;
        this.threadId = threadId;
        this.name = name;
        this.state = state;
        this.daemon = daemon;
    }

    public long getTimestamp() { return timestamp; }
    public long getThreadId() { return threadId; }
    public String getName() { return name; }
    public int getState() { return state; }
    public boolean isDaemon() { return daemon; }

    public static String stateToString(int state) {
        switch (state) {
            case STATE_NEW: return "NEW";
            case STATE_RUNNABLE: return "RUNNABLE";
            case STATE_BLOCKED: return "BLOCKED";
            case STATE_WAITING: return "WAITING";
            case STATE_TIMED_WAITING: return "TIMED_WAITING";
            case STATE_TERMINATED: return "TERMINATED";
            default: return "UNKNOWN";
        }
    }
}
