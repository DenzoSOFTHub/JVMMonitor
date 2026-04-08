package it.denzosoft.jvmmonitor.agent.collector;

import it.denzosoft.jvmmonitor.agent.instrument.web.BeaconScript;
import it.denzosoft.jvmmonitor.agent.instrument.web.UserActionTracker;
import it.denzosoft.jvmmonitor.agent.instrument.web.WebProbeInstaller;
import it.denzosoft.jvmmonitor.agent.module.Module;
import it.denzosoft.jvmmonitor.agent.transport.MessageQueue;
import it.denzosoft.jvmmonitor.agent.util.AgentLogger;

import java.lang.instrument.Instrumentation;

/**
 * Web probe module: captures user actions in the browser.
 * Auto-injects a JavaScript beacon into HTML pages served by the application.
 * Captures: clicks, navigations, AJAX/fetch calls, JS errors, page load timing, form submits.
 *
 * Activation: enable this module from the collector GUI/CLI.
 * The injected script tag can also be manually added to pages:
 *   (available via BeaconScript.SCRIPT)
 *
 * The beacon sends POST to /jvmmonitor-beacon which is intercepted by
 * the instrumented HttpServlet.service() method.
 */
public class WebProbeCollector implements Module {

    private final MessageQueue queue;
    private final Instrumentation instrumentation;
    private volatile boolean active;

    public WebProbeCollector(MessageQueue queue, Instrumentation instrumentation) {
        this.queue = queue;
        this.instrumentation = instrumentation;
    }

    public String getName() { return "webprobe"; }
    public int getMaxLevel() { return 1; }
    public boolean isCore() { return false; }
    public boolean isActive() { return active; }

    public void activate(int level) {
        if (active) return;
        active = true;
        WebProbeInstaller.install(instrumentation, queue);
        AgentLogger.info("Web probe activated — user actions will be captured");
        AgentLogger.info("Add this script tag to your HTML pages for browser monitoring:");
        AgentLogger.info("  <script src=\"/jvmmonitor-beacon.js\"></script>");
        AgentLogger.info("Or include it automatically via a Servlet Filter in your web.xml");
    }

    public void deactivate() {
        active = false;
        WebProbeInstaller.uninstall();
        AgentLogger.info("Web probe deactivated");
    }

    /** Get the JavaScript snippet for manual inclusion. */
    public static String getBeaconScript() {
        return BeaconScript.SCRIPT;
    }
}
