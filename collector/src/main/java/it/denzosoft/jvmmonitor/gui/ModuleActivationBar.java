package it.denzosoft.jvmmonitor.gui;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.net.AgentConnection;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * Reusable bar that shows "Module X is not active" with an Enable button.
 * When the module is active, the bar hides itself.
 * Add this to the NORTH of any panel that requires an on-demand module.
 */
public class ModuleActivationBar extends JPanel {

    private final JVMMonitorCollector collector;
    private final String moduleName;
    private final int defaultLevel;
    private final JLabel statusLabel;
    private final JButton enableBtn;
    private final JButton disableBtn;
    private volatile boolean moduleActive;

    /**
     * @param collector the collector instance
     * @param moduleName module name as registered in the agent (e.g., "exceptions", "locks", "network")
     * @param displayName human-readable name for the UI
     * @param defaultLevel activation level (1=statistical, 2=detailed, 3=surgical)
     */
    public ModuleActivationBar(JVMMonitorCollector collector, String moduleName,
                                String displayName, int defaultLevel) {
        this.collector = collector;
        this.moduleName = moduleName;
        this.defaultLevel = defaultLevel;

        setLayout(new FlowLayout(FlowLayout.LEFT, 5, 3));
        setBackground(new Color(255, 255, 220));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(200, 180, 100)),
                BorderFactory.createEmptyBorder(2, 5, 2, 5)));

        statusLabel = new JLabel(displayName + " module is not active.");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD));
        add(statusLabel);

        enableBtn = new JButton("Enable " + displayName);
        enableBtn.setBackground(new Color(50, 160, 50));
        enableBtn.setForeground(Color.WHITE);
        enableBtn.setFocusPainted(false);
        enableBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                enableModule();
            }
        });
        add(enableBtn);

        disableBtn = new JButton("Disable");
        disableBtn.setBackground(new Color(200, 50, 50));
        disableBtn.setForeground(Color.WHITE);
        disableBtn.setFocusPainted(false);
        disableBtn.setVisible(false);
        disableBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                disableModule();
            }
        });
        add(disableBtn);
    }

    private void enableModule() {
        AgentConnection conn = collector.getConnection();
        if (conn == null || !conn.isConnected()) {
            JOptionPane.showMessageDialog(this,
                    "Not connected to any agent.\nConnect first, then enable the module.",
                    "Not Connected", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            conn.enableModule(moduleName, defaultLevel, null, 0);
            moduleActive = true;
            refreshBarState();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Failed to enable module: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void disableModule() {
        AgentConnection conn = collector.getConnection();
        if (conn == null || !conn.isConnected()) return;
        try {
            conn.disableModule(moduleName);
            moduleActive = false;
            refreshBarState();
        } catch (Exception ex) {
            /* ignore */
        }
    }

    /** Call this from the panel's updateData() to auto-detect if data is arriving. */
    public void setDataReceived(boolean hasData) {
        if (hasData && !moduleActive) {
            moduleActive = true;
            refreshBarState();
        }
    }

    private void refreshBarState() {
        if (moduleActive) {
            setBackground(new Color(220, 255, 220));
            statusLabel.setText(moduleName + " module active");
            statusLabel.setForeground(new Color(0, 100, 0));
            enableBtn.setVisible(false);
            disableBtn.setVisible(true);
        } else {
            setBackground(new Color(255, 255, 220));
            statusLabel.setText(moduleName + " module is not active.");
            statusLabel.setForeground(Color.BLACK);
            enableBtn.setVisible(true);
            disableBtn.setVisible(false);
        }
    }
}
