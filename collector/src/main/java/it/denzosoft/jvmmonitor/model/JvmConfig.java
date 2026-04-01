package it.denzosoft.jvmmonitor.model;

/**
 * JVM configuration: startup parameters, system properties, classpath.
 */
public final class JvmConfig {

    private final String[] vmArguments;
    private final String[][] systemProperties;  /* {key, value} */
    private final String classpath;
    private final String javaVersion;
    private final String javaHome;
    private final String vmName;
    private final String vmVendor;
    private final long startTime;
    private final long uptime;
    private final int availableProcessors;
    private final String bootClassPath;

    public JvmConfig(String[] vmArguments, String[][] systemProperties, String classpath,
                     String javaVersion, String javaHome, String vmName, String vmVendor,
                     long startTime, long uptime, int availableProcessors, String bootClassPath) {
        this.vmArguments = vmArguments;
        this.systemProperties = systemProperties;
        this.classpath = classpath;
        this.javaVersion = javaVersion;
        this.javaHome = javaHome;
        this.vmName = vmName;
        this.vmVendor = vmVendor;
        this.startTime = startTime;
        this.uptime = uptime;
        this.availableProcessors = availableProcessors;
        this.bootClassPath = bootClassPath;
    }

    public String[] getVmArguments() { return vmArguments; }
    public String[][] getSystemProperties() { return systemProperties; }
    public String getClasspath() { return classpath; }
    public String getJavaVersion() { return javaVersion; }
    public String getJavaHome() { return javaHome; }
    public String getVmName() { return vmName; }
    public String getVmVendor() { return vmVendor; }
    public long getStartTime() { return startTime; }
    public long getUptime() { return uptime; }
    public int getAvailableProcessors() { return availableProcessors; }
    public String getBootClassPath() { return bootClassPath; }
}
