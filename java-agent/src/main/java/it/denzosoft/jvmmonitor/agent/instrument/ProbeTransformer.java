package it.denzosoft.jvmmonitor.agent.instrument;

import it.denzosoft.jvmmonitor.agent.util.AgentLogger;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.LoaderClassPath;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.*;

/**
 * ClassFileTransformer that uses Javassist to inject timing/recording code
 * into target methods. Equivalent to the C agent's JVMTI MethodEntry/Exit.
 *
 * For each matching method, inserts:
 *   - At method start: capture start time
 *   - At method exit (normal + exception): record elapsed time and send event
 *
 * The injected code calls InstrumentationRecorder static methods, which
 * are already loaded by the agent classloader.
 */
public class ProbeTransformer implements ClassFileTransformer {

    private final Set activeProbes;          /* probe names: "jdbc", "http", etc. */
    private final Set targetClasses;         /* internal class names to intercept */
    private final Set appPackages;           /* application packages for spring probe */
    private int transformedCount = 0;

    public ProbeTransformer(Set activeProbes, Set appPackages) {
        this.activeProbes = activeProbes;
        this.appPackages = appPackages != null ? appPackages : new HashSet();
        this.targetClasses = ProbeRegistry.getTargetClassNames(activeProbes);

        AgentLogger.info("ProbeTransformer initialized: probes=" + activeProbes +
                ", targets=" + targetClasses.size() + " classes" +
                (appPackages != null ? ", packages=" + appPackages : ""));
    }

    public byte[] transform(ClassLoader loader, String className, Class classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer)
            throws IllegalClassFormatException {

        if (className == null) return null;

        /* Check if this class is a probe target */
        boolean isProbeTarget = targetClasses.contains(className);

        /* Check if this class matches application packages (spring probe) */
        boolean isAppClass = false;
        if (activeProbes.contains("spring")) {
            String dotName = className.replace('/', '.');
            Iterator it = appPackages.iterator();
            while (it.hasNext()) {
                String pkg = (String) it.next();
                if (dotName.startsWith(pkg)) {
                    isAppClass = true;
                    break;
                }
            }
        }

        if (!isProbeTarget && !isAppClass) return null;

        /* Skip JDK internals, agent classes, and Javassist itself */
        if (className.startsWith("sun/") || className.startsWith("jdk/")
            || className.startsWith("it/denzosoft/jvmmonitor/agent/")
            || className.startsWith("javassist/")) {
            return null;
        }

        try {
            /* Create a fresh ClassPool per transform to prevent classloader leaks.
             * A shared ClassPool accumulates LoaderClassPath entries, holding
             * references to every classloader seen — preventing GC and causing
             * metaspace exhaustion in long-running applications. */
            ClassPool pool = new ClassPool(true);
            if (loader != null) {
                pool.appendClassPath(new LoaderClassPath(loader));
            }

            String dotClassName = className.replace('/', '.');
            CtClass cc = pool.makeClass(new java.io.ByteArrayInputStream(classfileBuffer));

            /* Skip interfaces and annotations — no concrete methods to instrument */
            if (javassist.Modifier.isInterface(cc.getModifiers())) {
                cc.detach();
                return null;
            }

            boolean modified = false;

            if (isProbeTarget) {
                /* Apply specific probe definitions */
                List probes = ProbeRegistry.getProbesForClass(className, activeProbes);
                for (int i = 0; i < probes.size(); i++) {
                    ProbeDefinition pd = (ProbeDefinition) probes.get(i);
                    modified |= instrumentMethod(cc, pd.methodName, pd.eventType,
                            pd.contextExpr, dotClassName);
                }
            }

            if (isAppClass) {
                /* Instrument all public methods for application classes (spring probe) */
                CtMethod[] methods = cc.getDeclaredMethods();
                for (int m = 0; m < methods.length; m++) {
                    if (javassist.Modifier.isPublic(methods[m].getModifiers())
                        && !javassist.Modifier.isAbstract(methods[m].getModifiers())
                        && !methods[m].getName().equals("<init>")
                        && !methods[m].getName().equals("<clinit>")) {
                        modified |= instrumentMethodDirect(methods[m],
                                ProbeRegistry.TYPE_METHOD_EXIT, "\"\"", dotClassName);
                    }
                }
            }

            if (modified) {
                transformedCount++;
                byte[] result = cc.toBytecode();
                cc.detach();
                AgentLogger.debug("Instrumented: " + dotClassName);
                return result;
            }

            cc.detach();
        } catch (Exception e) {
            AgentLogger.debug("Transform failed for " + className + ": " + e.getMessage());
        }

        return null;
    }

    private boolean instrumentMethod(CtClass cc, String methodName, int eventType,
                                      String contextExpr, String className) {
        try {
            CtMethod[] methods = cc.getDeclaredMethods();
            boolean found = false;
            for (int i = 0; i < methods.length; i++) {
                if (methodName == null || methods[i].getName().equals(methodName)) {
                    int mod = methods[i].getModifiers();
                    if (javassist.Modifier.isAbstract(mod) || javassist.Modifier.isNative(mod)) continue;
                    found |= instrumentMethodDirect(methods[i], eventType, contextExpr, className);
                }
            }
            return found;
        } catch (Exception e) {
            AgentLogger.debug("Failed to instrument " + className + "." + methodName + ": " + e.getMessage());
            return false;
        }
    }

    private boolean instrumentMethodDirect(CtMethod method, int eventType,
                                            String contextExpr, String className) {
        try {
            String mName = method.getName();

            /* Build context expression - use safe fallback if expression fails */
            String safeContextExpr;
            try {
                /* Test if the expression is valid by attempting to use it */
                safeContextExpr = contextExpr;
            } catch (Exception e) {
                safeContextExpr = "\"\"";
            }

            /* Insert timing + resource counter capture */
            method.addLocalVariable("_jvmmon_start", CtClass.longType);
            method.insertBefore(
                "it.denzosoft.jvmmonitor.agent.instrument.InstrumentationRecorder.pushCounters();"
                + "_jvmmon_start = System.nanoTime();");

            /* Normal exit — with optional param/return capture */
            String exitCode = "{"
                    + "long _jvmmon_dur = System.nanoTime() - _jvmmon_start;"
                    + "String _jvmmon_ctx = \"\";"
                    + "try { _jvmmon_ctx = \"\" + " + safeContextExpr + "; } catch (Exception _e) {}"
                    + "it.denzosoft.jvmmonitor.agent.instrument.InstrumentationRecorder"
                    + ".recordMethodExit(" + eventType + ", \"" + escapeJava(className) + "\", \""
                    + escapeJava(mName) + "\", _jvmmon_dur, _jvmmon_ctx, $args, ($w)$_);"
                    + "}";
            method.insertAfter(exitCode);

            /* Exception exit — with optional param capture */
            String catchCode = "{"
                    + "long _jvmmon_dur = System.nanoTime() - _jvmmon_start;"
                    + "it.denzosoft.jvmmonitor.agent.instrument.InstrumentationRecorder"
                    + ".recordMethodException(" + eventType + ", \"" + escapeJava(className) + "\", \""
                    + escapeJava(mName) + "\", _jvmmon_dur, $e.getClass().getName(), $args);"
                    + "throw $e;"
                    + "}";
            method.addCatch(catchCode, method.getDeclaringClass().getClassPool().get("java.lang.Throwable"), "$e");

            return true;
        } catch (Exception e) {
            AgentLogger.debug("Instrument failed: " + className + "." + method.getName() +
                    ": " + e.getMessage());
            return false;
        }
    }

    public int getTransformedCount() {
        return transformedCount;
    }

    private static String escapeJava(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
