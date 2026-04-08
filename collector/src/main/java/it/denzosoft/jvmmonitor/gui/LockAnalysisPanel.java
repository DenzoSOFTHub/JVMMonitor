package it.denzosoft.jvmmonitor.gui;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.gui.chart.BarChart;
import it.denzosoft.jvmmonitor.gui.chart.TimeSeriesChart;
import it.denzosoft.jvmmonitor.model.LockEvent;

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
 * Lock Analysis panel.
 * Top left: Contention rate chart over time.
 * Top right: Most contended locks bar chart.
 * Middle: Lock events table (waiter, lock, owner, waiters, type).
 * Bottom: Stack trace detail + contention summary for selected lock.
 */
public class LockAnalysisPanel extends JPanel {

    private final JVMMonitorCollector collector;
    private final JLabel summaryLabel;
    private final TimeSeriesChart contentionChart;
    private final BarChart topLocksChart;
    private final LockTableModel tableModel;
    private final JTextArea detailArea;
    private final LockHotspotModel hotspotModel;
    private final ModuleActivationBar moduleBar;

    /* Rate tracking */
    private final List<long[]> ratePoints = new ArrayList<long[]>();
    private static final int MAX_RATE_POINTS = 300;
    private long lastRateTs = 0;

    public LockAnalysisPanel(JVMMonitorCollector collector) {
        this.collector = collector;
        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        JPanel northPanel = new JPanel(new BorderLayout());
        moduleBar = new ModuleActivationBar(collector, "locks", "Locks", 1);
        northPanel.add(moduleBar, BorderLayout.NORTH);
        summaryLabel = new JLabel("Lock Analysis: no data");
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.BOLD, 13f));
        northPanel.add(summaryLabel, BorderLayout.SOUTH);
        add(northPanel, BorderLayout.NORTH);

        /* Charts */
        contentionChart = new TimeSeriesChart("Lock Contentions (per minute, 5 min)", "cont/min");
        contentionChart.defineSeries("Contentions", new Color(200, 50, 50), true);
        contentionChart.setShowLegend(false);

        topLocksChart = new BarChart("Most Contended Locks (last 60s)");

        JPanel chartsRow = new JPanel(new GridLayout(1, 2, 5, 0));
        chartsRow.add(contentionChart);
        chartsRow.add(topLocksChart);

        /* Table */
        tableModel = new LockTableModel();
        final JTable table = new JTable(tableModel);
        table.setDefaultRenderer(Object.class, new LockCellRenderer());
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(18);
        CsvExporter.install(table);

        table.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
            public void valueChanged(ListSelectionEvent e) {
                if (e.getValueIsAdjusting()) return;
                int row = table.getSelectedRow();
                if (row >= 0) {
                    int modelRow = table.convertRowIndexToModel(row);
                    LockEvent evt = tableModel.getEventAt(modelRow);
                    if (evt != null) showDetail(evt);
                }
            }
        });

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Lock Contention Events (last 60s)"));

        /* Detail area */
        detailArea = new JTextArea(6, 40);
        detailArea.setEditable(false);
        detailArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        detailArea.setText("(select a lock event to see details)");
        JScrollPane detailScroll = new JScrollPane(detailArea);
        detailScroll.setBorder(BorderFactory.createTitledBorder("Lock Detail & Stack Trace"));

        /* Lock hotspot table */
        hotspotModel = new LockHotspotModel();
        JTable hotspotTable = new JTable(hotspotModel);
        hotspotTable.setDefaultRenderer(Object.class, new LockHotspotRenderer());
        hotspotTable.setAutoCreateRowSorter(true);
        hotspotTable.setRowHeight(18);
        CsvExporter.install(table);

        /* Bottom tabs: Hotspots + Events */
        JTabbedPane bottomTabs = new JTabbedPane();
        bottomTabs.addTab("Lock Hotspots", new JScrollPane(hotspotTable));

        JPanel tableAndDetail = new JPanel(new BorderLayout(0, 5));
        tableAndDetail.add(tableScroll, BorderLayout.CENTER);
        tableAndDetail.add(detailScroll, BorderLayout.SOUTH);
        bottomTabs.addTab("Contention Events", tableAndDetail);

        final JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chartsRow, bottomTabs);
        split.setResizeWeight(0.5);
        split.addComponentListener(new java.awt.event.ComponentAdapter() {
            public void componentResized(java.awt.event.ComponentEvent e) {
                int half = split.getHeight() / 2; if (half > 0) split.setDividerLocation(half);
            }
        });
        add(split, BorderLayout.CENTER);
    }

    private void showDetail(LockEvent evt) {
        StringBuilder sb = new StringBuilder();
        sb.append("Event: ").append(evt.getEventTypeName()).append('\n');
        sb.append("Lock:  ").append(evt.getLockDisplayName()).append('\n');
        sb.append("Waiter Thread: ").append(evt.getThreadName()).append('\n');
        sb.append("Owner Thread:  ").append(evt.getOwnerThreadName());
        if (evt.getOwnerEntryCount() > 1) {
            sb.append(" (reentrant, depth=").append(evt.getOwnerEntryCount()).append(')');
        }
        sb.append('\n');
        sb.append("Threads waiting: ").append(evt.getWaiterCount()).append('\n');
        sb.append("Total contentions: ").append(evt.getTotalContentions()).append('\n');
        sb.append('\n');

        if (evt.getEventType() == LockEvent.CONTENDED_ENTER) {
            sb.append("BLOCKED — thread ").append(evt.getThreadName())
              .append(" is waiting to acquire lock held by ").append(evt.getOwnerThreadName()).append('\n');
        } else if (evt.getEventType() == LockEvent.CONTENDED_EXIT) {
            sb.append("ACQUIRED — thread ").append(evt.getThreadName())
              .append(" acquired the lock after contention\n");
        }

        String stack = evt.getStackTraceString();
        if (!stack.isEmpty()) {
            sb.append("\nWaiter Stack Trace:\n").append(stack);
        }

        detailArea.setText(sb.toString());
        detailArea.setCaretPosition(0);
    }

    public void updateData() {
        long now = System.currentTimeMillis();
        List<LockEvent> recent60 = collector.getStore().getLockEvents(now - 60000, now);
        moduleBar.setDataReceived(!recent60.isEmpty());

        /* Only count CONTENDED_ENTER events for the rate */
        int contentions = 0;
        for (int i = 0; i < recent60.size(); i++) {
            if (recent60.get(i).getEventType() == LockEvent.CONTENDED_ENTER) contentions++;
        }
        double rate = contentions; /* per minute */

        if (now - lastRateTs >= 2000) {
            ratePoints.add(TimeSeriesChart.point(now, rate));
            if (ratePoints.size() > MAX_RATE_POINTS) ratePoints.remove(0);
            lastRateTs = now;
        }
        contentionChart.setSeriesData("Contentions", ratePoints);

        /* Top contended locks (by lock class + hash) */
        Map<String, int[]> lockCounts = new LinkedHashMap<String, int[]>();
        for (int i = 0; i < recent60.size(); i++) {
            LockEvent e = recent60.get(i);
            if (e.getEventType() != LockEvent.CONTENDED_ENTER) continue;
            String key = e.getLockDisplayName();
            int[] cnt = lockCounts.get(key);
            if (cnt == null) { cnt = new int[]{0}; lockCounts.put(key, cnt); }
            cnt[0]++;
        }
        List<Map.Entry<String, int[]>> sorted =
                new ArrayList<Map.Entry<String, int[]>>(lockCounts.entrySet());
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
        topLocksChart.setData(labels, values);

        /* Table */
        tableModel.setData(recent60);

        /* Summary */
        int totalLocks = collector.getStore().getLockEventCount();
        summaryLabel.setText(String.format(
                "Lock Analysis: %d contentions/min  |  %d lock events total  |  %d distinct locks",
                contentions, totalLocks, lockCounts.size()));
        if (contentions > 100) summaryLabel.setForeground(Color.RED);
        else if (contentions > 20) summaryLabel.setForeground(new Color(200, 100, 0));
        else summaryLabel.setForeground(Color.BLACK);

        /* Lock hotspots: aggregate by code location (first stack frame) */
        Map<String, int[]> codeHotspots = new LinkedHashMap<String, int[]>();
        Map<String, String> hotspotLocks = new LinkedHashMap<String, String>();
        Map<String, int[]> hotspotWaiters = new LinkedHashMap<String, int[]>();
        for (int i = 0; i < recent60.size(); i++) {
            LockEvent e = recent60.get(i);
            if (e.getEventType() != LockEvent.CONTENDED_ENTER) continue;
            /* Use stack trace top frame as the code location */
            String loc;
            if (e.getStackFrames() != null && e.getStackFrames().length > 0) {
                loc = e.getStackFrames()[0].className + "." + e.getStackFrames()[0].methodName;
            } else {
                loc = "(unknown)";
            }
            int[] cnt = codeHotspots.get(loc);
            if (cnt == null) { cnt = new int[]{0}; codeHotspots.put(loc, cnt); }
            cnt[0]++;
            hotspotLocks.put(loc, e.getLockDisplayName());
            int[] mw = hotspotWaiters.get(loc);
            if (mw == null) { mw = new int[]{0}; hotspotWaiters.put(loc, mw); }
            if (e.getWaiterCount() > mw[0]) mw[0] = e.getWaiterCount();
        }
        List<Map.Entry<String, int[]>> hsSorted =
                new ArrayList<Map.Entry<String, int[]>>(codeHotspots.entrySet());
        Collections.sort(hsSorted, new Comparator<Map.Entry<String, int[]>>() {
            public int compare(Map.Entry<String, int[]> a, Map.Entry<String, int[]> b) {
                return b.getValue()[0] - a.getValue()[0];
            }
        });
        hotspotModel.setData(hsSorted, hotspotLocks, hotspotWaiters, contentions);
    }

    public void render() {
        repaint();
    }

    public void refresh() {
        updateData();
        render();
    }

    /* ── Table Model ─────────────────────────────────── */

    private static class LockTableModel extends AbstractTableModel {
        private final String[] COLS = {"Time", "Type", "Waiter", "Lock", "Owner", "Waiters"};
        private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
        private List<LockEvent> data = new ArrayList<LockEvent>();

        public void setData(List<LockEvent> data) {
            this.data = data;
            fireTableDataChanged();
        }

        public LockEvent getEventAt(int row) {
            int idx = data.size() - 1 - row;
            return (idx >= 0 && idx < data.size()) ? data.get(idx) : null;
        }

        public int getRowCount() { return data.size(); }
        public int getColumnCount() { return COLS.length; }
        public String getColumnName(int c) { return COLS[c]; }

        public Object getValueAt(int row, int col) {
            int idx = data.size() - 1 - row;
            if (idx < 0 || idx >= data.size()) return "";
            LockEvent e = data.get(idx);
            switch (col) {
                case 0: return sdf.format(new Date(e.getTimestamp()));
                case 1: return e.getEventTypeName();
                case 2: return e.getThreadName();
                case 3: return e.getLockDisplayName();
                case 4: return e.getOwnerThreadName();
                case 5: return Integer.valueOf(e.getWaiterCount());
                default: return "";
            }
        }
    }

    /* ── Cell Renderer ───────────────────────────────── */

    private static class LockCellRenderer extends AlignedCellRenderer {
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int col) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
            if (isSelected) return c;

            if (col == 1) {
                String type = value.toString();
                if ("CONTENDED".equals(type)) {
                    c.setForeground(Color.RED);
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                } else if ("ACQUIRED".equals(type)) {
                    c.setForeground(new Color(0, 128, 0));
                    c.setFont(c.getFont().deriveFont(Font.PLAIN));
                } else if ("DEADLOCK".equals(type)) {
                    c.setForeground(Color.RED);
                    c.setFont(c.getFont().deriveFont(Font.BOLD | Font.ITALIC));
                } else {
                    c.setForeground(Color.BLACK);
                    c.setFont(c.getFont().deriveFont(Font.PLAIN));
                }
            } else if (col == 5 && value instanceof Integer) {
                int waiters = ((Integer) value).intValue();
                if (waiters >= 3) {
                    c.setForeground(Color.RED);
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                } else if (waiters >= 2) {
                    c.setForeground(new Color(200, 100, 0));
                    c.setFont(c.getFont().deriveFont(Font.PLAIN));
                } else {
                    c.setForeground(Color.BLACK);
                    c.setFont(c.getFont().deriveFont(Font.PLAIN));
                }
            } else {
                c.setForeground(Color.BLACK);
                c.setFont(c.getFont().deriveFont(Font.PLAIN));
            }
            return c;
        }
    }

    /* ── Lock Hotspot Table ──────────────────────────── */

    private static class LockHotspotModel extends AbstractTableModel {
        private final String[] COLS = {"#", "Code Location (contention site)", "Lock Object", "Contentions", "% of Total", "Max Waiters"};
        private List<String[]> data = new ArrayList<String[]>();

        public void setData(List<Map.Entry<String, int[]>> hotspots,
                            Map<String, String> locks,
                            Map<String, int[]> maxWaiters,
                            int totalContentions) {
            data.clear();
            for (int i = 0; i < hotspots.size(); i++) {
                Map.Entry<String, int[]> entry = hotspots.get(i);
                String loc = entry.getKey();
                int count = entry.getValue()[0];
                String lock = locks.containsKey(loc) ? locks.get(loc) : "?";
                int mw = maxWaiters.containsKey(loc) ? maxWaiters.get(loc)[0] : 0;
                double pct = totalContentions > 0 ? count * 100.0 / totalContentions : 0;
                data.add(new String[]{
                    String.valueOf(i + 1),
                    loc,
                    lock,
                    String.valueOf(count),
                    String.format("%.1f%%", pct),
                    String.valueOf(mw)
                });
            }
            fireTableDataChanged();
        }

        public int getRowCount() { return data.size(); }
        public int getColumnCount() { return COLS.length; }
        public String getColumnName(int c) { return COLS[c]; }
        public Object getValueAt(int row, int col) {
            if (row < 0 || row >= data.size()) return "";
            String[] r = data.get(row);
            return (col >= 0 && col < r.length) ? r[col] : "";
        }
    }

    private static class LockHotspotRenderer extends AlignedCellRenderer {
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int col) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
            if (isSelected) return c;

            if (col == 3 || col == 4) {
                try {
                    String text = value.toString().replace("%", "");
                    double v = Double.parseDouble(text);
                    if (col == 4 && v > 30) { c.setForeground(Color.RED); c.setFont(c.getFont().deriveFont(Font.BOLD)); }
                    else if (col == 4 && v > 15) { c.setForeground(new Color(200, 100, 0)); c.setFont(c.getFont().deriveFont(Font.BOLD)); }
                    else if (col == 3 && v > 20) { c.setForeground(Color.RED); c.setFont(c.getFont().deriveFont(Font.BOLD)); }
                    else { c.setForeground(Color.BLACK); c.setFont(c.getFont().deriveFont(Font.PLAIN)); }
                } catch (NumberFormatException e) { c.setForeground(Color.BLACK); }
            } else if (col == 5) {
                try {
                    int mw = Integer.parseInt(value.toString());
                    if (mw >= 3) { c.setForeground(Color.RED); c.setFont(c.getFont().deriveFont(Font.BOLD)); }
                    else { c.setForeground(Color.BLACK); c.setFont(c.getFont().deriveFont(Font.PLAIN)); }
                } catch (NumberFormatException e) { c.setForeground(Color.BLACK); }
            } else {
                c.setForeground(Color.BLACK);
                c.setFont(c.getFont().deriveFont(Font.PLAIN));
            }
            return c;
        }
    }
}
