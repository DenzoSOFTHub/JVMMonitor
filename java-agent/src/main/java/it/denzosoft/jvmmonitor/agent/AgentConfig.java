package it.denzosoft.jvmmonitor.agent;

/**
 * Agent configuration parsed from the agent options string.
 * Format: key=value,key=value (comma or semicolon separated)
 *
 * Supported options:
 *   port=9090          TCP port to listen on
 *   interval=1000      Memory/thread polling interval (ms)
 *   sample_interval=50 CPU sample interval (ms)
 *   loglevel=info      Log level (error, info, debug)
 */
public class AgentConfig {

    private int port = 9090;
    /* Default 2000ms — collector GUI refreshes at 2s, polling faster is wasteful.
     * Override with interval=1000 if 1s granularity is needed. */
    private int monitorInterval = 2000;
    private int sampleInterval = 50;
    private int logLevel = 1; /* 0=error, 1=info, 2=debug */

    public AgentConfig(String options) {
        if (options == null || options.trim().length() == 0) return;

        String[] pairs = options.split("[,;]");
        for (int i = 0; i < pairs.length; i++) {
            String pair = pairs[i].trim();
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = pair.substring(0, eq).trim().toLowerCase();
            String val = pair.substring(eq + 1).trim();

            try {
                if ("port".equals(key)) {
                    port = Integer.parseInt(val);
                } else if ("interval".equals(key) || "monitor_interval".equals(key)) {
                    monitorInterval = Integer.parseInt(val);
                } else if ("sample_interval".equals(key)) {
                    sampleInterval = Integer.parseInt(val);
                } else if ("loglevel".equals(key)) {
                    if ("error".equals(val)) logLevel = 0;
                    else if ("info".equals(val)) logLevel = 1;
                    else if ("debug".equals(val)) logLevel = 2;
                }
            } catch (NumberFormatException e) {
                /* skip invalid values */
            }
        }
    }

    public int getPort() { return port; }
    public int getMonitorInterval() { return monitorInterval; }
    public int getSampleInterval() { return sampleInterval; }
    public int getLogLevel() { return logLevel; }
}
