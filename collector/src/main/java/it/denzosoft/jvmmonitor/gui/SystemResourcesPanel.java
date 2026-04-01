package it.denzosoft.jvmmonitor.gui;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.gui.chart.TimeSeriesChart;
import it.denzosoft.jvmmonitor.model.*;

import javax.swing.*;
import it.denzosoft.jvmmonitor.gui.chart.CsvExporter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import it.denzosoft.jvmmonitor.gui.chart.AlignedCellRenderer;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * System Resources panel (OS + JVM internals).
 * Top row: RSS/VM (left), FDs/Threads (center), TCP connections (right)
 * Middle: Safepoint stats
 * Bottom: Native Memory output + Classloader table
 */
public class SystemResourcesPanel extends JPanel {

    private final JVMMonitorCollector collector;
    private final TimeSeriesChart memoryChart;
    private final TimeSeriesChart fdChart;
    private final TimeSeriesChart tcpChart;
    private final JLabel safepointLabel;
    private final JTextArea nmtArea;
    private final ClassloaderTableModel clModel;
    private final ProcessTableModel procModel;
    private final JLabel procSummaryLabel;
    private final JitPanel jitPanel;

    public SystemResourcesPanel(JVMMonitorCollector collector) {
        this.collector = collector;
        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        /* Top row: 3 time-series charts */
        memoryChart = new TimeSeriesChart("Process Memory (5 min)", "MB");
        memoryChart.defineSeries("RSS", new Color(30, 100, 200), true);
        memoryChart.defineSeries("VM Size", new Color(200, 100, 30), false);

        fdChart = new TimeSeriesChart("File Descriptors & Threads (5 min)", "count");
        fdChart.defineSeries("Open FDs", new Color(100, 150, 50), true);
        fdChart.defineSeries("OS Threads", new Color(180, 80, 180), false);

        tcpChart = new TimeSeriesChart("TCP Connections (5 min)", "count");
        tcpChart.defineSeries("Established", new Color(50, 150, 50), true);
        tcpChart.defineSeries("Close Wait", new Color(220, 150, 30), true);
        tcpChart.defineSeries("Time Wait", new Color(150, 100, 200), true);

        JPanel chartsRow = new JPanel(new GridLayout(1, 3, 5, 0));
        chartsRow.add(memoryChart);
        chartsRow.add(fdChart);
        chartsRow.add(tcpChart);

        /* Middle: Safepoint */
        safepointLabel = new JLabel("Safepoints: no data");
        safepointLabel.setFont(safepointLabel.getFont().deriveFont(Font.BOLD, 12f));
        safepointLabel.setBorder(BorderFactory.createTitledBorder("Safepoints"));

        /* Bottom: NMT + Classloaders */
        nmtArea = new JTextArea(4, 40);
        nmtArea.setEditable(false);
        nmtArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane nmtScroll = new JScrollPane(nmtArea);
        nmtScroll.setBorder(BorderFactory.createTitledBorder("Native Memory (NMT)"));

        clModel = new ClassloaderTableModel();
        JTable clTable = new JTable(clModel);
        JScrollPane clScroll = new JScrollPane(clTable);
        clScroll.setBorder(BorderFactory.createTitledBorder("Classloaders"));

        /* Process list */
        JPanel procPanel = new JPanel(new BorderLayout());
        procSummaryLabel = new JLabel("Processes: waiting for data...");
        procSummaryLabel.setFont(procSummaryLabel.getFont().deriveFont(Font.BOLD, 12f));
        procPanel.add(procSummaryLabel, BorderLayout.NORTH);
        procModel = new ProcessTableModel();
        JTable procTable = new JTable(procModel);
        procTable.setDefaultRenderer(Object.class, new ProcessCellRenderer());
        procTable.setAutoCreateRowSorter(true);
        procTable.setRowHeight(18);
        CsvExporter.install(procTable);
        procPanel.add(new JScrollPane(procTable), BorderLayout.CENTER);

        /* Bottom tabs */
        JTabbedPane bottomTabs = new JTabbedPane();
        bottomTabs.addTab("Processes", procPanel);

        JPanel nmtClPanel = new JPanel(new GridLayout(1, 2, 5, 0));
        nmtClPanel.add(nmtScroll);
        nmtClPanel.add(clScroll);
        bottomTabs.addTab("NMT & Classloaders", nmtClPanel);

        /* JIT Compiler sub-tab */
        jitPanel = new JitPanel(collector);
        bottomTabs.addTab("JIT Compiler", jitPanel);

        /* Assemble */
        JPanel middleBottom = new JPanel(new BorderLayout(0, 5));
        middleBottom.add(safepointLabel, BorderLayout.NORTH);
        middleBottom.add(bottomTabs, BorderLayout.CENTER);

        final JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chartsRow, middleBottom);
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

        /* OS metrics history */
        List<OsMetrics> osHistory = collector.getStore().getOsMetricsHistory(from, now);
        List<long[]> rss = new ArrayList<long[]>();
        List<long[]> vm = new ArrayList<long[]>();
        List<long[]> fds = new ArrayList<long[]>();
        List<long[]> threads = new ArrayList<long[]>();
        List<long[]> tcpEst = new ArrayList<long[]>();
        List<long[]> tcpCw = new ArrayList<long[]>();
        List<long[]> tcpTw = new ArrayList<long[]>();

        for (int i = 0; i < osHistory.size(); i++) {
            OsMetrics m = osHistory.get(i);
            rss.add(TimeSeriesChart.point(m.getTimestamp(), m.getRssMB()));
            vm.add(TimeSeriesChart.point(m.getTimestamp(), m.getVmSizeMB()));
            fds.add(TimeSeriesChart.point(m.getTimestamp(), m.getOpenFileDescriptors()));
            threads.add(TimeSeriesChart.point(m.getTimestamp(), m.getOsThreadCount()));
            tcpEst.add(TimeSeriesChart.point(m.getTimestamp(), m.getTcpEstablished()));
            tcpCw.add(TimeSeriesChart.point(m.getTimestamp(), m.getTcpCloseWait()));
            tcpTw.add(TimeSeriesChart.point(m.getTimestamp(), m.getTcpTimeWait()));
        }

        memoryChart.setSeriesData("RSS", rss);
        memoryChart.setSeriesData("VM Size", vm);
        fdChart.setSeriesData("Open FDs", fds);
        fdChart.setSeriesData("OS Threads", threads);
        tcpChart.setSeriesData("Established", tcpEst);
        tcpChart.setSeriesData("Close Wait", tcpCw);
        tcpChart.setSeriesData("Time Wait", tcpTw);

        /* Safepoints */
        SafepointEvent sp = collector.getStore().getLatestSafepoint();
        if (sp != null && sp.isAvailable()) {
            double avgTime = sp.getSafepointCount() > 0
                    ? (double) sp.getTotalTimeMs() / sp.getSafepointCount() : 0;
            double avgSync = sp.getSafepointCount() > 0
                    ? (double) sp.getSyncTimeMs() / sp.getSafepointCount() : 0;
            safepointLabel.setText(String.format(
                    "Safepoints:  Count: %d  |  Total: %dms  |  Sync: %dms  |  Avg: %.1fms  |  Avg Sync: %.1fms",
                    sp.getSafepointCount(), sp.getTotalTimeMs(), sp.getSyncTimeMs(), avgTime, avgSync));
        }

        /* Native Memory */
        NativeMemoryStats nms = collector.getStore().getLatestNativeMemory();
        if (nms != null) {
            nmtArea.setText(nms.isAvailable() ? nms.getRawOutput() : nms.getRawOutput());
            nmtArea.setCaretPosition(0);
        }

        /* Classloaders */
        ClassloaderStats cls = collector.getStore().getLatestClassloaderStats();
        clModel.setData(cls);

        /* Process list */
        ProcessInfo procInfo = collector.getStore().getLatestProcessInfo();
        if (procInfo != null) {
            procModel.setData(procInfo.getTopProcesses());
            procSummaryLabel.setText(String.format(
                    "Processes: %d total  |  RAM: %.0f MB used / %.0f MB total (%.0f%%)  |  Swap: %.0f MB  |  Load: %.1f / %.1f / %.1f",
                    procInfo.getTotalProcesses(),
                    procInfo.getTotalMemoryMB() - procInfo.getFreeMemoryMB(), procInfo.getTotalMemoryMB(),
                    procInfo.getUsedMemoryPercent(),
                    procInfo.getSwapUsedBytes() / (1024.0 * 1024.0),
                    procInfo.getLoadAvg1(), procInfo.getLoadAvg5(), procInfo.getLoadAvg15()));
        }

        /* JIT */
        jitPanel.refresh();
    }

    private static class ClassloaderTableModel extends AbstractTableModel {
        private final String[] COLS = {"Classloader", "Classes"};
        private ClassloaderStats.LoaderInfo[] data = new ClassloaderStats.LoaderInfo[0];
        private int total = 0;

        public void setData(ClassloaderStats stats) {
            if (stats != null) {
                this.data = stats.getLoaders();
                this.total = stats.getTotalClassCount();
            } else {
                this.data = new ClassloaderStats.LoaderInfo[0];
                this.total = 0;
            }
            fireTableDataChanged();
        }

        public int getRowCount() { return data.length; }
        public int getColumnCount() { return COLS.length; }
        public String getColumnName(int c) { return COLS[c]; }

        public Object getValueAt(int row, int col) {
            switch (col) {
                case 0: return data[row].getLoaderClass();
                case 1: return Integer.valueOf(data[row].getClassCount());
                default: return "";
            }
        }
    }

    /* ── Process Table ───────────────────────────────── */

    private static class ProcessTableModel extends AbstractTableModel {
        private final String[] COLS = {"PID", "Name", "CPU %", "RSS (MB)", "Threads", "State"};
        private ProcessInfo.ProcessEntry[] data = new ProcessInfo.ProcessEntry[0];

        public void setData(ProcessInfo.ProcessEntry[] data) {
            this.data = data != null ? data : new ProcessInfo.ProcessEntry[0];
            /* Sort by CPU descending */
            java.util.Arrays.sort(this.data, new java.util.Comparator<ProcessInfo.ProcessEntry>() {
                public int compare(ProcessInfo.ProcessEntry a, ProcessInfo.ProcessEntry b) {
                    return Double.compare(b.cpuPercent, a.cpuPercent);
                }
            });
            fireTableDataChanged();
        }

        public int getRowCount() { return data.length; }
        public int getColumnCount() { return COLS.length; }
        public String getColumnName(int c) { return COLS[c]; }

        public Object getValueAt(int row, int col) {
            ProcessInfo.ProcessEntry p = data[row];
            switch (col) {
                case 0: return Integer.valueOf(p.pid);
                case 1: return p.name;
                case 2: return String.format("%.1f%%", p.cpuPercent);
                case 3: return String.format("%.0f", p.getRssMB());
                case 4: return Integer.valueOf(p.threads);
                case 5: return p.state;
                default: return "";
            }
        }
    }

    private static class ProcessCellRenderer extends AlignedCellRenderer {
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int col) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
            if (isSelected) return c;
            if (col == 1) {
                String name = value.toString();
                if (name.contains("java")) {
                    c.setForeground(new Color(0, 100, 200));
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                } else {
                    c.setForeground(Color.BLACK);
                    c.setFont(c.getFont().deriveFont(Font.PLAIN));
                }
            } else if (col == 2) {
                try {
                    double pct = Double.parseDouble(value.toString().replace("%", ""));
                    if (pct > 30) c.setForeground(Color.RED);
                    else if (pct > 10) c.setForeground(new Color(200, 100, 0));
                    else c.setForeground(Color.BLACK);
                } catch (NumberFormatException e) { c.setForeground(Color.BLACK); }
                c.setFont(c.getFont().deriveFont(Font.BOLD));
            } else {
                c.setForeground(Color.BLACK);
                c.setFont(c.getFont().deriveFont(Font.PLAIN));
            }
            return c;
        }
    }
}
