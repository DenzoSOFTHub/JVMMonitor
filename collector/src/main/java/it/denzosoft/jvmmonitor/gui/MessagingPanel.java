package it.denzosoft.jvmmonitor.gui;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.gui.chart.AlignedCellRenderer;
import it.denzosoft.jvmmonitor.gui.chart.TimeSeriesChart;
import it.denzosoft.jvmmonitor.model.QueueStats;
import it.denzosoft.jvmmonitor.model.QueueStats.QueueInfo;

import javax.swing.*;
import it.denzosoft.jvmmonitor.gui.chart.CsvExporter;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Messaging panel: monitors message queues (JMS, Kafka, RabbitMQ, etc.).
 * Shows queue depth, enqueue/dequeue rates, consumer lag, alerts.
 */
public class MessagingPanel extends JPanel {

    private final JVMMonitorCollector collector;
    private final JLabel summaryLabel;
    private final TimeSeriesChart depthChart;
    private final TimeSeriesChart rateChart;
    private final QueueTableModel tableModel;
    private final JTextArea alertArea;

    /* Track queue names for chart series */
    private final java.util.Set<String> knownQueues = new java.util.LinkedHashSet<String>();
    private static final Color[] QUEUE_COLORS = {
        new Color(30, 130, 200), new Color(220, 80, 50), new Color(50, 180, 50),
        new Color(180, 80, 200), new Color(200, 150, 30), new Color(100, 200, 200)
    };

    public MessagingPanel(JVMMonitorCollector collector) {
        this.collector = collector;
        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        summaryLabel = new JLabel("Messaging: no data");
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.BOLD, 13f));
        add(summaryLabel, BorderLayout.NORTH);

        /* Charts */
        depthChart = new TimeSeriesChart("Queue Depth (5 min)", "messages");
        rateChart = new TimeSeriesChart("Enqueue / Dequeue Rate (5 min)", "msg/s");

        JPanel chartsRow = new JPanel(new GridLayout(1, 2, 5, 0));
        chartsRow.add(depthChart);
        chartsRow.add(rateChart);

        /* Table */
        tableModel = new QueueTableModel();
        JTable table = new JTable(tableModel);
        table.setDefaultRenderer(Object.class, new QueueCellRenderer());
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(20);
        CsvExporter.install(table);
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Queues"));

        /* Alert area */
        alertArea = new JTextArea(3, 40);
        alertArea.setEditable(false);
        alertArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        alertArea.setForeground(new Color(180, 0, 0));
        JScrollPane alertScroll = new JScrollPane(alertArea);
        alertScroll.setBorder(BorderFactory.createTitledBorder("Queue Alerts"));

        JPanel bottomPanel = new JPanel(new BorderLayout(0, 5));
        bottomPanel.add(tableScroll, BorderLayout.CENTER);
        bottomPanel.add(alertScroll, BorderLayout.SOUTH);

        final JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chartsRow, bottomPanel);
        split.setResizeWeight(0.5);
        split.addComponentListener(new java.awt.event.ComponentAdapter() {
            public void componentResized(java.awt.event.ComponentEvent e) {
                int half = split.getHeight() / 2; if (half > 0) split.setDividerLocation(half);
            }
        });
        add(split, BorderLayout.CENTER);
    }

    public void refresh() {
        long now = System.currentTimeMillis();
        long from = now - 300000;

        /* Chart: depth and rates from history */
        List<QueueStats> history = collector.getStore().getQueueStatsHistory(from, now);

        /* Register series for each queue */
        for (int h = 0; h < history.size(); h++) {
            QueueInfo[] queues = history.get(h).getQueues();
            if (queues != null) {
                for (int q = 0; q < queues.length; q++) {
                    if (queues[q] != null && knownQueues.add(queues[q].name)) {
                        int ci = (knownQueues.size() - 1) % QUEUE_COLORS.length;
                        depthChart.defineSeries(queues[q].name, QUEUE_COLORS[ci], false);
                        rateChart.defineSeries(queues[q].name + " in", QUEUE_COLORS[ci], false);
                    }
                }
            }
        }

        /* Build chart data per queue */
        java.util.Map<String, List<long[]>> depthData = new java.util.LinkedHashMap<String, List<long[]>>();
        java.util.Map<String, List<long[]>> rateData = new java.util.LinkedHashMap<String, List<long[]>>();

        for (int h = 0; h < history.size(); h++) {
            QueueStats qs = history.get(h);
            QueueInfo[] queues = qs.getQueues();
            if (queues == null) continue;
            for (int q = 0; q < queues.length; q++) {
                if (queues[q] == null) continue;
                String name = queues[q].name;
                List<long[]> dp = depthData.get(name);
                if (dp == null) { dp = new ArrayList<long[]>(); depthData.put(name, dp); }
                dp.add(TimeSeriesChart.point(qs.getTimestamp(), queues[q].depth));

                List<long[]> rp = rateData.get(name + " in");
                if (rp == null) { rp = new ArrayList<long[]>(); rateData.put(name + " in", rp); }
                rp.add(TimeSeriesChart.point(qs.getTimestamp(), queues[q].enqueueRate));
            }
        }

        for (java.util.Map.Entry<String, List<long[]>> e : depthData.entrySet()) {
            depthChart.setSeriesData(e.getKey(), e.getValue());
        }
        for (java.util.Map.Entry<String, List<long[]>> e : rateData.entrySet()) {
            rateChart.setSeriesData(e.getKey(), e.getValue());
        }

        /* Table from latest */
        QueueStats latest = collector.getStore().getLatestQueueStats();
        if (latest == null) return;
        tableModel.setData(latest.getQueues());

        /* Summary */
        int total = latest.getQueueCount();
        int backlogged = 0;
        long totalDepth = 0;
        QueueInfo[] queues = latest.getQueues();
        if (queues != null) {
            for (int i = 0; i < queues.length; i++) {
                if (queues[i] != null) {
                    totalDepth += queues[i].depth;
                    if (queues[i].isBacklogged()) backlogged++;
                }
            }
        }
        summaryLabel.setText(String.format(
                "Messaging: %d queues  |  Total depth: %d  |  %d backlogged",
                total, totalDepth, backlogged));
        summaryLabel.setForeground(backlogged > 0 ? Color.RED : Color.BLACK);

        /* Alerts */
        StringBuilder alerts = new StringBuilder();
        if (queues != null) {
            for (int i = 0; i < queues.length; i++) {
                QueueInfo q = queues[i];
                if (q == null) continue;
                if (q.depth > 1000) {
                    alerts.append("[HIGH DEPTH] ").append(q.name).append(" (").append(q.type)
                          .append("): ").append(q.depth).append(" messages pending\n");
                }
                if (q.consumerLag > 10000) {
                    alerts.append("[CONSUMER LAG] ").append(q.name).append(": lag=")
                          .append(q.consumerLag).append(" — consumers can't keep up\n");
                }
                if (q.isStale()) {
                    alerts.append("[STALE] ").append(q.name).append(": oldest message is ")
                          .append(q.oldestMessageAge / 1000).append("s old — processing stuck?\n");
                }
                if (q.consumerCount == 0 && q.depth > 0) {
                    alerts.append("[NO CONSUMERS] ").append(q.name).append(": ")
                          .append(q.depth).append(" messages with 0 consumers!\n");
                }
            }
        }
        alertArea.setText(alerts.length() > 0 ? alerts.toString() : "No queue issues detected.");
    }

    /* ── Table Model ─────────────────────────────── */

    private static class QueueTableModel extends AbstractTableModel {
        private final String[] COLS = {
            "Queue", "Type", "Depth", "Enq/s", "Deq/s", "Consumers", "Producers",
            "Total Enqueued", "Total Dequeued", "Consumer Lag", "Oldest Msg"
        };
        private QueueInfo[] data = new QueueInfo[0];

        public void setData(QueueInfo[] data) {
            this.data = data != null ? data : new QueueInfo[0];
            fireTableDataChanged();
        }

        public int getRowCount() { return data.length; }
        public int getColumnCount() { return COLS.length; }
        public String getColumnName(int c) { return COLS[c]; }

        public Object getValueAt(int row, int col) {
            QueueInfo q = data[row];
            if (q == null) return "";
            switch (col) {
                case 0: return q.name;
                case 1: return q.type;
                case 2: return Long.valueOf(q.depth);
                case 3: return Long.valueOf(q.enqueueRate);
                case 4: return Long.valueOf(q.dequeueRate);
                case 5: return Integer.valueOf(q.consumerCount);
                case 6: return Integer.valueOf(q.producerCount);
                case 7: return Long.valueOf(q.totalEnqueued);
                case 8: return Long.valueOf(q.totalDequeued);
                case 9: return q.consumerLag > 0 ? Long.valueOf(q.consumerLag) : "";
                case 10: return q.oldestMessageAge > 0 ? (q.oldestMessageAge / 1000) + "s" : "";
                default: return "";
            }
        }
    }

    private static class QueueCellRenderer extends AlignedCellRenderer {
        protected void colorize(Component c, JTable table, Object value, int row, int col) {
            if (col == 2 && value instanceof Long) { /* Depth */
                long depth = ((Long) value).longValue();
                if (depth > 1000) { c.setForeground(Color.RED); c.setFont(c.getFont().deriveFont(Font.BOLD)); }
                else if (depth > 100) { c.setForeground(new Color(200, 100, 0)); }
            } else if (col == 9 && value instanceof Long) { /* Lag */
                long lag = ((Long) value).longValue();
                if (lag > 10000) { c.setForeground(Color.RED); c.setFont(c.getFont().deriveFont(Font.BOLD)); }
                else if (lag > 1000) { c.setForeground(new Color(200, 100, 0)); }
            } else if (col == 5 && value instanceof Integer) { /* Consumers */
                if (((Integer) value).intValue() == 0) {
                    c.setForeground(Color.RED);
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                }
            }
        }
    }
}
