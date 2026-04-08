package it.denzosoft.jvmmonitor.gui;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.model.GcEvent;

import javax.swing.*;
import it.denzosoft.jvmmonitor.gui.chart.CsvExporter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import it.denzosoft.jvmmonitor.gui.chart.AlignedCellRenderer;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

public class GcPanel extends JPanel {

    private final JVMMonitorCollector collector;
    private final JLabel summaryLabel;
    private final GcTableModel tableModel;
    private final GcBarChart barChart;

    public GcPanel(JVMMonitorCollector collector) {
        this.collector = collector;
        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        summaryLabel = new JLabel("GC: N/A");
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.BOLD, 14f));
        add(summaryLabel, BorderLayout.NORTH);

        /* Split: bar chart top, table bottom */
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        split.setResizeWeight(0.5);

        barChart = new GcBarChart();
        barChart.setPreferredSize(new Dimension(0, 200));
        split.setTopComponent(barChart);

        tableModel = new GcTableModel();
        JTable table = new JTable(tableModel);
        table.setDefaultRenderer(Object.class, new GcCellRenderer());
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(18);
        /* Time=90, Type=60, Duration=80, GC#=55, FullGC#=60 */
        table.getColumnModel().getColumn(0).setPreferredWidth(90); table.getColumnModel().getColumn(0).setMaxWidth(110);
        table.getColumnModel().getColumn(1).setPreferredWidth(60); table.getColumnModel().getColumn(1).setMaxWidth(80);
        table.getColumnModel().getColumn(2).setPreferredWidth(80); table.getColumnModel().getColumn(2).setMaxWidth(100);
        table.getColumnModel().getColumn(3).setPreferredWidth(55); table.getColumnModel().getColumn(3).setMaxWidth(70);
        table.getColumnModel().getColumn(4).setPreferredWidth(60); table.getColumnModel().getColumn(4).setMaxWidth(75);
        split.setBottomComponent(new JScrollPane(table));

        add(split, BorderLayout.CENTER);
    }

    public void refresh() {
        long now = System.currentTimeMillis();
        List<GcEvent> events = collector.getStore().getGcEvents(now - 300000, now);

        double freq = collector.getAnalysisContext().getGcFrequencyPerMinute(60);
        double avgPause = collector.getAnalysisContext().getAvgGcPauseMs(60);
        double throughput = collector.getAnalysisContext().getGcThroughputPercent(60);

        summaryLabel.setText(String.format(
                "GC: %.0f/min | Avg Pause: %.1fms | Throughput: %.1f%%",
                freq, avgPause, throughput));

        tableModel.setData(events);
        barChart.setData(events);
    }

    private static class GcTableModel extends AbstractTableModel {
        private final String[] COLUMNS = {"Time", "Type", "Duration (ms)", "GC #", "Full GC #"};
        private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
        private List<GcEvent> data = new ArrayList<GcEvent>();

        public void setData(List<GcEvent> data) {
            this.data = data;
            fireTableDataChanged();
        }

        public int getRowCount() { return data.size(); }
        public int getColumnCount() { return COLUMNS.length; }
        public String getColumnName(int col) { return COLUMNS[col]; }

        public Object getValueAt(int row, int col) {
            int idx = data.size() - 1 - row;
            if (idx < 0 || idx >= data.size()) return "";
            GcEvent e = data.get(idx); /* newest first */
            switch (col) {
                case 0: return sdf.format(new Date(e.getTimestamp()));
                case 1: return e.getTypeName();
                case 2: return String.format("%.1f", e.getDurationMs());
                case 3: return Integer.valueOf(e.getGcCount());
                case 4: return Integer.valueOf(e.getFullGcCount());
                default: return "";
            }
        }
    }

    private static class GcCellRenderer extends AlignedCellRenderer {
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int col) {
            Component c = super.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, col);
            if (col == 1 && !isSelected) {
                String type = value.toString();
                if ("Full".equals(type)) c.setForeground(Color.RED);
                else c.setForeground(new Color(0, 128, 0));
            } else if (col == 2 && !isSelected) {
                try {
                    double ms = Double.parseDouble(value.toString());
                    if (ms > 500) c.setForeground(Color.RED);
                    else if (ms > 100) c.setForeground(new Color(200, 100, 0));
                    else c.setForeground(Color.BLACK);
                } catch (NumberFormatException e) {
                    c.setForeground(Color.BLACK);
                }
            } else if (!isSelected) {
                c.setForeground(Color.BLACK);
            }
            return c;
        }
    }

    /**
     * Simple bar chart showing GC pause durations over time.
     */
    private static class GcBarChart extends JPanel {
        private List<GcEvent> data = new ArrayList<GcEvent>();

        public void setData(List<GcEvent> data) {
            this.data = data;
            repaint();
        }

        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            g2.setColor(Color.WHITE);
            g2.fillRect(0, 0, w, h);

            if (data == null || data.isEmpty()) {
                g2.setColor(Color.GRAY);
                g2.drawString("No GC events", w / 2 - 40, h / 2);
                return;
            }

            int margin = 50;
            int graphW = w - margin - 10;
            int graphH = h - 30;

            /* Find max pause */
            double maxPause = 10;
            for (int i = 0; i < data.size(); i++) {
                double ms = data.get(i).getDurationMs();
                if (ms > maxPause) maxPause = ms;
            }
            maxPause *= 1.1;

            long minTs = data.get(0).getTimestamp();
            long maxTs = data.get(data.size() - 1).getTimestamp();
            long tsRange = maxTs - minTs;
            if (tsRange <= 0) tsRange = 1;

            /* Y-axis */
            g2.setColor(Color.GRAY);
            g2.setFont(g2.getFont().deriveFont(10f));
            g2.drawString(String.format("%.0fms", maxPause), 2, 15);
            g2.drawString("0ms", 2, graphH + 5);
            g2.drawLine(margin, 5, margin, graphH);

            /* Bars */
            int barWidth = Math.max(2, graphW / Math.max(data.size(), 1) - 1);
            for (int i = 0; i < data.size(); i++) {
                GcEvent e = data.get(i);
                int x = margin + (int) ((e.getTimestamp() - minTs) * graphW / tsRange);
                int barH = (int) (e.getDurationMs() / maxPause * graphH);
                if (barH < 1) barH = 1;
                int y = graphH - barH;

                if (e.getGcType() == GcEvent.TYPE_FULL) {
                    g2.setColor(new Color(220, 50, 50, 180));
                } else {
                    g2.setColor(new Color(50, 150, 50, 180));
                }
                g2.fillRect(x, y, barWidth, barH);
            }

            g2.setColor(Color.BLACK);
            g2.setFont(g2.getFont().deriveFont(11f));
            g2.drawString("GC Pauses (last 5 min) - Green=Young, Red=Full", margin + 5, h - 5);
        }
    }
}
