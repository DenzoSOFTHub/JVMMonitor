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

        /* Bottom: port + buttons */
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));
        bottom.add(new JLabel("Agent Port:"));
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
                    String agentPath = findAgentLibrary();
                    String options = "port=" + port;

                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            self.setTitle("Attaching to PID " + pid + "...");
                        }
                    });

                    AttachHelper.attach(pid, agentPath, options);
                    Thread.sleep(1000);
                    collector.connect("127.0.0.1", port);
                    Thread.sleep(500);

                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            JOptionPane.showMessageDialog(self,
                                    "Successfully attached to PID " + pid,
                                    "Success", JOptionPane.INFORMATION_MESSAGE);
                            dispose();
                        }
                    });
                } catch (final Exception ex) {
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            JOptionPane.showMessageDialog(self,
                                    "Attach failed: " + ex.getMessage(),
                                    "Error", JOptionPane.ERROR_MESSAGE);
                            self.setTitle("Attach to JVM");
                        }
                    });
                }
            }
        }).start();
    }

    private static String findAgentLibrary() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String libName = os.contains("win") ? "jvmmonitor.dll" : "jvmmonitor.so";
        String jarDir = System.getProperty("user.dir");
        String[] paths = new String[]{
            jarDir + "/" + libName,
            jarDir + "/dist/linux/" + libName,
            jarDir + "/dist/windows/" + libName,
        };
        for (int i = 0; i < paths.length; i++) {
            if (new java.io.File(paths[i]).exists()) return paths[i];
        }
        return libName;
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
            return data.get(row)[col];
        }
    }
}
