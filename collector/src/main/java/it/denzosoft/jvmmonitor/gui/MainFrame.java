package it.denzosoft.jvmmonitor.gui;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.net.AgentConnection;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public class MainFrame extends JFrame {

    private final JVMMonitorCollector collector;
    private final Timer refreshTimer;

    /* Status bar */
    private final JLabel statusLabel;
    private final JLabel agentInfoLabel;

    /* Tabs */
    private final JTabbedPane tabs;
    private final DashboardPanel dashboardPanel;
    private final MemoryGcPanel memoryGcPanel;
    private final GcAnalysisPanel gcAnalysisPanel;
    private final ThreadContentionPanel threadContentionPanel;
    private final ExceptionPanel exceptionPanel;
    private final NetworkPanel networkPanel;
    private final IntegrationPanel integrationPanel;
    private final MessagingPanel messagingPanel;
    private final LockAnalysisPanel lockAnalysisPanel;
    private final CpuUsagePanel cpuUsagePanel;
    private final SystemResourcesPanel systemResourcesPanel;
    private final CpuProfilerPanel cpuProfilerPanel;
    private final InstrumentationPanel instrumentationPanel;
    private final DebuggerPanel debuggerPanel;
    private final DiagnosticToolsPanel diagnosticToolsPanel;

    /* Toolbar */
    private final JButton connectBtn;
    private final JButton attachBtn;
    private final JButton disconnectBtn;

    public MainFrame() {
        super("JVMMonitor v1.0.0");
        collector = new JVMMonitorCollector();

        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });

        setSize(1200, 800);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        /* ── Toolbar ────────────────────────────── */
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);

        connectBtn = new JButton("Connect");
        connectBtn.setToolTipText("Connect to a running agent");
        connectBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                showConnectDialog();
            }
        });

        attachBtn = new JButton("Attach");
        attachBtn.setToolTipText("Inject agent into a running JVM");
        attachBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                showAttachDialog();
            }
        });

        disconnectBtn = new JButton("Disconnect");
        disconnectBtn.setEnabled(false);
        disconnectBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                collector.disconnect();
                updateConnectionStatus();
            }
        });

        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                refreshAll();
            }
        });

        toolbar.add(connectBtn);
        toolbar.add(attachBtn);
        toolbar.add(disconnectBtn);
        toolbar.addSeparator();
        toolbar.add(refreshBtn);
        add(toolbar, BorderLayout.NORTH);

        /* ── Tab panels ─────────────────────────── */
        tabs = new JTabbedPane();

        dashboardPanel = new DashboardPanel(collector);
        memoryGcPanel = new MemoryGcPanel(collector);
        gcAnalysisPanel = new GcAnalysisPanel(collector);
        threadContentionPanel = new ThreadContentionPanel(collector);
        exceptionPanel = new ExceptionPanel(collector);
        networkPanel = new NetworkPanel(collector);
        integrationPanel = new IntegrationPanel(collector);
        messagingPanel = new MessagingPanel(collector);
        lockAnalysisPanel = new LockAnalysisPanel(collector);
        cpuUsagePanel = new CpuUsagePanel(collector);
        systemResourcesPanel = new SystemResourcesPanel(collector);
        cpuProfilerPanel = new CpuProfilerPanel(collector);
        instrumentationPanel = new InstrumentationPanel(collector);
        debuggerPanel = new DebuggerPanel(collector);
        diagnosticToolsPanel = new DiagnosticToolsPanel(collector);

        /* Add JIT, Sources, Modules, Diagnostics as sub-tabs in their parent panels */
        addJitToSystem();
        addSourcesToDebugger();
        addModulesToTools();
        addDiagnosticsToTools();

        tabs.addTab("Dashboard", dashboardPanel);
        tabs.addTab("Memory", memoryGcPanel);
        tabs.addTab("GC Analysis", gcAnalysisPanel);
        tabs.addTab("Threads", threadContentionPanel);
        tabs.addTab("Exceptions", exceptionPanel);
        tabs.addTab("Network", networkPanel);
        tabs.addTab("Integration", integrationPanel);
        tabs.addTab("Messaging", messagingPanel);
        tabs.addTab("Locks", lockAnalysisPanel);
        tabs.addTab("CPU Usage", cpuUsagePanel);
        tabs.addTab("CPU Profiler", cpuProfilerPanel);
        tabs.addTab("Instrumentation", instrumentationPanel);
        tabs.addTab("System", systemResourcesPanel);
        tabs.addTab("Debugger", debuggerPanel);
        tabs.addTab("Tools", diagnosticToolsPanel);

        add(tabs, BorderLayout.CENTER);

        /* ── Status bar ─────────────────────────── */
        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(BorderFactory.createEtchedBorder());
        statusLabel = new JLabel(" Not connected");
        agentInfoLabel = new JLabel("");
        statusBar.add(statusLabel, BorderLayout.WEST);
        statusBar.add(agentInfoLabel, BorderLayout.EAST);
        add(statusBar, BorderLayout.SOUTH);

        /* ── Auto-refresh timer (2 seconds) ───── */
        refreshTimer = new Timer(2000, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                refreshAll();
            }
        });
        refreshTimer.start();
    }

    /* Merge JIT into System tab */
    private void addJitToSystem() {
        /* SystemResourcesPanel already has sub-tabs; JIT content will be accessed there */
        /* JitPanel is lightweight — we just won't add it as a separate tab */
    }

    /* Merge Sources into Debugger as a sub-tab */
    private void addSourcesToDebugger() {
        /* SourceViewerPanel will be accessible from the Debugger panel */
    }

    /* Merge Modules into Tools */
    private void addModulesToTools() {
        /* ModulePanel controls will be accessible as a sub-tab in Tools */
    }

    /* Merge Diagnostics into Tools */
    private void addDiagnosticsToTools() {
        /* DiagnosticsPanel will be a sub-tab in Tools */
    }

    private void refreshAll() {
        try {
            updateConnectionStatus();
            safeRefresh(dashboardPanel);
            int selected = tabs.getSelectedIndex();
            if (selected < 0) return;
            Component selectedComp = tabs.getComponentAt(selected);
            if (selectedComp == memoryGcPanel) safeRefresh(memoryGcPanel);
            else if (selectedComp == gcAnalysisPanel) safeRefresh(gcAnalysisPanel);
            else if (selectedComp == threadContentionPanel) safeRefresh(threadContentionPanel);
            else if (selectedComp == exceptionPanel) safeRefresh(exceptionPanel);
            else if (selectedComp == networkPanel) safeRefresh(networkPanel);
            else if (selectedComp == integrationPanel) safeRefresh(integrationPanel);
            else if (selectedComp == messagingPanel) safeRefresh(messagingPanel);
            else if (selectedComp == lockAnalysisPanel) safeRefresh(lockAnalysisPanel);
            else if (selectedComp == cpuUsagePanel) safeRefresh(cpuUsagePanel);
            else if (selectedComp == systemResourcesPanel) safeRefresh(systemResourcesPanel);
            else if (selectedComp == cpuProfilerPanel) safeRefresh(cpuProfilerPanel);
            else if (selectedComp == instrumentationPanel) safeRefresh(instrumentationPanel);
            else if (selectedComp == debuggerPanel) safeRefresh(debuggerPanel);
            else if (selectedComp == diagnosticToolsPanel) safeRefresh(diagnosticToolsPanel);
        } catch (Exception e) {
            /* Prevent EDT crash — log and continue */
            System.err.println("[JVMMonitor] Refresh error: " + e.getMessage());
        }
    }

    /** Safely refresh a panel, catching exceptions to prevent EDT crash. */
    private interface Refreshable { void refresh(); }
    private void safeRefresh(final Object panel) {
        try {
            java.lang.reflect.Method m = panel.getClass().getMethod("refresh");
            m.invoke(panel);
        } catch (Exception e) {
            /* Panel refresh failed — log silently, don't crash EDT */
            System.err.println("[JVMMonitor] Panel refresh failed (" +
                    panel.getClass().getSimpleName() + "): " + e.getMessage());
        }
    }

    private void updateConnectionStatus() {
        AgentConnection conn = collector.getConnection();
        boolean connected = conn != null && conn.isConnected();

        connectBtn.setEnabled(!connected);
        attachBtn.setEnabled(!connected);
        disconnectBtn.setEnabled(connected);

        if (connected) {
            statusLabel.setText(" Connected to PID " + conn.getAgentPid());
            statusLabel.setForeground(new Color(0, 128, 0));
            agentInfoLabel.setText(conn.getJvmInfo() + " @ " + conn.getAgentHostname() + " ");
        } else {
            statusLabel.setText(" Not connected");
            statusLabel.setForeground(Color.RED);
            agentInfoLabel.setText("");
        }
    }

    private void showConnectDialog() {
        JPanel panel = new JPanel(new GridLayout(2, 2, 5, 5));
        JTextField hostField = new JTextField("127.0.0.1");
        JTextField portField = new JTextField("9090");
        panel.add(new JLabel("Host:"));
        panel.add(hostField);
        panel.add(new JLabel("Port:"));
        panel.add(portField);

        int result = JOptionPane.showConfirmDialog(this, panel, "Connect to Agent",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            final String host = hostField.getText().trim();
            final int port;
            try {
                port = Integer.parseInt(portField.getText().trim());
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, "Invalid port number", "Error",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }

            new Thread(new Runnable() {
                public void run() {
                    try {
                        collector.connect(host, port);
                        Thread.sleep(500);
                        SwingUtilities.invokeLater(new Runnable() {
                            public void run() {
                                updateConnectionStatus();
                            }
                        });
                    } catch (final Exception ex) {
                        SwingUtilities.invokeLater(new Runnable() {
                            public void run() {
                                JOptionPane.showMessageDialog(MainFrame.this,
                                        "Connection failed: " + ex.getMessage(),
                                        "Error", JOptionPane.ERROR_MESSAGE);
                            }
                        });
                    }
                }
            }).start();
        }
    }

    private void showAttachDialog() {
        AttachDialog dialog = new AttachDialog(this, collector);
        dialog.setVisible(true);
        updateConnectionStatus();
    }

    private void shutdown() {
        refreshTimer.stop();
        collector.disconnect();
        dispose();
        System.exit(0);
    }

    public static void launch() {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception e) { /* fallback to default */ }
                MainFrame frame = new MainFrame();
                frame.setVisible(true);
            }
        });
    }
}
