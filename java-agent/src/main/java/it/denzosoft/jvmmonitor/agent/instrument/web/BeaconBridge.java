package it.denzosoft.jvmmonitor.agent.instrument.web;

/**
 * Bridge object stored in System.properties to allow cross-classloader
 * beacon processing. The Servlet code (in webapp classloader) finds this
 * object via System.getProperties().get("jvmmonitor.beacon.queue")
 * and calls processBeacon(String) via reflection.
 *
 * This works because System.properties is a global Hashtable accessible
 * from any classloader.
 */
public class BeaconBridge {

    private static final String PROPERTY_KEY = "jvmmonitor.beacon.queue";

    /** Register the bridge in System.properties. */
    public static void register() {
        System.getProperties().put(PROPERTY_KEY, new BeaconBridge());
    }

    /** Unregister the bridge. */
    public static void unregister() {
        System.getProperties().remove(PROPERTY_KEY);
    }

    /**
     * Called via reflection from the injected Servlet code.
     * This method is public so it can be found via getMethod().
     * @param json the beacon JSON payload
     */
    public void processBeacon(String json) {
        UserActionTracker.processBeacon(json);
    }
}
