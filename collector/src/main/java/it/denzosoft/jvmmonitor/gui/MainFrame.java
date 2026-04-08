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
    private final JmxBrowserPanel jmxBrowserPanel;
    private final SettingsPanel settingsPanel;

    /* Toolbar */
    private final JButton connectBtn;
    private final JButton attachBtn;
    private final JButton disconnectBtn;

    public MainFrame() {
        super("JVMMonitor v1.1.0");
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

        JButton demoBtn = new JButton("Demo Agent");
        demoBtn.setToolTipText("Launch an in-process demo agent and connect to it");
        demoBtn.setBackground(new Color(70, 150, 90));
        demoBtn.setForeground(Color.WHITE);
        demoBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                launchDemoAgent();
            }
        });

        toolbar.add(connectBtn);
        toolbar.add(attachBtn);
        toolbar.add(disconnectBtn);
        toolbar.addSeparator();
        toolbar.add(demoBtn);
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
        jmxBrowserPanel = new JmxBrowserPanel(collector);
        settingsPanel = new SettingsPanel(collector);

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
        tabs.addTab("JMX Browser", jmxBrowserPanel);
        tabs.addTab("Settings", settingsPanel);

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

        /* Wire settings panel refresh interval callback */
        settingsPanel.setOnRefreshIntervalChanged(new Runnable() {
            public void run() {
                int interval = settingsPanel.getRefreshInterval();
                refreshTimer.setDelay(interval);
                refreshTimer.setInitialDelay(interval);
            }
        });
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

    /** All panels that need periodic data updates. */
    private Object[] allPanels;

    private Object[] getAllPanels() {
        if (allPanels == null) {
            allPanels = new Object[]{
                dashboardPanel, memoryGcPanel, gcAnalysisPanel, threadContentionPanel,
                exceptionPanel, networkPanel, integrationPanel, messagingPanel,
                lockAnalysisPanel, cpuUsagePanel, cpuProfilerPanel, instrumentationPanel,
                systemResourcesPanel, debuggerPanel, diagnosticToolsPanel,
                jmxBrowserPanel, settingsPanel
            };
        }
        return allPanels;
    }

    private void refreshAll() {
        try {
            updateConnectionStatus();

            /* Update ALL panels — data + rendering.
             * updateData() feeds charts/tables with fresh data from EventStore.
             * Swing components repaint automatically when their model changes
             * (setText, fireTableDataChanged, setSeriesData, addSnapshot, etc.)
             * so all panels stay current even when not visible. */
            Object[] panels = getAllPanels();
            for (int i = 0; i < panels.length; i++) {
                safeCall(panels[i], "updateData");
            }
        } catch (Exception e) {
            System.err.println("[JVMMonitor] Refresh error: " + e.getMessage());
        }
    }

    /**
     * Call a method by name on a panel. Falls back to "refresh" if the
     * specific method doesn't exist (backwards compatibility).
     */
    private void safeCall(final Object panel, String methodName) {
        try {
            java.lang.reflect.Method m = panel.getClass().getMethod(methodName);
            m.invoke(panel);
        } catch (NoSuchMethodException e) {
            /* Panel doesn't have this method — try "refresh" as fallback */
            if (!"refresh".equals(methodName)) {
                try {
                    java.lang.reflect.Method fallback = panel.getClass().getMethod("refresh");
                    fallback.invoke(panel);
                } catch (Exception ex) { /* ignore */ }
            }
        } catch (Exception e) {
            System.err.println("[JVMMonitor] Panel " + methodName + " failed (" +
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
            if (!refreshTimer.isRunning()) {
                refreshTimer.start();
            }
        } else {
            statusLabel.setText(" Not connected");
            statusLabel.setForeground(Color.RED);
            agentInfoLabel.setText("");
            if (refreshTimer.isRunning()) {
                refreshTimer.stop();
            }
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

    /* Background demo agent server — kept across clicks so a single instance runs. */
    private volatile it.denzosoft.jvmmonitor.demo.DemoAgent demoAgentInstance;
    private volatile int demoAgentPort;
    private volatile boolean demoConnecting;

    private void launchDemoAgent() {
        if (collector.getConnection() != null && collector.getConnection().isConnected()) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "Already connected to an agent. Disconnect and start the demo?",
                    "Demo Agent", JOptionPane.YES_NO_OPTION);
            if (choice != JOptionPane.YES_OPTION) return;
            collector.disconnect();
        }

        /* Start the DemoAgent server on a free port if not already running. */
        if (demoAgentInstance == null) {
            try {
                java.net.ServerSocket probe = new java.net.ServerSocket(0);
                demoAgentPort = probe.getLocalPort();
                probe.close();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Cannot find a free port: " + ex.getMessage(),
                        "Demo Agent", JOptionPane.ERROR_MESSAGE);
                return;
            }

            demoAgentInstance = new it.denzosoft.jvmmonitor.demo.DemoAgent(demoAgentPort);
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try {
                        demoAgentInstance.run();
                    } catch (Exception ex) {
                        System.err.println("[JVMMonitor] Demo agent error: " + ex.getMessage());
                    }
                }
            }, "jvmmonitor-demo-agent");
            t.setDaemon(true);
            t.start();
        }

        if (demoConnecting) return; /* guard against multiple clicks */
        demoConnecting = true;
        final int port = demoAgentPort;
        /* Give the server a moment to bind, then connect. */
        new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(300);
                    collector.connect("127.0.0.1", port);
                    Thread.sleep(500);
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() { updateConnectionStatus(); demoConnecting = false; }
                    });
                } catch (final Exception ex) {
                    demoConnecting = false;
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            JOptionPane.showMessageDialog(MainFrame.this,
                                    "Demo agent connect failed: " + ex.getMessage(),
                                    "Demo Agent", JOptionPane.ERROR_MESSAGE);
                        }
                    });
                }
            }
        }, "jvmmonitor-demo-connect").start();
    }

    private void showAttachDialog() {
        AttachDialog dialog = new AttachDialog(this, collector);
        dialog.setVisible(true);
        updateConnectionStatus();
    }

    private void shutdown() {
        refreshTimer.stop();
        settingsPanel.setOnRefreshIntervalChanged(null);
        if (demoAgentInstance != null) {
            try {
                /* DemoAgent.run() blocks on accept(); closing instance stops it */
                demoAgentInstance = null;
            } catch (Exception ignored) {}
        }
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
