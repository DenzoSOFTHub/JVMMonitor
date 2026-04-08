package it.denzosoft.jvmmonitor.gui;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.analysis.AlarmThresholds;
import it.denzosoft.jvmmonitor.gui.chart.AlignedCellRenderer;
import it.denzosoft.jvmmonitor.gui.chart.CsvExporter;
import it.denzosoft.jvmmonitor.net.AgentConnection;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.util.*;
import java.util.List;

/**
 * Unified Settings panel. Centralizes all configurable parameters:
 * - Connection settings
 * - GUI settings (refresh interval)
 * - Alarm thresholds (with save/load)
 * - Instrumentation defaults
 * - Data retention
 */
public class SettingsPanel extends JPanel {

    private final JVMMonitorCollector collector;

    /* Connection */
    private final JTextField hostField;
    private final JTextField portField;
    private final JButton connectBtn;
    private final JLabel connectionStatus;

    /* GUI */
    private final JSpinner refreshSpinner;

    /* Instrumentation defaults */
    private final JTextField defaultPackagesField;
    private final JCheckBox defaultCaptureParams;
    private final JSpinner maxValueLengthSpinner;

    /* Alarm thresholds table */
    private final ThresholdTableModel thresholdModel;

    /* Callback to update refresh timer in MainFrame */
    private Runnable onRefreshIntervalChanged;

    public SettingsPanel(JVMMonitorCollector collector) {
        this.collector = collector;
        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        JTabbedPane tabs = new JTabbedPane();

        /* ── Connection tab ──────────────────────── */
        JPanel connPanel = new JPanel(new GridBagLayout());
        connPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0;
        connPanel.add(new JLabel("Host:"), gbc);
        gbc.gridx = 1;
        hostField = new JTextField("127.0.0.1", 20);
        connPanel.add(hostField, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        connPanel.add(new JLabel("Port:"), gbc);
        gbc.gridx = 1;
        portField = new JTextField("9090", 8);
        connPanel.add(portField, gbc);

        gbc.gridx = 0; gbc.gridy = 2;
        connectBtn = new JButton("Connect");
        connectBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { doConnect(); }
        });
        connPanel.add(connectBtn, gbc);
        gbc.gridx = 1;
        connectionStatus = new JLabel("Not connected");
        connPanel.add(connectionStatus, gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2;
        connPanel.add(new JLabel(" "), gbc);
        gbc.gridy = 4;
        connPanel.add(new JLabel("Agent connection parameters. After connecting, the status bar shows agent info."), gbc);

        tabs.addTab("Connection", connPanel);

        /* ── GUI Settings tab ────────────────────── */
        JPanel guiPanel = new JPanel(new GridBagLayout());
        guiPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0;
        guiPanel.add(new JLabel("Refresh interval (ms):"), gbc);
        gbc.gridx = 1;
        refreshSpinner = new JSpinner(new SpinnerNumberModel(2000, 500, 30000, 500));
        refreshSpinner.setToolTipText("How often charts and tables refresh (default 2000ms)");
        guiPanel.add(refreshSpinner, gbc);
        gbc.gridx = 2;
        JButton applyRefresh = new JButton("Apply");
        applyRefresh.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (onRefreshIntervalChanged != null) onRefreshIntervalChanged.run();
            }
        });
        guiPanel.add(applyRefresh, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 3;
        guiPanel.add(new JLabel(" "), gbc);

        gbc.gridy = 2;
        guiPanel.add(new JLabel("Instrumentation Defaults"), gbc);
        gbc.gridwidth = 1;

        gbc.gridx = 0; gbc.gridy = 3;
        guiPanel.add(new JLabel("Default packages:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2;
        defaultPackagesField = new JTextField("com.myapp", 30);
        defaultPackagesField.setToolTipText("Default application packages for Spring probe (comma-separated)");
        guiPanel.add(defaultPackagesField, gbc);

        gbc.gridwidth = 1;
        gbc.gridx = 0; gbc.gridy = 4;
        guiPanel.add(new JLabel("Capture params:"), gbc);
        gbc.gridx = 1;
        defaultCaptureParams = new JCheckBox("Capture input params & return values (JSON)", false);
        defaultCaptureParams.setToolTipText("When enabled, instrumented methods record parameter values and return values as JSON");
        guiPanel.add(defaultCaptureParams, gbc);

        gbc.gridx = 0; gbc.gridy = 5;
        guiPanel.add(new JLabel("Max value length:"), gbc);
        gbc.gridx = 1;
        maxValueLengthSpinner = new JSpinner(new SpinnerNumberModel(500, -1, 100000, 100));
        maxValueLengthSpinner.setToolTipText("Max chars per serialized value. -1 = unlimited. Also applies to SQL strings.");
        guiPanel.add(maxValueLengthSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 7; gbc.gridwidth = 3;
        guiPanel.add(new JLabel(" "), gbc);
        gbc.gridy = 8;
        guiPanel.add(new JLabel("<html><i>Note: Instrumentation defaults apply when starting instrumentation from the Instrumentation tab.</i></html>"), gbc);

        tabs.addTab("GUI & Instrumentation", guiPanel);

        /* ── Alarm Thresholds tab ────────────────── */
        JPanel alarmPanel = new JPanel(new BorderLayout(5, 5));
        AlarmThresholds thresholds = collector.getThresholds();
        Map thresholdMap = thresholds.toMap();
        List keys = new ArrayList(thresholdMap.keySet());

        thresholdModel = new ThresholdTableModel(keys, thresholdMap);
        JTable thresholdTable = new JTable(thresholdModel);
        thresholdTable.setDefaultRenderer(Object.class, new AlignedCellRenderer());
        thresholdTable.getColumnModel().getColumn(0).setPreferredWidth(200);
        thresholdTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        thresholdTable.getColumnModel().getColumn(2).setPreferredWidth(400);
        thresholdTable.setRowHeight(20);
        CsvExporter.install(thresholdTable);

        JPanel alarmBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));

        JButton applyBtn = new JButton("Apply");
        applyBtn.setToolTipText("Apply changes to running alarm engine");
        applyBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { applyThresholds(); }
        });

        JButton saveThreshBtn = new JButton("Save to File");
        saveThreshBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { saveThresholds(); }
        });

        JButton loadThreshBtn = new JButton("Load from File");
        loadThreshBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { loadThresholds(); }
        });

        JButton resetBtn = new JButton("Reset Defaults");
        resetBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { resetThresholds(); }
        });

        alarmBtns.add(applyBtn);
        alarmBtns.add(saveThreshBtn);
        alarmBtns.add(loadThreshBtn);
        alarmBtns.add(resetBtn);

        alarmPanel.add(alarmBtns, BorderLayout.NORTH);
        alarmPanel.add(new JScrollPane(thresholdTable), BorderLayout.CENTER);

        tabs.addTab("Alarm Thresholds", alarmPanel);

        /* ── Save/Load all settings ──────────────── */
        JPanel allPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        JButton saveAllBtn = new JButton("Save All Settings");
        saveAllBtn.setToolTipText("Save connection, GUI, instrumentation, and alarm settings to a single file");
        saveAllBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { saveAllSettings(); }
        });
        JButton loadAllBtn = new JButton("Load All Settings");
        loadAllBtn.setToolTipText("Load all settings from a previously saved file");
        loadAllBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { loadAllSettings(); }
        });
        allPanel.add(saveAllBtn);
        allPanel.add(loadAllBtn);
        allPanel.add(new JLabel("Save/load all settings (connection, GUI, instrumentation, thresholds) to a single .settings file"));

        tabs.addTab("Import / Export", allPanel);

        add(tabs, BorderLayout.CENTER);
    }

    public void updateData() {
        updateConnectionStatus();
    }

    public void render() {
        repaint();
    }

    public void refresh() {
        updateData();
        render();
    }

    public int getRefreshInterval() {
        return ((Number) refreshSpinner.getValue()).intValue();
    }

    public void setOnRefreshIntervalChanged(Runnable callback) {
        this.onRefreshIntervalChanged = callback;
    }

    public String getDefaultPackages() { return defaultPackagesField.getText(); }
    public boolean isDefaultCaptureParams() { return defaultCaptureParams.isSelected(); }
    public int getDefaultMaxValueLength() { return ((Number) maxValueLengthSpinner.getValue()).intValue(); }

    public void updateConnectionStatus() {
        AgentConnection conn = collector.getConnection();
        if (conn != null && conn.isConnected()) {
            connectionStatus.setText("Connected to PID " + conn.getAgentPid() +
                    " @ " + conn.getAgentHostname());
            connectionStatus.setForeground(new Color(0, 128, 0));
        } else {
            connectionStatus.setText("Not connected");
            connectionStatus.setForeground(Color.RED);
        }
    }

    private void doConnect() {
        final String host = hostField.getText().trim();
        final int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Invalid port number");
            return;
        }
        connectionStatus.setText("Connecting...");
        new Thread(new Runnable() {
            public void run() {
                try {
                    collector.connect(host, port);
                    Thread.sleep(500);
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() { updateConnectionStatus(); }
                    });
                } catch (Exception e) {
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            connectionStatus.setText("Failed: " + host + ":" + port);
                            connectionStatus.setForeground(Color.RED);
                        }
                    });
                }
            }
        }).start();
    }

    private void applyThresholds() {
        File tmp = null;
        BufferedWriter w = null;
        try {
            tmp = File.createTempFile("jvmmon-thresh", ".tmp");
            w = new BufferedWriter(new FileWriter(tmp));
            List keys = thresholdModel.getKeys();
            Map values = thresholdModel.getValues();
            for (int i = 0; i < keys.size(); i++) {
                w.write(keys.get(i) + "=" + values.get(keys.get(i)) + "\n");
            }
            w.close();
            w = null;
            collector.getThresholds().load(tmp);
            JOptionPane.showMessageDialog(this, "Thresholds applied.");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Apply failed: " + e.getMessage());
        } finally {
            if (w != null) { try { w.close(); } catch (Exception ignored) {} }
            if (tmp != null) tmp.delete();
        }
    }

    private void saveThresholds() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("jvmmonitor.thresholds"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            applyThresholds();
            collector.getThresholds().save(fc.getSelectedFile());
            JOptionPane.showMessageDialog(this, "Saved to " + fc.getSelectedFile().getName());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Save failed: " + e.getMessage());
        }
    }

    private void loadThresholds() {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            collector.getThresholds().load(fc.getSelectedFile());
            Map newMap = collector.getThresholds().toMap();
            thresholdModel.updateValues(newMap);
            JOptionPane.showMessageDialog(this, "Loaded from " + fc.getSelectedFile().getName());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Load failed: " + e.getMessage());
        }
    }

    private void resetThresholds() {
        AlarmThresholds defaults = new AlarmThresholds();
        Map defMap = defaults.toMap();
        thresholdModel.updateValues(defMap);
        try {
            File tmp = File.createTempFile("jvmmon-thresh", ".tmp");
            defaults.save(tmp);
            collector.getThresholds().load(tmp);
            tmp.delete();
        } catch (Exception e) { /* ignore */ }
    }

    private void saveAllSettings() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("jvmmonitor.settings"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        BufferedWriter w = null;
        try {
            w = new BufferedWriter(new FileWriter(fc.getSelectedFile()));
            w.write("# JVMMonitor Settings v1.1.0\n\n");

            w.write("# Connection\n");
            w.write("connection.host=" + hostField.getText().trim() + "\n");
            w.write("connection.port=" + portField.getText().trim() + "\n\n");

            w.write("# GUI\n");
            w.write("gui.refreshInterval=" + refreshSpinner.getValue() + "\n\n");

            w.write("# Instrumentation\n");
            w.write("instr.defaultPackages=" + defaultPackagesField.getText().trim() + "\n");
            w.write("instr.captureParams=" + defaultCaptureParams.isSelected() + "\n");
            w.write("instr.maxValueLength=" + maxValueLengthSpinner.getValue() + "\n\n");

            w.write("# Alarm Thresholds\n");
            applyThresholds();
            Map thMap = collector.getThresholds().toMap();
            Iterator it = thMap.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry entry = (Map.Entry) it.next();
                w.write(entry.getKey() + "=" + entry.getValue() + "\n");
            }

            w.close();
            w = null;
            JOptionPane.showMessageDialog(this, "All settings saved to " + fc.getSelectedFile().getName());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Save failed: " + e.getMessage());
        } finally {
            if (w != null) { try { w.close(); } catch (Exception ignored) {} }
        }
    }

    private void loadAllSettings() {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(fc.getSelectedFile()));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim();

                if ("connection.host".equals(key)) hostField.setText(val);
                else if ("connection.port".equals(key)) portField.setText(val);
                else if ("gui.refreshInterval".equals(key)) {
                    try { refreshSpinner.setValue(Integer.valueOf(Integer.parseInt(val))); } catch (Exception e) {}
                }
                else if ("instr.defaultPackages".equals(key)) defaultPackagesField.setText(val);
                else if ("instr.captureParams".equals(key)) defaultCaptureParams.setSelected("true".equals(val));
                else if ("instr.maxValueLength".equals(key)) {
                    try { maxValueLengthSpinner.setValue(Integer.valueOf(Integer.parseInt(val))); } catch (Exception e) {}
                }
            }
            r.close();
            r = null;

            /* Load thresholds from the same file (AlarmThresholds ignores unknown keys) */
            collector.getThresholds().load(fc.getSelectedFile());
            Map newMap = collector.getThresholds().toMap();
            thresholdModel.updateValues(newMap);

            JOptionPane.showMessageDialog(this, "All settings loaded from " + fc.getSelectedFile().getName());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Load failed: " + e.getMessage());
        } finally {
            if (r != null) { try { r.close(); } catch (Exception ignored) {} }
        }
    }

    /* ── Threshold Table Model ───────────────── */

    private static String getDescription(String key) {
        if ("memory.liveSetMinFullGcs".equals(key)) return "Min Full GCs to detect live set growth trend";
        if ("memory.oldGenAfterFullGcPct".equals(key)) return "Old Gen % after Full GC to alarm";
        if ("memory.allocPressureRatio".equals(key)) return "Alloc/reclaim rate ratio to trigger alarm";
        if ("gc.throughputWarnPct".equals(key)) return "GC throughput % below which to warn";
        if ("gc.throughputCritPct".equals(key)) return "GC throughput % below which to raise critical";
        if ("gc.pauseMaxMs".equals(key)) return "Max GC pause (ms) to raise critical";
        if ("cpu.warnPct".equals(key)) return "JVM CPU % sustained above which to warn";
        if ("cpu.critPct".equals(key)) return "JVM CPU % sustained above which to raise critical";
        if ("cpu.runawayThreadPct".equals(key)) return "Single thread CPU % to flag as runaway";
        if ("threads.blockedWarnPct".equals(key)) return "Blocked thread % to warn";
        if ("threads.blockedCritPct".equals(key)) return "Blocked thread % to raise critical";
        if ("threads.blockedMinCount".equals(key)) return "Minimum blocked threads to trigger alarm";
        if ("threads.countWarn".equals(key)) return "Thread count to warn (thread leak)";
        if ("threads.countCrit".equals(key)) return "Thread count to raise critical";
        if ("exceptions.spikeMultiplier".equals(key)) return "Exception rate spike multiplier vs baseline";
        if ("exceptions.minRate".equals(key)) return "Min exceptions/min to trigger spike alarm";
        if ("exceptions.recurringBugMin".equals(key)) return "Same-location exception count in 60s to flag";
        if ("exceptions.highRate".equals(key)) return "Overall exception rate/min for high rate alarm";
        if ("network.closeWaitWarn".equals(key)) return "CLOSE_WAIT count to warn (connection leak)";
        if ("network.closeWaitCrit".equals(key)) return "CLOSE_WAIT count to raise critical";
        if ("network.retransmitWarnPct".equals(key)) return "TCP retransmit % to warn";
        if ("network.retransmitCritPct".equals(key)) return "TCP retransmit % to raise critical";
        if ("network.establishedWarn".equals(key)) return "Established connections to warn";
        if ("response.degradationRatio".equals(key)) return "Response time ratio vs baseline to alarm";
        if ("response.minMs".equals(key)) return "Min response time (ms) to consider degradation";
        if ("response.jdbcSlowMs".equals(key)) return "JDBC query duration (ms) to flag as slow";
        if ("response.jdbcSlowConnectMs".equals(key)) return "getConnection() duration (ms) to flag pool exhaustion";
        if ("classloader.warn".equals(key)) return "Classloader count to warn";
        if ("classloader.crit".equals(key)) return "Classloader count to raise critical";
        if ("safepoint.syncWarnMs".equals(key)) return "Avg safepoint sync time (ms) to warn";
        if ("safepoint.syncCritMs".equals(key)) return "Avg safepoint sync time (ms) to raise critical";
        if ("safepoint.totalWarnMs".equals(key)) return "Avg safepoint total time (ms) to warn";
        if ("safepoint.totalCritMs".equals(key)) return "Avg safepoint total time (ms) to raise critical";
        return "";
    }

    private static class ThresholdTableModel extends AbstractTableModel {
        private static final String[] COLS = {"Parameter", "Value", "Description"};
        private final List keys;
        private final Map values;

        ThresholdTableModel(List keys, Map values) {
            this.keys = keys;
            this.values = values;
        }

        List getKeys() { return keys; }
        Map getValues() { return values; }

        void updateValues(Map newValues) {
            Iterator it = newValues.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry e = (Map.Entry) it.next();
                values.put(e.getKey(), e.getValue());
            }
            fireTableDataChanged();
        }

        public int getRowCount() { return keys.size(); }
        public int getColumnCount() { return 3; }
        public String getColumnName(int col) { return COLS[col]; }
        public boolean isCellEditable(int row, int col) { return col == 1; }

        public Object getValueAt(int row, int col) {
            String key = (String) keys.get(row);
            if (col == 0) return key;
            if (col == 1) return values.get(key);
            return getDescription(key);
        }

        public void setValueAt(Object val, int row, int col) {
            if (col == 1) {
                String key = (String) keys.get(row);
                values.put(key, val.toString());
                fireTableCellUpdated(row, col);
            }
        }
    }
}
