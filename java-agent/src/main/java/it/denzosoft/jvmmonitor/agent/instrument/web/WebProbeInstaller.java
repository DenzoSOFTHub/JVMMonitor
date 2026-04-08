package it.denzosoft.jvmmonitor.agent.instrument.web;

import it.denzosoft.jvmmonitor.agent.transport.MessageQueue;
import it.denzosoft.jvmmonitor.agent.util.AgentLogger;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.LoaderClassPath;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * Installs the web probe by instrumenting ServletContext.addServlet or
 * by directly instrumenting HttpServlet.service to:
 * 1. Handle /jvmmonitor-beacon POST (receive user action data)
 * 2. Inject the beacon script into HTML responses
 *
 * The approach: intercept the Servlet's service() method.
 * Before the method runs, check if this is a beacon request.
 * After the method runs, if the response is HTML, append the script.
 */
public class WebProbeInstaller {

    private static volatile boolean installed = false;
    private static volatile java.lang.instrument.ClassFileTransformer installedTransformer;
    private static volatile Instrumentation savedInstrumentation;

    public static void install(Instrumentation instrumentation, MessageQueue queue) {
        if (installed) return;
        installed = true;
        savedInstrumentation = instrumentation;

        UserActionTracker.init(queue);
        UserActionTracker.setEnabled(true);
        BeaconBridge.register();

        /* Cross-classloader beacon processing is handled via BeaconBridge
         * registered in System.properties. The injected Servlet code calls
         * the bridge via reflection — no need for bootstrap classloader access. */

        /* Instrument the Servlet container to intercept beacon requests.
         * Strategy: transform the DispatcherServlet (Spring), FrameworkServlet,
         * or any HttpServlet subclass that handles requests.
         * Also install a transformer for future class loads. */
        installedTransformer = new HttpServletTransformer();
        instrumentation.addTransformer(installedTransformer, true);

        /* Retransform already loaded Servlet classes */
        Class[] loaded = instrumentation.getAllLoadedClasses();
        String[] targets = {
            "javax.servlet.http.HttpServlet",
            "jakarta.servlet.http.HttpServlet",
            "org.springframework.web.servlet.FrameworkServlet",
            "org.springframework.web.servlet.DispatcherServlet",
            "org.apache.catalina.core.ApplicationFilterChain"
        };
        for (int i = 0; i < loaded.length; i++) {
            String name = loaded[i].getName();
            for (int t = 0; t < targets.length; t++) {
                if (targets[t].equals(name)) {
                    if (instrumentation.isModifiableClass(loaded[i])) {
                        try {
                            instrumentation.retransformClasses(loaded[i]);
                            AgentLogger.info("WebProbe: retransformed " + name);
                        } catch (Exception e) {
                            AgentLogger.debug("WebProbe: cannot retransform " + name + ": " + e.getMessage());
                        }
                    }
                }
            }
        }

        AgentLogger.info("WebProbe installed — beacon endpoint: /jvmmonitor-beacon");
    }

    public static void uninstall() {
        UserActionTracker.setEnabled(false);
        BeaconBridge.unregister();
        if (installedTransformer != null && savedInstrumentation != null) {
            savedInstrumentation.removeTransformer(installedTransformer);
            installedTransformer = null;
        }
        installed = false;
        AgentLogger.info("WebProbe uninstalled");
    }

    /**
     * Transformer that instruments HttpServlet.service() to:
     * 1. Intercept /jvmmonitor-beacon requests
     * 2. Inject beacon script into HTML responses
     */
    private static class HttpServletTransformer implements ClassFileTransformer {

        public byte[] transform(ClassLoader loader, String className, Class classBeingRedefined,
                                ProtectionDomain pd, byte[] classfileBuffer)
                throws IllegalClassFormatException {

            if (className == null) return null;
            /* Match ApplicationFilterChain.doFilter — this is the entry point
             * for every HTTP request in Tomcat/Jetty/Undertow.
             * Also match Spring's FrameworkServlet for broader coverage. */
            if (!"org/apache/catalina/core/ApplicationFilterChain".equals(className)
                && !"org/eclipse/jetty/servlet/ServletHandler$ChainEnd".equals(className)
                && !"io/undertow/servlet/handlers/FilterHandler$FilterChainImpl".equals(className)
                && !"javax/servlet/http/HttpServlet".equals(className)
                && !"jakarta/servlet/http/HttpServlet".equals(className)
                && !"org/springframework/web/servlet/FrameworkServlet".equals(className)) {
                return null;
            }

            CtClass cc = null;
            try {
                /* Create a fresh ClassPool per transform to prevent classloader leaks.
                 * A shared ClassPool accumulates LoaderClassPath entries, holding
                 * references to every classloader seen — preventing GC and causing
                 * metaspace exhaustion in long-running applications. */
                ClassPool pool = new ClassPool(false);
                pool.appendSystemPath();
                if (loader != null) {
                    pool.appendClassPath(new LoaderClassPath(loader));
                }

                cc = pool.makeClass(new java.io.ByteArrayInputStream(classfileBuffer));

                /* Find service(HttpServletRequest, HttpServletResponse) */
                CtMethod[] methods = cc.getDeclaredMethods();
                for (int i = 0; i < methods.length; i++) {
                    String mName = methods[i].getName();
                    if (!"service".equals(mName) && !"doService".equals(mName)
                        && !"doDispatch".equals(mName) && !"doFilter".equals(mName)
                        && !"internalDoFilter".equals(mName)) continue;
                    CtClass[] params = methods[i].getParameterTypes();
                    if (params.length < 2) continue;
                    /* Accept any parameter types that look like Servlet request/response */
                    String p0 = params[0].getName();
                    if (!p0.contains("Request")) continue;

                    /* Before: handle beacon requests.
                     * Detect javax vs jakarta namespace from the class being transformed. */
                    String servletPkg = className.startsWith("jakarta/") ? "jakarta" : "javax";
                    String before =
                        "{"
                        + "  String _juri = ((" + servletPkg + ".servlet.http.HttpServletRequest)$1).getRequestURI();"
                        + "  if (\"/jvmmonitor-beacon\".equals(_juri)) {"
                        + "    try {"
                        + "      StringBuilder _sb = new StringBuilder();"
                        + "      java.io.BufferedReader _br = $1.getReader();"
                        + "      char[] _buf = new char[1024];"
                        + "      int _n;"
                        + "      while ((_n = _br.read(_buf)) > 0) _sb.append(_buf, 0, _n);"
                        + "      String _json = _sb.toString();"
                        /* Use the shared queue via System property bridge */
                        + "      Object _q = System.getProperties().get(\"jvmmonitor.beacon.queue\");"
                        + "      if (_q != null) {"
                        + "        java.lang.reflect.Method _m = _q.getClass().getMethod("
                        + "          \"processBeacon\", new Class[]{String.class});"
                        + "        _m.invoke(_q, new Object[]{_json});"
                        + "      } else {"
                        + "        System.err.println(\"[JVMMonitor] Beacon bridge not registered\");"
                        + "      }"
                        + "    } catch (Exception _e) {"
                        + "      System.err.println(\"[JVMMonitor] Beacon error: \" + _e);"
                        + "    }"
                        + "    ((" + servletPkg + ".servlet.http.HttpServletResponse)$2).setStatus(204);"
                        + "    ((" + servletPkg + ".servlet.http.HttpServletResponse)$2).setHeader(\"Access-Control-Allow-Origin\", \"*\");"
                        + "    return;"
                        + "  }"
                        + "}";
                    methods[i].insertBefore(before);

                    AgentLogger.info("WebProbe: instrumented HttpServlet.service()");
                    break;
                }

                byte[] result = cc.toBytecode();
                cc.detach();
                return result;
            } catch (Exception e) {
                if (cc != null) {
                    try { cc.detach(); } catch (Exception ignored) { /* best effort */ }
                }
                AgentLogger.debug("WebProbe: transform failed: " + e.getMessage());
            }
            return null;
        }
    }
}
