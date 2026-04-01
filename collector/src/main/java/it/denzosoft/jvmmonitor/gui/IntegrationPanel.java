package it.denzosoft.jvmmonitor.gui;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.gui.chart.AlignedCellRenderer;
import it.denzosoft.jvmmonitor.gui.chart.TimeSeriesChart;
import it.denzosoft.jvmmonitor.model.NetworkSnapshot;
import it.denzosoft.jvmmonitor.model.NetworkSnapshot.SocketInfo;

import javax.swing.*;
import it.denzosoft.jvmmonitor.gui.chart.CsvExporter;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/**
 * Integration panel: tracks all external systems (by IP) that communicate
 * with the JVM. Shows calls/min, bytes in/out, last call time, direction.
 */
public class IntegrationPanel extends JPanel {

    private final JVMMonitorCollector collector;
    private final JLabel summaryLabel;
    private final IntegrationTableModel tableModel;
    private final TimeSeriesChart trafficChart;

    /** Accumulated integration data across refreshes. */
    private final Map<String, IntegrationEntry> entries = new LinkedHashMap<String, IntegrationEntry>();
    private NetworkSnapshot lastSnapshot = null;

    public IntegrationPanel(JVMMonitorCollector collector) {
        this.collector = collector;
        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        summaryLabel = new JLabel("Integration Map: no data");
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.BOLD, 13f));
        add(summaryLabel, BorderLayout.NORTH);

        /* Traffic chart */
        trafficChart = new TimeSeriesChart("Integration Traffic (5 min)", "KB/s");
        trafficChart.defineSeries("Inbound", new Color(50, 150, 50), true);
        trafficChart.defineSeries("Outbound", new Color(200, 100, 30), true);

        /* Table */
        tableModel = new IntegrationTableModel();
        JTable table = new JTable(tableModel);
        table.setDefaultRenderer(Object.class, new IntegrationCellRenderer());
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(20);
        CsvExporter.install(table);

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createTitledBorder(
                "External Systems (aggregated by remote IP)"));

        final JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, trafficChart, tableScroll);
        split.setResizeWeight(0.5);
        split.addComponentListener(new java.awt.event.ComponentAdapter() {
            public void componentResized(java.awt.event.ComponentEvent e) {
                int half = split.getHeight() / 2; if (half > 0) split.setDividerLocation(half);
            }
        });
        add(split, BorderLayout.CENTER);
    }

    public void refresh() {
        NetworkSnapshot snapshot = collector.getStore().getLatestNetworkSnapshot();
        if (snapshot == null) return;

        long now = System.currentTimeMillis();

        /* Update entries from current snapshot */
        SocketInfo[] sockets = snapshot.getSockets();
        if (sockets == null) return;

        /* Track which IPs are active in this snapshot */
        Set<String> activeIPs = new HashSet<String>();

        for (int i = 0; i < sockets.length; i++) {
            SocketInfo s = sockets[i];
            if (s == null || s.state == SocketInfo.STATE_LISTEN) continue;

            String remoteIP = ipToString(s.remoteAddr);
            if ("0.0.0.0".equals(remoteIP)) continue;
            activeIPs.add(remoteIP);

            String key = remoteIP;
            IntegrationEntry entry = entries.get(key);
            if (entry == null) {
                entry = new IntegrationEntry(remoteIP);
                entries.put(key, entry);
            }

            /* Determine direction */
            String dir = s.getDirection();
            if ("IN".equals(dir)) {
                entry.inboundConnections++;
                entry.isInbound = true;
            } else {
                entry.outboundConnections++;
                entry.isOutbound = true;
            }

            /* Accumulate traffic */
            entry.totalBytesIn += s.bytesIn;
            entry.totalBytesOut += s.bytesOut;
            entry.totalRequests += s.requestCount;
            entry.lastSeen = now;

            /* Service name */
            if (s.serviceName != null && !s.serviceName.isEmpty() && entry.serviceName.isEmpty()) {
                entry.serviceName = s.serviceName;
            }
            if (entry.remotePort == 0 && s.remotePort > 0) {
                entry.remotePort = s.remotePort;
            }

            /* Track active connections */
            if (s.state == SocketInfo.STATE_ESTABLISHED) {
                entry.activeConnections++;
            }
        }

        /* Compute rates based on delta from last snapshot */
        if (lastSnapshot != null) {
            long dtMs = snapshot.getTimestamp() - lastSnapshot.getTimestamp();
            if (dtMs > 0) {
                for (IntegrationEntry e : entries.values()) {
                    /* Simple rate estimate */
                    if (activeIPs.contains(e.remoteIP)) {
                        e.callsPerMinute = e.totalRequests > 0
                                ? e.totalRequests * 60000.0 / (now - e.firstSeen) : 0;
                    }
                }
            }
        }

        /* Set first seen for new entries */
        for (IntegrationEntry e : entries.values()) {
            if (e.firstSeen == 0) e.firstSeen = now;
        }

        lastSnapshot = snapshot;

        /* Reset per-snapshot counters */
        for (IntegrationEntry e : entries.values()) {
            e.activeConnections = 0;
            e.inboundConnections = 0;
            e.outboundConnections = 0;
        }

        /* Update table */
        List<IntegrationEntry> sorted = new ArrayList<IntegrationEntry>(entries.values());
        Collections.sort(sorted, new Comparator<IntegrationEntry>() {
            public int compare(IntegrationEntry a, IntegrationEntry b) {
                return Long.compare(b.totalBytesIn + b.totalBytesOut, a.totalBytesIn + a.totalBytesOut);
            }
        });
        tableModel.setData(sorted);

        /* Summary */
        int inbound = 0, outbound = 0;
        for (IntegrationEntry e : sorted) {
            if (e.isInbound) inbound++;
            if (e.isOutbound) outbound++;
        }
        summaryLabel.setText(String.format(
                "Integration Map: %d systems  |  %d inbound  %d outbound  |  Total connections tracked",
                sorted.size(), inbound, outbound));

        /* Traffic chart */
        long from = now - 300000;
        List<NetworkSnapshot> history = collector.getStore().getNetworkHistory(from, now);
        List<long[]> inPts = new ArrayList<long[]>();
        List<long[]> outPts = new ArrayList<long[]>();
        for (int h = 0; h < history.size(); h++) {
            NetworkSnapshot ns = history.get(h);
            long totalIn = 0, totalOut = 0;
            SocketInfo[] ss = ns.getSockets();
            if (ss != null) {
                for (int j = 0; j < ss.length; j++) {
                    if (ss[j] != null) {
                        totalIn += ss[j].bytesIn;
                        totalOut += ss[j].bytesOut;
                    }
                }
            }
            inPts.add(TimeSeriesChart.point(ns.getTimestamp(), totalIn / 1024.0));
            outPts.add(TimeSeriesChart.point(ns.getTimestamp(), totalOut / 1024.0));
        }
        trafficChart.setSeriesData("Inbound", inPts);
        trafficChart.setSeriesData("Outbound", outPts);
    }

    private static String ipToString(long addr) {
        if (addr == 0) return "0.0.0.0";
        return (addr & 0xFF) + "." + ((addr >> 8) & 0xFF) + "." +
               ((addr >> 16) & 0xFF) + "." + ((addr >> 24) & 0xFF);
    }

    /* ── Entry ───────────────────────────────────── */

    private static class IntegrationEntry {
        final String remoteIP;
        String serviceName = "";
        int remotePort = 0;
        boolean isInbound = false;
        boolean isOutbound = false;
        int activeConnections = 0;
        int inboundConnections = 0;
        int outboundConnections = 0;
        long totalBytesIn = 0;
        long totalBytesOut = 0;
        int totalRequests = 0;
        double callsPerMinute = 0;
        long firstSeen = 0;
        long lastSeen = 0;

        IntegrationEntry(String remoteIP) { this.remoteIP = remoteIP; }

        String getDirection() {
            if (isInbound && isOutbound) return "IN/OUT";
            if (isInbound) return "IN";
            return "OUT";
        }
    }

    /* ── Table Model ─────────────────────────────── */

    private static class IntegrationTableModel extends AbstractTableModel {
        private final String[] COLS = {
            "Remote IP", "Service / Host", "Protocol", "Direction", "Port",
            "Calls/min", "Total Requests", "Bytes In", "Bytes Out", "Last Seen"
        };
        private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        private List<IntegrationEntry> data = new ArrayList<IntegrationEntry>();

        public void setData(List<IntegrationEntry> data) {
            this.data = data;
            fireTableDataChanged();
        }

        public int getRowCount() { return data.size(); }
        public int getColumnCount() { return COLS.length; }
        public String getColumnName(int c) { return COLS[c]; }

        public Object getValueAt(int row, int col) {
            IntegrationEntry e = data.get(row);
            switch (col) {
                case 0: return e.remoteIP;
                case 1: return e.serviceName;
                case 2: return detectProtocol(e.remotePort, e.serviceName);
                case 3: return e.getDirection();
                case 4: return e.remotePort > 0 ? Integer.valueOf(e.remotePort) : "";
                case 5: return String.format("%.1f", e.callsPerMinute);
                case 6: return Integer.valueOf(e.totalRequests);
                case 7: return formatBytes(e.totalBytesIn);
                case 8: return formatBytes(e.totalBytesOut);
                case 9: return e.lastSeen > 0 ? sdf.format(new Date(e.lastSeen)) : "";
                default: return "";
            }
        }

        private static String detectProtocol(int port, String svc) {
            String lower = svc.toLowerCase();
            if (lower.contains("postgre") || lower.contains("mysql") || lower.contains("oracle")
                || lower.contains("sql server") || lower.contains("mongo") || lower.contains("couch")) {
                return "DATABASE";
            }
            if (lower.contains("redis") || lower.contains("memcach")) return "CACHE";
            if (lower.contains("kafka") || lower.contains("rabbit") || lower.contains("activemq")) return "MESSAGING";
            if (lower.contains("smtp") || lower.contains("pop3") || lower.contains("imap")) return "MAIL";
            if (lower.contains("ftp") || lower.contains("sftp")) return "FILE TRANSFER";
            if (lower.contains("ldap")) return "DIRECTORY";
            if (port == 80 || port == 443 || port == 8080 || port == 8443) return "HTTP/REST";
            if (port == 21 || port == 22 || port == 990) return "FILE TRANSFER";
            if (port == 25 || port == 465 || port == 587) return "MAIL";
            if (port == 5432 || port == 3306 || port == 1521 || port == 1433) return "DATABASE";
            if (port == 6379 || port == 11211) return "CACHE";
            if (port == 9092 || port == 5672) return "MESSAGING";
            return "TCP";
        }

        private static String formatBytes(long bytes) {
            if (bytes <= 0) return "0";
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
            if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
            return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }

    /* ── Cell Renderer ───────────────────────────── */

    private static class IntegrationCellRenderer extends AlignedCellRenderer {
        protected void colorize(Component c, JTable table, Object value, int row, int col) {
            if (col == 2) { /* Protocol */
                String proto = value != null ? value.toString() : "";
                if ("DATABASE".equals(proto)) c.setForeground(new Color(0, 100, 180));
                else if ("HTTP/REST".equals(proto)) c.setForeground(new Color(0, 128, 0));
                else if ("MESSAGING".equals(proto)) c.setForeground(new Color(150, 80, 200));
                else if ("MAIL".equals(proto)) c.setForeground(new Color(200, 100, 0));
                else if ("CACHE".equals(proto)) c.setForeground(new Color(200, 50, 50));
                c.setFont(c.getFont().deriveFont(Font.BOLD));
            } else if (col == 3) { /* Direction */
                String dir = value != null ? value.toString() : "";
                if ("IN".equals(dir)) {
                    c.setForeground(new Color(0, 128, 0));
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                } else if ("OUT".equals(dir)) {
                    c.setForeground(new Color(200, 100, 0));
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                } else if ("IN/OUT".equals(dir)) {
                    c.setForeground(new Color(0, 100, 180));
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                }
            } else if (col == 5) { /* Calls/min */
                try {
                    double rate = Double.parseDouble(value.toString());
                    if (rate > 100) {
                        c.setForeground(Color.RED);
                        c.setFont(c.getFont().deriveFont(Font.BOLD));
                    } else if (rate > 20) {
                        c.setForeground(new Color(200, 100, 0));
                    }
                } catch (NumberFormatException e) { /* ignore */ }
            }
        }
    }
}
