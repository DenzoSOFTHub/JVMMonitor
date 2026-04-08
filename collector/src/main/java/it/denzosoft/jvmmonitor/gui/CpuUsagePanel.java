package it.denzosoft.jvmmonitor.gui;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.gui.chart.AlignedCellRenderer;
import it.denzosoft.jvmmonitor.gui.chart.TimeSeriesChart;
import it.denzosoft.jvmmonitor.model.CpuUsageSnapshot;
import it.denzosoft.jvmmonitor.model.CpuUsageSnapshot.ThreadCpuInfo;

import javax.swing.*;
import it.denzosoft.jvmmonitor.gui.chart.CsvExporter;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/**
 * CPU Usage Analysis panel with:
 * - System vs JVM CPU charts
 * - Per-thread CPU table with delta (CPU consumed in last interval)
 * - Hot thread detection (auto-flag threads >20% CPU)
 * - CPU spike → stack trace capture (records top threads at spike moments)
 */
public class CpuUsagePanel extends JPanel {

    private final JVMMonitorCollector collector;
    private final JLabel summaryLabel;
    private final TimeSeriesChart cpuChart;
    private final TimeSeriesChart headroomChart;
    private final ThreadCpuTableModel tableModel;
    private final JTextArea hotThreadArea;
    private final JTextArea spikeArea;

    /* Track previous snapshot for delta computation */
    private CpuUsageSnapshot previousSnapshot = null;
    private final Map<Long, Long> prevThreadCpuTime = new LinkedHashMap<Long, Long>();

    /* Top process CPU history for headroom chart */
    private final List[] procCpuHist = new List[5];
    private static final int MAX_PROC_HIST = 150;

    /* Spike detection */
    private double cpuSpikeThreshold = 80.0; /* % */
    private final List<String> spikeLog = new ArrayList<String>();
    private static final int MAX_SPIKE_LOG = 50;
    private final SimpleDateFormat spikeFmt = new SimpleDateFormat("HH:mm:ss");

    public CpuUsagePanel(JVMMonitorCollector collector) {
        this.collector = collector;
        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        summaryLabel = new JLabel("CPU: no data");
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.BOLD, 13f));
        add(summaryLabel, BorderLayout.NORTH);

        /* Charts */
        cpuChart = new TimeSeriesChart("CPU Usage (5 min)", "%");
        cpuChart.defineSeries("System Total", new Color(200, 80, 80), false);
        cpuChart.defineSeries("JVM Process", new Color(30, 130, 200), true);
        cpuChart.defineSeries("System Avg(5)", new Color(180, 50, 50), false, true);
        cpuChart.defineSeries("JVM Avg(5)", new Color(20, 80, 180), false, true);
        cpuChart.setFixedMaxY(100);

        headroomChart = new TimeSeriesChart("CPU Breakdown (5 min)", "%");
        headroomChart.defineSeries("Available", new Color(50, 180, 50), true);
        headroomChart.defineSeries("JVM", new Color(30, 130, 200), true);
        headroomChart.defineSeries("Proc #1", new Color(220, 60, 60), false);
        headroomChart.defineSeries("Proc #2", new Color(200, 130, 30), false);
        headroomChart.defineSeries("Proc #3", new Color(150, 80, 200), false);
        headroomChart.defineSeries("Proc #4", new Color(180, 140, 50), false);
        headroomChart.defineSeries("Proc #5", new Color(100, 160, 100), false);
        headroomChart.setFixedMaxY(100);
        headroomChart.setShowLegend(true);

        JPanel chartsRow = new JPanel(new GridLayout(1, 2, 5, 0));
        chartsRow.add(cpuChart);
        chartsRow.add(headroomChart);

        /* Bottom: tabbed (Thread CPU + Hot Threads + Spike Log) */
        JTabbedPane bottomTabs = new JTabbedPane();

        /* Per-thread CPU table */
        tableModel = new ThreadCpuTableModel();
        JTable table = new JTable(tableModel);
        table.setDefaultRenderer(Object.class, new CpuCellRenderer());
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(20);
        CsvExporter.install(table);
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createTitledBorder(
                "Per-Thread CPU (sorted by delta — who consumed CPU in the last interval)"));
        bottomTabs.addTab("Thread CPU", tableScroll);

        /* Hot thread detection */
        hotThreadArea = new JTextArea();
        hotThreadArea.setEditable(false);
        hotThreadArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        hotThreadArea.setText("Hot threads (>20% CPU) will appear here with their stack traces.\n" +
                "This auto-detects which threads are consuming the most CPU right now.");
        JScrollPane hotScroll = new JScrollPane(hotThreadArea);
        hotScroll.setBorder(BorderFactory.createTitledBorder(
                "Hot Threads (auto-detected — consuming >20% CPU)"));
        bottomTabs.addTab("Hot Threads", hotScroll);

        /* Spike log */
        spikeArea = new JTextArea();
        spikeArea.setEditable(false);
        spikeArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        spikeArea.setText("CPU spike events (>80% system CPU) are automatically captured here.\n" +
                "Each spike records: timestamp, CPU%, and top thread stacks at that moment.");
        JScrollPane spikeScroll = new JScrollPane(spikeArea);
        spikeScroll.setBorder(BorderFactory.createTitledBorder(
                "CPU Spike Log (auto-captured when system CPU > 80%)"));
        bottomTabs.addTab("Spike Log", spikeScroll);

        for (int pi = 0; pi < 5; pi++) procCpuHist[pi] = new java.util.ArrayList();

        final JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chartsRow, bottomTabs);
        split.setResizeWeight(0.5);
        split.addComponentListener(new java.awt.event.ComponentAdapter() {
            public void componentResized(java.awt.event.ComponentEvent e) {
                int half = split.getHeight() / 2; if (half > 0) split.setDividerLocation(half);
            }
        });
        add(split, BorderLayout.CENTER);
    }

    public void updateData() {
        long now = System.currentTimeMillis();
        long from = now - 300000;

        List<CpuUsageSnapshot> history = collector.getStore().getCpuUsageHistory(from, now);

        /* Chart data */
        List<long[]> sysTotal = new ArrayList<long[]>();
        List<long[]> jvmProc = new ArrayList<long[]>();
        List<long[]> available = new ArrayList<long[]>();
        List<long[]> jvmShare = new ArrayList<long[]>();

        for (int i = 0; i < history.size(); i++) {
            CpuUsageSnapshot s = history.get(i);
            sysTotal.add(TimeSeriesChart.point(s.getTimestamp(), s.getSystemCpuPercent()));
            jvmProc.add(TimeSeriesChart.point(s.getTimestamp(), s.getProcessCpuPercent()));
            available.add(TimeSeriesChart.point(s.getTimestamp(), s.getAvailableCpuPercent()));
            jvmShare.add(TimeSeriesChart.point(s.getTimestamp(), s.getProcessCpuPercent()));
        }

        /* Moving average over 5 periods */
        List<long[]> sysAvg = movingAverage(history, true, 5);
        List<long[]> jvmAvg = movingAverage(history, false, 5);

        cpuChart.setSeriesData("System Total", sysTotal);
        cpuChart.setSeriesData("JVM Process", jvmProc);
        cpuChart.setSeriesData("System Avg(5)", sysAvg);
        cpuChart.setSeriesData("JVM Avg(5)", jvmAvg);
        headroomChart.setSeriesData("Available", available);
        headroomChart.setSeriesData("JVM", jvmShare);

        /* Top 5 processes by CPU (from ProcessInfo, accumulated in history buffer) */
        it.denzosoft.jvmmonitor.model.ProcessInfo procInfo = collector.getStore().getLatestProcessInfo();
        if (procInfo != null) {
            it.denzosoft.jvmmonitor.model.ProcessInfo.ProcessEntry[] procs = procInfo.getTopProcesses();
            if (procs != null) {
                int top = Math.min(procs.length, 5);
                for (int pi = 0; pi < 5; pi++) {
                    if (pi < top && procs[pi] != null) {
                        procCpuHist[pi].add(TimeSeriesChart.point(now, procs[pi].cpuPercent));
                    }
                    while (procCpuHist[pi].size() > MAX_PROC_HIST) procCpuHist[pi].remove(0);
                    headroomChart.setSeriesData("Proc #" + (pi + 1), procCpuHist[pi]);
                }
            }
        }

        /* Latest snapshot */
        CpuUsageSnapshot latest = collector.getStore().getLatestCpuUsage();
        if (latest == null) return;

        /* Compute CPU delta per thread */
        ThreadCpuInfo[] threads = latest.getTopThreads();
        ThreadCpuDelta[] deltas = null;
        if (threads != null) {
            deltas = new ThreadCpuDelta[threads.length];
            long dtMs = previousSnapshot != null
                    ? latest.getTimestamp() - previousSnapshot.getTimestamp() : 0;

            for (int i = 0; i < threads.length; i++) {
                ThreadCpuInfo t = threads[i];
                Long prevCpu = prevThreadCpuTime.get(Long.valueOf(t.threadId));
                long deltaMs = 0;
                double deltaPct = 0;
                if (prevCpu != null && dtMs > 0) {
                    deltaMs = t.cpuTimeMs - prevCpu.longValue();
                    if (deltaMs < 0) deltaMs = 0;
                    deltaPct = (deltaMs * 100.0) / dtMs;
                    if (deltaPct > 100) deltaPct = 100;
                }
                deltas[i] = new ThreadCpuDelta(t.threadId, t.threadName, t.cpuTimeMs,
                        deltaMs, deltaPct, t.cpuPercent, t.state);
            }

            /* Update previous */
            prevThreadCpuTime.clear();
            for (int i = 0; i < threads.length; i++) {
                prevThreadCpuTime.put(Long.valueOf(threads[i].threadId), Long.valueOf(threads[i].cpuTimeMs));
            }
        }
        previousSnapshot = latest;

        /* Table */
        if (deltas != null) {
            tableModel.setData(deltas);
        }

        /* Summary */
        summaryLabel.setText(String.format(
                "CPU: System %.1f%%  JVM %.1f%%  Available %.1f%%  |  %d cores  |  User: %dms  Sys: %dms",
                latest.getSystemCpuPercent(), latest.getProcessCpuPercent(),
                latest.getAvailableCpuPercent(), latest.getAvailableProcessors(),
                latest.getProcessUserTimeMs(), latest.getProcessSystemTimeMs()));

        if (latest.getAvailableCpuPercent() < 10) {
            summaryLabel.setForeground(Color.RED);
        } else if (latest.getAvailableCpuPercent() < 30) {
            summaryLabel.setForeground(new Color(200, 100, 0));
        } else {
            summaryLabel.setForeground(Color.BLACK);
        }

        /* Hot thread detection */
        if (deltas != null) {
            StringBuilder hot = new StringBuilder();
            int hotCount = 0;
            /* Sort by delta % desc */
            ThreadCpuDelta[] sorted = (ThreadCpuDelta[]) deltas.clone();
            java.util.Arrays.sort(sorted, new Comparator<ThreadCpuDelta>() {
                public int compare(ThreadCpuDelta a, ThreadCpuDelta b) {
                    return Double.compare(b.deltaPct, a.deltaPct);
                }
            });

            for (int i = 0; i < sorted.length; i++) {
                if (sorted[i].deltaPct > 20) {
                    hotCount++;
                    hot.append(String.format("HOT THREAD #%d: \"%s\" — %.1f%% CPU (delta: %dms)\n",
                            hotCount, sorted[i].threadName, sorted[i].deltaPct, sorted[i].deltaMs));
                    hot.append(String.format("  State: %s  |  Cumulative CPU: %dms\n",
                            sorted[i].state, sorted[i].cpuTimeMs));
                    hot.append("  (Use CPU Profiler to capture its stack trace)\n\n");
                }
            }
            if (hotCount == 0) {
                hot.append("No hot threads detected (all threads < 20% CPU delta).\n");
            }
            hotThreadArea.setText(hot.toString());
            hotThreadArea.setCaretPosition(0);
        }

        /* CPU spike detection */
        if (latest.getSystemCpuPercent() > cpuSpikeThreshold) {
            StringBuilder spike = new StringBuilder();
            spike.append(spikeFmt.format(new Date(latest.getTimestamp())));
            spike.append(String.format(" | System: %.1f%% JVM: %.1f%%",
                    latest.getSystemCpuPercent(), latest.getProcessCpuPercent()));
            if (deltas != null) {
                /* Find top 3 CPU consumers at spike moment */
                ThreadCpuDelta[] sorted = (ThreadCpuDelta[]) deltas.clone();
                java.util.Arrays.sort(sorted, new Comparator<ThreadCpuDelta>() {
                    public int compare(ThreadCpuDelta a, ThreadCpuDelta b) {
                        return Double.compare(b.deltaPct, a.deltaPct);
                    }
                });
                spike.append(" | Top: ");
                int shown = Math.min(3, sorted.length);
                for (int i = 0; i < shown; i++) {
                    if (i > 0) spike.append(", ");
                    spike.append(String.format("%s(%.0f%%)", sorted[i].threadName, sorted[i].deltaPct));
                }
            }
            spike.append('\n');

            spikeLog.add(spike.toString());
            if (spikeLog.size() > MAX_SPIKE_LOG) spikeLog.remove(0);

            StringBuilder sb = new StringBuilder();
            sb.append("CPU Spike Events (system CPU > ").append((int) cpuSpikeThreshold).append("%):\n\n");
            for (int i = spikeLog.size() - 1; i >= 0; i--) {
                sb.append(spikeLog.get(i));
            }
            spikeArea.setText(sb.toString());
            spikeArea.setCaretPosition(0);
        }
    }

    public void render() {
        repaint();
    }

    public void refresh() {
        updateData();
        render();
    }

    /* ── Moving average helper ────────────────────── */

    private static List<long[]> movingAverage(List<CpuUsageSnapshot> history, boolean system, int window) {
        List<long[]> result = new ArrayList<long[]>();
        for (int i = 0; i < history.size(); i++) {
            double sum = 0;
            int count = 0;
            for (int j = Math.max(0, i - window + 1); j <= i; j++) {
                CpuUsageSnapshot s = history.get(j);
                sum += system ? s.getSystemCpuPercent() : s.getProcessCpuPercent();
                count++;
            }
            double avg = count > 0 ? sum / count : 0;
            result.add(TimeSeriesChart.point(history.get(i).getTimestamp(), avg));
        }
        return result;
    }

    /* ── Thread CPU Delta ────────────────────────── */

    private static class ThreadCpuDelta {
        final long threadId;
        final String threadName;
        final long cpuTimeMs;
        final long deltaMs;     /* CPU time consumed in last interval */
        final double deltaPct;  /* delta as % of interval */
        final double reportedPct;
        final String state;

        ThreadCpuDelta(long threadId, String threadName, long cpuTimeMs,
                       long deltaMs, double deltaPct, double reportedPct, String state) {
            this.threadId = threadId;
            this.threadName = threadName;
            this.cpuTimeMs = cpuTimeMs;
            this.deltaMs = deltaMs;
            this.deltaPct = deltaPct;
            this.reportedPct = reportedPct;
            this.state = state;
        }
    }

    /* ── Table Model ─────────────────────────────── */

    private static class ThreadCpuTableModel extends AbstractTableModel {
        private final String[] COLS = {"#", "Thread", "CPU Delta %", "Delta (ms)", "CPU Total (ms)", "State"};
        private ThreadCpuDelta[] data = new ThreadCpuDelta[0];

        public void setData(ThreadCpuDelta[] data) {
            this.data = data != null ? data : new ThreadCpuDelta[0];
            java.util.Arrays.sort(this.data, new Comparator<ThreadCpuDelta>() {
                public int compare(ThreadCpuDelta a, ThreadCpuDelta b) {
                    return Double.compare(b.deltaPct, a.deltaPct);
                }
            });
            fireTableDataChanged();
        }

        public int getRowCount() { return data.length; }
        public int getColumnCount() { return COLS.length; }
        public String getColumnName(int c) { return COLS[c]; }

        public Object getValueAt(int row, int col) {
            ThreadCpuDelta t = data[row];
            switch (col) {
                case 0: return Integer.valueOf(row + 1);
                case 1: return t.threadName;
                case 2: return String.format("%.1f%%", t.deltaPct);
                case 3: return Long.valueOf(t.deltaMs);
                case 4: return Long.valueOf(t.cpuTimeMs);
                case 5: return t.state;
                default: return "";
            }
        }
    }

    /* ── Cell Renderer ───────────────────────────── */

    private static class CpuCellRenderer extends AlignedCellRenderer {
        protected void colorize(Component c, JTable table, Object value, int row, int col) {
            if (col == 2) {
                try {
                    double pct = Double.parseDouble(value.toString().replace("%", ""));
                    if (pct > 30) { c.setForeground(Color.RED); c.setFont(c.getFont().deriveFont(Font.BOLD)); }
                    else if (pct > 15) { c.setForeground(new Color(200, 100, 0)); c.setFont(c.getFont().deriveFont(Font.BOLD)); }
                    else if (pct > 5) { c.setForeground(new Color(0, 128, 0)); }
                } catch (NumberFormatException e) { /* ignore */ }
            } else if (col == 5) {
                String state = value != null ? value.toString() : "";
                if ("RUNNABLE".equals(state)) c.setForeground(new Color(0, 128, 0));
                else if ("BLOCKED".equals(state)) { c.setForeground(Color.RED); c.setFont(c.getFont().deriveFont(Font.BOLD)); }
            }
        }
    }
}
