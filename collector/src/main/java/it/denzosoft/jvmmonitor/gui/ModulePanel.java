package it.denzosoft.jvmmonitor.gui;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.net.AgentConnection;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

public class ModulePanel extends JPanel {

    private final JVMMonitorCollector collector;

    private static final String[] MODULE_NAMES = {
        "alloc", "locks", "memory", "resources", "io", "method"
    };
    private static final String[] MODULE_DESCS = {
        "Allocation profiling (sampling, lifetime tracking, bytecode analysis)",
        "Lock contention analysis (monitor events, owner stacks, lock scope)",
        "Memory leak detection (instance count, heap snapshot, retention paths)",
        "Resource leak tracking (JDBC connections, streams, sockets)",
        "I/O latency analysis (DB, HTTP, file, socket wait times)",
        "Method profiling (targeted entry/exit timing)"
    };
    private static final String[][] LEVEL_DESCS = {
        {"Off", "Statistical (~2%)", "Detailed (~5%)", "Surgical (~10%)"},
        {"Off", "Statistical (~2%)", "Detailed (~5%)", "Surgical (~8%)"},
        {"Off", "Statistical (~2%)", "Detailed (STW ~2s)", "Surgical (~5%)"},
        {"Off", "Statistical (~2%)", "Detailed (~3%)", "Surgical (~5%)"},
        {"Off", "Statistical (~2%)", "Detailed (~4%)", "Surgical (~8%)"},
        {"Off", "Statistical (~3%)", "Detailed (~8%)", "Surgical (~15%)"}
    };

    private final JSlider[] levelSliders;
    private final JLabel[] levelLabels;
    private final JTextField targetField;
    private final JSpinner durationSpinner;

    public ModulePanel(JVMMonitorCollector collector) {
        this.collector = collector;
        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        /* Module list */
        JPanel modulesPanel = new JPanel();
        modulesPanel.setLayout(new BoxLayout(modulesPanel, BoxLayout.Y_AXIS));

        levelSliders = new JSlider[MODULE_NAMES.length];
        levelLabels = new JLabel[MODULE_NAMES.length];

        for (int i = 0; i < MODULE_NAMES.length; i++) {
            JPanel row = new JPanel(new BorderLayout(10, 0));
            row.setBorder(BorderFactory.createTitledBorder(
                    MODULE_NAMES[i].toUpperCase() + " — " + MODULE_DESCS[i]));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));

            final int idx = i;
            levelSliders[i] = new JSlider(0, 3, 0);
            levelSliders[i].setMajorTickSpacing(1);
            levelSliders[i].setPaintTicks(true);
            levelSliders[i].setSnapToTicks(true);

            levelLabels[i] = new JLabel(LEVEL_DESCS[i][0]);
            levelLabels[i].setPreferredSize(new Dimension(200, 20));

            levelSliders[i].addChangeListener(new javax.swing.event.ChangeListener() {
                public void stateChanged(javax.swing.event.ChangeEvent e) {
                    int val = levelSliders[idx].getValue();
                    levelLabels[idx].setText(LEVEL_DESCS[idx][val]);
                }
            });

            JPanel sliderPanel = new JPanel(new BorderLayout());
            sliderPanel.add(levelSliders[i], BorderLayout.CENTER);
            sliderPanel.add(levelLabels[i], BorderLayout.EAST);
            row.add(sliderPanel, BorderLayout.CENTER);

            final JButton applyBtn = new JButton("Apply");
            applyBtn.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    applyModule(idx);
                }
            });
            row.add(applyBtn, BorderLayout.EAST);

            modulesPanel.add(row);
        }

        add(new JScrollPane(modulesPanel), BorderLayout.CENTER);

        /* Bottom: target + duration */
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        bottomPanel.setBorder(BorderFactory.createTitledBorder("Options"));
        bottomPanel.add(new JLabel("Target (class.method):"));
        targetField = new JTextField(25);
        bottomPanel.add(targetField);
        bottomPanel.add(new JLabel("Duration (sec):"));
        durationSpinner = new JSpinner(new SpinnerNumberModel(300, 10, 3600, 10));
        bottomPanel.add(durationSpinner);

        JButton disableAllBtn = new JButton("Disable All");
        disableAllBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                disableAll();
            }
        });
        bottomPanel.add(disableAllBtn);

        add(bottomPanel, BorderLayout.SOUTH);
    }

    private void applyModule(int idx) {
        AgentConnection conn = collector.getConnection();
        if (conn == null || !conn.isConnected()) {
            JOptionPane.showMessageDialog(this, "Not connected to agent",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String name = MODULE_NAMES[idx];
        int level = levelSliders[idx].getValue();
        String target = targetField.getText().trim();
        if (target.isEmpty()) target = null;
        int duration = ((Number) durationSpinner.getValue()).intValue();

        try {
            if (level == 0) {
                conn.disableModule(name);
            } else {
                conn.enableModule(name, level, target, duration);
            }
            JOptionPane.showMessageDialog(this,
                    name.toUpperCase() + " -> Level " + level +
                    (level > 0 ? " (" + duration + "s)" : ""),
                    "Module Updated", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void disableAll() {
        AgentConnection conn = collector.getConnection();
        if (conn == null || !conn.isConnected()) return;

        for (int i = 0; i < MODULE_NAMES.length; i++) {
            try {
                conn.disableModule(MODULE_NAMES[i]);
                levelSliders[i].setValue(0);
            } catch (Exception ex) { /* continue */ }
        }
    }
}
