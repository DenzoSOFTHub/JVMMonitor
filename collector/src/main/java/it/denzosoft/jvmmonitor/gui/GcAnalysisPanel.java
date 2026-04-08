package it.denzosoft.jvmmonitor.gui;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.gui.chart.TimeSeriesChart;
import it.denzosoft.jvmmonitor.model.GcDetail;
import it.denzosoft.jvmmonitor.model.GcEvent;

import javax.swing.*;
import it.denzosoft.jvmmonitor.gui.chart.CsvExporter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import it.denzosoft.jvmmonitor.gui.chart.AlignedCellRenderer;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Advanced GC Analysis panel with:
 * - GC rectangle chart (width=duration, height=freed memory)
 * - CPU vs GC duration correlation scatter
 * - Promotion rate chart (Eden -> Old Gen)
 * - GC cause table (last 20)
 */
public class GcAnalysisPanel extends JPanel {

    private final JVMMonitorCollector collector;
    private final JLabel summaryLabel;
    private final GcRectangleChart rectChart;
    private final TimeSeriesChart promotionChart;
    private final TimeSeriesChart throughputChart;
    private final CpuGcCorrelationChart correlationChart;
    private final GcCauseTableModel causeModel;
    private final GcCollectorTableModel collectorModel;

    public GcAnalysisPanel(JVMMonitorCollector collector) {
        this.collector = collector;
        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        summaryLabel = new JLabel("GC Analysis: no data");
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.BOLD, 13f));
        add(summaryLabel, BorderLayout.NORTH);

        /* Top: Rectangle chart (left) + Correlation chart (right) */
        rectChart = new GcRectangleChart();
        correlationChart = new CpuGcCorrelationChart();

        JPanel topRow = new JPanel(new GridLayout(1, 2, 5, 0));
        topRow.add(rectChart);
        topRow.add(correlationChart);

        /* Middle: Promotion chart (left) + Throughput chart (right) */
        promotionChart = new TimeSeriesChart("Promotion Rate: Eden -> Old Gen (5 min)", "MB");
        promotionChart.defineSeries("Promoted", new Color(200, 100, 30), true);
        promotionChart.defineSeries("Freed", new Color(50, 150, 50), false);

        throughputChart = new TimeSeriesChart("GC Throughput (5 min)", "%");
        throughputChart.defineSeries("Throughput", new Color(0, 150, 100), true);
        throughputChart.setFixedMaxY(100);

        JPanel middleRow = new JPanel(new GridLayout(1, 2, 5, 0));
        middleRow.add(promotionChart);
        middleRow.add(throughputChart);

        /* Bottom: Cause table */
        causeModel = new GcCauseTableModel();
        JTable causeTable = new JTable(causeModel);
        causeTable.setDefaultRenderer(Object.class, new GcCauseCellRenderer());
        causeTable.setRowHeight(18);
        CsvExporter.install(causeTable);
        /* GC Events: Time=90, Type=55, Cause=fills, Duration=80, Freed=75, Promoted=80 */
        causeTable.getColumnModel().getColumn(0).setPreferredWidth(90); causeTable.getColumnModel().getColumn(0).setMaxWidth(110);
        causeTable.getColumnModel().getColumn(1).setPreferredWidth(55); causeTable.getColumnModel().getColumn(1).setMaxWidth(70);
        causeTable.getColumnModel().getColumn(2).setPreferredWidth(250);
        causeTable.getColumnModel().getColumn(3).setPreferredWidth(80); causeTable.getColumnModel().getColumn(3).setMaxWidth(100);
        causeTable.getColumnModel().getColumn(4).setPreferredWidth(75); causeTable.getColumnModel().getColumn(4).setMaxWidth(95);
        causeTable.getColumnModel().getColumn(5).setPreferredWidth(80); causeTable.getColumnModel().getColumn(5).setMaxWidth(100);
        JScrollPane causeScroll = new JScrollPane(causeTable);
        causeScroll.setBorder(BorderFactory.createTitledBorder("Last 20 GC Events"));

        /* Top: all 4 charts in 2x2 grid */
        JPanel chartsPanel = new JPanel(new GridLayout(2, 2, 5, 5));
        chartsPanel.add(rectChart);
        chartsPanel.add(correlationChart);
        chartsPanel.add(promotionChart);
        chartsPanel.add(throughputChart);

        /* GC Collectors table */
        collectorModel = new GcCollectorTableModel();
        JTable collectorTable = new JTable(collectorModel);
        collectorTable.setDefaultRenderer(Object.class, new AlignedCellRenderer());
        collectorTable.setAutoCreateRowSorter(true);
        collectorTable.setRowHeight(18);
        CsvExporter.install(collectorTable);
        /* Collectors: Collector=180, Collections=80, Time=80, Memory Pools=fills */
        collectorTable.getColumnModel().getColumn(0).setPreferredWidth(180);
        collectorTable.getColumnModel().getColumn(1).setPreferredWidth(80); collectorTable.getColumnModel().getColumn(1).setMaxWidth(100);
        collectorTable.getColumnModel().getColumn(2).setPreferredWidth(80); collectorTable.getColumnModel().getColumn(2).setMaxWidth(100);
        collectorTable.getColumnModel().getColumn(3).setPreferredWidth(300);

        /* Bottom: tabbed panel */
        JTabbedPane bottomTabs = new JTabbedPane();
        bottomTabs.addTab("GC Events", causeScroll);
        bottomTabs.addTab("GC Collectors", new JScrollPane(collectorTable));

        final JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chartsPanel, bottomTabs);
        split.setResizeWeight(0.5);
        /* Force 50% on first show and every resize */
        split.addComponentListener(new java.awt.event.ComponentAdapter() {
            public void componentResized(java.awt.event.ComponentEvent e) {
                int half = split.getHeight() / 2;
                if (half > 0) split.setDividerLocation(half);
            }
        });
        add(split, BorderLayout.CENTER);
    }

    public void updateData() {
        long now = System.currentTimeMillis();
        long from = now - 300000;
        List<GcEvent> events = collector.getStore().getGcEvents(from, now);

        rectChart.setData(events);
        correlationChart.setData(events);

        /* Promotion chart */
        List<long[]> promotedPts = new ArrayList<long[]>();
        List<long[]> freedPts = new ArrayList<long[]>();
        for (int i = 0; i < events.size(); i++) {
            GcEvent e = events.get(i);
            promotedPts.add(TimeSeriesChart.point(e.getTimestamp(), e.getPromotedMB()));
            freedPts.add(TimeSeriesChart.point(e.getTimestamp(), e.getFreedMB()));
        }
        promotionChart.setSeriesData("Promoted", promotedPts);
        promotionChart.setSeriesData("Freed", freedPts);

        /* GC Throughput (sliding window) */
        List<long[]> throughputPts = new ArrayList<long[]>();
        int windowSec = 10;
        for (long t = from; t <= now; t += 5000) {
            long wFrom = t - windowSec * 1000L;
            long totalPauseNs = 0;
            int count = 0;
            for (int i = 0; i < events.size(); i++) {
                GcEvent e = events.get(i);
                if (e.getTimestamp() >= wFrom && e.getTimestamp() <= t) {
                    totalPauseNs += e.getDurationNanos();
                    count++;
                }
            }
            double tp = count > 0 ? (1.0 - totalPauseNs / (windowSec * 1e9)) * 100 : 100;
            if (tp < 0) tp = 0;
            throughputPts.add(TimeSeriesChart.point(t, tp));
        }
        throughputChart.setSeriesData("Throughput", throughputPts);

        /* Cause table: last 20 */
        int start = Math.max(0, events.size() - 20);
        List<GcEvent> last20 = events.subList(start, events.size());
        causeModel.setData(last20);

        /* GC Collectors */
        GcDetail detail = collector.getStore().getLatestGcDetail();
        collectorModel.setData(detail);

        /* Summary */
        long totalFreed = 0, totalPromoted = 0;
        for (int i = 0; i < events.size(); i++) {
            totalFreed += events.get(i).getFreedBytes();
            totalPromoted += events.get(i).getPromotedBytes();
        }
        double avgFreed = events.isEmpty() ? 0 : totalFreed / (double) events.size() / (1024 * 1024);
        double avgPromoted = events.isEmpty() ? 0 : totalPromoted / (double) events.size() / (1024 * 1024);
        summaryLabel.setText(String.format(
                "GC Analysis: %d events  |  Avg freed: %.0f MB  |  Avg promoted: %.1f MB  |  Total freed: %.0f MB",
                events.size(), avgFreed, avgPromoted, totalFreed / (1024.0 * 1024)));
    }

    public void render() {
        repaint();
    }

    public void refresh() {
        updateData();
        render();
    }

    /* ── GC Rectangle Chart ──────────────────────────── */

    private static class GcRectangleChart extends JPanel {
        private List<GcEvent> data = new ArrayList<GcEvent>();

        GcRectangleChart() { setBackground(Color.WHITE); }

        void setData(List<GcEvent> data) {
            this.data = data;
            repaint();
        }

        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            int mL = 55, mR = 10, mT = 15, mB = 30;
            int gW = w - mL - mR, gH = h - mT - mB;

            g2.setColor(Color.WHITE);
            g2.fillRect(0, 0, w, h);

            if (data == null || data.isEmpty()) {
                g2.setColor(Color.GRAY);
                g2.drawString("No GC events", w / 2 - 40, h / 2);
                return;
            }

            /* Find ranges */
            long minTs = data.get(0).getTimestamp();
            long maxTs = data.get(data.size() - 1).getTimestamp();
            long tsRange = maxTs - minTs;
            if (tsRange <= 0) tsRange = 1;

            double maxFreed = 1;
            double maxDur = 1;
            for (int i = 0; i < data.size(); i++) {
                if (data.get(i).getFreedMB() > maxFreed) maxFreed = data.get(i).getFreedMB();
                if (data.get(i).getDurationMs() > maxDur) maxDur = data.get(i).getDurationMs();
            }
            maxFreed *= 1.1;
            maxDur *= 1.1;

            /* Grid */
            g2.setFont(g2.getFont().deriveFont(10f));
            g2.setColor(Color.GRAY);
            for (int i = 0; i <= 4; i++) {
                int y = mT + gH - (gH * i / 4);
                g2.drawString(String.format("%.0f MB", maxFreed * i / 4), 2, y + 4);
                g2.setColor(new Color(230, 230, 230));
                g2.drawLine(mL, y, mL + gW, y);
                g2.setColor(Color.GRAY);
            }
            g2.drawRect(mL, mT, gW, gH);

            /* Draw rectangles: x=time, width proportional to duration, height=freed */
            for (int i = 0; i < data.size(); i++) {
                GcEvent e = data.get(i);
                int x = mL + (int) ((e.getTimestamp() - minTs) * gW / tsRange);
                int rw = Math.max(2, (int) (e.getDurationMs() / maxDur * gW / 3));
                if (rw > 40) rw = 40;
                int rh = (int) (e.getFreedMB() / maxFreed * gH);
                if (rh < 2) rh = 2;
                int y = mT + gH - rh;

                if (e.getGcType() == GcEvent.TYPE_FULL) {
                    g2.setColor(new Color(220, 50, 50, 180));
                } else {
                    g2.setColor(new Color(50, 150, 50, 180));
                }
                g2.fillRect(x, y, rw, rh);
                g2.setColor(new Color(0, 0, 0, 80));
                g2.drawRect(x, y, rw, rh);
            }

            g2.setColor(Color.BLACK);
            g2.setFont(g2.getFont().deriveFont(11f));
            g2.drawString("GC Events: width=duration, height=freed memory (green=Young, red=Full)", mL + 5, h - 8);
        }
    }

    /* ── CPU vs GC Correlation ───────────────────────── */

    private static class CpuGcCorrelationChart extends JPanel {
        private List<GcEvent> data = new ArrayList<GcEvent>();

        CpuGcCorrelationChart() { setBackground(Color.WHITE); }

        void setData(List<GcEvent> data) {
            this.data = data;
            repaint();
        }

        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            int mL = 55, mR = 10, mT = 15, mB = 30;
            int gW = w - mL - mR, gH = h - mT - mB;

            g2.setColor(Color.WHITE);
            g2.fillRect(0, 0, w, h);

            if (data == null || data.isEmpty()) {
                g2.setColor(Color.GRAY);
                g2.drawString("No GC events", w / 2 - 40, h / 2);
                return;
            }

            double maxDur = 1;
            for (int i = 0; i < data.size(); i++) {
                if (data.get(i).getDurationMs() > maxDur) maxDur = data.get(i).getDurationMs();
            }
            maxDur *= 1.1;

            /* Grid: X = CPU%, Y = GC Duration */
            g2.setFont(g2.getFont().deriveFont(10f));
            g2.setColor(Color.GRAY);
            for (int i = 0; i <= 4; i++) {
                int y = mT + gH - (gH * i / 4);
                g2.drawString(String.format("%.0f ms", maxDur * i / 4), 2, y + 4);
                g2.setColor(new Color(230, 230, 230));
                g2.drawLine(mL, y, mL + gW, y);
                g2.setColor(Color.GRAY);
            }
            for (int i = 0; i <= 4; i++) {
                int x = mL + gW * i / 4;
                g2.drawString((25 * i) + "%", x - 8, mT + gH + 15);
            }
            g2.drawRect(mL, mT, gW, gH);

            /* Scatter plot */
            for (int i = 0; i < data.size(); i++) {
                GcEvent e = data.get(i);
                double cpu = e.getProcessCpuAtGc();
                if (cpu <= 0) continue;
                int x = mL + (int) (cpu / 100 * gW);
                int y = mT + gH - (int) (e.getDurationMs() / maxDur * gH);
                int radius = 4 + (int) (e.getFreedMB() / 50);
                if (radius > 12) radius = 12;

                if (e.getGcType() == GcEvent.TYPE_FULL) {
                    g2.setColor(new Color(220, 50, 50, 150));
                } else {
                    g2.setColor(new Color(50, 130, 200, 150));
                }
                g2.fillOval(x - radius, y - radius, radius * 2, radius * 2);
                g2.setColor(new Color(0, 0, 0, 80));
                g2.drawOval(x - radius, y - radius, radius * 2, radius * 2);
            }

            g2.setColor(Color.BLACK);
            g2.setFont(g2.getFont().deriveFont(11f));
            g2.drawString("CPU% (x) vs GC Duration (y) — bubble size=freed memory", mL + 5, h - 8);
        }
    }

    /* ── GC Cause Table ──────────────────────────────── */

    private static class GcCauseTableModel extends AbstractTableModel {
        private final String[] COLS = {"Time", "Type", "Cause", "Duration", "Freed (MB)", "Promoted (MB)"};
        private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
        private List<GcEvent> data = new ArrayList<GcEvent>();

        public void setData(List<GcEvent> data) {
            this.data = data;
            fireTableDataChanged();
        }

        public int getRowCount() { return data.size(); }
        public int getColumnCount() { return COLS.length; }
        public String getColumnName(int c) { return COLS[c]; }

        public Object getValueAt(int row, int col) {
            int idx = data.size() - 1 - row;
            if (idx < 0 || idx >= data.size()) return "";
            GcEvent e = data.get(idx);
            switch (col) {
                case 0: return sdf.format(new Date(e.getTimestamp()));
                case 1: return e.getTypeName();
                case 2: return e.getCause().isEmpty() ? "N/A" : e.getCause();
                case 3: return String.format("%.1f ms", e.getDurationMs());
                case 4: return String.format("%.0f", e.getFreedMB());
                case 5: return String.format("%.1f", e.getPromotedMB());
                default: return "";
            }
        }
    }

    private static class GcCauseCellRenderer extends AlignedCellRenderer {
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int col) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
            if (isSelected) return c;

            if (col == 1) {
                String type = value.toString();
                c.setForeground("Full".equals(type) ? Color.RED : new Color(0, 128, 0));
                c.setFont(c.getFont().deriveFont(Font.BOLD));
            } else if (col == 2) {
                String cause = value.toString();
                if (cause.contains("System.gc")) {
                    c.setForeground(new Color(200, 100, 0));
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                } else if (cause.contains("Humongous")) {
                    c.setForeground(Color.RED);
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                } else {
                    c.setForeground(Color.BLACK);
                    c.setFont(c.getFont().deriveFont(Font.PLAIN));
                }
            } else if (col == 3) {
                try {
                    double ms = Double.parseDouble(value.toString().replace(" ms", ""));
                    if (ms > 200) c.setForeground(Color.RED);
                    else if (ms > 50) c.setForeground(new Color(200, 100, 0));
                    else c.setForeground(Color.BLACK);
                } catch (NumberFormatException e) { c.setForeground(Color.BLACK); }
                c.setFont(c.getFont().deriveFont(Font.PLAIN));
            } else {
                c.setForeground(Color.BLACK);
                c.setFont(c.getFont().deriveFont(Font.PLAIN));
            }
            return c;
        }
    }

    /* ── GC Collector Table ──────────────────────────── */

    private static class GcCollectorTableModel extends AbstractTableModel {
        private final String[] COLS = {"Collector", "Collections", "Time (ms)", "Memory Pools"};
        private GcDetail.CollectorInfo[] data = new GcDetail.CollectorInfo[0];

        public void setData(GcDetail detail) {
            this.data = detail != null ? detail.getCollectors() : new GcDetail.CollectorInfo[0];
            fireTableDataChanged();
        }

        public int getRowCount() { return data.length; }
        public int getColumnCount() { return COLS.length; }
        public String getColumnName(int c) { return COLS[c]; }

        public Object getValueAt(int row, int col) {
            GcDetail.CollectorInfo ci = data[row];
            switch (col) {
                case 0: return ci.getName();
                case 1: return Long.valueOf(ci.getCollectionCount());
                case 2: return Long.valueOf(ci.getCollectionTimeMs());
                case 3:
                    String[] pools = ci.getMemoryPools();
                    if (pools == null || pools.length == 0) return "";
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < pools.length; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(pools[i]);
                    }
                    return sb.toString();
                default: return "";
            }
        }
    }
}
