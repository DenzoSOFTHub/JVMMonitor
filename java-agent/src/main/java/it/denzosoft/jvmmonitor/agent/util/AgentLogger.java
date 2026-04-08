package it.denzosoft.jvmmonitor.agent.util;

import java.text.SimpleDateFormat;
import java.util.Date;

/** Simple logger to stderr with [JVMMonitor] prefix. */
public final class AgentLogger {

    private static volatile int level = 1; /* 0=error, 1=info, 2=debug */

    private static final ThreadLocal SDF = new ThreadLocal() {
        protected Object initialValue() {
            return new SimpleDateFormat("HH:mm:ss.SSS");
        }
    };

    private AgentLogger() {}

    public static void setLevel(int l) { level = l; }

    public static void error(String msg) {
        log("ERROR", msg);
    }

    public static void info(String msg) {
        if (level >= 1) log("INFO", msg);
    }

    public static void debug(String msg) {
        if (level >= 2) log("DEBUG", msg);
    }

    private static void log(String lvl, String msg) {
        String ts = ((SimpleDateFormat) SDF.get()).format(new Date());
        System.err.println("[JVMMonitor " + ts + " " + lvl + "] " + msg);
    }
}
