package it.denzosoft.jvmmonitor.model;

public final class OsMetrics {

    private final long timestamp;
    private final int openFileDescriptors;
    private final long rssBytes;
    private final long vmSizeBytes;
    private final long voluntaryContextSwitches;
    private final long involuntaryContextSwitches;
    private final int tcpEstablished;
    private final int tcpCloseWait;
    private final int tcpTimeWait;
    private final int osThreadCount;

    public OsMetrics(long timestamp, int openFileDescriptors, long rssBytes, long vmSizeBytes,
                     long voluntaryContextSwitches, long involuntaryContextSwitches,
                     int tcpEstablished, int tcpCloseWait, int tcpTimeWait, int osThreadCount) {
        this.timestamp = timestamp;
        this.openFileDescriptors = openFileDescriptors;
        this.rssBytes = rssBytes;
        this.vmSizeBytes = vmSizeBytes;
        this.voluntaryContextSwitches = voluntaryContextSwitches;
        this.involuntaryContextSwitches = involuntaryContextSwitches;
        this.tcpEstablished = tcpEstablished;
        this.tcpCloseWait = tcpCloseWait;
        this.tcpTimeWait = tcpTimeWait;
        this.osThreadCount = osThreadCount;
    }

    public long getTimestamp() { return timestamp; }
    public int getOpenFileDescriptors() { return openFileDescriptors; }
    public long getRssBytes() { return rssBytes; }
    public long getVmSizeBytes() { return vmSizeBytes; }
    public long getVoluntaryContextSwitches() { return voluntaryContextSwitches; }
    public long getInvoluntaryContextSwitches() { return involuntaryContextSwitches; }
    public int getTcpEstablished() { return tcpEstablished; }
    public int getTcpCloseWait() { return tcpCloseWait; }
    public int getTcpTimeWait() { return tcpTimeWait; }
    public int getOsThreadCount() { return osThreadCount; }

    public double getRssMB() { return rssBytes / (1024.0 * 1024.0); }
    public double getVmSizeMB() { return vmSizeBytes / (1024.0 * 1024.0); }
}
