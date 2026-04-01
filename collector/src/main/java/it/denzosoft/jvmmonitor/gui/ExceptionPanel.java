package it.denzosoft.jvmmonitor.gui;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.gui.chart.BarChart;
import it.denzosoft.jvmmonitor.gui.chart.TimeSeriesChart;
import it.denzosoft.jvmmonitor.model.ExceptionEvent;

import javax.swing.*;
import it.denzosoft.jvmmonitor.gui.chart.CsvExporter;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import it.denzosoft.jvmmonitor.gui.chart.AlignedCellRenderer;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/**
 * Exception monitoring panel.
 * Top left: Exception rate time-series chart.
 * Top right: Top exception classes horizontal bar chart.
 * Middle: Recent exceptions table.
 * Bottom: Stack trace detail for the selected exception.
 */
public class ExceptionPanel extends JPanel {

    private final JVMMonitorCollector collector;
    private final TimeSeriesChart rateChart;
    private final BarChart topClassesChart;
    private final ExcTableModel tableModel;
    private final JLabel summaryLabel;
    private final JTextArea stackTraceArea;
    private final HotspotTableModel hotspotModel;

    /* Accumulated rate data points */
    private final List<long[]> ratePoints = new ArrayList<long[]>();
    private static final int MAX_RATE_POINTS = 300;
    private long lastRateTs = 0;

    public ExceptionPanel(JVMMonitorCollector collector) {
        this.collector = collector;
        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        summaryLabel = new JLabel("Exceptions: no data");
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.BOLD, 13f));
        add(summaryLabel, BorderLayout.NORTH);

        /* Charts row */
        rateChart = new TimeSeriesChart("Exception Rate (per minute, 5 min)", "exc/min");
        rateChart.defineSeries("Rate", new Color(220, 80, 50), true);
        rateChart.setShowLegend(false);

        topClassesChart = new BarChart("Top Exception Classes (last 60s)");

        JPanel chartsRow = new JPanel(new GridLayout(1, 2, 5, 0));
        chartsRow.add(rateChart);
        chartsRow.add(topClassesChart);

        /* Table */
        tableModel = new ExcTableModel();
        final JTable table = new JTable(tableModel);
        table.setDefaultRenderer(Object.class, new ExcCellRenderer());
        table.setAutoCreateRowSorter(true);

        /* Listen for selection to show stack trace */
        table.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
            public void valueChanged(ListSelectionEvent e) {
                if (e.getValueIsAdjusting()) return;
                int selectedRow = table.getSelectedRow();
                if (selectedRow >= 0) {
                    int modelRow = table.convertRowIndexToModel(selectedRow);
                    ExceptionEvent exc = tableModel.getEventAt(modelRow);
                    if (exc != null) {
                        showStackTrace(exc);
                    }
                }
            }
        });

        /* Stack trace area */
        stackTraceArea = new JTextArea(6, 40);
        stackTraceArea.setEditable(false);
        stackTraceArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        stackTraceArea.setText("(select an exception to see its stack trace)");
        JScrollPane stackScroll = new JScrollPane(stackTraceArea);
        stackScroll.setBorder(BorderFactory.createTitledBorder("Stack Trace"));

        /* Hotspot table */
        hotspotModel = new HotspotTableModel();
        JTable hotspotTable = new JTable(hotspotModel);
        hotspotTable.setDefaultRenderer(Object.class, new HotspotCellRenderer());
        hotspotTable.setAutoCreateRowSorter(true);
        hotspotTable.setRowHeight(18);
        CsvExporter.install(table);

        /* Bottom tabs: Events + Hotspots */
        JTabbedPane bottomTabs = new JTabbedPane();

        JPanel hotspotPanel = new JPanel(new BorderLayout());
        hotspotPanel.add(new JScrollPane(hotspotTable), BorderLayout.CENTER);
        bottomTabs.addTab("Exception Hotspots", hotspotPanel);

        JPanel tableAndStack = new JPanel(new BorderLayout(0, 5));
        tableAndStack.add(new JScrollPane(table), BorderLayout.CENTER);
        tableAndStack.add(stackScroll, BorderLayout.SOUTH);
        bottomTabs.addTab("Recent Events", tableAndStack);

        final JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                chartsRow, bottomTabs);
        split.setResizeWeight(0.5);
        split.addComponentListener(new java.awt.event.ComponentAdapter() {
            public void componentResized(java.awt.event.ComponentEvent e) {
                int half = split.getHeight() / 2; if (half > 0) split.setDividerLocation(half);
            }
        });
        add(split, BorderLayout.CENTER);
    }

    private void showStackTrace(ExceptionEvent exc) {
        StringBuilder sb = new StringBuilder();
        sb.append(exc.getDisplayName());
        sb.append("\n  thrown at ").append(exc.getThrowClass()).append('.').append(exc.getThrowMethod());
        sb.append(" (line ").append(exc.getThrowLocation()).append(')');
        if (exc.isCaught()) {
            sb.append("\n  caught at ").append(exc.getCatchClass()).append('.').append(exc.getCatchMethod());
        } else {
            sb.append("\n  NOT CAUGHT");
        }
        sb.append("\n\nStack Trace:\n");
        sb.append(exc.getStackTraceString());
        stackTraceArea.setText(sb.toString());
        stackTraceArea.setCaretPosition(0);
    }

    public void refresh() {
        long now = System.currentTimeMillis();

        /* Rate chart */
        List<ExceptionEvent> recent60 = collector.getStore().getExceptions(now - 60000, now);
        double rate = recent60.size();

        if (now - lastRateTs >= 2000) {
            ratePoints.add(TimeSeriesChart.point(now, rate));
            if (ratePoints.size() > MAX_RATE_POINTS) ratePoints.remove(0);
            lastRateTs = now;
        }
        rateChart.setSeriesData("Rate", ratePoints);

        /* Top exception classes */
        Map<String, int[]> classCount = new LinkedHashMap<String, int[]>();
        for (int i = 0; i < recent60.size(); i++) {
            String cls = recent60.get(i).getDisplayName();
            int[] cnt = classCount.get(cls);
            if (cnt == null) { cnt = new int[]{0}; classCount.put(cls, cnt); }
            cnt[0]++;
        }
        List<Map.Entry<String, int[]>> sorted = new ArrayList<Map.Entry<String, int[]>>(classCount.entrySet());
        Collections.sort(sorted, new Comparator<Map.Entry<String, int[]>>() {
            public int compare(Map.Entry<String, int[]> a, Map.Entry<String, int[]> b) {
                return b.getValue()[0] - a.getValue()[0];
            }
        });
        List<String> labels = new ArrayList<String>();
        List<Integer> values = new ArrayList<Integer>();
        for (int i = 0; i < Math.min(sorted.size(), 10); i++) {
            labels.add(sorted.get(i).getKey());
            values.add(sorted.get(i).getValue()[0]);
        }
        topClassesChart.setData(labels, values);

        /* Table */
        tableModel.setData(recent60);

        /* Summary */
        ExceptionEvent latest = collector.getStore().getLatestException();
        if (latest != null) {
            summaryLabel.setText(String.format(
                    "Exceptions: %.0f/min  |  Total thrown: %d  Caught: %d  Dropped: %d",
                    rate, latest.getTotalThrown(), latest.getTotalCaught(), latest.getTotalDropped()));
            if (rate > 1000) summaryLabel.setForeground(Color.RED);
            else if (rate > 100) summaryLabel.setForeground(new Color(200, 100, 0));
            else summaryLabel.setForeground(Color.BLACK);
        }

        /* Exception hotspots: aggregate by throw location */
        Map<String, int[]> locationCounts = new LinkedHashMap<String, int[]>();
        Map<String, String> locationExcTypes = new LinkedHashMap<String, String>();
        Map<String, int[]> locationUncaught = new LinkedHashMap<String, int[]>();
        for (int i = 0; i < recent60.size(); i++) {
            ExceptionEvent e = recent60.get(i);
            String loc = e.getThrowClass() + "." + e.getThrowMethod();
            int[] cnt = locationCounts.get(loc);
            if (cnt == null) { cnt = new int[]{0}; locationCounts.put(loc, cnt); }
            cnt[0]++;
            /* Track dominant exception type at this location */
            String prev = locationExcTypes.get(loc);
            if (prev == null) locationExcTypes.put(loc, e.getDisplayName());
            /* Count uncaught */
            int[] uc = locationUncaught.get(loc);
            if (uc == null) { uc = new int[]{0}; locationUncaught.put(loc, uc); }
            if (!e.isCaught()) uc[0]++;
        }
        List<Map.Entry<String, int[]>> hotspots =
                new ArrayList<Map.Entry<String, int[]>>(locationCounts.entrySet());
        Collections.sort(hotspots, new Comparator<Map.Entry<String, int[]>>() {
            public int compare(Map.Entry<String, int[]> a, Map.Entry<String, int[]> b) {
                return b.getValue()[0] - a.getValue()[0];
            }
        });
        hotspotModel.setData(hotspots, locationExcTypes, locationUncaught, recent60.size());
    }

    private static class ExcTableModel extends AbstractTableModel {
        private final String[] COLS = {"Time", "Exception", "Thrown At", "Caught?", "Stack Depth"};
        private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
        private List<ExceptionEvent> data = new ArrayList<ExceptionEvent>();

        public void setData(List<ExceptionEvent> data) {
            this.data = data;
            fireTableDataChanged();
        }

        public ExceptionEvent getEventAt(int row) {
            int idx = data.size() - 1 - row;
            return (idx >= 0 && idx < data.size()) ? data.get(idx) : null;
        }

        public int getRowCount() { return data.size(); }
        public int getColumnCount() { return COLS.length; }
        public String getColumnName(int c) { return COLS[c]; }

        public Object getValueAt(int row, int col) {
            ExceptionEvent e = data.get(data.size() - 1 - row);
            switch (col) {
                case 0: return sdf.format(new Date(e.getTimestamp()));
                case 1: return e.getDisplayName();
                case 2: return e.getThrowClass() + "." + e.getThrowMethod();
                case 3: return e.isCaught() ? "yes" : "NO";
                case 4: return Integer.valueOf(e.getStackDepth());
                default: return "";
            }
        }
    }

    private static class ExcCellRenderer extends AlignedCellRenderer {
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int col) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
            if (col == 3 && !isSelected) {
                c.setForeground("NO".equals(value) ? Color.RED : new Color(0, 128, 0));
                c.setFont(c.getFont().deriveFont("NO".equals(value) ? Font.BOLD : Font.PLAIN));
            } else if (!isSelected) {
                c.setForeground(Color.BLACK);
                c.setFont(c.getFont().deriveFont(Font.PLAIN));
            }
            return c;
        }
    }

    /* ── Hotspot Table ───────────────────────────────── */

    private static class HotspotTableModel extends AbstractTableModel {
        private final String[] COLS = {"#", "Code Location", "Exception Type", "Count", "% of Total", "Uncaught"};
        private List<String[]> data = new ArrayList<String[]>();

        public void setData(List<Map.Entry<String, int[]>> hotspots,
                            Map<String, String> excTypes,
                            Map<String, int[]> uncaught,
                            int totalExceptions) {
            data.clear();
            for (int i = 0; i < hotspots.size(); i++) {
                Map.Entry<String, int[]> entry = hotspots.get(i);
                String loc = entry.getKey();
                int count = entry.getValue()[0];
                String excType = excTypes.get(loc);
                int uc = uncaught.containsKey(loc) ? uncaught.get(loc)[0] : 0;
                double pct = totalExceptions > 0 ? count * 100.0 / totalExceptions : 0;
                data.add(new String[]{
                    String.valueOf(i + 1),
                    loc,
                    excType != null ? excType : "?",
                    String.valueOf(count),
                    String.format("%.1f%%", pct),
                    uc > 0 ? String.valueOf(uc) : "0"
                });
            }
            fireTableDataChanged();
        }

        public int getRowCount() { return data.size(); }
        public int getColumnCount() { return COLS.length; }
        public String getColumnName(int c) { return COLS[c]; }

        public Object getValueAt(int row, int col) {
            return data.get(row)[col];
        }
    }

    private static class HotspotCellRenderer extends AlignedCellRenderer {
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int col) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
            if (isSelected) return c;

            if (col == 3 || col == 4) {
                /* Count / percentage — highlight high values */
                try {
                    String text = value.toString().replace("%", "");
                    double v = Double.parseDouble(text);
                    if (col == 4) { /* percentage */
                        if (v > 30) { c.setForeground(Color.RED); c.setFont(c.getFont().deriveFont(Font.BOLD)); }
                        else if (v > 15) { c.setForeground(new Color(200, 100, 0)); c.setFont(c.getFont().deriveFont(Font.BOLD)); }
                        else { c.setForeground(Color.BLACK); c.setFont(c.getFont().deriveFont(Font.PLAIN)); }
                    } else {
                        if (v > 50) { c.setForeground(Color.RED); c.setFont(c.getFont().deriveFont(Font.BOLD)); }
                        else { c.setForeground(Color.BLACK); c.setFont(c.getFont().deriveFont(Font.PLAIN)); }
                    }
                } catch (NumberFormatException e) {
                    c.setForeground(Color.BLACK);
                }
            } else if (col == 5) {
                /* Uncaught */
                if (!"0".equals(value)) {
                    c.setForeground(Color.RED);
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                } else {
                    c.setForeground(new Color(0, 128, 0));
                    c.setFont(c.getFont().deriveFont(Font.PLAIN));
                }
            } else {
                c.setForeground(Color.BLACK);
                c.setFont(c.getFont().deriveFont(Font.PLAIN));
            }
            return c;
        }
    }
}
