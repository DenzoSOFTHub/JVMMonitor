package it.denzosoft.jvmmonitor.debug;

import it.denzosoft.javadecompiler.DenzoDecompiler;
import it.denzosoft.javadecompiler.api.loader.Loader;
import it.denzosoft.javadecompiler.api.printer.Printer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridge between JVMMonitor and DenzoSOFT Java Decompiler.
 * Decompiles raw class bytes into Java source with line number tracking.
 */
public class DecompilerBridge {

    private final DenzoDecompiler decompiler = new DenzoDecompiler();

    /** Cache of decompiled sources: className -> DecompiledSource. Capped at 100 entries. */
    private static final int MAX_CACHE_SIZE = 100;
    private final Map<String, DecompiledSource> cache = new LinkedHashMap<String, DecompiledSource>();

    /**
     * Decompile class bytes into Java source.
     * Returns a DecompiledSource with line-to-source mapping.
     */
    public DecompiledSource decompile(String internalName, final byte[] classBytes) {
        DecompiledSource cached = cache.get(internalName);
        if (cached != null) return cached;

        final SourceCollector collector = new SourceCollector();
        Loader loader = new Loader() {
            public boolean canLoad(String name) { return true; }
            public byte[] load(String name) { return classBytes; }
        };

        try {
            decompiler.decompile(loader, collector, internalName);
        } catch (Exception e) {
            /* Decompilation failed — return raw info */
            collector.sb.append("// Decompilation failed: ").append(e.getMessage()).append('\n');
            collector.sb.append("// Class: ").append(internalName).append('\n');
        }

        DecompiledSource result = new DecompiledSource(
                internalName, collector.sb.toString(), collector.lineMap);
        if (cache.size() >= MAX_CACHE_SIZE) {
            /* Evict oldest entry instead of clearing all */
            java.util.Iterator it = cache.keySet().iterator();
            if (it.hasNext()) { it.next(); it.remove(); }
        }
        cache.put(internalName, result);
        return result;
    }

    public void clearCache() {
        cache.clear();
    }

    /**
     * Result of decompilation: source text + line number mapping.
     */
    public static class DecompiledSource {
        public final String className;
        public final String sourceText;
        /** Maps bytecode line number -> source text line number (1-based) */
        public final Map<Integer, Integer> lineMap;

        public DecompiledSource(String className, String sourceText, Map<Integer, Integer> lineMap) {
            this.className = className;
            this.sourceText = sourceText;
            this.lineMap = lineMap;
        }

        /** Get source line numbers as array of lines. */
        public String[] getSourceLines() {
            return sourceText.split("\n", -1);
        }

        /** Find the source line for a given bytecode line number. */
        public int getSourceLineForBytecodeLineNumber(int bcLine) {
            Integer mapped = lineMap.get(Integer.valueOf(bcLine));
            return mapped != null ? mapped.intValue() : bcLine;
        }
    }

    /**
     * Printer implementation that collects source text and tracks line numbers.
     */
    private static class SourceCollector implements Printer {
        final StringBuilder sb = new StringBuilder();
        final Map<Integer, Integer> lineMap = new LinkedHashMap<Integer, Integer>();
        private int currentSourceLine = 0;
        private int indentLevel = 0;

        public void start(int maxLineNumber, int majorVersion, int minorVersion) {}
        public void end() {}

        public void printText(String text) { sb.append(text); }
        public void printNumericConstant(String constant) { sb.append(constant); }
        public void printStringConstant(String constant, String ownerInternalName) {
            sb.append('"').append(constant).append('"');
        }
        public void printKeyword(String keyword) { sb.append(keyword); }

        public void printDeclaration(int type, String internalTypeName, String name, String descriptor) {
            sb.append(name);
        }
        public void printReference(int type, String internalTypeName, String name, String descriptor, String ownerInternalName) {
            sb.append(name);
        }

        public void indent() { indentLevel++; }
        public void unindent() { indentLevel--; }

        public void startLine(int lineNumber) {
            currentSourceLine++;
            for (int i = 0; i < indentLevel; i++) sb.append("    ");
            if (lineNumber > 0) {
                lineMap.put(Integer.valueOf(lineNumber), Integer.valueOf(currentSourceLine));
            }
        }
        public void endLine() { sb.append('\n'); }
        public void extraLine(int count) {
            for (int i = 0; i < count; i++) {
                currentSourceLine++;
                sb.append('\n');
            }
        }

        public void startMarker(int type) {}
        public void endMarker(int type) {}
    }
}
