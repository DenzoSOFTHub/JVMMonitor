package it.denzosoft.jvmmonitor.gui;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.debug.DecompilerBridge;
import it.denzosoft.jvmmonitor.debug.DecompilerBridge.DecompiledSource;
import it.denzosoft.jvmmonitor.gui.chart.AlignedCellRenderer;
import it.denzosoft.jvmmonitor.model.BreakpointHit;
import it.denzosoft.jvmmonitor.net.AgentConnection;

import javax.swing.*;
import it.denzosoft.jvmmonitor.gui.chart.CsvExporter;
import javax.swing.table.AbstractTableModel;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;

/**
 * Remote debugger panel.
 * Left: source code viewer (decompiled) with breakpoint markers.
 * Right top: variable inspector.
 * Right bottom: thread list (suspended threads).
 * Top: breakpoint controls + step controls.
 */
public class DebuggerPanel extends JPanel {

    private final JVMMonitorCollector collector;
    private final DecompilerBridge decompiler = new DecompilerBridge();
    private final JLabel statusLabel;
    private final JTextField bpClassField;
    private final JTextField bpLineField;
    private final JTextField bpConditionField;
    private final JTextField watchExprField;
    private final DefaultListModel watchListModel;
    private final JTextPane sourcePane;
    private final VariableTableModel varModel;
    private final BreakpointTableModel bpModel;
    private final JButton resumeBtn, stepOverBtn, stepIntoBtn, stepOutBtn;
    private final DefaultListModel threadListModel;

    /* State */
    private BreakpointHit currentHit = null;
    private DecompiledSource currentSource = null;
    private final List<String[]> breakpoints = new ArrayList<String[]>(); /* {className, line, condition} */
    private boolean debuggerEnabled = false;
    private final JButton enableBtn;

    public DebuggerPanel(JVMMonitorCollector collector) {
        this.collector = collector;
        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        /* ── Top control bar ─────────────────────── */
        JPanel controlBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));

        enableBtn = new JButton("Enable Debugger");
        enableBtn.setBackground(new Color(50, 160, 50));
        enableBtn.setForeground(Color.WHITE);
        enableBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { toggleDebugger(); }
        });
        controlBar.add(enableBtn);
        controlBar.add(Box.createHorizontalStrut(10));

        controlBar.add(new JLabel("Class:"));
        bpClassField = new JTextField("com.myapp.service.OrderService", 25);
        controlBar.add(bpClassField);
        controlBar.add(new JLabel("Line:"));
        bpLineField = new JTextField("42", 5);
        controlBar.add(bpLineField);
        controlBar.add(new JLabel("Condition:"));
        bpConditionField = new JTextField("", 15);
        bpConditionField.setToolTipText("Optional: e.g., orderId > 100 (evaluated on agent side)");
        controlBar.add(bpConditionField);

        JButton addBpBtn = new JButton("Set Breakpoint");
        addBpBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { addBreakpoint(); }
        });
        controlBar.add(addBpBtn);

        JButton removeBpBtn = new JButton("Remove");
        removeBpBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { removeBreakpoint(); }
        });
        controlBar.add(removeBpBtn);

        controlBar.add(Box.createHorizontalStrut(20));

        resumeBtn = new JButton("Resume (F8)");
        resumeBtn.setEnabled(false);
        resumeBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { doResume(); }
        });
        stepOverBtn = new JButton("Step Over (F6)");
        stepOverBtn.setEnabled(false);
        stepOverBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { doStepOver(); }
        });
        stepIntoBtn = new JButton("Step Into (F5)");
        stepIntoBtn.setEnabled(false);
        stepIntoBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { doStepInto(); }
        });
        stepOutBtn = new JButton("Step Out (F7)");
        stepOutBtn.setEnabled(false);
        stepOutBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { doStepOut(); }
        });

        controlBar.add(resumeBtn);
        controlBar.add(stepOverBtn);
        controlBar.add(stepIntoBtn);
        controlBar.add(stepOutBtn);

        statusLabel = new JLabel("Set a breakpoint and wait for it to be hit");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 12f));

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(controlBar, BorderLayout.NORTH);
        topPanel.add(statusLabel, BorderLayout.SOUTH);
        add(topPanel, BorderLayout.NORTH);

        /* ── Left: source code viewer ────────────── */
        sourcePane = new JTextPane();
        sourcePane.setEditable(false);
        sourcePane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        sourcePane.setText("// No source loaded.\n// Set a breakpoint on a class to decompile and view its source.");
        JScrollPane sourceScroll = new JScrollPane(sourcePane);
        sourceScroll.setBorder(BorderFactory.createTitledBorder("Source Code (decompiled)"));
        sourceScroll.setPreferredSize(new Dimension(600, 0));

        /* ── Right: variables + breakpoints + threads ── */
        varModel = new VariableTableModel();
        JTable varTable = new JTable(varModel);
        varTable.setDefaultRenderer(Object.class, new AlignedCellRenderer());
        varTable.setRowHeight(18);
        CsvExporter.install(varTable);
        JScrollPane varScroll = new JScrollPane(varTable);
        varScroll.setBorder(BorderFactory.createTitledBorder("Variables (Watches)"));

        bpModel = new BreakpointTableModel();
        JTable bpTable = new JTable(bpModel);
        bpTable.setRowHeight(18);
        CsvExporter.install(bpTable);
        JScrollPane bpScroll = new JScrollPane(bpTable);
        bpScroll.setBorder(BorderFactory.createTitledBorder("Breakpoints"));

        threadListModel = new DefaultListModel();
        JList threadList = new JList(threadListModel);
        JScrollPane threadScroll = new JScrollPane(threadList);
        threadScroll.setBorder(BorderFactory.createTitledBorder("Suspended Threads"));

        /* Watch expressions */
        JPanel watchPanel = new JPanel(new BorderLayout(0, 3));
        watchPanel.setBorder(BorderFactory.createTitledBorder("Watch Expressions"));
        JPanel watchBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
        watchExprField = new JTextField(15);
        watchExprField.setToolTipText("Enter variable or expression to watch");
        JButton watchAddBtn = new JButton("+");
        watchAddBtn.setMargin(new Insets(0, 4, 0, 4));
        watchAddBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                String expr = watchExprField.getText().trim();
                if (!expr.isEmpty()) { watchListModel.addElement(expr); watchExprField.setText(""); }
            }
        });
        JButton watchRemoveBtn = new JButton("-");
        watchRemoveBtn.setMargin(new Insets(0, 4, 0, 4));
        watchBar.add(watchExprField);
        watchBar.add(watchAddBtn);
        watchBar.add(watchRemoveBtn);
        watchPanel.add(watchBar, BorderLayout.NORTH);
        watchListModel = new DefaultListModel();
        final JList watchList = new JList(watchListModel);
        watchRemoveBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                int idx = watchList.getSelectedIndex();
                if (idx >= 0) watchListModel.remove(idx);
            }
        });
        watchPanel.add(new JScrollPane(watchList), BorderLayout.CENTER);

        JPanel rightPanel = new JPanel(new GridLayout(4, 1, 0, 5));
        rightPanel.add(varScroll);
        rightPanel.add(watchPanel);
        rightPanel.add(bpScroll);
        rightPanel.add(threadScroll);
        rightPanel.setPreferredSize(new Dimension(400, 0));

        JSplitPane debugSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sourceScroll, rightPanel);
        debugSplit.setResizeWeight(0.65);

        /* Source viewer sub-tab */
        SourceViewerPanel sourceViewer = new SourceViewerPanel(collector);

        JTabbedPane mainTabs = new JTabbedPane();
        mainTabs.addTab("Debug", debugSplit);
        mainTabs.addTab("Source Viewer", sourceViewer);
        add(mainTabs, BorderLayout.CENTER);

        /* Register breakpoint listener */
        setupListeners();
    }

    private void setupListeners() {
        /* Will be called when a breakpoint is hit */
        AgentConnection conn = collector.getConnection();
        if (conn != null) {
            conn.setBreakpointListener(new AgentConnection.BreakpointListener() {
                public void onBreakpointHit(final BreakpointHit hit) {
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() { handleBreakpointHit(hit); }
                    });
                }
            });
            conn.setClassBytesListener(new AgentConnection.ClassBytesListener() {
                public void onClassBytes(final String className, final byte[] bytes) {
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() { handleClassBytes(className, bytes); }
                    });
                }
            });
        }
    }

    public void refresh() {
        /* Re-register listeners if connection changed */
        AgentConnection conn = collector.getConnection();
        if (conn != null && conn.isConnected()) {
            setupListeners();
        }
    }

    private void toggleDebugger() {
        debuggerEnabled = !debuggerEnabled;
        if (debuggerEnabled) {
            enableBtn.setText("Disable Debugger");
            enableBtn.setBackground(new Color(200, 50, 50));
            statusLabel.setText("Debugger ENABLED — set breakpoints and wait for hits");
            statusLabel.setForeground(new Color(0, 100, 0));
            setupListeners();
        } else {
            enableBtn.setText("Enable Debugger");
            enableBtn.setBackground(new Color(50, 160, 50));
            statusLabel.setText("Debugger disabled — all breakpoints removed");
            statusLabel.setForeground(Color.BLACK);

            /* Remove all breakpoints from agent */
            AgentConnection conn = collector.getConnection();
            if (conn != null && conn.isConnected()) {
                for (int i = 0; i < breakpoints.size(); i++) {
                    try {
                        String[] bp = breakpoints.get(i);
                        conn.debugRemoveBreakpoint(bp[0], Integer.parseInt(bp[1]));
                    } catch (Exception ex) { /* ignore */ }
                }
            }
            breakpoints.clear();
            bpModel.fireTableDataChanged();
            disableStepControls();
        }
    }

    private void addBreakpoint() {
        if (!debuggerEnabled) {
            statusLabel.setText("Enable the debugger first!");
            statusLabel.setForeground(Color.RED);
            return;
        }
        String cls = bpClassField.getText().trim().replace('.', '/');
        String lineStr = bpLineField.getText().trim();
        int line;
        try { line = Integer.parseInt(lineStr); } catch (NumberFormatException e) { return; }

        String condition = bpConditionField.getText().trim();
        breakpoints.add(new String[]{cls, String.valueOf(line), condition});
        bpModel.fireTableDataChanged();

        /* Send to agent */
        AgentConnection conn = collector.getConnection();
        if (conn != null && conn.isConnected()) {
            try {
                conn.debugSetBreakpoint(cls, line);
                conn.debugGetClassBytes(cls);
                statusLabel.setText("Breakpoint set at " + cls.replace('/', '.') + ":" + line + " — waiting for hit...");
                statusLabel.setForeground(new Color(0, 100, 0));
            } catch (Exception ex) {
                statusLabel.setText("Failed: " + ex.getMessage());
                statusLabel.setForeground(Color.RED);
            }
        }
    }

    private void removeBreakpoint() {
        String cls = bpClassField.getText().trim().replace('.', '/');
        String lineStr = bpLineField.getText().trim();

        for (int i = breakpoints.size() - 1; i >= 0; i--) {
            if (breakpoints.get(i)[0].equals(cls) && breakpoints.get(i)[1].equals(lineStr)) {
                breakpoints.remove(i);
                break;
            }
        }
        bpModel.fireTableDataChanged();

        AgentConnection conn = collector.getConnection();
        if (conn != null && conn.isConnected()) {
            try {
                conn.debugRemoveBreakpoint(cls, Integer.parseInt(lineStr));
            } catch (Exception ex) { /* ignore */ }
        }
    }

    private void handleBreakpointHit(BreakpointHit hit) {
        currentHit = hit;
        statusLabel.setText(String.format("STOPPED at %s.%s() line %d  [thread: %s]",
                hit.getDisplayClassName(), hit.getMethodName(), hit.getLineNumber(), hit.getThreadName()));
        statusLabel.setForeground(Color.RED);

        /* Enable step controls */
        resumeBtn.setEnabled(true);
        stepOverBtn.setEnabled(true);
        stepIntoBtn.setEnabled(true);
        stepOutBtn.setEnabled(true);

        /* Update variables */
        varModel.setData(hit.getVariables());

        /* Update threads */
        threadListModel.clear();
        threadListModel.addElement("[SUSPENDED] " + hit.getThreadName() + " (tid=" + hit.getThreadId() + ")");

        /* Decompile and show source */
        if (hit.getClassBytes() != null && hit.getClassBytes().length > 0) {
            handleClassBytes(hit.getClassName(), hit.getClassBytes());
        }

        /* Highlight the breakpoint line */
        if (currentSource != null) {
            highlightLine(hit.getLineNumber());
        }
    }

    private void handleClassBytes(String className, byte[] bytes) {
        currentSource = decompiler.decompile(className, bytes);
        displaySource(currentSource);
    }

    private void displaySource(DecompiledSource source) {
        sourcePane.setText("");
        StyledDocument doc = sourcePane.getStyledDocument();

        /* Styles */
        Style normal = sourcePane.addStyle("normal", null);
        StyleConstants.setFontFamily(normal, Font.MONOSPACED);
        StyleConstants.setFontSize(normal, 13);

        Style lineNumStyle = sourcePane.addStyle("lineNum", null);
        StyleConstants.setFontFamily(lineNumStyle, Font.MONOSPACED);
        StyleConstants.setFontSize(lineNumStyle, 13);
        StyleConstants.setForeground(lineNumStyle, Color.GRAY);

        Style bpLineStyle = sourcePane.addStyle("bpLine", null);
        StyleConstants.setFontFamily(bpLineStyle, Font.MONOSPACED);
        StyleConstants.setFontSize(bpLineStyle, 13);
        StyleConstants.setBackground(bpLineStyle, new Color(255, 220, 220));

        String[] lines = source.getSourceLines();
        try {
            for (int i = 0; i < lines.length; i++) {
                String lineNum = String.format("%4d ", i + 1);
                doc.insertString(doc.getLength(), lineNum, lineNumStyle);
                doc.insertString(doc.getLength(), lines[i] + "\n", normal);
            }
        } catch (BadLocationException e) {
            /* ignore */
        }
    }

    private void highlightLine(int bcLineNumber) {
        if (currentSource == null) return;
        int sourceLine = currentSource.getSourceLineForBytecodeLineNumber(bcLineNumber);
        String[] lines = currentSource.getSourceLines();

        /* Find the position of the target line */
        int pos = 0;
        for (int i = 0; i < sourceLine - 1 && i < lines.length; i++) {
            pos += 5 + lines[i].length() + 1; /* "NNNN " + line + "\n" */
        }

        StyledDocument doc = sourcePane.getStyledDocument();
        Style highlight = sourcePane.addStyle("highlight", null);
        StyleConstants.setBackground(highlight, new Color(255, 255, 150));
        StyleConstants.setFontFamily(highlight, Font.MONOSPACED);
        StyleConstants.setFontSize(highlight, 13);
        StyleConstants.setBold(highlight, true);

        if (sourceLine > 0 && sourceLine <= lines.length) {
            int lineLen = 5 + lines[sourceLine - 1].length();
            doc.setCharacterAttributes(pos, lineLen, highlight, false);
            /* Scroll to the line */
            sourcePane.setCaretPosition(Math.min(pos, doc.getLength()));
        }
    }

    private void doResume() {
        if (currentHit == null) return;
        AgentConnection conn = collector.getConnection();
        if (conn != null) {
            try { conn.debugResume(currentHit.getThreadId()); } catch (Exception e) { /* ignore */ }
        }
        disableStepControls();
        statusLabel.setText("Resumed — waiting for next breakpoint...");
        statusLabel.setForeground(new Color(0, 100, 0));
    }

    private void doStepOver() {
        if (currentHit == null) return;
        AgentConnection conn = collector.getConnection();
        if (conn != null) {
            try { conn.debugStepOver(currentHit.getThreadId()); } catch (Exception e) { /* ignore */ }
        }
        statusLabel.setText("Stepping over...");
    }

    private void doStepInto() {
        if (currentHit == null) return;
        AgentConnection conn = collector.getConnection();
        if (conn != null) {
            try { conn.debugStepInto(currentHit.getThreadId()); } catch (Exception e) { /* ignore */ }
        }
        statusLabel.setText("Stepping into...");
    }

    private void doStepOut() {
        if (currentHit == null) return;
        AgentConnection conn = collector.getConnection();
        if (conn != null) {
            try { conn.debugStepOut(currentHit.getThreadId()); } catch (Exception e) { /* ignore */ }
        }
        statusLabel.setText("Stepping out...");
    }

    private void disableStepControls() {
        resumeBtn.setEnabled(false);
        stepOverBtn.setEnabled(false);
        stepIntoBtn.setEnabled(false);
        stepOutBtn.setEnabled(false);
        currentHit = null;
        varModel.setData(null);
        threadListModel.clear();
    }

    /* ── Variable Table ──────────────────────────────── */

    private class VariableTableModel extends AbstractTableModel {
        private final String[] COLS = {"Name", "Type", "Value"};
        private BreakpointHit.DebugVariable[] data = new BreakpointHit.DebugVariable[0];

        public void setData(BreakpointHit.DebugVariable[] data) {
            this.data = data != null ? data : new BreakpointHit.DebugVariable[0];
            fireTableDataChanged();
        }

        public int getRowCount() { return data.length; }
        public int getColumnCount() { return COLS.length; }
        public String getColumnName(int c) { return COLS[c]; }

        public Object getValueAt(int row, int col) {
            BreakpointHit.DebugVariable v = data[row];
            switch (col) {
                case 0: return v.name;
                case 1: return v.type;
                case 2: return v.value;
                default: return "";
            }
        }
    }

    /* ── Breakpoint Table ────────────────────────────── */

    private class BreakpointTableModel extends AbstractTableModel {
        private final String[] COLS = {"Class", "Line", "Condition"};

        public int getRowCount() { return breakpoints.size(); }
        public int getColumnCount() { return COLS.length; }
        public String getColumnName(int c) { return COLS[c]; }

        public Object getValueAt(int row, int col) {
            String[] bp = breakpoints.get(row);
            switch (col) {
                case 0: return bp[0].replace('/', '.');
                case 1: return bp[1];
                case 2: return bp.length > 2 ? bp[2] : "";
                default: return "";
            }
        }
    }
}
