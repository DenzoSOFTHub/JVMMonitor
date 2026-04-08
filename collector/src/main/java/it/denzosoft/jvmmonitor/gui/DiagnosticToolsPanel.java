package it.denzosoft.jvmmonitor.gui;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.gui.chart.AlignedCellRenderer;
import it.denzosoft.jvmmonitor.model.*;
import it.denzosoft.jvmmonitor.net.AgentConnection;
import it.denzosoft.jvmmonitor.protocol.ProtocolConstants;

import javax.swing.*;
import it.denzosoft.jvmmonitor.gui.chart.CsvExporter;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/**
 * Diagnostic Tools panel with sub-tabs:
 * - Field Watch: monitor field access/modification
 * - Thread Dump: full stack dump with save to file
 * - Deadlock Detection: find and display deadlocks
 * - GC Root Analysis: trace reference chains
 * - JVM Configuration: startup params, properties
 * - JMX MBean Browser: navigate MBeans
 */
public class DiagnosticToolsPanel extends JPanel {

    private final JVMMonitorCollector collector;

    /* Field Watch */
    private final JTextField fwClassField, fwFieldField;
    private final FieldWatchTableModel fwModel;
    private final List<FieldWatchEvent> fwEvents = Collections.synchronizedList(new ArrayList<FieldWatchEvent>());

    /* Thread Dump */
    private final JTextArea threadDumpArea;

    /* Deadlock */
    private final JTextArea deadlockArea;

    /* GC Root */
    private final JTextField gcRootClassField, gcRootFieldField;
    private final JTextArea gcRootArea;

    /* JVM Config */
    private final JTextArea jvmConfigArea;

    /* JMX Browser */

    /* New tools */
    private JTextArea monitorMapArea;
    private JTextArea objectLifetimeArea;
    private JTextArea hotSwapArea;

    public DiagnosticToolsPanel(JVMMonitorCollector collector) {
        this.collector = collector;
        setLayout(new BorderLayout());

        JTabbedPane tabs = new JTabbedPane();

        /* ── Field Watch ─────────────────────────── */
        JPanel fwPanel = new JPanel(new BorderLayout(5, 5));
        JPanel fwControl = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));
        fwControl.add(new JLabel("Class:"));
        fwClassField = new JTextField("com.myapp.service.OrderService", 25);
        fwControl.add(fwClassField);
        fwControl.add(new JLabel("Field:"));
        fwFieldField = new JTextField("orderCount", 15);
        fwControl.add(fwFieldField);
        JButton fwAddBtn = new JButton("Watch");
        fwAddBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { addFieldWatch(); }
        });
        fwControl.add(fwAddBtn);
        JButton fwRemoveBtn = new JButton("Remove");
        fwRemoveBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { removeFieldWatch(); }
        });
        fwControl.add(fwRemoveBtn);
        JButton fwClearBtn = new JButton("Clear Events");
        fwClearBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { fwEvents.clear(); fwModel.fireTableDataChanged(); }
        });
        fwControl.add(fwClearBtn);
        fwPanel.add(fwControl, BorderLayout.NORTH);

        fwModel = new FieldWatchTableModel();
        JTable fwTable = new JTable(fwModel);
        fwTable.setDefaultRenderer(Object.class, new FieldWatchCellRenderer());
        fwTable.setAutoCreateRowSorter(true);
        fwTable.setRowHeight(18);
        CsvExporter.install(fwTable);
        fwPanel.add(new JScrollPane(fwTable), BorderLayout.CENTER);
        tabs.addTab("Field Watch", fwPanel);

        /* ── Thread Dump ─────────────────────────── */
        JPanel tdPanel = new JPanel(new BorderLayout(5, 5));
        JPanel tdControl = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));
        JButton tdTakeBtn = new JButton("Take Thread Dump");
        tdTakeBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { takeThreadDump(); }
        });
        JButton tdSaveBtn = new JButton("Save to File");
        tdSaveBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { saveThreadDump(); }
        });
        tdControl.add(tdTakeBtn);
        tdControl.add(tdSaveBtn);
        tdPanel.add(tdControl, BorderLayout.NORTH);
        threadDumpArea = new JTextArea();
        threadDumpArea.setEditable(false);
        threadDumpArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        threadDumpArea.setText("Press 'Take Thread Dump' to capture all thread stacks.");
        tdPanel.add(new JScrollPane(threadDumpArea), BorderLayout.CENTER);
        tabs.addTab("Thread Dump", tdPanel);

        /* ── Deadlock Detection ──────────────────── */
        JPanel dlPanel = new JPanel(new BorderLayout(5, 5));
        JPanel dlControl = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));
        JButton dlDetectBtn = new JButton("Detect Deadlocks");
        dlDetectBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { detectDeadlocks(); }
        });
        dlControl.add(dlDetectBtn);
        dlPanel.add(dlControl, BorderLayout.NORTH);
        deadlockArea = new JTextArea();
        deadlockArea.setEditable(false);
        deadlockArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        deadlockArea.setText("Press 'Detect Deadlocks' to scan for deadlocked threads.");
        dlPanel.add(new JScrollPane(deadlockArea), BorderLayout.CENTER);
        tabs.addTab("Deadlock Detection", dlPanel);

        /* ── GC Root Analysis ────────────────────── */
        JPanel gcPanel = new JPanel(new BorderLayout(5, 5));
        JPanel gcControl = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));
        gcControl.add(new JLabel("Class:"));
        gcRootClassField = new JTextField("com.myapp.model.Order", 25);
        gcControl.add(gcRootClassField);
        gcControl.add(new JLabel("Field:"));
        gcRootFieldField = new JTextField("", 15);
        gcControl.add(gcRootFieldField);
        JButton gcAnalyzeBtn = new JButton("Analyze GC Roots");
        gcAnalyzeBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { analyzeGcRoots(); }
        });
        JButton gcForceBtn = new JButton("Force GC First");
        gcForceBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { forceGc(); }
        });
        gcControl.add(gcAnalyzeBtn);
        gcControl.add(gcForceBtn);
        gcPanel.add(gcControl, BorderLayout.NORTH);
        gcRootArea = new JTextArea();
        gcRootArea.setEditable(false);
        gcRootArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        gcRootArea.setText("Enter a class name and press 'Analyze GC Roots' to trace reference chains.\n" +
                "Use 'Force GC First' to trigger a GC before analysis for accurate results.");
        gcPanel.add(new JScrollPane(gcRootArea), BorderLayout.CENTER);
        tabs.addTab("GC Root Analysis", gcPanel);

        /* ── JVM Configuration ───────────────────── */
        JPanel cfgPanel = new JPanel(new BorderLayout(5, 5));
        JPanel cfgControl = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));
        JButton cfgRefreshBtn = new JButton("Load JVM Configuration");
        cfgRefreshBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { loadJvmConfig(); }
        });
        cfgControl.add(cfgRefreshBtn);
        cfgPanel.add(cfgControl, BorderLayout.NORTH);
        jvmConfigArea = new JTextArea();
        jvmConfigArea.setEditable(false);
        jvmConfigArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        jvmConfigArea.setText("Press 'Load JVM Configuration' to fetch startup parameters and properties.");
        cfgPanel.add(new JScrollPane(jvmConfigArea), BorderLayout.CENTER);
        tabs.addTab("JVM Config", cfgPanel);

        /* JMX Browser moved to its own top-level tab (JmxBrowserPanel). */

        /* ── Heap Dump ───────────────────────────── */
        JPanel hdPanel = new JPanel(new BorderLayout(5, 5));
        JPanel hdControl = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));
        JButton hdTakeBtn = new JButton("Take Heap Dump");
        hdTakeBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { takeHeapDump(); }
        });
        hdControl.add(hdTakeBtn);
        hdControl.add(new JLabel("(Saved to agent host filesystem)"));
        hdPanel.add(hdControl, BorderLayout.NORTH);
        JTextArea hdArea = new JTextArea();
        hdArea.setEditable(false);
        hdArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        hdArea.setText("Press 'Take Heap Dump' to trigger a heap dump on the agent side.\n" +
                "The .hprof file is saved on the target JVM's filesystem.\n" +
                "Use a tool like Eclipse MAT or VisualVM to analyze it.");
        hdPanel.add(new JScrollPane(hdArea), BorderLayout.CENTER);
        tabs.addTab("Heap Dump", hdPanel);

        /* ── Thread Control ──────────────────────── */
        JPanel tcPanel = new JPanel(new BorderLayout(5, 5));
        JPanel tcControl = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));
        final JTextField tcThreadField = new JTextField("", 10);
        tcControl.add(new JLabel("Thread ID:"));
        tcControl.add(tcThreadField);
        JButton tcSuspendBtn = new JButton("Suspend");
        tcSuspendBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                sendThreadCmd(ProtocolConstants.DIAG_CMD_SUSPEND_THREAD, tcThreadField.getText().trim());
            }
        });
        JButton tcResumeBtn = new JButton("Resume");
        tcResumeBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                sendThreadCmd(ProtocolConstants.DIAG_CMD_RESUME_THREAD, tcThreadField.getText().trim());
            }
        });
        JButton tcStopBtn = new JButton("Stop (Kill)");
        tcStopBtn.setForeground(Color.RED);
        tcStopBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                int r = JOptionPane.showConfirmDialog(DiagnosticToolsPanel.this,
                        "Force-stop thread " + tcThreadField.getText() + "? This throws ThreadDeath.",
                        "Confirm", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (r == JOptionPane.YES_OPTION)
                    sendThreadCmd(ProtocolConstants.DIAG_CMD_STOP_THREAD, tcThreadField.getText().trim());
            }
        });
        tcControl.add(tcSuspendBtn);
        tcControl.add(tcResumeBtn);
        tcControl.add(tcStopBtn);
        tcPanel.add(tcControl, BorderLayout.NORTH);
        JTextArea tcInfo = new JTextArea("Enter a thread ID and use Suspend/Resume/Stop.\n" +
                "Suspend: freezes the thread. Resume: unfreezes it.\n" +
                "Stop: force-throws ThreadDeath in the thread (use with caution).\n\n" +
                "Get thread IDs from the Threads tab or Thread Dump.");
        tcInfo.setEditable(false);
        tcInfo.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        tcPanel.add(new JScrollPane(tcInfo), BorderLayout.CENTER);
        tabs.addTab("Thread Control", tcPanel);

        /* ── Monitor Ownership Map ───────────────── */
        JPanel mmPanel = new JPanel(new BorderLayout(5, 5));
        JPanel mmControl = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));
        JButton mmRefreshBtn = new JButton("Capture Monitor Map");
        final JTextArea mmArea = new JTextArea();
        mmArea.setEditable(false);
        mmArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        mmArea.setText("Press 'Capture Monitor Map' to see which threads hold which monitors.");
        mmRefreshBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                AgentConnection conn = collector.getConnection();
                if (conn != null && conn.isConnected()) {
                    try {
                        conn.sendCommand(ProtocolConstants.DIAG_CMD_GET_MONITOR_MAP, new byte[0]);
                        mmArea.setText("Capturing monitor ownership...");
                    } catch (Exception ex) { mmArea.setText("Failed: " + ex.getMessage()); }
                }
            }
        });
        mmControl.add(mmRefreshBtn);
        mmPanel.add(mmControl, BorderLayout.NORTH);
        mmPanel.add(new JScrollPane(mmArea), BorderLayout.CENTER);
        tabs.addTab("Monitor Map", mmPanel);
        this.monitorMapArea = mmArea;

        /* ── Object Lifetime Profiler ────────────── */
        JPanel olPanel = new JPanel(new BorderLayout(5, 5));
        JPanel olControl = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));
        JButton olStartBtn = new JButton("Start Tracking");
        olStartBtn.setBackground(new Color(50, 160, 50));
        olStartBtn.setForeground(Color.WHITE);
        JButton olStopBtn = new JButton("Stop & Analyze");
        olStopBtn.setBackground(new Color(200, 50, 50));
        olStopBtn.setForeground(Color.WHITE);
        olStopBtn.setEnabled(false);
        final JTextArea olArea = new JTextArea();
        olArea.setEditable(false);
        olArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        olArea.setText("Start tracking to tag new objects and measure their lifetime.\n" +
                "When you stop, the distribution shows:\n" +
                "- How many objects died within 1 GC cycle (short-lived, eden)\n" +
                "- How many survived to old gen (long-lived)\n" +
                "- How many were never freed (leak candidates)");
        olStartBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                AgentConnection conn = collector.getConnection();
                if (conn != null && conn.isConnected()) {
                    try {
                        conn.sendCommand(ProtocolConstants.DIAG_CMD_START_OBJ_TRACKING, new byte[0]);
                        olArea.setText("Tracking object lifetimes...");
                        olStopBtn.setEnabled(true);
                        olStartBtn.setEnabled(false);
                    } catch (Exception ex) { olArea.setText("Failed: " + ex.getMessage()); }
                }
            }
        });
        olStopBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                AgentConnection conn = collector.getConnection();
                if (conn != null && conn.isConnected()) {
                    try {
                        conn.sendCommand(ProtocolConstants.DIAG_CMD_STOP_OBJ_TRACKING, new byte[0]);
                        olArea.setText("Analyzing lifetime distribution...");
                        olStartBtn.setEnabled(true);
                        olStopBtn.setEnabled(false);
                    } catch (Exception ex) { olArea.setText("Failed: " + ex.getMessage()); }
                }
            }
        });
        olControl.add(olStartBtn);
        olControl.add(olStopBtn);
        olPanel.add(olControl, BorderLayout.NORTH);
        olPanel.add(new JScrollPane(olArea), BorderLayout.CENTER);
        tabs.addTab("Object Lifetime", olPanel);
        this.objectLifetimeArea = olArea;

        /* ── Hot Swap / Live Code Patching ────────── */
        JPanel hsPanel = new JPanel(new BorderLayout(5, 5));
        JPanel hsControl = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));
        hsControl.add(new JLabel("Class (internal name):"));
        final JTextField hsClassField = new JTextField("com/myapp/service/OrderService", 25);
        hsControl.add(hsClassField);
        JButton hsLoadBtn = new JButton("Load .class File");
        final JTextArea hsArea = new JTextArea();
        hsArea.setEditable(false);
        hsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        hsArea.setText("Hot Swap: replace a class at runtime without restart.\n\n" +
                "1. Enter the fully qualified class name\n" +
                "2. Click 'Load .class File' to select the new compiled class\n" +
                "3. The agent uses JVMTI RedefineClasses to replace it\n\n" +
                "Limitations:\n" +
                "- Cannot change class schema (add/remove fields or methods)\n" +
                "- Cannot change inheritance\n" +
                "- Method bodies can be changed freely");
        hsLoadBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                JFileChooser fc = new JFileChooser();
                fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Class files", "class"));
                if (fc.showOpenDialog(DiagnosticToolsPanel.this) == JFileChooser.APPROVE_OPTION) {
                    try {
                        java.io.File f = fc.getSelectedFile();
                        byte[] classBytes = new byte[(int) f.length()];
                        java.io.FileInputStream fis = new java.io.FileInputStream(f);
                        fis.read(classBytes);
                        fis.close();
                        sendHotSwap(hsClassField.getText().trim(), classBytes);
                        hsArea.setText("Sent " + classBytes.length + " bytes for hot swap of " +
                                hsClassField.getText() + "...\nWaiting for agent confirmation.");
                    } catch (Exception ex) {
                        hsArea.setText("Failed to read file: " + ex.getMessage());
                    }
                }
            }
        });
        hsControl.add(hsLoadBtn);
        hsPanel.add(hsControl, BorderLayout.NORTH);
        hsPanel.add(new JScrollPane(hsArea), BorderLayout.CENTER);
        tabs.addTab("Hot Swap", hsPanel);
        this.hotSwapArea = hsArea;

        /* Agent Modules (moved from separate tab) */
        ModulePanel modulePanel = new ModulePanel(collector);
        tabs.addTab("Agent Modules", modulePanel);

        /* Auto-Diagnostics (moved from separate tab) */
        DiagnosticsPanel diagnosticsPanel = new DiagnosticsPanel(collector);
        tabs.addTab("Auto-Diagnostics", diagnosticsPanel);

        /* ── Alarm Thresholds Configuration ──────── */
        tabs.addTab("Alarm Config", createAlarmConfigPanel());

        /* ── Session (Save / Load / Export HTML) ──── */
        JPanel sessionPanel = new JPanel(new BorderLayout(5, 5));
        JPanel sessionControl = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));
        final JTextArea sessionArea = new JTextArea();
        sessionArea.setEditable(false);
        sessionArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        sessionArea.setText("Session Management\n" +
                "==================\n\n" +
                "Save Session:   Save all collected data to a compressed binary file (.jvmsession.gz)\n" +
                "                for later analysis. Includes memory, GC, threads, exceptions,\n" +
                "                CPU, network, locks, instrumentation, and all other metrics.\n\n" +
                "Load Session:   Load a previously saved session file. All charts and tables\n" +
                "                will be populated with the saved data for replay.\n\n" +
                "Export HTML:    Export a human-readable HTML report of the current session.\n");

        JButton saveBtn = new JButton("Save Session");
        saveBtn.setToolTipText("Save session to compressed binary file (.jvmsession.gz)");
        saveBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                saveSession(sessionArea);
            }
        });

        JButton loadBtn = new JButton("Load Session");
        loadBtn.setToolTipText("Load a previously saved session for replay");
        loadBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                loadSession(sessionArea);
            }
        });

        JButton exportBtn = new JButton("Export HTML Report");
        exportBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                exportSession(sessionArea);
            }
        });

        sessionControl.add(saveBtn);
        sessionControl.add(loadBtn);
        sessionControl.add(exportBtn);
        sessionPanel.add(sessionControl, BorderLayout.NORTH);
        sessionPanel.add(new JScrollPane(sessionArea), BorderLayout.CENTER);
        tabs.addTab("Session", sessionPanel);

        add(tabs, BorderLayout.CENTER);
    }

    private JPanel createAlarmConfigPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));

        /* Table model for threshold editing */
        final it.denzosoft.jvmmonitor.analysis.AlarmThresholds thresholds = collector.getThresholds();
        final java.util.Map<String, String> thresholdMap = thresholds.toMap();
        final java.util.List<String> keys = new java.util.ArrayList<String>(thresholdMap.keySet());

        final javax.swing.table.AbstractTableModel model = new javax.swing.table.AbstractTableModel() {
            private final String[] COLS = {"Parameter", "Value", "Description"};
            public int getRowCount() { return keys.size(); }
            public int getColumnCount() { return 3; }
            public String getColumnName(int col) { return COLS[col]; }
            public boolean isCellEditable(int row, int col) { return col == 1; }
            public Object getValueAt(int row, int col) {
                String key = keys.get(row);
                if (col == 0) return key;
                if (col == 1) return thresholdMap.get(key);
                return getDescription(key);
            }
            public void setValueAt(Object val, int row, int col) {
                if (col == 1) {
                    String key = keys.get(row);
                    thresholdMap.put(key, val.toString());
                    fireTableCellUpdated(row, col);
                }
            }
        };

        final JTable table = new JTable(model);
        table.setDefaultRenderer(Object.class, new AlignedCellRenderer());
        table.getColumnModel().getColumn(0).setPreferredWidth(200);
        table.getColumnModel().getColumn(1).setPreferredWidth(80);
        table.getColumnModel().getColumn(2).setPreferredWidth(400);
        table.setRowHeight(20);
        CsvExporter.install(table);

        /* Buttons */
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));

        JButton applyBtn = new JButton("Apply");
        applyBtn.setToolTipText("Apply threshold changes to the running engine");
        applyBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                /* Write values back to thresholds object */
                for (int i = 0; i < keys.size(); i++) {
                    String key = keys.get(i);
                    String val = thresholdMap.get(key);
                    /* Use save/load round-trip: write to temp, reload */
                    try {
                        File tmp = File.createTempFile("jvmmon-thresh", ".tmp");
                        thresholds.save(tmp);
                        /* Re-read the map into the file with updated values */
                        BufferedWriter w = new BufferedWriter(new FileWriter(tmp));
                        for (int j = 0; j < keys.size(); j++) {
                            w.write(keys.get(j) + "=" + thresholdMap.get(keys.get(j)) + "\n");
                        }
                        w.close();
                        thresholds.load(tmp);
                        tmp.delete();
                        break;
                    } catch (Exception ex) {
                        System.err.println("[JVMMonitor] Failed to apply thresholds: " + ex);
                    }
                }
                JOptionPane.showMessageDialog(table, "Thresholds applied.");
            }
        });

        JButton saveBtn = new JButton("Save to File");
        saveBtn.setToolTipText("Save thresholds to a .thresholds file");
        saveBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                JFileChooser fc = new JFileChooser();
                fc.setSelectedFile(new File("jvmmonitor.thresholds"));
                if (fc.showSaveDialog(table) != JFileChooser.APPROVE_OPTION) return;
                try {
                    /* Apply current values first */
                    File tmp = File.createTempFile("jvmmon-thresh", ".tmp");
                    BufferedWriter w = new BufferedWriter(new FileWriter(tmp));
                    for (int j = 0; j < keys.size(); j++) {
                        w.write(keys.get(j) + "=" + thresholdMap.get(keys.get(j)) + "\n");
                    }
                    w.close();
                    thresholds.load(tmp);
                    tmp.delete();
                    thresholds.save(fc.getSelectedFile());
                    JOptionPane.showMessageDialog(table,
                            "Thresholds saved to " + fc.getSelectedFile().getName());
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(table, "Save failed: " + ex.getMessage());
                }
            }
        });

        JButton loadBtn = new JButton("Load from File");
        loadBtn.setToolTipText("Load thresholds from a .thresholds file");
        loadBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                JFileChooser fc = new JFileChooser();
                fc.setFileFilter(new javax.swing.filechooser.FileFilter() {
                    public boolean accept(File f) {
                        return f.isDirectory() || f.getName().endsWith(".thresholds");
                    }
                    public String getDescription() { return "Threshold files (*.thresholds)"; }
                });
                if (fc.showOpenDialog(table) != JFileChooser.APPROVE_OPTION) return;
                try {
                    thresholds.load(fc.getSelectedFile());
                    /* Refresh table */
                    java.util.Map<String, String> newMap = thresholds.toMap();
                    for (java.util.Map.Entry<String, String> entry : newMap.entrySet()) {
                        thresholdMap.put(entry.getKey(), entry.getValue());
                    }
                    model.fireTableDataChanged();
                    JOptionPane.showMessageDialog(table,
                            "Thresholds loaded from " + fc.getSelectedFile().getName());
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(table, "Load failed: " + ex.getMessage());
                }
            }
        });

        JButton resetBtn = new JButton("Reset Defaults");
        resetBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                it.denzosoft.jvmmonitor.analysis.AlarmThresholds defaults =
                        new it.denzosoft.jvmmonitor.analysis.AlarmThresholds();
                java.util.Map<String, String> defMap = defaults.toMap();
                for (java.util.Map.Entry<String, String> entry : defMap.entrySet()) {
                    thresholdMap.put(entry.getKey(), entry.getValue());
                }
                try {
                    File tmp = File.createTempFile("jvmmon-thresh", ".tmp");
                    defaults.save(tmp);
                    thresholds.load(tmp);
                    tmp.delete();
                } catch (Exception ex) { /* ignore */ }
                model.fireTableDataChanged();
            }
        });

        btnPanel.add(applyBtn);
        btnPanel.add(saveBtn);
        btnPanel.add(loadBtn);
        btnPanel.add(resetBtn);

        panel.add(btnPanel, BorderLayout.NORTH);
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        return panel;
    }

    private static String getDescription(String key) {
        if ("memory.liveSetMinFullGcs".equals(key)) return "Min Full GCs to detect live set trend";
        if ("memory.oldGenAfterFullGcPct".equals(key)) return "Old Gen % after Full GC to alarm (heap that cannot be freed)";
        if ("memory.allocPressureRatio".equals(key)) return "Alloc/reclaim rate ratio to trigger pressure alarm";
        if ("gc.throughputWarnPct".equals(key)) return "GC throughput % below which to warn (time in app vs GC)";
        if ("gc.throughputCritPct".equals(key)) return "GC throughput % below which to raise critical";
        if ("gc.pauseMaxMs".equals(key)) return "Max GC pause (ms) to raise critical";
        if ("cpu.warnPct".equals(key)) return "JVM CPU % sustained above which to warn";
        if ("cpu.critPct".equals(key)) return "JVM CPU % sustained above which to raise critical";
        if ("cpu.runawayThreadPct".equals(key)) return "Single thread CPU % to flag as runaway";
        if ("threads.blockedWarnPct".equals(key)) return "Blocked thread % to warn (lock contention)";
        if ("threads.blockedCritPct".equals(key)) return "Blocked thread % to raise critical";
        if ("threads.blockedMinCount".equals(key)) return "Minimum blocked threads to trigger alarm";
        if ("threads.countWarn".equals(key)) return "Thread count to warn (thread leak)";
        if ("threads.countCrit".equals(key)) return "Thread count to raise critical";
        if ("exceptions.spikeMultiplier".equals(key)) return "Exception rate spike multiplier vs baseline (e.g. 3x)";
        if ("exceptions.minRate".equals(key)) return "Minimum exceptions/min to trigger spike alarm";
        if ("exceptions.recurringBugMin".equals(key)) return "Same-location exception count in 60s to flag as bug";
        if ("exceptions.highRate".equals(key)) return "Overall exception rate/min for high rate alarm";
        if ("network.closeWaitWarn".equals(key)) return "CLOSE_WAIT count to warn (connection leak)";
        if ("network.closeWaitCrit".equals(key)) return "CLOSE_WAIT count to raise critical";
        if ("network.retransmitWarnPct".equals(key)) return "TCP retransmit % to warn (network degradation)";
        if ("network.retransmitCritPct".equals(key)) return "TCP retransmit % to raise critical";
        if ("network.establishedWarn".equals(key)) return "Established TCP connections to warn";
        if ("response.degradationRatio".equals(key)) return "Response time ratio vs baseline to alarm (e.g. 2x)";
        if ("response.minMs".equals(key)) return "Min response time (ms) to consider degradation";
        if ("response.jdbcSlowMs".equals(key)) return "JDBC query duration (ms) to flag as slow";
        if ("response.jdbcSlowConnectMs".equals(key)) return "getConnection() duration (ms) to flag pool exhaustion";
        if ("classloader.warn".equals(key)) return "Classloader count to warn (classloader leak)";
        if ("classloader.crit".equals(key)) return "Classloader count to raise critical";
        if ("safepoint.syncWarnMs".equals(key)) return "Avg safepoint sync time (ms) to warn";
        if ("safepoint.syncCritMs".equals(key)) return "Avg safepoint sync time (ms) to raise critical";
        if ("safepoint.totalWarnMs".equals(key)) return "Avg safepoint total time (ms) to warn";
        if ("safepoint.totalCritMs".equals(key)) return "Avg safepoint total time (ms) to raise critical";
        return "";
    }

    private void saveSession(JTextArea logArea) {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("jvmmonitor-session-" +
                new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".jvmsession.gz"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        logArea.setText("Saving session...\n");
        try {
            long start = System.currentTimeMillis();
            it.denzosoft.jvmmonitor.storage.SessionSerializer.save(collector.getStore(), fc.getSelectedFile());
            long elapsed = System.currentTimeMillis() - start;
            long fileSize = fc.getSelectedFile().length();
            String sizeStr;
            if (fileSize > 1024 * 1024) {
                sizeStr = String.format("%.1f MB", fileSize / (1024.0 * 1024.0));
            } else {
                sizeStr = String.format("%.1f KB", fileSize / 1024.0);
            }
            logArea.setText("Session saved successfully!\n\n" +
                    "File: " + fc.getSelectedFile().getAbsolutePath() + "\n" +
                    "Size: " + sizeStr + " (compressed)\n" +
                    "Time: " + elapsed + " ms\n\n" +
                    "Saved data:\n" +
                    "  Memory snapshots:       " + collector.getStore().getMemorySnapshotCount() + "\n" +
                    "  GC events:              " + collector.getStore().getGcEventCount() + "\n" +
                    "  CPU samples:            " + collector.getStore().getCpuSampleCount() + "\n" +
                    "  Exceptions:             " + collector.getStore().getExceptionCount() + "\n" +
                    "  JIT events:             " + collector.getStore().getJitEventCount() + "\n" +
                    "  Lock events:            " + collector.getStore().getLockEventCount() + "\n" +
                    "  Allocation events:      " + collector.getStore().getAllocationEventCount() + "\n" +
                    "  Instrumentation events: " + collector.getStore().getInstrumentationEventCount() + "\n\n" +
                    "Use 'Load Session' to replay this file later.");
        } catch (Exception ex) {
            logArea.setText("Save failed: " + ex.getMessage());
            System.err.println("[JVMMonitor] Session save error: " + ex);
        }
    }

    private void loadSession(JTextArea logArea) {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new javax.swing.filechooser.FileFilter() {
            public boolean accept(File f) {
                return f.isDirectory() || f.getName().endsWith(".jvmsession.gz");
            }
            public String getDescription() {
                return "JVMMonitor Sessions (*.jvmsession.gz)";
            }
        });
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        logArea.setText("Loading session...\n");
        try {
            long start = System.currentTimeMillis();
            long saveTs = it.denzosoft.jvmmonitor.storage.SessionSerializer.load(
                    collector.getStore(), fc.getSelectedFile());
            long elapsed = System.currentTimeMillis() - start;
            long fileSize = fc.getSelectedFile().length();
            String sizeStr;
            if (fileSize > 1024 * 1024) {
                sizeStr = String.format("%.1f MB", fileSize / (1024.0 * 1024.0));
            } else {
                sizeStr = String.format("%.1f KB", fileSize / 1024.0);
            }
            logArea.setText("Session loaded successfully!\n\n" +
                    "File: " + fc.getSelectedFile().getAbsolutePath() + "\n" +
                    "Size: " + sizeStr + "\n" +
                    "Saved at: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(saveTs)) + "\n" +
                    "Load time: " + elapsed + " ms\n\n" +
                    "All panels are now populated with the loaded data.\n" +
                    "Switch to any tab to view the recorded metrics.");
        } catch (Exception ex) {
            logArea.setText("Load failed: " + ex.getMessage());
            System.err.println("[JVMMonitor] Session load error: " + ex);
        }
    }

    private void exportSession(JTextArea logArea) {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("jvmmonitor-report-" +
                new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".html"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        try {
            FileWriter fw = new FileWriter(fc.getSelectedFile());
            long now = System.currentTimeMillis();
            long from = now - 300000;
            it.denzosoft.jvmmonitor.storage.EventStore store = collector.getStore();

            fw.write("<!DOCTYPE html><html><head><meta charset='UTF-8'>\n");
            fw.write("<title>JVMMonitor Report</title>\n");
            fw.write("<style>body{font-family:sans-serif;margin:20px;font-size:13px}" +
                    "table{border-collapse:collapse;margin:10px 0;width:100%}" +
                    "th,td{border:1px solid #ddd;padding:5px 8px;text-align:left}" +
                    "th{background:#f5f5f5;font-weight:bold}" +
                    "td.num{text-align:right}" +
                    "h2{color:#333;border-bottom:2px solid #2196F3;padding-bottom:5px;margin-top:30px}" +
                    ".warn{color:#e65100}.crit{color:#c62828;font-weight:bold}" +
                    ".card{border:1px solid #ddd;border-left:4px solid #2196F3;padding:10px;margin:8px 0}" +
                    ".card-crit{border-left-color:#c62828}.card-warn{border-left-color:#e65100}" +
                    "</style></head><body>\n");
            fw.write("<h1>JVMMonitor Session Report</h1>\n");
            fw.write("<p>Generated: " + new Date() + " | JVMMonitor v1.1.0</p>\n");

            /* ── Agent ──────────────────────────── */
            it.denzosoft.jvmmonitor.net.AgentConnection conn = collector.getConnection();
            if (conn != null && conn.isConnected()) {
                fw.write("<h2>Agent Connection</h2>\n");
                fw.write("<table><tr><th>PID</th><th>Host</th><th>JVM</th></tr>\n");
                fw.write("<tr><td>" + conn.getAgentPid() + "</td><td>" + conn.getAgentHostname() +
                        "</td><td>" + conn.getJvmInfo() + "</td></tr></table>\n");
            }

            /* ── Memory ─────────────────────────── */
            it.denzosoft.jvmmonitor.model.MemorySnapshot mem = store.getLatestMemorySnapshot();
            if (mem != null) {
                fw.write("<h2>Memory</h2>\n");
                fw.write("<table><tr><th>Metric</th><th>Value</th></tr>\n");
                fw.write("<tr><td>Heap Used</td><td class='num'>" + mem.getHeapUsedMB() + "</td></tr>\n");
                fw.write("<tr><td>Heap Max</td><td class='num'>" + mem.getHeapMaxMB() + "</td></tr>\n");
                fw.write("<tr><td>Heap Usage</td><td class='num'>" + String.format("%.1f%%", mem.getHeapUsagePercent()) + "</td></tr>\n");
                fw.write("<tr><td>Non-Heap</td><td class='num'>" + String.format("%.1f MB", mem.getNonHeapUsed() / (1024.0 * 1024.0)) + "</td></tr>\n");
                fw.write("<tr><td>Allocation Rate</td><td class='num'>" + String.format("%.0f MB/h", collector.getAnalysisContext().getHeapGrowthRateMBPerHour(5)) + "</td></tr>\n");
                fw.write("</table>\n");
            }

            /* ── GC ─────────────────────────────── */
            fw.write("<h2>GC Statistics</h2>\n");
            fw.write("<table><tr><th>Metric</th><th>Value</th></tr>\n");
            fw.write("<tr><td>Frequency</td><td class='num'>" + String.format("%.0f/min", collector.getAnalysisContext().getGcFrequencyPerMinute(60)) + "</td></tr>\n");
            fw.write("<tr><td>Avg Pause</td><td class='num'>" + String.format("%.1f ms", collector.getAnalysisContext().getAvgGcPauseMs(60)) + "</td></tr>\n");
            fw.write("<tr><td>Max Pause</td><td class='num'>" + String.format("%.1f ms", collector.getAnalysisContext().getMaxGcPauseMs(60)) + "</td></tr>\n");
            fw.write("<tr><td>Throughput</td><td class='num'>" + String.format("%.1f%%", collector.getAnalysisContext().getGcThroughputPercent(60)) + "</td></tr>\n");
            fw.write("<tr><td>Total GC Events</td><td class='num'>" + store.getGcEventCount() + "</td></tr>\n");
            fw.write("</table>\n");

            /* GC Events detail */
            List<it.denzosoft.jvmmonitor.model.GcEvent> gcEvts = store.getGcEvents(from, now);
            if (!gcEvts.isEmpty()) {
                int shown = Math.min(gcEvts.size(), 30);
                fw.write("<h3>Recent GC Events (" + shown + ")</h3>\n");
                fw.write("<table><tr><th>Time</th><th>Type</th><th>Duration (ms)</th><th>Cause</th><th>Freed (MB)</th><th>Promoted (MB)</th></tr>\n");
                for (int i = gcEvts.size() - shown; i < gcEvts.size(); i++) {
                    it.denzosoft.jvmmonitor.model.GcEvent e = gcEvts.get(i);
                    fw.write("<tr><td>" + new SimpleDateFormat("HH:mm:ss.SSS").format(new Date(e.getTimestamp())) +
                            "</td><td>" + e.getTypeName() + "</td><td class='num'>" + String.format("%.1f", e.getDurationMs()) +
                            "</td><td>" + e.getCause() + "</td><td class='num'>" + String.format("%.0f", e.getFreedMB()) +
                            "</td><td class='num'>" + String.format("%.1f", e.getPromotedMB()) + "</td></tr>\n");
                }
                fw.write("</table>\n");
            }

            /* ── Threads ────────────────────────── */
            List<it.denzosoft.jvmmonitor.model.ThreadInfo> threads = store.getLatestThreadInfo();
            int runnable = 0, blocked = 0, waiting = 0;
            for (int i = 0; i < threads.size(); i++) {
                switch (threads.get(i).getState()) {
                    case it.denzosoft.jvmmonitor.model.ThreadInfo.STATE_RUNNABLE: runnable++; break;
                    case it.denzosoft.jvmmonitor.model.ThreadInfo.STATE_BLOCKED: blocked++; break;
                    default: waiting++; break;
                }
            }
            fw.write("<h2>Threads (" + threads.size() + ": " + runnable + " runnable, " + blocked + " blocked, " + waiting + " waiting)</h2>\n");
            fw.write("<table><tr><th>ID</th><th>Name</th><th>State</th><th>Daemon</th></tr>\n");
            for (int i = 0; i < threads.size(); i++) {
                it.denzosoft.jvmmonitor.model.ThreadInfo t = threads.get(i);
                String stateColor = t.getState() == it.denzosoft.jvmmonitor.model.ThreadInfo.STATE_BLOCKED ? " class='crit'" : "";
                fw.write("<tr><td>" + t.getThreadId() + "</td><td>" + esc(t.getName()) +
                        "</td><td" + stateColor + ">" + it.denzosoft.jvmmonitor.model.ThreadInfo.stateToString(t.getState()) +
                        "</td><td>" + (t.isDaemon() ? "yes" : "no") + "</td></tr>\n");
            }
            fw.write("</table>\n");

            /* ── Exceptions ─────────────────────── */
            List<it.denzosoft.jvmmonitor.model.ExceptionEvent> excs = store.getExceptions(from, now);
            fw.write("<h2>Exceptions (last 5 min: " + excs.size() + ")</h2>\n");
            fw.write("<p>Rate: " + String.format("%.0f/min", collector.getAnalysisContext().getExceptionRatePerMinute(60)) + "</p>\n");
            if (!excs.isEmpty()) {
                /* Aggregate by location */
                java.util.Map<String, int[]> excHotspots = new java.util.LinkedHashMap<String, int[]>();
                for (int i = 0; i < excs.size(); i++) {
                    String loc = excs.get(i).getThrowClass() + "." + excs.get(i).getThrowMethod();
                    int[] cnt = excHotspots.get(loc);
                    if (cnt == null) { cnt = new int[]{0}; excHotspots.put(loc, cnt); }
                    cnt[0]++;
                }
                fw.write("<h3>Exception Hotspots</h3>\n");
                fw.write("<table><tr><th>Code Location</th><th>Count</th></tr>\n");
                for (java.util.Map.Entry<String, int[]> entry : excHotspots.entrySet()) {
                    fw.write("<tr><td>" + esc(entry.getKey()) + "</td><td class='num'>" + entry.getValue()[0] + "</td></tr>\n");
                }
                fw.write("</table>\n");

                fw.write("<h3>Recent Exceptions (last 50)</h3>\n");
                fw.write("<table><tr><th>Exception</th><th>Thrown At</th><th>Caught</th></tr>\n");
                int shown = Math.min(excs.size(), 50);
                for (int i = excs.size() - shown; i < excs.size(); i++) {
                    it.denzosoft.jvmmonitor.model.ExceptionEvent e = excs.get(i);
                    fw.write("<tr><td>" + esc(e.getDisplayName()) + "</td><td>" + esc(e.getThrowClass() + "." + e.getThrowMethod()) +
                            "</td><td>" + (e.isCaught() ? "yes" : "<b class='crit'>NO</b>") + "</td></tr>\n");
                }
                fw.write("</table>\n");
            }

            /* ── Network ────────────────────────── */
            it.denzosoft.jvmmonitor.model.NetworkSnapshot net = store.getLatestNetworkSnapshot();
            if (net != null) {
                fw.write("<h2>Network (" + net.getSocketCount() + " sockets)</h2>\n");
                fw.write("<p>ESTABLISHED: " + net.getEstablishedCount() + " | CLOSE_WAIT: " + net.getCloseWaitCount() +
                        " | TIME_WAIT: " + net.getTimeWaitCount() + " | LISTEN: " + net.getListenCount() + "</p>\n");
                fw.write("<p>Segments in: " + net.getInSegments() + " | out: " + net.getOutSegments() +
                        " | retrans: " + net.getRetransSegments() + " | errors: " + net.getInErrors() + "</p>\n");
                it.denzosoft.jvmmonitor.model.NetworkSnapshot.SocketInfo[] sockets = net.getSockets();
                if (sockets != null && sockets.length > 0) {
                    fw.write("<table><tr><th>Direction</th><th>Destination</th><th>State</th><th>Requests</th><th>Bytes In</th><th>Bytes Out</th></tr>\n");
                    for (int i = 0; i < sockets.length; i++) {
                        if (sockets[i] == null) continue;
                        it.denzosoft.jvmmonitor.model.NetworkSnapshot.SocketInfo s = sockets[i];
                        String stateClass = s.state == it.denzosoft.jvmmonitor.model.NetworkSnapshot.SocketInfo.STATE_CLOSE_WAIT ? " class='crit'" : "";
                        fw.write("<tr><td>" + s.getDirection() + "</td><td>" + esc(s.getDestination()) +
                                "</td><td" + stateClass + ">" + s.getStateName() + "</td><td class='num'>" + s.requestCount +
                                "</td><td class='num'>" + s.formatBytes(s.bytesIn) + "</td><td class='num'>" + s.formatBytes(s.bytesOut) + "</td></tr>\n");
                    }
                    fw.write("</table>\n");
                }
            }

            /* ── Messaging ──────────────────────── */
            it.denzosoft.jvmmonitor.model.QueueStats qs = store.getLatestQueueStats();
            if (qs != null && qs.getQueueCount() > 0) {
                fw.write("<h2>Message Queues (" + qs.getQueueCount() + ")</h2>\n");
                fw.write("<table><tr><th>Queue</th><th>Type</th><th>Depth</th><th>Enq/s</th><th>Deq/s</th><th>Consumers</th><th>Lag</th></tr>\n");
                it.denzosoft.jvmmonitor.model.QueueStats.QueueInfo[] queues = qs.getQueues();
                for (int i = 0; i < queues.length; i++) {
                    if (queues[i] == null) continue;
                    it.denzosoft.jvmmonitor.model.QueueStats.QueueInfo q = queues[i];
                    String depthClass = q.depth > 1000 ? " class='crit'" : "";
                    fw.write("<tr><td>" + esc(q.name) + "</td><td>" + q.type + "</td><td" + depthClass + " class='num'>" + q.depth +
                            "</td><td class='num'>" + q.enqueueRate + "</td><td class='num'>" + q.dequeueRate +
                            "</td><td class='num'>" + q.consumerCount + "</td><td class='num'>" + q.consumerLag + "</td></tr>\n");
                }
                fw.write("</table>\n");
            }

            /* ── Lock Contention ─────────────────── */
            List<it.denzosoft.jvmmonitor.model.LockEvent> locks = store.getLockEvents(from, now);
            if (!locks.isEmpty()) {
                fw.write("<h2>Lock Contention (" + locks.size() + " events in last 5 min)</h2>\n");
                /* Aggregate by lock */
                java.util.Map<String, int[]> lockHotspots = new java.util.LinkedHashMap<String, int[]>();
                for (int i = 0; i < locks.size(); i++) {
                    if (locks.get(i).getEventType() != it.denzosoft.jvmmonitor.model.LockEvent.CONTENDED_ENTER) continue;
                    String key = locks.get(i).getLockDisplayName();
                    int[] cnt = lockHotspots.get(key);
                    if (cnt == null) { cnt = new int[]{0}; lockHotspots.put(key, cnt); }
                    cnt[0]++;
                }
                fw.write("<table><tr><th>Lock</th><th>Contentions</th></tr>\n");
                for (java.util.Map.Entry<String, int[]> entry : lockHotspots.entrySet()) {
                    fw.write("<tr><td>" + esc(entry.getKey()) + "</td><td class='num'>" + entry.getValue()[0] + "</td></tr>\n");
                }
                fw.write("</table>\n");
            }

            /* ── CPU Usage ──────────────────────── */
            it.denzosoft.jvmmonitor.model.CpuUsageSnapshot cpu = store.getLatestCpuUsage();
            if (cpu != null) {
                fw.write("<h2>CPU Usage</h2>\n");
                fw.write("<table><tr><th>Metric</th><th>Value</th></tr>\n");
                fw.write("<tr><td>System CPU</td><td class='num'>" + String.format("%.1f%%", cpu.getSystemCpuPercent()) + "</td></tr>\n");
                fw.write("<tr><td>JVM CPU</td><td class='num'>" + String.format("%.1f%%", cpu.getProcessCpuPercent()) + "</td></tr>\n");
                fw.write("<tr><td>Available</td><td class='num'>" + String.format("%.1f%%", cpu.getAvailableCpuPercent()) + "</td></tr>\n");
                fw.write("<tr><td>Processors</td><td class='num'>" + cpu.getAvailableProcessors() + "</td></tr>\n");
                fw.write("</table>\n");
            }

            /* ── OS Metrics ─────────────────────── */
            it.denzosoft.jvmmonitor.model.OsMetrics os = store.getLatestOsMetrics();
            if (os != null) {
                fw.write("<h2>OS Metrics</h2>\n");
                fw.write("<table><tr><th>Metric</th><th>Value</th></tr>\n");
                fw.write("<tr><td>RSS</td><td class='num'>" + String.format("%.0f MB", os.getRssMB()) + "</td></tr>\n");
                fw.write("<tr><td>VM Size</td><td class='num'>" + String.format("%.0f MB", os.getVmSizeMB()) + "</td></tr>\n");
                fw.write("<tr><td>Open FDs</td><td class='num'>" + os.getOpenFileDescriptors() + "</td></tr>\n");
                fw.write("<tr><td>OS Threads</td><td class='num'>" + os.getOsThreadCount() + "</td></tr>\n");
                fw.write("<tr><td>TCP Established</td><td class='num'>" + os.getTcpEstablished() + "</td></tr>\n");
                fw.write("<tr><td>TCP Close Wait</td><td class='num'>" + os.getTcpCloseWait() + "</td></tr>\n");
                fw.write("</table>\n");
            }

            /* ── Safepoints ─────────────────────── */
            it.denzosoft.jvmmonitor.model.SafepointEvent sp = store.getLatestSafepoint();
            if (sp != null && sp.isAvailable()) {
                fw.write("<h2>Safepoints</h2>\n");
                fw.write("<p>Count: " + sp.getSafepointCount() + " | Total: " + sp.getTotalTimeMs() +
                        "ms | Sync: " + sp.getSyncTimeMs() + "ms</p>\n");
            }

            /* ── Classloaders ───────────────────── */
            it.denzosoft.jvmmonitor.model.ClassloaderStats cls = store.getLatestClassloaderStats();
            if (cls != null) {
                fw.write("<h2>Classloaders (" + cls.getLoaderCount() + " loaders, " + cls.getTotalClassCount() + " classes)</h2>\n");
                fw.write("<table><tr><th>Classloader</th><th>Classes</th></tr>\n");
                it.denzosoft.jvmmonitor.model.ClassloaderStats.LoaderInfo[] loaders = cls.getLoaders();
                for (int i = 0; i < loaders.length; i++) {
                    fw.write("<tr><td>" + esc(loaders[i].getLoaderClass()) + "</td><td class='num'>" + loaders[i].getClassCount() + "</td></tr>\n");
                }
                fw.write("</table>\n");
            }

            /* ── Class Histogram ────────────────── */
            it.denzosoft.jvmmonitor.model.ClassHistogram histo = store.getLatestClassHistogram();
            if (histo != null) {
                fw.write("<h2>Class Histogram (top 30)</h2>\n");
                fw.write("<table><tr><th>#</th><th>Class</th><th>Instances</th><th>Size (MB)</th></tr>\n");
                it.denzosoft.jvmmonitor.model.ClassHistogram.Entry[] entries = histo.getEntries();
                int shown = Math.min(entries != null ? entries.length : 0, 30);
                for (int i = 0; i < shown; i++) {
                    if (entries[i] == null) continue;
                    fw.write("<tr><td>" + (i + 1) + "</td><td>" +
                            it.denzosoft.jvmmonitor.gui.chart.ClassNameFormatter.format(entries[i].getClassName()) +
                            "</td><td class='num'>" + entries[i].getInstanceCount() +
                            "</td><td class='num'>" + String.format("%.2f", entries[i].getTotalSizeMB()) + "</td></tr>\n");
                }
                fw.write("</table>\n");
            }

            /* ── Alarms ─────────────────────────── */
            List<it.denzosoft.jvmmonitor.model.AlarmEvent> alarms = store.getActiveAlarms();
            fw.write("<h2>Active Alarms (" + alarms.size() + ")</h2>\n");
            if (!alarms.isEmpty()) {
                for (int i = 0; i < alarms.size(); i++) {
                    it.denzosoft.jvmmonitor.model.AlarmEvent a = alarms.get(i);
                    String cls2 = a.getSeverity() >= 2 ? "card-crit" : a.getSeverity() >= 1 ? "card-warn" : "";
                    fw.write("<div class='card " + cls2 + "'>" +
                            it.denzosoft.jvmmonitor.model.AlarmEvent.severityToString(a.getSeverity()) +
                            " " + esc(a.getMessage()) + "</div>\n");
                }
            } else {
                fw.write("<p style='color:green'>No active alarms.</p>\n");
            }

            /* ── Diagnostics ────────────────────── */
            List<it.denzosoft.jvmmonitor.model.Diagnosis> diagnoses = collector.getDiagnosisEngine().runDiagnostics();
            fw.write("<h2>Diagnostics (" + diagnoses.size() + " findings)</h2>\n");
            if (!diagnoses.isEmpty()) {
                for (int i = 0; i < diagnoses.size(); i++) {
                    it.denzosoft.jvmmonitor.model.Diagnosis d = diagnoses.get(i);
                    String cls2 = d.getSeverity() >= 2 ? "card-crit" : d.getSeverity() >= 1 ? "card-warn" : "";
                    fw.write("<div class='card " + cls2 + "'>\n");
                    fw.write("<b>" + (d.getSeverity() == 2 ? "CRITICAL" : d.getSeverity() == 1 ? "WARNING" : "INFO") +
                            " — " + esc(d.getCategory()) + "</b><br>\n");
                    if (d.getLocation() != null) fw.write("Where: " + esc(d.getLocation()) + "<br>\n");
                    if (d.getSummary() != null) fw.write("Problem: " + esc(d.getSummary()) + "<br>\n");
                    if (d.getEvidence() != null) fw.write("Evidence: <pre>" + esc(d.getEvidence()) + "</pre>\n");
                    if (d.getSuggestedAction() != null) fw.write("Action: <code>" + esc(d.getSuggestedAction()) + "</code><br>\n");
                    fw.write("</div>\n");
                }
            } else {
                fw.write("<p style='color:green'>No issues detected.</p>\n");
            }

            /* ── Event Summary ──────────────────── */
            fw.write("<h2>Event Summary</h2>\n");
            fw.write("<table><tr><th>Event Type</th><th>Count</th></tr>\n");
            fw.write("<tr><td>CPU Samples</td><td class='num'>" + store.getCpuSampleCount() + "</td></tr>\n");
            fw.write("<tr><td>GC Events</td><td class='num'>" + store.getGcEventCount() + "</td></tr>\n");
            fw.write("<tr><td>Memory Snapshots</td><td class='num'>" + store.getMemorySnapshotCount() + "</td></tr>\n");
            fw.write("<tr><td>Exceptions</td><td class='num'>" + store.getExceptionCount() + "</td></tr>\n");
            fw.write("<tr><td>JIT Events</td><td class='num'>" + store.getJitEventCount() + "</td></tr>\n");
            fw.write("<tr><td>Lock Events</td><td class='num'>" + store.getLockEventCount() + "</td></tr>\n");
            fw.write("<tr><td>Allocation Events</td><td class='num'>" + store.getAllocationEventCount() + "</td></tr>\n");
            fw.write("<tr><td>Instrumentation Events</td><td class='num'>" + store.getInstrumentationEventCount() + "</td></tr>\n");
            fw.write("</table>\n");

            fw.write("<hr><p><i>Generated by JVMMonitor v1.1.0 — " + new Date() + "</i></p>\n");
            fw.write("</body></html>\n");
            fw.close();

            logArea.setText("Report exported to: " + fc.getSelectedFile().getAbsolutePath() +
                    "\n\nIncludes:\n" +
                    "- Agent connection info\n" +
                    "- Memory (heap, non-heap, allocation rate)\n" +
                    "- GC statistics + recent events (" + gcEvts.size() + ")\n" +
                    "- Threads (" + threads.size() + ")\n" +
                    "- Exceptions + hotspots (" + excs.size() + ")\n" +
                    "- Network connections (" + (net != null ? net.getSocketCount() : 0) + " sockets)\n" +
                    "- Message queues (" + (qs != null ? qs.getQueueCount() : 0) + ")\n" +
                    "- Lock contention (" + locks.size() + " events)\n" +
                    "- CPU usage\n" +
                    "- OS metrics\n" +
                    "- Safepoints\n" +
                    "- Classloaders\n" +
                    "- Class histogram\n" +
                    "- Alarms (" + alarms.size() + ")\n" +
                    "- Diagnostics (" + diagnoses.size() + " findings)\n" +
                    "- Event counts (all types)");
        } catch (Exception ex) {
            logArea.setText("Export failed: " + ex.getMessage());
        }
    }

    public void updateData() {
        /* Field watch events: update table */
        fwModel.fireTableDataChanged();
    }

    public void render() {
        repaint();
    }

    public void refresh() {
        updateData();
        render();
    }

    /* ── Field Watch commands ─────────────────────── */

    private void addFieldWatch() {
        String cls = fwClassField.getText().trim().replace('.', '/');
        String field = fwFieldField.getText().trim();
        AgentConnection conn = collector.getConnection();
        if (conn != null && conn.isConnected()) {
            try {
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);
                byte[] cn = cls.getBytes("UTF-8"); dos.writeShort(cn.length); dos.write(cn);
                byte[] fn = field.getBytes("UTF-8"); dos.writeShort(fn.length); dos.write(fn);
                dos.flush();
                conn.sendCommand(ProtocolConstants.DIAG_CMD_SET_FIELD_WATCH, baos.toByteArray());
            } catch (Exception ex) { /* ignore */ }
        }
    }

    private void removeFieldWatch() {
        String cls = fwClassField.getText().trim().replace('.', '/');
        String field = fwFieldField.getText().trim();
        AgentConnection conn = collector.getConnection();
        if (conn != null && conn.isConnected()) {
            try {
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);
                byte[] cn = cls.getBytes("UTF-8"); dos.writeShort(cn.length); dos.write(cn);
                byte[] fn = field.getBytes("UTF-8"); dos.writeShort(fn.length); dos.write(fn);
                dos.flush();
                conn.sendCommand(ProtocolConstants.DIAG_CMD_REMOVE_FIELD_WATCH, baos.toByteArray());
            } catch (Exception ex) { /* ignore */ }
        }
    }

    public void onFieldWatchEvent(FieldWatchEvent e) {
        fwEvents.add(e);
        if (fwEvents.size() > 5000) fwEvents.remove(0);
    }

    /* ── Thread Dump ─────────────────────────────── */

    private void takeThreadDump() {
        AgentConnection conn = collector.getConnection();
        if (conn != null && conn.isConnected()) {
            try {
                conn.sendCommand(ProtocolConstants.DIAG_CMD_THREAD_DUMP, new byte[0]);
                threadDumpArea.setText("Requesting thread dump from agent...");
            } catch (Exception ex) {
                threadDumpArea.setText("Failed: " + ex.getMessage());
            }
        }
    }

    public void onThreadDump(ThreadDump dump) {
        threadDumpArea.setText(dump.toText());
        threadDumpArea.setCaretPosition(0);
    }

    private void saveThreadDump() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("thread-dump-" +
                new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".txt"));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                FileWriter fw = new FileWriter(fc.getSelectedFile());
                fw.write(threadDumpArea.getText());
                fw.close();
                JOptionPane.showMessageDialog(this, "Saved to " + fc.getSelectedFile().getAbsolutePath());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage());
            }
        }
    }

    /* ── Deadlock ─────────────────────────────────── */

    private void detectDeadlocks() {
        AgentConnection conn = collector.getConnection();
        if (conn != null && conn.isConnected()) {
            try {
                conn.sendCommand(ProtocolConstants.DIAG_CMD_DETECT_DEADLOCKS, new byte[0]);
                deadlockArea.setText("Scanning for deadlocks...");
            } catch (Exception ex) {
                deadlockArea.setText("Failed: " + ex.getMessage());
            }
        }
    }

    public void onDeadlockInfo(DeadlockInfo info) {
        deadlockArea.setText(info.toText());
        deadlockArea.setCaretPosition(0);
        if (info.isDeadlockFound()) {
            deadlockArea.setForeground(Color.RED);
        } else {
            deadlockArea.setForeground(new Color(0, 128, 0));
        }
    }

    /* ── GC Root ──────────────────────────────────── */

    private void analyzeGcRoots() {
        String cls = gcRootClassField.getText().trim().replace('.', '/');
        String field = gcRootFieldField.getText().trim();
        AgentConnection conn = collector.getConnection();
        if (conn != null && conn.isConnected()) {
            try {
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);
                byte[] cn = cls.getBytes("UTF-8"); dos.writeShort(cn.length); dos.write(cn);
                byte[] fn = field.getBytes("UTF-8"); dos.writeShort(fn.length); dos.write(fn);
                dos.flush();
                conn.sendCommand(ProtocolConstants.DIAG_CMD_GC_ROOTS, baos.toByteArray());
                gcRootArea.setText("Analyzing GC roots for " + cls.replace('/', '.') + "...");
            } catch (Exception ex) {
                gcRootArea.setText("Failed: " + ex.getMessage());
            }
        }
    }

    private void forceGc() {
        AgentConnection conn = collector.getConnection();
        if (conn != null && conn.isConnected()) {
            try {
                conn.sendCommand(ProtocolConstants.DIAG_CMD_FORCE_GC, new byte[0]);
                gcRootArea.setText("GC triggered. Wait a moment then analyze GC roots.");
            } catch (Exception ex) { /* ignore */ }
        }
    }

    public void onGcRootPath(GcRootPath path) {
        gcRootArea.setText(path.toText());
        gcRootArea.setCaretPosition(0);
    }

    /* ── JVM Config ──────────────────────────────── */

    private void loadJvmConfig() {
        AgentConnection conn = collector.getConnection();
        if (conn != null && conn.isConnected()) {
            try {
                conn.sendCommand(ProtocolConstants.DIAG_CMD_GET_JVM_CONFIG, new byte[0]);
                jvmConfigArea.setText("Loading JVM configuration...");
            } catch (Exception ex) {
                jvmConfigArea.setText("Failed: " + ex.getMessage());
            }
        }
    }

    public void onJvmConfig(JvmConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== JVM Configuration ===\n\n");
        sb.append("Java Version:  ").append(config.getJavaVersion()).append('\n');
        sb.append("VM Name:       ").append(config.getVmName()).append('\n');
        sb.append("VM Vendor:     ").append(config.getVmVendor()).append('\n');
        sb.append("Java Home:     ").append(config.getJavaHome()).append('\n');
        sb.append("Processors:    ").append(config.getAvailableProcessors()).append('\n');
        sb.append("Uptime:        ").append(config.getUptime() / 1000).append(" sec\n");
        sb.append('\n');
        sb.append("=== VM Arguments ===\n");
        String[] args = config.getVmArguments();
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                sb.append("  ").append(args[i]).append('\n');
            }
        }
        sb.append('\n');
        sb.append("=== Classpath ===\n");
        String cp = config.getClasspath();
        if (cp != null) {
            String[] parts = cp.split(java.io.File.pathSeparator);
            for (int i = 0; i < parts.length; i++) {
                sb.append("  ").append(parts[i]).append('\n');
            }
        }
        sb.append('\n');
        sb.append("=== System Properties ===\n");
        String[][] props = config.getSystemProperties();
        if (props != null) {
            for (int i = 0; i < props.length; i++) {
                sb.append("  ").append(props[i][0]).append(" = ").append(props[i][1]).append('\n');
            }
        }
        jvmConfigArea.setText(sb.toString());
        jvmConfigArea.setCaretPosition(0);
    }

    /* ── Heap Dump ───────────────────────────────── */

    private void takeHeapDump() {
        AgentConnection conn = collector.getConnection();
        if (conn != null && conn.isConnected()) {
            try {
                conn.sendCommand(ProtocolConstants.DIAG_CMD_HEAP_DUMP, new byte[0]);
                JOptionPane.showMessageDialog(this,
                        "Heap dump requested. The .hprof file will be saved on the agent host.",
                        "Heap Dump", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Failed: " + ex.getMessage());
            }
        }
    }

    /* ── Thread Control command ──────────────────── */

    private void sendThreadCmd(int cmdType, String threadIdStr) {
        try {
            long tid = Long.parseLong(threadIdStr);
            AgentConnection conn = collector.getConnection();
            if (conn != null && conn.isConnected()) {
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                new java.io.DataOutputStream(baos).writeLong(tid);
                conn.sendCommand(cmdType, baos.toByteArray());
            }
        } catch (Exception ex) { /* ignore */ }
    }

    /* ── Hot Swap command ────────────────────────── */

    private void sendHotSwap(String className, byte[] classBytes) {
        AgentConnection conn = collector.getConnection();
        if (conn != null && conn.isConnected()) {
            try {
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);
                byte[] cn = className.getBytes("UTF-8");
                dos.writeShort(cn.length);
                dos.write(cn);
                dos.writeInt(classBytes.length);
                dos.write(classBytes);
                dos.flush();
                conn.sendCommand(ProtocolConstants.DIAG_CMD_HOT_SWAP, baos.toByteArray());
            } catch (Exception ex) {
                if (hotSwapArea != null) hotSwapArea.setText("Failed: " + ex.getMessage());
            }
        }
    }

    /* ── Callbacks for new message types ──────────── */

    public void onMonitorMap(MonitorMap map) {
        if (monitorMapArea != null) {
            monitorMapArea.setText(map.toText());
            monitorMapArea.setCaretPosition(0);
        }
    }

    public void onObjectLifetimeStats(ObjectLifetimeStats stats) {
        if (objectLifetimeArea != null) {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Object Lifetime Distribution ===\n\n");
            sb.append(String.format("Total tracked: %d  |  Freed: %d  |  Still alive: %d\n\n",
                    stats.getTotalTracked(), stats.getTotalFreed(), stats.getStillAlive()));

            ObjectLifetimeStats.LifetimeBucket[] buckets = stats.getBuckets();
            if (buckets != null) {
                sb.append("Lifetime Distribution:\n");
                for (int i = 0; i < buckets.length; i++) {
                    sb.append(String.format("  %-20s %6d (%5.1f%%)  %s\n",
                            buckets[i].label, buckets[i].count, buckets[i].percent,
                            bar(buckets[i].percent)));
                }
            }

            sb.append("\nTop Long-Lived Classes (leak candidates):\n");
            sb.append(String.format("  %-40s %8s %10s %10s %10s\n",
                    "Class", "Count", "Avg Life", "Max Life", "Never Freed"));
            ObjectLifetimeStats.ClassLifetime[] top = stats.getTopLongLived();
            if (top != null) {
                for (int i = 0; i < top.length; i++) {
                    sb.append(String.format("  %-40s %8d %8.0f ms %8.0f ms %10d\n",
                            top[i].className, top[i].count,
                            top[i].avgLifetimeMs, top[i].maxLifetimeMs, top[i].neverFreedCount));
                }
            }
            objectLifetimeArea.setText(sb.toString());
            objectLifetimeArea.setCaretPosition(0);
        }
    }

    public void onHotSwapResult(String result) {
        if (hotSwapArea != null) {
            hotSwapArea.setText(result);
        }
    }

    private static String bar(double pct) {
        int len = (int)(pct / 2);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 50; i++) sb.append(i < len ? '#' : '.');
        sb.append(']');
        return sb.toString();
    }

    /* ── Field Watch Table ────────────────────────── */

    private class FieldWatchTableModel extends AbstractTableModel {
        private final String[] COLS = {"Time", "Type", "Class.Field", "Old Value", "New Value", "Thread", "Access Location"};
        private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");

        public int getRowCount() { return fwEvents.size(); }
        public int getColumnCount() { return COLS.length; }
        public String getColumnName(int c) { return COLS[c]; }

        public Object getValueAt(int row, int col) {
            int idx = fwEvents.size() - 1 - row;
            if (idx < 0 || idx >= fwEvents.size()) return "";
            FieldWatchEvent e = fwEvents.get(idx);
            switch (col) {
                case 0: return sdf.format(new Date(e.getTimestamp()));
                case 1: return e.getTypeName();
                case 2: return e.getFullFieldName();
                case 3: return e.getOldValue();
                case 4: return e.getNewValue();
                case 5: return e.getThreadName();
                case 6: return e.getAccessLocation();
                default: return "";
            }
        }
    }

    private static class FieldWatchCellRenderer extends AlignedCellRenderer {
        protected void colorize(Component c, JTable table, Object value, int row, int col) {
            if (col == 1) {
                if ("WRITE".equals(value)) {
                    c.setForeground(Color.RED);
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                } else {
                    c.setForeground(new Color(0, 128, 0));
                }
            }
        }
    }

    /** Escape HTML special characters for safe inclusion in report. */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
