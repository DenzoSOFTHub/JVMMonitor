package it.denzosoft.jvmmonitor.gui;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.gui.chart.StackedAreaChart;
import it.denzosoft.jvmmonitor.model.ThreadInfo;

import javax.swing.*;
import it.denzosoft.jvmmonitor.gui.chart.CsvExporter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import it.denzosoft.jvmmonitor.gui.chart.AlignedCellRenderer;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Threads & Contention panel.
 * Top: Stacked area chart of thread state distribution over time.
 * Bottom: Thread table with color-coded states.
 */
public class ThreadContentionPanel extends JPanel {

    private final JVMMonitorCollector collector;
    private final StackedAreaChart stateChart;
    private final StackedAreaChart activityChart;
    private final ThreadTableModel tableModel;
    private final JLabel summaryLabel;
    private final JTextField threadFilterField;
    private final JTextField packageFilterField;

    public ThreadContentionPanel(JVMMonitorCollector collector) {
        this.collector = collector;
        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        summaryLabel = new JLabel("Threads: 0");
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.BOLD, 13f));
        add(summaryLabel, BorderLayout.NORTH);

        stateChart = new StackedAreaChart("Thread State Distribution (last 5 min)");
        /* Order bottom-up: problematic states at bottom, healthy at top */
        stateChart.addCategory("BLOCKED", new Color(220, 50, 50));
        stateChart.addCategory("TIMED_WAITING", new Color(180, 140, 200));
        stateChart.addCategory("WAITING", new Color(220, 170, 30));
        stateChart.addCategory("RUNNABLE", new Color(50, 160, 50));

        tableModel = new ThreadTableModel();
        JTable table = new JTable(tableModel);
        table.setDefaultRenderer(Object.class, new StateCellRenderer());
        table.getColumnModel().getColumn(0).setPreferredWidth(80);
        table.getColumnModel().getColumn(1).setPreferredWidth(300);
        table.getColumnModel().getColumn(2).setPreferredWidth(120);
        table.getColumnModel().getColumn(3).setPreferredWidth(60);
        table.setAutoCreateRowSorter(true);

        /* Thread Activity chart: what threads are doing (DB, Network, Disk, etc.) */
        JPanel activityPanel = new JPanel(new BorderLayout(5, 3));
        JPanel filterBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        filterBar.add(new JLabel("Thread name filter:"));
        threadFilterField = new JTextField("", 15);
        threadFilterField.setToolTipText("Regex filter on thread name (e.g., http-nio)");
        filterBar.add(threadFilterField);
        filterBar.add(new JLabel("App packages:"));
        packageFilterField = new JTextField("com.myapp", 15);
        packageFilterField.setToolTipText("Show only threads with these packages in stack (comma-separated)");
        filterBar.add(packageFilterField);
        activityPanel.add(filterBar, BorderLayout.NORTH);

        activityChart = new StackedAreaChart("Thread Activity (what threads are doing now)");
        activityChart.addCategory("DATABASE", new Color(0, 100, 180));
        activityChart.addCategory("NETWORK", new Color(200, 100, 0));
        activityChart.addCategory("DISK I/O", new Color(150, 80, 200));
        activityChart.addCategory("MESSAGING", new Color(180, 50, 50));
        activityChart.addCategory("WEB SERVICE", new Color(50, 180, 50));
        activityChart.addCategory("LOCK WAIT", new Color(220, 50, 50));
        activityChart.addCategory("APPLICATION", new Color(80, 160, 200));
        activityChart.addCategory("OTHER", new Color(180, 180, 180));
        activityPanel.add(activityChart, BorderLayout.CENTER);

        /* Top tabs: State Chart + Thread Activity */
        JTabbedPane topTabs = new JTabbedPane();
        topTabs.addTab("Thread States", stateChart);
        topTabs.addTab("Thread Activity", activityPanel);

        /* Bottom: Thread Table */
        JScrollPane tableScroll = new JScrollPane(table);

        final JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topTabs, tableScroll);
        split.setResizeWeight(0.5);
        split.addComponentListener(new java.awt.event.ComponentAdapter() {
            public void componentResized(java.awt.event.ComponentEvent e) {
                int half = split.getHeight() / 2;
                if (half > 0) split.setDividerLocation(half);
            }
        });
        add(split, BorderLayout.CENTER);
    }

    public void updateData() {
        List<ThreadInfo> threads = collector.getStore().getLatestThreadInfo();
        tableModel.setData(threads);

        int total = threads.size(), runnable = 0, blocked = 0, waiting = 0, timedWait = 0;
        for (int i = 0; i < threads.size(); i++) {
            switch (threads.get(i).getState()) {
                case ThreadInfo.STATE_RUNNABLE: runnable++; break;
                case ThreadInfo.STATE_BLOCKED: blocked++; break;
                case ThreadInfo.STATE_WAITING: waiting++; break;
                case ThreadInfo.STATE_TIMED_WAITING: timedWait++; break;
            }
        }

        summaryLabel.setText(String.format(
                "Threads: %d  |  RUNNABLE: %d  BLOCKED: %d  WAITING: %d  TIMED_WAITING: %d",
                total, runnable, blocked, waiting, timedWait));
        if (total > 0 && blocked * 100 / total > 30) {
            summaryLabel.setForeground(Color.RED);
        } else {
            summaryLabel.setForeground(Color.BLACK);
        }

        /* Add snapshot to stacked chart */
        if (total > 0) {
            /* Order must match addCategory: BLOCKED, TIMED_WAITING, WAITING, RUNNABLE */
            stateChart.addSnapshot(System.currentTimeMillis(),
                    new int[]{blocked, timedWait, waiting, runnable});
        }

        /* Thread activity analysis */
        String nameFilter = threadFilterField.getText().trim();
        String pkgFilter = packageFilterField.getText().trim();
        int db = 0, net = 0, disk = 0, msg = 0, ws = 0, lock = 0, app = 0, other = 0;

        for (int i = 0; i < threads.size(); i++) {
            ThreadInfo t = threads.get(i);
            String name = t.getName().toLowerCase();

            /* Apply thread name filter */
            if (!nameFilter.isEmpty()) {
                try {
                    if (!name.matches("(?i).*" + nameFilter + ".*")) continue;
                } catch (Exception ex) { /* invalid regex, skip filter */ }
            }

            /* Classify by thread name pattern */
            if (name.contains("hikari") || name.contains("jdbc") || name.contains("db")
                || name.contains("postgres") || name.contains("mysql") || name.contains("oracle")
                || name.contains("connection-pool")) {
                db++;
            } else if (name.contains("http") || name.contains("nio") || name.contains("netty")
                       || name.contains("grpc") || name.contains("socket")) {
                if (name.contains("exec") || name.contains("worker")) ws++;
                else net++;
            } else if (name.contains("kafka") || name.contains("rabbit") || name.contains("jms")
                       || name.contains("activemq") || name.contains("consumer") || name.contains("producer")) {
                msg++;
            } else if (name.contains("file") || name.contains("io-") || name.contains("async-io")) {
                disk++;
            } else if (t.getState() == ThreadInfo.STATE_BLOCKED) {
                lock++;
            } else if (name.contains("scheduler") || name.contains("worker") || name.contains("pool")) {
                app++;
            } else {
                other++;
            }
        }

        if (total > 0) {
            /* Order: DATABASE, NETWORK, DISK, MESSAGING, WEB SERVICE, LOCK WAIT, APPLICATION, OTHER */
            activityChart.addSnapshot(System.currentTimeMillis(),
                    new int[]{db, net, disk, msg, ws, lock, app, other});
        }
    }

    public void render() {
        repaint();
    }

    public void refresh() {
        updateData();
        render();
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
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
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
