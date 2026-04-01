package it.denzosoft.jvmmonitor.gui;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.model.ThreadInfo;

import javax.swing.*;
import it.denzosoft.jvmmonitor.gui.chart.CsvExporter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import it.denzosoft.jvmmonitor.gui.chart.AlignedCellRenderer;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class ThreadPanel extends JPanel {

    private final JVMMonitorCollector collector;
    private final ThreadTableModel tableModel;
    private final JLabel summaryLabel;

    public ThreadPanel(JVMMonitorCollector collector) {
        this.collector = collector;
        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        summaryLabel = new JLabel("Threads: 0");
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.BOLD, 14f));
        add(summaryLabel, BorderLayout.NORTH);

        tableModel = new ThreadTableModel();
        JTable table = new JTable(tableModel);
        table.setDefaultRenderer(Object.class, new StateCellRenderer());
        table.getColumnModel().getColumn(0).setPreferredWidth(80);
        table.getColumnModel().getColumn(1).setPreferredWidth(300);
        table.getColumnModel().getColumn(2).setPreferredWidth(120);
        table.getColumnModel().getColumn(3).setPreferredWidth(60);
        table.setAutoCreateRowSorter(true);
        add(new JScrollPane(table), BorderLayout.CENTER);
    }

    public void refresh() {
        List<ThreadInfo> threads = collector.getStore().getLatestThreadInfo();
        tableModel.setData(threads);

        int total = threads.size();
        int runnable = 0, blocked = 0, waiting = 0;
        for (int i = 0; i < threads.size(); i++) {
            switch (threads.get(i).getState()) {
                case ThreadInfo.STATE_RUNNABLE: runnable++; break;
                case ThreadInfo.STATE_BLOCKED: blocked++; break;
                case ThreadInfo.STATE_WAITING:
                case ThreadInfo.STATE_TIMED_WAITING: waiting++; break;
            }
        }
        summaryLabel.setText(String.format(
                "Threads: %d (Runnable: %d, Blocked: %d, Waiting: %d)",
                total, runnable, blocked, waiting));
    }

    private static class ThreadTableModel extends AbstractTableModel {
        private final String[] COLUMNS = {"ID", "Name", "State", "Daemon"};
        private List<ThreadInfo> data = new ArrayList<ThreadInfo>();

        public void setData(List<ThreadInfo> data) {
            this.data = data;
            fireTableDataChanged();
        }

        public int getRowCount() { return data.size(); }
        public int getColumnCount() { return COLUMNS.length; }
        public String getColumnName(int col) { return COLUMNS[col]; }

        public Object getValueAt(int row, int col) {
            ThreadInfo t = data.get(row);
            switch (col) {
                case 0: return Long.valueOf(t.getThreadId());
                case 1: return t.getName();
                case 2: return ThreadInfo.stateToString(t.getState());
                case 3: return t.isDaemon() ? "yes" : "no";
                default: return "";
            }
        }
    }

    private static class StateCellRenderer extends AlignedCellRenderer {
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int col) {
            Component c = super.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, col);
            if (col == 2 && !isSelected) {
                String state = value.toString();
                if ("BLOCKED".equals(state)) c.setForeground(Color.RED);
                else if ("RUNNABLE".equals(state)) c.setForeground(new Color(0, 128, 0));
                else if (state.contains("WAITING")) c.setForeground(new Color(200, 150, 0));
                else c.setForeground(Color.BLACK);
            } else if (!isSelected) {
                c.setForeground(Color.BLACK);
            }
            return c;
        }
    }
}
