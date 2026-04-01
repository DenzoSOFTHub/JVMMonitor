package it.denzosoft.jvmmonitor.gui;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.debug.DecompilerBridge;
import it.denzosoft.jvmmonitor.debug.DecompilerBridge.DecompiledSource;
import it.denzosoft.jvmmonitor.net.AgentConnection;
import it.denzosoft.jvmmonitor.protocol.ProtocolConstants;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.*;

/**
 * Source Viewer panel: request a class bytecode from the agent,
 * decompile it with DenzoSOFT Java Decompiler, display with syntax highlighting.
 */
public class SourceViewerPanel extends JPanel {

    private final JVMMonitorCollector collector;
    private final DecompilerBridge decompiler = new DecompilerBridge();
    private final JTextField classField;
    private final JTextPane sourcePane;
    private final JLabel statusLabel;
    private final DefaultListModel historyModel;

    /* Syntax highlight colors */
    private static final Color COL_KEYWORD = new Color(0, 0, 180);
    private static final Color COL_STRING = new Color(0, 128, 0);
    private static final Color COL_COMMENT = new Color(128, 128, 128);
    private static final Color COL_NUMBER = new Color(180, 0, 0);
    private static final Color COL_ANNOTATION = new Color(128, 128, 0);
    private static final Color COL_TYPE = new Color(0, 100, 100);
    private static final Color COL_LINE_NUM = new Color(160, 160, 160);

    private static final Set<String> KEYWORDS = new HashSet<String>(Arrays.asList(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new",
        "package", "private", "protected", "public", "return", "short", "static",
        "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
        "transient", "try", "void", "volatile", "while", "true", "false", "null"
    ));

    public SourceViewerPanel(JVMMonitorCollector collector) {
        this.collector = collector;
        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        /* Top bar */
        JPanel topBar = new JPanel(new BorderLayout(5, 3));
        JPanel inputBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));
        inputBar.add(new JLabel("Class name:"));
        classField = new JTextField("com.myapp.service.OrderService", 35);
        inputBar.add(classField);

        JButton decompileBtn = new JButton("Decompile");
        decompileBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { requestDecompile(); }
        });
        inputBar.add(decompileBtn);

        /* Enter key triggers decompile */
        classField.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { requestDecompile(); }
        });

        statusLabel = new JLabel("Enter a fully qualified class name and press Decompile");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 12f));
        topBar.add(inputBar, BorderLayout.NORTH);
        topBar.add(statusLabel, BorderLayout.SOUTH);
        add(topBar, BorderLayout.NORTH);

        /* Source pane */
        sourcePane = new JTextPane();
        sourcePane.setEditable(false);
        sourcePane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        sourcePane.setBackground(new Color(252, 252, 252));
        JScrollPane sourceScroll = new JScrollPane(sourcePane);
        sourceScroll.setBorder(BorderFactory.createTitledBorder("Decompiled Source"));

        /* History list */
        historyModel = new DefaultListModel();
        final JList historyList = new JList(historyModel);
        historyList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        historyList.addListSelectionListener(new javax.swing.event.ListSelectionListener() {
            public void valueChanged(javax.swing.event.ListSelectionEvent e) {
                if (!e.getValueIsAdjusting()) {
                    String selected = (String) historyList.getSelectedValue();
                    if (selected != null) {
                        classField.setText(selected);
                        requestDecompile();
                    }
                }
            }
        });
        JScrollPane historyScroll = new JScrollPane(historyList);
        historyScroll.setBorder(BorderFactory.createTitledBorder("History"));
        historyScroll.setPreferredSize(new Dimension(250, 0));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sourceScroll, historyScroll);
        split.setResizeWeight(0.8);
        add(split, BorderLayout.CENTER);

        /* Register class bytes listener */
        setupListener();
    }

    private void setupListener() {
        AgentConnection conn = collector.getConnection();
        if (conn != null) {
            conn.setClassBytesListener(new AgentConnection.ClassBytesListener() {
                public void onClassBytes(final String className, final byte[] bytes) {
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() { decompileAndDisplay(className, bytes); }
                    });
                }
            });
        }
    }

    public void refresh() {
        AgentConnection conn = collector.getConnection();
        if (conn != null && conn.isConnected()) {
            setupListener();
        }
    }

    private void requestDecompile() {
        String className = classField.getText().trim().replace('.', '/');
        if (className.isEmpty()) return;

        /* Check if already cached in decompiler */
        DecompiledSource cached = decompiler.decompile(className, null);
        if (cached != null && cached.sourceText != null && !cached.sourceText.contains("Decompilation failed")) {
            displayWithHighlighting(cached.sourceText);
            statusLabel.setText("Loaded from cache: " + className.replace('/', '.'));
            statusLabel.setForeground(new Color(0, 100, 0));
            addToHistory(className.replace('/', '.'));
            return;
        }

        /* Request bytecode from agent */
        AgentConnection conn = collector.getConnection();
        if (conn != null && conn.isConnected()) {
            try {
                conn.debugGetClassBytes(className);
                statusLabel.setText("Requesting bytecode for " + className.replace('/', '.') + "...");
                statusLabel.setForeground(Color.BLACK);
            } catch (Exception ex) {
                statusLabel.setText("Failed: " + ex.getMessage());
                statusLabel.setForeground(Color.RED);
            }
        } else {
            statusLabel.setText("Not connected to agent");
            statusLabel.setForeground(Color.RED);
        }
    }

    private void decompileAndDisplay(String className, byte[] bytes) {
        decompiler.clearCache();
        DecompiledSource source = decompiler.decompile(className, bytes);

        if (source != null && source.sourceText != null) {
            displayWithHighlighting(source.sourceText);
            statusLabel.setText(String.format("Decompiled: %s (%d bytes bytecode -> %d lines source)",
                    className.replace('/', '.'), bytes.length,
                    source.getSourceLines().length));
            statusLabel.setForeground(new Color(0, 100, 0));
        } else {
            sourcePane.setText("// Decompilation failed for " + className);
            statusLabel.setText("Decompilation failed");
            statusLabel.setForeground(Color.RED);
        }

        addToHistory(className.replace('/', '.'));
    }

    private void addToHistory(String className) {
        /* Add to history if not already there */
        for (int i = 0; i < historyModel.size(); i++) {
            if (className.equals(historyModel.get(i))) return;
        }
        historyModel.insertElementAt(className, 0);
        if (historyModel.size() > 30) historyModel.remove(historyModel.size() - 1);
    }

    private void displayWithHighlighting(String sourceText) {
        sourcePane.setText("");
        StyledDocument doc = sourcePane.getStyledDocument();

        /* Define styles */
        Style normal = sourcePane.addStyle("normal", null);
        StyleConstants.setFontFamily(normal, Font.MONOSPACED);
        StyleConstants.setFontSize(normal, 13);
        StyleConstants.setForeground(normal, Color.BLACK);

        Style keyword = sourcePane.addStyle("keyword", null);
        StyleConstants.setFontFamily(keyword, Font.MONOSPACED);
        StyleConstants.setFontSize(keyword, 13);
        StyleConstants.setForeground(keyword, COL_KEYWORD);
        StyleConstants.setBold(keyword, true);

        Style string = sourcePane.addStyle("string", null);
        StyleConstants.setFontFamily(string, Font.MONOSPACED);
        StyleConstants.setFontSize(string, 13);
        StyleConstants.setForeground(string, COL_STRING);

        Style comment = sourcePane.addStyle("comment", null);
        StyleConstants.setFontFamily(comment, Font.MONOSPACED);
        StyleConstants.setFontSize(comment, 13);
        StyleConstants.setForeground(comment, COL_COMMENT);
        StyleConstants.setItalic(comment, true);

        Style number = sourcePane.addStyle("number", null);
        StyleConstants.setFontFamily(number, Font.MONOSPACED);
        StyleConstants.setFontSize(number, 13);
        StyleConstants.setForeground(number, COL_NUMBER);

        Style annotation = sourcePane.addStyle("annotation", null);
        StyleConstants.setFontFamily(annotation, Font.MONOSPACED);
        StyleConstants.setFontSize(annotation, 13);
        StyleConstants.setForeground(annotation, COL_ANNOTATION);

        Style lineNum = sourcePane.addStyle("lineNum", null);
        StyleConstants.setFontFamily(lineNum, Font.MONOSPACED);
        StyleConstants.setFontSize(lineNum, 13);
        StyleConstants.setForeground(lineNum, COL_LINE_NUM);
        StyleConstants.setBackground(lineNum, new Color(245, 245, 245));

        String[] lines = sourceText.split("\n", -1);
        try {
            for (int ln = 0; ln < lines.length; ln++) {
                /* Line number */
                String lnStr = String.format("%4d  ", ln + 1);
                doc.insertString(doc.getLength(), lnStr, lineNum);

                /* Syntax highlight the line */
                highlightLine(doc, lines[ln], normal, keyword, string, comment, number, annotation);
                doc.insertString(doc.getLength(), "\n", normal);
            }
        } catch (BadLocationException e) {
            /* ignore */
        }
        sourcePane.setCaretPosition(0);
    }

    private void highlightLine(StyledDocument doc, String line,
                                Style normal, Style keyword, Style string,
                                Style comment, Style number, Style annotation)
            throws BadLocationException {

        /* Comment detection */
        String trimmed = line.trim();
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
            doc.insertString(doc.getLength(), line, comment);
            return;
        }

        /* Annotation */
        if (trimmed.startsWith("@")) {
            int space = trimmed.indexOf(' ');
            if (space < 0) space = trimmed.length();
            String anno = trimmed.substring(0, space);
            int leadingSpaces = line.indexOf(trimmed);
            if (leadingSpaces > 0) doc.insertString(doc.getLength(), line.substring(0, leadingSpaces), normal);
            doc.insertString(doc.getLength(), anno, annotation);
            if (space < trimmed.length()) {
                highlightLine(doc, trimmed.substring(space), normal, keyword, string, comment, number, annotation);
            }
            return;
        }

        /* Token-by-token */
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);

            /* String literal */
            if (c == '"') {
                int end = line.indexOf('"', i + 1);
                if (end < 0) end = line.length() - 1;
                doc.insertString(doc.getLength(), line.substring(i, end + 1), string);
                i = end + 1;
                continue;
            }

            /* Char literal */
            if (c == '\'') {
                int end = Math.min(i + 3, line.length());
                if (end < line.length() && line.charAt(end - 1) == '\'') {
                    doc.insertString(doc.getLength(), line.substring(i, end), string);
                    i = end;
                    continue;
                }
            }

            /* Word (keyword or identifier) */
            if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                while (i < line.length() && Character.isJavaIdentifierPart(line.charAt(i))) i++;
                String word = line.substring(start, i);
                if (KEYWORDS.contains(word)) {
                    doc.insertString(doc.getLength(), word, keyword);
                } else {
                    doc.insertString(doc.getLength(), word, normal);
                }
                continue;
            }

            /* Number */
            if (Character.isDigit(c)) {
                int start = i;
                while (i < line.length() && (Character.isDigit(line.charAt(i)) || line.charAt(i) == '.' ||
                       line.charAt(i) == 'x' || line.charAt(i) == 'X' || line.charAt(i) == 'L' ||
                       line.charAt(i) == 'f' || line.charAt(i) == 'F' ||
                       (line.charAt(i) >= 'a' && line.charAt(i) <= 'f') ||
                       (line.charAt(i) >= 'A' && line.charAt(i) <= 'F'))) i++;
                doc.insertString(doc.getLength(), line.substring(start, i), number);
                continue;
            }

            /* Other characters */
            doc.insertString(doc.getLength(), String.valueOf(c), normal);
            i++;
        }
    }
}
