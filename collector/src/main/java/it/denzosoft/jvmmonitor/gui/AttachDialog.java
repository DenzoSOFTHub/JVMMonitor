package it.denzosoft.jvmmonitor.gui;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.net.AttachHelper;

import javax.swing.*;
import it.denzosoft.jvmmonitor.gui.chart.CsvExporter;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;

public class AttachDialog extends JDialog {

    private final JVMMonitorCollector collector;
    private final JvmTableModel tableModel;
    private final JTable table;
    private final JComboBox agentTypeCombo;
    private final JTextField agentPathField;
    private final JSpinner portSpinner;

    public AttachDialog(Frame owner, JVMMonitorCollector collector) {
        super(owner, "Attach to JVM", true);
        this.collector = collector;

        setSize(600, 400);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout(5, 5));

        /* Info */
        JLabel infoLabel = new JLabel("  Select a JVM to inject the agent into:");
        add(infoLabel, BorderLayout.NORTH);

        /* JVM list */
        tableModel = new JvmTableModel();
        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(80);
        table.getColumnModel().getColumn(1).setPreferredWidth(450);
        add(new JScrollPane(table), BorderLayout.CENTER);

        /* Bottom: agent type + port + buttons */
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));

        bottom.add(new JLabel("Agent:"));
        /* Detect available agents and build combo items */
        String javaAgentPath = findJavaAgent();
        String nativeAgentPath = findNativeAgent();
        System.err.println("[JVMMonitor] Agent search: Java=" + javaAgentPath + " Native=" + nativeAgentPath);
        System.err.println("[JVMMonitor] JAR dir=" + getJarDir() + " CWD=" + System.getProperty("user.dir"));
        String javaLabel = "Java Agent (portable, recommended)";
        String nativeLabel = "Native Agent (.so/.dll)";
        if (javaAgentPath != null) {
            javaLabel = javaLabel + " — " + new java.io.File(javaAgentPath).getName();
        } else {
            javaLabel = javaLabel + " — NOT FOUND";
        }
        if (nativeAgentPath != null) {
            nativeLabel = nativeLabel + " — " + new java.io.File(nativeAgentPath).getName();
        } else {
            nativeLabel = nativeLabel + " — NOT FOUND";
        }
        agentTypeCombo = new JComboBox(new String[]{javaLabel, nativeLabel, "Custom..."});
        agentTypeCombo.setToolTipText("Select which agent to inject into the target JVM");
        /* Default to Java agent if found, otherwise native */
        if (javaAgentPath != null) {
            agentTypeCombo.setSelectedIndex(0);
        } else if (nativeAgentPath != null) {
            agentTypeCombo.setSelectedIndex(1);
        } else {
            agentTypeCombo.setSelectedIndex(2); /* Custom if nothing found */
        }
        bottom.add(agentTypeCombo);

        agentPathField = new JTextField(30);
        agentPathField.setVisible(false);
        agentPathField.setToolTipText("Full path to agent file");
        bottom.add(agentPathField);

        JButton browseBtn = new JButton("...");
        browseBtn.setVisible(false);
        browseBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                JFileChooser fc = new JFileChooser();
                if (fc.showOpenDialog(AttachDialog.this) == JFileChooser.APPROVE_OPTION) {
                    agentPathField.setText(fc.getSelectedFile().getAbsolutePath());
                }
            }
        });
        bottom.add(browseBtn);

        final JButton browseBtnRef = browseBtn;
        agentTypeCombo.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                boolean custom = agentTypeCombo.getSelectedIndex() == 2;
                agentPathField.setVisible(custom);
                browseBtnRef.setVisible(custom);
                AttachDialog.this.pack();
            }
        });

        bottom.add(new JLabel("Port:"));
        portSpinner = new JSpinner(new SpinnerNumberModel(9090, 1024, 65535, 1));
        bottom.add(portSpinner);

        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                loadJvmList();
            }
        });
        bottom.add(refreshBtn);

        JButton attachBtn = new JButton("Attach & Connect");
        attachBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                doAttach();
            }
        });
        bottom.add(attachBtn);

        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });
        bottom.add(cancelBtn);

        add(bottom, BorderLayout.SOUTH);

        /* Load JVM list */
        loadJvmList();
    }

    private void loadJvmList() {
        try {
            if (!AttachHelper.isAvailable()) {
                JOptionPane.showMessageDialog(this,
                        "Attach API not available.\n" + AttachHelper.getError(),
                        "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            List<String[]> vms = AttachHelper.listJvms();
            tableModel.setData(vms);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                    "Failed to list JVMs: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void doAttach() {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Select a JVM first",
                    "Error", JOptionPane.WARNING_MESSAGE);
            return;
        }

        final String pid = tableModel.getData().get(row)[0];
        final int port = ((Number) portSpinner.getValue()).intValue();
        final JDialog self = this;

        new Thread(new Runnable() {
            public void run() {
                try {
                    int agentChoice = agentTypeCombo.getSelectedIndex();
                    String agentPath;
                    boolean isJavaAgent;

                    if (agentChoice == 0) {
                        /* Java Agent */
                        agentPath = findJavaAgent();
                        isJavaAgent = true;
                        if (agentPath == null) {
                            throw new Exception(
                                "Java agent (jvmmonitor-agent.jar) not found.\n" +
                                "Place it in the same directory as jvmmonitor.jar or in dist/.");
                        }
                    } else if (agentChoice == 1) {
                        /* Native Agent */
                        agentPath = findNativeAgent();
                        isJavaAgent = false;
                        if (agentPath == null) {
                            throw new Exception(
                                "Native agent (jvmmonitor.so / jvmmonitor.dll) not found.\n" +
                                "Place it in the same directory as jvmmonitor.jar or in dist/linux/ (dist/windows/).");
                        }
                    } else {
                        /* Custom path */
                        agentPath = agentPathField.getText().trim();
                        if (agentPath.length() == 0) {
                            throw new Exception("Please enter the path to the agent file.");
                        }
                        if (!new java.io.File(agentPath).isFile()) {
                            throw new Exception("File not found: " + agentPath);
                        }
                        isJavaAgent = agentPath.endsWith(".jar");
                    }

                    final String agentType = isJavaAgent ? "Java" : "native";
                    final String finalAgentPath = agentPath;
                    String options = "port=" + port;

                    /* Check if agent is already listening on that port */
                    boolean alreadyRunning = false;
                    try {
                        java.net.Socket testSock = new java.net.Socket();
                        testSock.connect(new java.net.InetSocketAddress("127.0.0.1", port), 500);
                        testSock.close();
                        alreadyRunning = true;
                    } catch (Exception ignored) {
                        /* port not open — agent not running, proceed with attach */
                    }

                    if (alreadyRunning) {
                        /* Agent already listening — just connect */
                        SwingUtilities.invokeLater(new Runnable() {
                            public void run() {
                                self.setTitle("Agent already running on port " + port + " — connecting...");
                            }
                        });
                    } else {
                        SwingUtilities.invokeLater(new Runnable() {
                            public void run() {
                                self.setTitle("Attaching " + agentType + " agent to PID " + pid + "...");
                            }
                        });

                        if (isJavaAgent) {
                            AttachHelper.attachJavaAgent(pid, agentPath, options);
                        } else {
                            AttachHelper.attach(pid, agentPath, options);
                        }
                        Thread.sleep(1500);
                    }

                    collector.connect("127.0.0.1", port);
                    Thread.sleep(500);

                    final boolean wasAlreadyRunning = alreadyRunning;
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            String msg;
                            if (wasAlreadyRunning) {
                                msg = "Agent was already running on port " + port + ".\nConnected to PID " + pid + ".";
                            } else {
                                msg = "Successfully attached " + agentType + " agent to PID " + pid +
                                      "\nAgent: " + finalAgentPath;
                            }
                            JOptionPane.showMessageDialog(self, msg,
                                    "Success", JOptionPane.INFORMATION_MESSAGE);
                            dispose();
                        }
                    });
                } catch (final Exception ex) {
                    /* Print full stack trace to stderr for debugging */
                    System.err.println("[JVMMonitor] Attach failed:");
                    ex.printStackTrace(System.err);

                    final String msg = ex.getMessage() != null ? ex.getMessage()
                            : ex.getClass().getName() + " (no message)";
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            JOptionPane.showMessageDialog(self,
                                    "Attach failed: " + msg,
                                    "Error", JOptionPane.ERROR_MESSAGE);
                            self.setTitle("Attach to JVM");
                        }
                    });
                }
            }
        }).start();
    }

    /** Get the directory where the collector JAR lives. */
    private static String getJarDir() {
        try {
            String path = AttachDialog.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI().getPath();
            return new java.io.File(path).getParent();
        } catch (Exception e) {
            return null;
        }
    }

    /** Find the Java agent JAR (portable, preferred). */
    private static String findJavaAgent() {
        String cwd = System.getProperty("user.dir");
        String jarDir = getJarDir();

        java.util.List paths = new java.util.ArrayList();
        /* Next to collector JAR (dist/jvmmonitor-agent.jar) */
        if (jarDir != null) {
            paths.add(jarDir + "/jvmmonitor-agent.jar");
            paths.add(jarDir + "/../jvmmonitor-agent.jar");
        }
        /* Current working directory */
        paths.add(cwd + "/jvmmonitor-agent.jar");
        paths.add(cwd + "/dist/jvmmonitor-agent.jar");

        for (int i = 0; i < paths.size(); i++) {
            java.io.File f = new java.io.File((String) paths.get(i));
            if (f.isFile()) return f.getAbsolutePath();
        }
        return null;
    }

    /** Find the native agent library (.so / .dll). */
    private static String findNativeAgent() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        String libName = osName.contains("win") ? "jvmmonitor.dll" : "jvmmonitor.so";
        String subDir = osName.contains("win") ? "windows" : "linux";
        String cwd = System.getProperty("user.dir");
        String jarDir = getJarDir();

        java.util.List paths = new java.util.ArrayList();
        /* Next to collector JAR (dist/linux/ or dist/windows/) */
        if (jarDir != null) {
            paths.add(jarDir + "/" + subDir + "/" + libName);
            paths.add(jarDir + "/" + libName);
            paths.add(jarDir + "/../" + subDir + "/" + libName);
        }
        /* Current working directory */
        paths.add(cwd + "/" + libName);
        paths.add(cwd + "/dist/" + subDir + "/" + libName);
        paths.add(cwd + "/dist/" + libName);
        /* System paths */
        paths.add("/usr/local/lib/" + libName);

        for (int i = 0; i < paths.size(); i++) {
            java.io.File f = new java.io.File((String) paths.get(i));
            if (f.isFile()) return f.getAbsolutePath();
        }
        return null;
    }

    private static class JvmTableModel extends AbstractTableModel {
        private final String[] COLUMNS = {"PID", "Application"};
        private List<String[]> data = new ArrayList<String[]>();

        public List<String[]> getData() { return data; }

        public void setData(List<String[]> data) {
            this.data = data;
            fireTableDataChanged();
        }

        public int getRowCount() { return data.size(); }
        public int getColumnCount() { return COLUMNS.length; }
        public String getColumnName(int col) { return COLUMNS[col]; }

        public Object getValueAt(int row, int col) {
            if (row < 0 || row >= data.size()) return "";
            String[] r = data.get(row);
            return (col >= 0 && col < r.length) ? r[col] : "";
        }
    }
}
