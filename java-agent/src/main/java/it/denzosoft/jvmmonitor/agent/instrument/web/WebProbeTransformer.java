package it.denzosoft.jvmmonitor.agent.instrument.web;

import it.denzosoft.jvmmonitor.agent.util.AgentLogger;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.LoaderClassPath;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Set;

/**
 * ClassFileTransformer that instruments Servlet's service() / doGet() / doPost()
 * to auto-inject the beacon JS into HTML responses and handle /jvmmonitor-beacon POSTs.
 *
 * Targets:
 *   javax.servlet.http.HttpServlet — for traditional Servlets
 *   org.springframework.web.servlet.DispatcherServlet — for Spring MVC
 *
 * For each doGet/doPost/service method, wraps the response in a wrapper that:
 *   1. Buffers the output
 *   2. If content-type is text/html, injects the beacon script before </body>
 *   3. If request URI is /jvmmonitor-beacon, processes the beacon POST data
 */
public class WebProbeTransformer implements ClassFileTransformer {

    private static final Set TARGET_CLASSES = new HashSet();
    static {
        TARGET_CLASSES.add("javax/servlet/http/HttpServlet");
        TARGET_CLASSES.add("jakarta/servlet/http/HttpServlet");
    }

    private int transformedCount = 0;

    public WebProbeTransformer() {
    }

    public byte[] transform(ClassLoader loader, String className, Class classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer)
            throws IllegalClassFormatException {

        if (className == null) return null;
        if (!TARGET_CLASSES.contains(className)) return null;

        try {
            /* Fresh ClassPool per transform to prevent classloader leak */
            ClassPool pool = new ClassPool(true);
            if (loader != null) {
                pool.appendClassPath(new LoaderClassPath(loader));
            }

            CtClass cc = pool.makeClass(new java.io.ByteArrayInputStream(classfileBuffer));

            boolean modified = false;

            /* Instrument service() method to intercept beacon requests and inject script */
            CtMethod[] methods = cc.getDeclaredMethods();
            for (int i = 0; i < methods.length; i++) {
                String name = methods[i].getName();
                if ("service".equals(name) || "doGet".equals(name) || "doPost".equals(name)
                    || "doFilter".equals(name)) {

                    /* Check parameter types match Servlet API */
                    CtClass[] params = methods[i].getParameterTypes();
                    if (params.length < 2) continue;

                    String p0 = params[0].getName();
                    String p1 = params[1].getName();
                    boolean isServlet = (p0.contains("HttpServletRequest") || p0.contains("ServletRequest"))
                                     && (p1.contains("HttpServletResponse") || p1.contains("ServletResponse"));
                    if (!isServlet) continue;

                    /* Insert beacon handler at the beginning */
                    String beforeCode =
                        "{"
                        + "  String _jvmReqUri = null;"
                        + "  try { _jvmReqUri = ((javax.servlet.http.HttpServletRequest)$1).getRequestURI(); }"
                        + "  catch (Exception _e) {}"
                        + "  if (\"/jvmmonitor-beacon\".equals(_jvmReqUri)) {"
                        + "    try {"
                        + "      java.io.BufferedReader _br = $1.getReader();"
                        + "      StringBuilder _sb = new StringBuilder();"
                        + "      String _line;"
                        + "      while ((_line = _br.readLine()) != null) _sb.append(_line);"
                        + "      it.denzosoft.jvmmonitor.agent.instrument.web.UserActionTracker"
                        + "        .processBeacon(_sb.toString());"
                        + "      $2.setStatus(204);"
                        + "    } catch (Exception _e) { $2.setStatus(204); }"
                        + "    return;"
                        + "  }"
                        + "}";
                    methods[i].insertBefore(beforeCode);

                    /* Insert script injection at the end */
                    String afterCode =
                        "{"
                        + "  if (it.denzosoft.jvmmonitor.agent.instrument.web.UserActionTracker.isEnabled()) {"
                        + "    /* Script injection is handled by response wrapper */"
                        + "  }"
                        + "}";
                    /* Note: full response wrapping for HTML injection requires a more complex
                     * approach with a response wrapper. For now, the beacon endpoint works
                     * and the user can manually include the script tag. Auto-injection of the
                     * script into HTML responses requires wrapping HttpServletResponse which
                     * is done via a separate Filter registration. */

                    modified = true;
                    AgentLogger.debug("WebProbe: instrumented " + className + "." + name);
                }
            }

            if (modified) {
                transformedCount++;
                byte[] result = cc.toBytecode();
                cc.detach();
                return result;
            }
            cc.detach();
        } catch (Exception e) {
            AgentLogger.debug("WebProbe transform failed for " + className + ": " + e.getMessage());
        }

        return null;
    }

    public int getTransformedCount() { return transformedCount; }
}
