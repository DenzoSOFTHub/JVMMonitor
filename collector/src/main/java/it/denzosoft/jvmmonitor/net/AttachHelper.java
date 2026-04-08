package it.denzosoft.jvmmonitor.net;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles JVM attach/detach entirely via reflection.
 * Loads tools.jar at runtime if needed (JDK 6-8).
 * On JDK 9+ the attach API is in the jdk.attach module, no tools.jar needed.
 */
public final class AttachHelper {

    private static Class<?> vmClass;
    private static Class<?> vmdClass;
    private static boolean initialized;
    private static String initError;
    /** URLClassLoader for tools.jar. Intentionally kept open for the lifetime
     *  of the process because the Attach API classes it loaded must remain accessible. */
    private static URLClassLoader toolsLoader;

    private AttachHelper() {}

    /**
     * Ensure the Attach API classes are available.
     * Tries in order:
     *   1. Direct Class.forName (works on JDK 9+ and when tools.jar is on classpath)
     *   2. Load tools.jar from JAVA_HOME/../lib/tools.jar via URLClassLoader (JDK 6-8)
     *   3. Load from java.home/../lib/tools.jar
     *   4. Search common JDK locations
     */
    private static synchronized void init() {
        if (initialized) return;
        initialized = true;

        /* Attempt 1: already on classpath or JDK 9+ module */
        try {
            vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
            vmdClass = Class.forName("com.sun.tools.attach.VirtualMachineDescriptor");
            return;
        } catch (ClassNotFoundException e) {
            /* Not directly available, try loading tools.jar */
        }

        /* Attempt 2-4: find and load tools.jar */
        File toolsJar = findToolsJar();
        if (toolsJar == null) {
            initError = "Cannot find tools.jar. Ensure you are running with a JDK (not a JRE).\n" +
                    "Alternatively, set JAVA_HOME to point to a JDK installation.";
            return;
        }

        try {
            URL toolsUrl = toolsJar.toURI().toURL();
            toolsLoader = new URLClassLoader(
                    new URL[]{ toolsUrl }, AttachHelper.class.getClassLoader());
            vmClass = toolsLoader.loadClass("com.sun.tools.attach.VirtualMachine");
            vmdClass = toolsLoader.loadClass("com.sun.tools.attach.VirtualMachineDescriptor");
        } catch (Exception e) {
            initError = "Found tools.jar at " + toolsJar.getAbsolutePath() +
                    " but failed to load Attach API: " + e.getMessage();
        }
    }

    private static File findToolsJar() {
        List<String> candidates = new ArrayList<String>();

        /* From JAVA_HOME env */
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null) {
            candidates.add(javaHome + File.separator + "lib" + File.separator + "tools.jar");
            candidates.add(javaHome + File.separator + ".." + File.separator + "lib" + File.separator + "tools.jar");
        }

        /* From java.home system property (points to JRE inside JDK) */
        String javaHomeProp = System.getProperty("java.home");
        if (javaHomeProp != null) {
            candidates.add(javaHomeProp + File.separator + ".." + File.separator + "lib" + File.separator + "tools.jar");
            candidates.add(javaHomeProp + File.separator + "lib" + File.separator + "tools.jar");
        }

        /* Common locations */
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("linux") || os.contains("mac")) {
            candidates.add("/usr/lib/jvm/default-java/lib/tools.jar");
            candidates.add("/usr/lib/jvm/java-8-openjdk-amd64/lib/tools.jar");
            candidates.add("/usr/lib/jvm/java-7-openjdk-amd64/lib/tools.jar");
            candidates.add("/usr/lib/jvm/java-6-openjdk-amd64/lib/tools.jar");
        }
        if (os.contains("win")) {
            String progFiles = System.getenv("ProgramFiles");
            if (progFiles != null) {
                candidates.add(progFiles + "\\Java\\jdk1.8.0_*\\lib\\tools.jar");
                candidates.add(progFiles + "\\Java\\jdk1.7.0_*\\lib\\tools.jar");
            }
        }

        for (int i = 0; i < candidates.size(); i++) {
            File f = new File(candidates.get(i));
            if (f.isFile()) {
                return f;
            }
        }

        return null;
    }

    /**
     * List all JVMs visible on this machine.
     * Returns a list of "PID DISPLAY_NAME" strings.
     */
    public static List<String[]> listJvms() throws Exception {
        init();
        if (vmClass == null) {
            throw new RuntimeException(initError);
        }

        Method listMethod = vmClass.getMethod("list");
        List<?> vms = (List<?>) listMethod.invoke(null);

        Method getId = vmdClass.getMethod("id");
        Method getName = vmdClass.getMethod("displayName");

        List<String[]> result = new ArrayList<String[]>();
        for (int i = 0; i < vms.size(); i++) {
            Object vmd = vms.get(i);
            String pid = (String) getId.invoke(vmd);
            String name = (String) getName.invoke(vmd);
            result.add(new String[]{ pid, name });
        }
        return result;
    }

    /**
     * Attach the native agent to a running JVM.
     * @param pid           Target JVM process ID
     * @param agentPath     Path to jvmmonitor.so / jvmmonitor.dll
     * @param agentOptions  Options string (e.g., "host=127.0.0.1,port=9090")
     */
    public static void attach(String pid, String agentPath, String agentOptions) throws Exception {
        init();
        if (vmClass == null) {
            throw new RuntimeException(initError);
        }

        Object vm;
        try {
            Method attachMethod = vmClass.getMethod("attach", String.class);
            vm = attachMethod.invoke(null, pid);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw unwrap(e, "Failed to attach to PID " + pid);
        }

        try {
            Method loadAgent = vmClass.getMethod("loadAgentPath", String.class, String.class);
            loadAgent.invoke(vm, agentPath, agentOptions);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw unwrap(e, "Failed to load native agent");
        } finally {
            try {
                Method detach = vmClass.getMethod("detach");
                detach.invoke(vm);
            } catch (Exception e) { /* ignore detach errors */ }
        }
    }

    /**
     * Attach a Java agent (.jar) to a running JVM.
     * Uses vm.loadAgent() instead of vm.loadAgentPath().
     * @param pid           Target JVM process ID
     * @param agentJarPath  Path to jvmmonitor-agent.jar
     * @param agentOptions  Options string (e.g., "port=9090")
     */
    public static void attachJavaAgent(String pid, String agentJarPath, String agentOptions) throws Exception {
        init();
        if (vmClass == null) {
            throw new RuntimeException(initError);
        }

        Object vm;
        try {
            Method attachMethod = vmClass.getMethod("attach", String.class);
            vm = attachMethod.invoke(null, pid);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw unwrap(e, "Failed to attach to PID " + pid);
        }

        try {
            Method loadAgent = vmClass.getMethod("loadAgent", String.class, String.class);
            loadAgent.invoke(vm, agentJarPath, agentOptions);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw unwrap(e, "Failed to load Java agent");
        } finally {
            try {
                Method detach = vmClass.getMethod("detach");
                detach.invoke(vm);
            } catch (Exception e) { /* ignore detach errors */ }
        }
    }

    /** Same fix for native agent attach. */

    /**
     * Unwrap InvocationTargetException to get the real cause with a clear message.
     */
    private static Exception unwrap(java.lang.reflect.InvocationTargetException e, String context) {
        Throwable cause = e.getCause();
        if (cause == null) cause = e;
        String msg = cause.getMessage();
        if (msg == null || msg.isEmpty()) {
            msg = cause.getClass().getName();
        }
        return new Exception(context + ": " + msg, cause);
    }

    /**
     * Check if the Attach API is available.
     */
    public static boolean isAvailable() {
        init();
        return vmClass != null;
    }

    /**
     * Get the error message if the Attach API is not available.
     */
    public static String getError() {
        init();
        return initError;
    }
}
