package it.denzosoft.jvmmonitor.agent.instrument;

import java.util.*;

/**
 * Registry of all instrumentation probe definitions.
 * Mirrors the C agent's 8 probe types: JDBC, Spring, HTTP, Messaging, Mail, Cache, Disk I/O, Socket I/O.
 */
public final class ProbeRegistry {

    /* Event types matching InstrumentationEvent constants in the collector */
    public static final int TYPE_METHOD_EXIT    = 2;
    public static final int TYPE_JDBC_QUERY     = 3;
    public static final int TYPE_JDBC_CONNECT   = 4;
    public static final int TYPE_JDBC_CLOSE     = 5;
    public static final int TYPE_HTTP_REQUEST   = 6;
    public static final int TYPE_DISK_READ      = 8;
    public static final int TYPE_DISK_WRITE     = 9;
    public static final int TYPE_SOCKET_READ    = 10;
    public static final int TYPE_SOCKET_WRITE   = 11;
    public static final int TYPE_SOCKET_CONNECT = 12;
    public static final int TYPE_SOCKET_CLOSE   = 13;

    private static final List ALL_PROBES = new ArrayList();

    static {
        /* ── JDBC ──────────────────────────────── */
        ALL_PROBES.add(new ProbeDefinition("jdbc",
                "java.sql.Statement", "execute", TYPE_JDBC_QUERY, "$1"));
        ALL_PROBES.add(new ProbeDefinition("jdbc",
                "java.sql.Statement", "executeQuery", TYPE_JDBC_QUERY, "$1"));
        ALL_PROBES.add(new ProbeDefinition("jdbc",
                "java.sql.Statement", "executeUpdate", TYPE_JDBC_QUERY, "$1"));
        ALL_PROBES.add(new ProbeDefinition("jdbc",
                "java.sql.PreparedStatement", "execute", TYPE_JDBC_QUERY, "\"PreparedStatement\""));
        ALL_PROBES.add(new ProbeDefinition("jdbc",
                "java.sql.PreparedStatement", "executeQuery", TYPE_JDBC_QUERY, "\"PreparedStatement\""));
        ALL_PROBES.add(new ProbeDefinition("jdbc",
                "java.sql.PreparedStatement", "executeUpdate", TYPE_JDBC_QUERY, "\"PreparedStatement\""));
        ALL_PROBES.add(new ProbeDefinition("jdbc",
                "javax.sql.DataSource", "getConnection", TYPE_JDBC_CONNECT, "\"DataSource.getConnection\""));
        ALL_PROBES.add(new ProbeDefinition("jdbc",
                "java.sql.Connection", "close", TYPE_JDBC_CLOSE, "\"Connection.close\""));

        /* ── Spring ────────────────────────────── */
        /* Spring controllers/services are matched by application package, not here */

        /* ── HTTP Client ───────────────────────── */
        ALL_PROBES.add(new ProbeDefinition("http",
                "java.net.HttpURLConnection", "getResponseCode", TYPE_HTTP_REQUEST, "getURL().toString()"));
        ALL_PROBES.add(new ProbeDefinition("http",
                "java.net.HttpURLConnection", "getInputStream", TYPE_HTTP_REQUEST, "getURL().toString()"));

        /* ── Messaging ─────────────────────────── */
        ALL_PROBES.add(new ProbeDefinition("messaging",
                "javax.jms.MessageProducer", "send", TYPE_METHOD_EXIT, "\"JMS.send\""));
        ALL_PROBES.add(new ProbeDefinition("messaging",
                "javax.jms.MessageConsumer", "receive", TYPE_METHOD_EXIT, "\"JMS.receive\""));

        /* ── Mail ──────────────────────────────── */
        ALL_PROBES.add(new ProbeDefinition("mail",
                "javax.mail.Transport", "send", TYPE_METHOD_EXIT, "\"Mail.send\""));

        /* ── Cache ─────────────────────────────── */
        ALL_PROBES.add(new ProbeDefinition("cache",
                "javax.cache.Cache", "get", TYPE_METHOD_EXIT, "\"Cache.get(\" + $1 + \")\""));
        ALL_PROBES.add(new ProbeDefinition("cache",
                "javax.cache.Cache", "put", TYPE_METHOD_EXIT, "\"Cache.put(\" + $1 + \")\""));

        /* ── Disk I/O ──────────────────────────── */
        ALL_PROBES.add(new ProbeDefinition("disk_io",
                "java.io.FileInputStream", "read", TYPE_DISK_READ, "\"FileInputStream.read\""));
        ALL_PROBES.add(new ProbeDefinition("disk_io",
                "java.io.FileOutputStream", "write", TYPE_DISK_WRITE, "\"FileOutputStream.write\""));
        ALL_PROBES.add(new ProbeDefinition("disk_io",
                "java.io.RandomAccessFile", "read", TYPE_DISK_READ, "\"RandomAccessFile.read\""));
        ALL_PROBES.add(new ProbeDefinition("disk_io",
                "java.io.RandomAccessFile", "write", TYPE_DISK_WRITE, "\"RandomAccessFile.write\""));

        /* ── Socket I/O ────────────────────────── */
        ALL_PROBES.add(new ProbeDefinition("socket_io",
                "java.net.Socket", "connect", TYPE_SOCKET_CONNECT, "\"Socket.connect\""));
        ALL_PROBES.add(new ProbeDefinition("socket_io",
                "java.net.Socket", "close", TYPE_SOCKET_CLOSE, "\"Socket.close\""));

        /* ── Scheduling ────────────────────────── */
        ALL_PROBES.add(new ProbeDefinition("scheduling",
                "java.util.concurrent.ScheduledThreadPoolExecutor", "schedule",
                TYPE_METHOD_EXIT, "\"Scheduled.schedule\""));
        ALL_PROBES.add(new ProbeDefinition("scheduling",
                "java.util.concurrent.ScheduledThreadPoolExecutor", "scheduleAtFixedRate",
                TYPE_METHOD_EXIT, "\"Scheduled.scheduleAtFixedRate\""));
        ALL_PROBES.add(new ProbeDefinition("scheduling",
                "java.util.concurrent.ScheduledThreadPoolExecutor", "scheduleWithFixedDelay",
                TYPE_METHOD_EXIT, "\"Scheduled.scheduleWithFixedDelay\""));
        ALL_PROBES.add(new ProbeDefinition("scheduling",
                "java.util.Timer", "schedule",
                TYPE_METHOD_EXIT, "\"Timer.schedule\""));
        ALL_PROBES.add(new ProbeDefinition("scheduling",
                "java.util.Timer", "scheduleAtFixedRate",
                TYPE_METHOD_EXIT, "\"Timer.scheduleAtFixedRate\""));
        ALL_PROBES.add(new ProbeDefinition("scheduling",
                "org.springframework.scheduling.TaskScheduler", "schedule",
                TYPE_METHOD_EXIT, "\"Spring.TaskScheduler.schedule\""));
        ALL_PROBES.add(new ProbeDefinition("scheduling",
                "org.springframework.scheduling.TaskScheduler", "scheduleAtFixedRate",
                TYPE_METHOD_EXIT, "\"Spring.TaskScheduler.scheduleAtFixedRate\""));
        ALL_PROBES.add(new ProbeDefinition("scheduling",
                "org.springframework.scheduling.TaskScheduler", "scheduleWithFixedDelay",
                TYPE_METHOD_EXIT, "\"Spring.TaskScheduler.scheduleWithFixedDelay\""));

        /* EJB Timer Service (TimerBean / @Schedule) */
        ALL_PROBES.add(new ProbeDefinition("scheduling",
                "javax.ejb.TimerService", "createTimer",
                TYPE_METHOD_EXIT, "\"EJB.TimerService.createTimer\""));
        ALL_PROBES.add(new ProbeDefinition("scheduling",
                "javax.ejb.TimerService", "createSingleActionTimer",
                TYPE_METHOD_EXIT, "\"EJB.TimerService.createSingleActionTimer\""));
        ALL_PROBES.add(new ProbeDefinition("scheduling",
                "javax.ejb.TimerService", "createIntervalTimer",
                TYPE_METHOD_EXIT, "\"EJB.TimerService.createIntervalTimer\""));
        ALL_PROBES.add(new ProbeDefinition("scheduling",
                "javax.ejb.TimerService", "createCalendarTimer",
                TYPE_METHOD_EXIT, "\"EJB.TimerService.createCalendarTimer\""));
        ALL_PROBES.add(new ProbeDefinition("scheduling",
                "javax.ejb.Timer", "cancel",
                TYPE_METHOD_EXIT, "\"EJB.Timer.cancel\""));
        /* Jakarta EE 9+ namespace */
        ALL_PROBES.add(new ProbeDefinition("scheduling",
                "jakarta.ejb.TimerService", "createTimer",
                TYPE_METHOD_EXIT, "\"EJB.TimerService.createTimer\""));
        ALL_PROBES.add(new ProbeDefinition("scheduling",
                "jakarta.ejb.TimerService", "createSingleActionTimer",
                TYPE_METHOD_EXIT, "\"EJB.TimerService.createSingleActionTimer\""));
        ALL_PROBES.add(new ProbeDefinition("scheduling",
                "jakarta.ejb.TimerService", "createIntervalTimer",
                TYPE_METHOD_EXIT, "\"EJB.TimerService.createIntervalTimer\""));
        ALL_PROBES.add(new ProbeDefinition("scheduling",
                "jakarta.ejb.TimerService", "createCalendarTimer",
                TYPE_METHOD_EXIT, "\"EJB.TimerService.createCalendarTimer\""));
        ALL_PROBES.add(new ProbeDefinition("scheduling",
                "jakarta.ejb.Timer", "cancel",
                TYPE_METHOD_EXIT, "\"EJB.Timer.cancel\""));

        /* Quartz Scheduler */
        ALL_PROBES.add(new ProbeDefinition("scheduling",
                "org.quartz.core.JobRunShell", "run",
                TYPE_METHOD_EXIT, "\"Quartz.JobRunShell.run\""));
        ALL_PROBES.add(new ProbeDefinition("scheduling",
                "org.quartz.Job", "execute",
                TYPE_METHOD_EXIT, "\"Quartz.Job.execute\""));
        ALL_PROBES.add(new ProbeDefinition("scheduling",
                "org.quartz.Scheduler", "scheduleJob",
                TYPE_METHOD_EXIT, "\"Quartz.scheduleJob\""));
        ALL_PROBES.add(new ProbeDefinition("scheduling",
                "org.quartz.Scheduler", "triggerJob",
                TYPE_METHOD_EXIT, "\"Quartz.triggerJob\""));
        ALL_PROBES.add(new ProbeDefinition("scheduling",
                "org.quartz.Scheduler", "pauseJob",
                TYPE_METHOD_EXIT, "\"Quartz.pauseJob\""));
        ALL_PROBES.add(new ProbeDefinition("scheduling",
                "org.quartz.Scheduler", "resumeJob",
                TYPE_METHOD_EXIT, "\"Quartz.resumeJob\""));
        ALL_PROBES.add(new ProbeDefinition("scheduling",
                "org.quartz.Scheduler", "deleteJob",
                TYPE_METHOD_EXIT, "\"Quartz.deleteJob\""));

        /* ── JMS Extended ───────────────────────── */
        /* Connection lifecycle */
        ALL_PROBES.add(new ProbeDefinition("jms_ops",
                "javax.jms.ConnectionFactory", "createConnection",
                TYPE_METHOD_EXIT, "\"JMS.createConnection\""));
        ALL_PROBES.add(new ProbeDefinition("jms_ops",
                "javax.jms.Connection", "start",
                TYPE_METHOD_EXIT, "\"JMS.Connection.start\""));
        ALL_PROBES.add(new ProbeDefinition("jms_ops",
                "javax.jms.Connection", "close",
                TYPE_METHOD_EXIT, "\"JMS.Connection.close\""));
        /* Session lifecycle */
        ALL_PROBES.add(new ProbeDefinition("jms_ops",
                "javax.jms.Connection", "createSession",
                TYPE_METHOD_EXIT, "\"JMS.createSession\""));
        ALL_PROBES.add(new ProbeDefinition("jms_ops",
                "javax.jms.Session", "close",
                TYPE_METHOD_EXIT, "\"JMS.Session.close\""));
        /* Producer / Consumer creation */
        ALL_PROBES.add(new ProbeDefinition("jms_ops",
                "javax.jms.Session", "createProducer",
                TYPE_METHOD_EXIT, "\"JMS.createProducer(\" + $1 + \")\""));
        ALL_PROBES.add(new ProbeDefinition("jms_ops",
                "javax.jms.Session", "createConsumer",
                TYPE_METHOD_EXIT, "\"JMS.createConsumer(\" + $1 + \")\""));
        /* Queue / Topic browsing */
        ALL_PROBES.add(new ProbeDefinition("jms_ops",
                "javax.jms.Session", "createQueue",
                TYPE_METHOD_EXIT, "\"JMS.createQueue(\" + $1 + \")\""));
        ALL_PROBES.add(new ProbeDefinition("jms_ops",
                "javax.jms.Session", "createTopic",
                TYPE_METHOD_EXIT, "\"JMS.createTopic(\" + $1 + \")\""));
        ALL_PROBES.add(new ProbeDefinition("jms_ops",
                "javax.jms.QueueBrowser", "getEnumeration",
                TYPE_METHOD_EXIT, "\"JMS.QueueBrowser.getEnumeration\""));
        /* Message acknowledgement and commit/rollback */
        ALL_PROBES.add(new ProbeDefinition("jms_ops",
                "javax.jms.Message", "acknowledge",
                TYPE_METHOD_EXIT, "\"JMS.Message.acknowledge\""));
        ALL_PROBES.add(new ProbeDefinition("jms_ops",
                "javax.jms.Session", "commit",
                TYPE_METHOD_EXIT, "\"JMS.Session.commit\""));
        ALL_PROBES.add(new ProbeDefinition("jms_ops",
                "javax.jms.Session", "rollback",
                TYPE_METHOD_EXIT, "\"JMS.Session.rollback\""));
    }

    private ProbeRegistry() {}

    /**
     * Get probe definitions matching the given active probe names.
     * @param activeProbes set of probe names (e.g., "jdbc", "http", "socket_io")
     * @return list of matching ProbeDefinitions
     */
    public static List getActiveProbes(Set activeProbes) {
        List result = new ArrayList();
        for (int i = 0; i < ALL_PROBES.size(); i++) {
            ProbeDefinition pd = (ProbeDefinition) ALL_PROBES.get(i);
            if (activeProbes.contains(pd.probeName)) {
                result.add(pd);
            }
        }
        return result;
    }

    /**
     * Get all class names targeted by the given active probes.
     * @return set of internal class names (slash-separated)
     */
    public static Set getTargetClassNames(Set activeProbes) {
        Set result = new HashSet();
        for (int i = 0; i < ALL_PROBES.size(); i++) {
            ProbeDefinition pd = (ProbeDefinition) ALL_PROBES.get(i);
            if (activeProbes.contains(pd.probeName)) {
                result.add(pd.getInternalClassName());
            }
        }
        return result;
    }

    /**
     * Get probe definitions for a specific class.
     * @param internalClassName slash-separated class name
     */
    public static List getProbesForClass(String internalClassName, Set activeProbes) {
        List result = new ArrayList();
        for (int i = 0; i < ALL_PROBES.size(); i++) {
            ProbeDefinition pd = (ProbeDefinition) ALL_PROBES.get(i);
            if (activeProbes.contains(pd.probeName) && pd.getInternalClassName().equals(internalClassName)) {
                result.add(pd);
            }
        }
        return result;
    }
}
