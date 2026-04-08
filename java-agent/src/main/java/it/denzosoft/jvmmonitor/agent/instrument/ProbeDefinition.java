package it.denzosoft.jvmmonitor.agent.instrument;

/**
 * Defines a single instrumentation target: which class/method to intercept
 * and what event type to generate.
 */
public final class ProbeDefinition {

    public final String probeName;    /* jdbc, spring, http, messaging, mail, cache, disk_io, socket_io */
    public final String className;    /* fully qualified, dot-separated */
    public final String methodName;   /* method to intercept (null = all public methods) */
    public final int eventType;       /* InstrumentationEvent type constant */
    public final String contextExpr;  /* Javassist expression for context string (e.g., "$1" for first arg) */

    public ProbeDefinition(String probeName, String className, String methodName,
                           int eventType, String contextExpr) {
        this.probeName = probeName;
        this.className = className;
        this.methodName = methodName;
        this.eventType = eventType;
        this.contextExpr = contextExpr;
    }

    /** Convert dot-separated class name to JVM internal slash format. */
    public String getInternalClassName() {
        return className.replace('.', '/');
    }
}
