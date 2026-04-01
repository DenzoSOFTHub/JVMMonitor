package it.denzosoft.jvmmonitor.gui;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.gui.chart.TimeSeriesChart;
import it.denzosoft.jvmmonitor.model.NetworkSnapshot;
import it.denzosoft.jvmmonitor.model.NetworkSnapshot.SocketInfo;

import javax.swing.*;
import it.denzosoft.jvmmonitor.gui.chart.CsvExporter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import it.denzosoft.jvmmonitor.gui.chart.AlignedCellRenderer;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Network monitoring panel.
 * Top: TCP traffic charts (segments in/out, retransmissions).
 * Middle: Active connections table with state, queues, warnings.
 * Bottom: Listening ports + alerts for problematic connections.
 */
public class NetworkPanel extends JPanel {

    private final JVMMonitorCollector collector;
    private final JLabel summaryLabel;
    private final TimeSeriesChart trafficChart;
    private final TimeSeriesChart retransChart;
    private final SocketTableModel socketModel;
    private final JTextArea alertArea;

    public NetworkPanel(JVMMonitorCollector collector) {
        this.collector = collector;
        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        summaryLabel = new JLabel("Network: no data");
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.BOLD, 13f));
        add(summaryLabel, BorderLayout.NORTH);

        /* Charts row */
        trafficChart = new TimeSeriesChart("TCP Segments (5 min)", "segs");
        trafficChart.defineSeries("In", new Color(30, 130, 200), true);
        trafficChart.defineSeries("Out", new Color(200, 130, 30), true);

        retransChart = new TimeSeriesChart("Retransmissions & Errors (5 min)", "count");
        retransChart.defineSeries("Retransmissions", new Color(220, 50, 50), true);
        retransChart.defineSeries("Resets", new Color(200, 100, 0), false);
        retransChart.defineSeries("Errors", new Color(150, 50, 150), false);

        JPanel chartsRow = new JPanel(new GridLayout(1, 2, 5, 0));
        chartsRow.add(trafficChart);
        chartsRow.add(retransChart);

        /* Socket table */
        socketModel = new SocketTableModel();
        JTable table = new JTable(socketModel);
        table.setDefaultRenderer(Object.class, new SocketCellRenderer());
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(18);
        CsvExporter.install(table);
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Active Connections"));

        /* Alert area */
        alertArea = new JTextArea(4, 40);
        alertArea.setEditable(false);
        alertArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        alertArea.setForeground(new Color(180, 0, 0));
        JScrollPane alertScroll = new JScrollPane(alertArea);
        alertScroll.setBorder(BorderFactory.createTitledBorder("Connection Warnings"));

        /* Bottom: table + alerts */
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

        /* Chart data from history */
        List<NetworkSnapshot> history = collector.getStore().getNetworkHistory(from, now);
        List<long[]> inPts = new ArrayList<long[]>();
        List<long[]> outPts = new ArrayList<long[]>();
        List<long[]> retransPts = new ArrayList<long[]>();
        List<long[]> resetPts = new ArrayList<long[]>();
        List<long[]> errPts = new ArrayList<long[]>();

        for (int i = 0; i < history.size(); i++) {
            NetworkSnapshot n = history.get(i);
            inPts.add(TimeSeriesChart.point(n.getTimestamp(), n.getInSegments()));
            outPts.add(TimeSeriesChart.point(n.getTimestamp(), n.getOutSegments()));
            retransPts.add(TimeSeriesChart.point(n.getTimestamp(), n.getRetransSegments()));
            resetPts.add(TimeSeriesChart.point(n.getTimestamp(), n.getOutResets()));
            errPts.add(TimeSeriesChart.point(n.getTimestamp(), n.getInErrors()));
        }
        trafficChart.setSeriesData("In", inPts);
        trafficChart.setSeriesData("Out", outPts);
        retransChart.setSeriesData("Retransmissions", retransPts);
        retransChart.setSeriesData("Resets", resetPts);
        retransChart.setSeriesData("Errors", errPts);

        /* Latest snapshot for table + summary */
        NetworkSnapshot latest = collector.getStore().getLatestNetworkSnapshot();
        if (latest == null) return;

        SocketInfo[] sockets = latest.getSockets();
        socketModel.setData(sockets);

        /* Summary */
        int listen = latest.getListenCount();
        int estab = latest.getEstablishedCount();
        int closeWait = latest.getCloseWaitCount();
        int timeWait = latest.getTimeWaitCount();
        summaryLabel.setText(String.format(
                "Sockets: %d  |  LISTEN: %d  ESTABLISHED: %d  CLOSE_WAIT: %d  TIME_WAIT: %d  |  " +
                "Segs in: %d  out: %d  retrans: %d  resets: %d",
                latest.getSocketCount(), listen, estab, closeWait, timeWait,
                latest.getInSegments(), latest.getOutSegments(),
                latest.getRetransSegments(), latest.getOutResets()));

        if (closeWait > 2) {
            summaryLabel.setForeground(Color.RED);
        } else if (latest.getRetransSegments() > 100) {
            summaryLabel.setForeground(new Color(200, 100, 0));
        } else {
            summaryLabel.setForeground(Color.BLACK);
        }

        /* Detect problematic connections */
        StringBuilder alerts = new StringBuilder();
        if (sockets != null) {
            for (int i = 0; i < sockets.length; i++) {
                SocketInfo s = sockets[i];
                if (s == null) continue;
                if (s.state == SocketInfo.STATE_CLOSE_WAIT) {
                    alerts.append("[CLOSE_WAIT] ").append(s.getLocalAddrString())
                          .append(" -> ").append(s.getRemoteAddrString())
                          .append(" (remote closed, local not — possible connection leak)\n");
                }
                if (s.state == SocketInfo.STATE_SYN_SENT) {
                    alerts.append("[SYN_SENT] ").append(s.getLocalAddrString())
                          .append(" -> ").append(s.getRemoteAddrString())
                          .append(" (connection attempt hanging — no response, check timeout)\n");
                }
                if (s.state == SocketInfo.STATE_ESTABLISHED && s.txQueue > 4096) {
                    alerts.append("[TX BACKPRESSURE] ").append(s.getLocalAddrString())
                          .append(" -> ").append(s.getRemoteAddrString())
                          .append(String.format(" (tx_queue=%d — outgoing data stuck, remote not reading)\n", s.txQueue));
                }
                if (s.state == SocketInfo.STATE_ESTABLISHED && s.rxQueue > 4096) {
                    alerts.append("[RX BACKPRESSURE] ").append(s.getLocalAddrString())
                          .append(" -> ").append(s.getRemoteAddrString())
                          .append(String.format(" (rx_queue=%d — incoming data not consumed, call without timeout?)\n", s.rxQueue));
                }
            }
        }
        if (latest.getRetransSegments() > 50) {
            alerts.append(String.format("[RETRANSMISSIONS] %d retransmitted segments — network issues or slow remote\n",
                    latest.getRetransSegments()));
        }
        alertArea.setText(alerts.length() > 0 ? alerts.toString() : "No connection issues detected.");
    }

    /* ── Socket Table Model ──────────────────────────── */

    private static class SocketTableModel extends AbstractTableModel {
        private final String[] COLS = {"Dir", "Destination / Service", "State", "Requests", "Bytes In", "Bytes Out", "TX Queue", "RX Queue"};
        private SocketInfo[] data = new SocketInfo[0];

        public void setData(SocketInfo[] data) {
            /* Filter out null entries */
            if (data != null) {
                java.util.List<SocketInfo> valid = new java.util.ArrayList<SocketInfo>();
                for (int i = 0; i < data.length; i++) {
                    if (data[i] != null) valid.add(data[i]);
                }
                this.data = valid.toArray(new SocketInfo[valid.size()]);
            } else {
                this.data = new SocketInfo[0];
            }
            fireTableDataChanged();
        }

        public int getRowCount() { return data.length; }
        public int getColumnCount() { return COLS.length; }
        public String getColumnName(int c) { return COLS[c]; }

        public Object getValueAt(int row, int col) {
            if (row >= data.length || data[row] == null) return "";
            SocketInfo s = data[row];
            switch (col) {
                case 0: return s.getDirection();
                case 1: return s.getDestination();
                case 2: return s.getStateName();
                case 3: return s.requestCount > 0 ? Integer.valueOf(s.requestCount) : "";
                case 4: return s.bytesIn > 0 ? s.formatBytes(s.bytesIn) : "";
                case 5: return s.bytesOut > 0 ? s.formatBytes(s.bytesOut) : "";
                case 6: return s.txQueue > 0 ? Long.valueOf(s.txQueue) : "";
                case 7: return s.rxQueue > 0 ? Long.valueOf(s.rxQueue) : "";
                default: return "";
            }
        }
    }

    /* ── Cell Renderer ───────────────────────────────── */

    private static class SocketCellRenderer extends AlignedCellRenderer {
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int col) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
            if (isSelected) return c;

            String text = value != null ? value.toString() : "";
            c.setFont(c.getFont().deriveFont(Font.PLAIN));
            c.setForeground(Color.BLACK);

            if (col == 0) { /* Direction */
                if ("OUT".equals(text)) {
                    c.setForeground(new Color(200, 100, 0));
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                } else if ("IN".equals(text)) {
                    c.setForeground(new Color(0, 128, 0));
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                } else if ("LISTEN".equals(text)) {
                    c.setForeground(new Color(0, 100, 180));
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                }
            } else if (col == 1) { /* Destination */
                if (text.contains("LEAK") || text.contains("STUCK") || text.contains("HANGING")) {
                    c.setForeground(Color.RED);
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                }
            } else if (col == 2) { /* State */
                if ("CLOSE_WAIT".equals(text)) {
                    c.setForeground(Color.RED);
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                } else if ("SYN_SENT".equals(text)) {
                    c.setForeground(new Color(200, 100, 0));
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                } else if ("ESTABLISHED".equals(text)) {
                    c.setForeground(new Color(0, 128, 0));
                } else if ("LISTEN".equals(text)) {
                    c.setForeground(new Color(0, 100, 180));
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                }
            } else if ((col == 6 || col == 7) && value instanceof Long) { /* Queues */
                long v = ((Long) value).longValue();
                if (v > 4096) {
                    c.setForeground(Color.RED);
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                } else if (v > 0) {
                    c.setForeground(new Color(200, 100, 0));
                }
            }
            return c;
        }
    }
}
