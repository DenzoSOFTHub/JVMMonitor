package it.denzosoft.jvmmonitor.gui;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.analysis.HeapAnalyzer;
import it.denzosoft.jvmmonitor.analysis.HeapAnalyzer.OldGenEntry;
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
 * Correlated Memory + GC panel.
 * Top row: Heap area chart (left) + Non-heap area chart (right)
 * Middle row: GC pause chart (left) + GC throughput chart (right)
 * Bottom: Tabbed area with GC detail table + Old Gen analysis + Leak suspects
 */
public class MemoryGcPanel extends JPanel {

    private final JVMMonitorCollector collector;
    private final TimeSeriesChart heapChart;
    private final TimeSeriesChart nonHeapChart;
    private final TimeSeriesChart allocRateChart;
    private final TimeSeriesChart liveDataChart;
    private final OldGenTableModel oldGenModel;
    private final OldGenTableModel leakModel;
    private final JLabel summaryLabel;
    private final JLabel oldGenLabel;
    private final JLabel leakLabel;
    private final MemoryPoolsPanel poolsPanel;
    private final HistogramTableModel histoModel;
    private final JLabel histoLabel;
    private final OldGenTableModel bigObjModel;
    private final JLabel bigObjLabel;
    private final AllocRecordingPanel allocPanel;

    public MemoryGcPanel(JVMMonitorCollector collector) {
        this.collector = collector;
        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        summaryLabel = new JLabel(" ");
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.BOLD, 13f));
        add(summaryLabel, BorderLayout.NORTH);

        /* Memory charts row */
        heapChart = new TimeSeriesChart("Heap Usage (5 min)", "MB");
        heapChart.defineSeries("Used", new Color(30, 100, 200), true);
        heapChart.defineSeries("Max", new Color(200, 50, 50), false);

        nonHeapChart = new TimeSeriesChart("Non-Heap Usage (5 min)", "MB");
        nonHeapChart.defineSeries("Used", new Color(150, 80, 200), true);

        JPanel memRow = new JPanel(new GridLayout(1, 2, 5, 0));
        memRow.add(heapChart);
        memRow.add(nonHeapChart);

        /* Allocation rate + Live data set charts */
        allocRateChart = new TimeSeriesChart("Allocation Rate (5 min)", "MB/s");
        allocRateChart.defineSeries("Alloc Rate", new Color(220, 100, 30), true);

        liveDataChart = new TimeSeriesChart("Live Data Set — heap after Full GC (5 min)", "MB");
        liveDataChart.defineSeries("After GC", new Color(200, 50, 50), true);
        liveDataChart.defineSeries("Heap Max", new Color(150, 150, 150), false);

        JPanel rateRow = new JPanel(new GridLayout(1, 2, 5, 0));
        rateRow.add(allocRateChart);
        rateRow.add(liveDataChart);

        /* Charts combined: memory + allocation/live data */
        JPanel chartsPanel = new JPanel(new GridLayout(2, 1, 0, 5));
        chartsPanel.add(memRow);
        chartsPanel.add(rateRow);

        /* Bottom: tabbed analysis area */
        JTabbedPane analysisTabs = new JTabbedPane();

        /* ── Histogram (FIRST tab) with manual request ── */
        JPanel histoPanel = new JPanel(new BorderLayout());
        JPanel histoBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));
        JButton histoRequestBtn = new JButton("Request Histogram");
        histoRequestBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                try {
                    if (collector.getConnection() != null && collector.getConnection().isConnected()) {
                        collector.getConnection().enableModule("histogram", 1, null, 5);
                        histoLabel.setText("Requesting histogram from agent...");
                    }
                } catch (Exception ex) { histoLabel.setText("Failed: " + ex.getMessage()); }
            }
        });
        histoLabel = new JLabel("Press 'Request Histogram' to capture class data");
        histoLabel.setFont(histoLabel.getFont().deriveFont(Font.BOLD, 12f));
        histoBar.add(histoRequestBtn);
        histoBar.add(histoLabel);
        histoPanel.add(histoBar, BorderLayout.NORTH);
        histoModel = new HistogramTableModel();
        JTable histoTable = new JTable(histoModel);
        histoTable.setDefaultRenderer(Object.class, new OldGenCellRenderer());
        histoTable.setAutoCreateRowSorter(true);
        histoTable.setRowHeight(18);
        CsvExporter.install(histoTable);
        histoPanel.add(new JScrollPane(histoTable), BorderLayout.CENTER);
        analysisTabs.addTab("Histogram", histoPanel);

        /* Old Gen analysis table */
        JPanel oldGenPanel = new JPanel(new BorderLayout());
        oldGenLabel = new JLabel("Old Generation: waiting for histogram data...");
        oldGenLabel.setFont(oldGenLabel.getFont().deriveFont(Font.BOLD, 12f));
        oldGenPanel.add(oldGenLabel, BorderLayout.NORTH);
        oldGenModel = new OldGenTableModel();
        JTable oldGenTable = new JTable(oldGenModel);
        oldGenTable.setDefaultRenderer(Object.class, new OldGenCellRenderer());
        oldGenTable.setRowHeight(18);
        CsvExporter.install(oldGenTable);
        oldGenPanel.add(new JScrollPane(oldGenTable), BorderLayout.CENTER);
        analysisTabs.addTab("Old Gen Objects", oldGenPanel);

        /* Leak suspects table */
        JPanel leakPanel = new JPanel(new BorderLayout());
        leakLabel = new JLabel("Leak Suspects: waiting for histogram data...");
        leakLabel.setFont(leakLabel.getFont().deriveFont(Font.BOLD, 12f));
        leakLabel.setForeground(new Color(180, 0, 0));
        leakPanel.add(leakLabel, BorderLayout.NORTH);
        leakModel = new OldGenTableModel();
        JTable leakTable = new JTable(leakModel);
        leakTable.setDefaultRenderer(Object.class, new OldGenCellRenderer());
        leakTable.setRowHeight(18);
        CsvExporter.install(leakTable);
        leakPanel.add(new JScrollPane(leakTable), BorderLayout.CENTER);
        analysisTabs.addTab("Leak Suspects", leakPanel);

        /* Memory Pools visual panel */
        poolsPanel = new MemoryPoolsPanel();
        analysisTabs.addTab("Memory Pools", poolsPanel);

        /* Big Objects analysis */
        JPanel bigObjPanel = new JPanel(new BorderLayout());
        bigObjLabel = new JLabel("Big Objects: classes with average instance size > 1 KB");
        bigObjLabel.setFont(bigObjLabel.getFont().deriveFont(Font.BOLD, 12f));
        bigObjPanel.add(bigObjLabel, BorderLayout.NORTH);
        bigObjModel = new OldGenTableModel();
        JTable bigObjTable = new JTable(bigObjModel);
        bigObjTable.setDefaultRenderer(Object.class, new OldGenCellRenderer());
        bigObjTable.setAutoCreateRowSorter(true);
        bigObjTable.setRowHeight(18);
        CsvExporter.install(bigObjTable);
        bigObjPanel.add(new JScrollPane(bigObjTable), BorderLayout.CENTER);
        analysisTabs.addTab("Big Objects", bigObjPanel);

        /* Allocation Recording */
        allocPanel = new AllocRecordingPanel(collector);
        analysisTabs.addTab("Allocation Recording", allocPanel);

        final JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chartsPanel, analysisTabs);
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

        /* Memory snapshots */
        List<MemorySnapshot> snapshots = collector.getStore().getMemorySnapshots(from, now);
        List<long[]> heapUsed = new ArrayList<long[]>();
        List<long[]> heapMax = new ArrayList<long[]>();
        List<long[]> nonHeapUsed = new ArrayList<long[]>();

        for (int i = 0; i < snapshots.size(); i++) {
            MemorySnapshot s = snapshots.get(i);
            heapUsed.add(TimeSeriesChart.point(s.getTimestamp(), s.getHeapUsed() / (1024.0 * 1024.0)));
            heapMax.add(TimeSeriesChart.point(s.getTimestamp(), s.getHeapMax() / (1024.0 * 1024.0)));
            nonHeapUsed.add(TimeSeriesChart.point(s.getTimestamp(), s.getNonHeapUsed() / (1024.0 * 1024.0)));
        }
        heapChart.setSeriesData("Used", heapUsed);
        heapChart.setSeriesData("Max", heapMax);
        nonHeapChart.setSeriesData("Used", nonHeapUsed);

        /* GC events (needed for allocation rate computation) */
        List<GcEvent> gcEvents = collector.getStore().getGcEvents(from, now);

        /* Allocation rate and live data set from GC events */
        List<long[]> allocRatePts = new ArrayList<long[]>();
        List<long[]> liveDataPts = new ArrayList<long[]>();
        List<long[]> heapMaxPts = new ArrayList<long[]>();

        for (int i = 0; i < gcEvents.size(); i++) {
            GcEvent e = gcEvents.get(i);

            /* Live data set = heap after Full GC only (true floor — everything reclaimable was freed) */
            if (e.getHeapAfter() > 0 && e.getGcType() == GcEvent.TYPE_FULL) {
                liveDataPts.add(TimeSeriesChart.point(e.getTimestamp(),
                        e.getHeapAfter() / (1024.0 * 1024.0)));
            }
            if (e.getHeapBefore() > 0) {
                /* Heap max line */
                MemorySnapshot latestSnap = collector.getStore().getLatestMemorySnapshot();
                long hMax = latestSnap != null ? latestSnap.getHeapMax() : 0;
                heapMaxPts.add(TimeSeriesChart.point(e.getTimestamp(),
                        hMax / (1024.0 * 1024.0)));
            }

            /* Allocation rate = (heapBefore[N] - heapAfter[N-1]) / time between GCs */
            if (i > 0 && e.getHeapBefore() > 0) {
                GcEvent prev = gcEvents.get(i - 1);
                if (prev.getHeapAfter() > 0) {
                    long allocated = e.getHeapBefore() - prev.getHeapAfter();
                    long dtMs = e.getTimestamp() - prev.getTimestamp();
                    if (dtMs > 0 && allocated > 0) {
                        double mbPerSec = (allocated / (1024.0 * 1024.0)) / (dtMs / 1000.0);
                        allocRatePts.add(TimeSeriesChart.point(e.getTimestamp(), mbPerSec));
                    }
                }
            }
        }
        allocRateChart.setSeriesData("Alloc Rate", allocRatePts);
        liveDataChart.setSeriesData("After GC", liveDataPts);
        liveDataChart.setSeriesData("Heap Max", heapMaxPts);

        /* Summary */
        MemorySnapshot latest = collector.getStore().getLatestMemorySnapshot();
        double gcFreq = collector.getAnalysisContext().getGcFrequencyPerMinute(60);
        double avgPause = collector.getAnalysisContext().getAvgGcPauseMs(60);
        double throughput = collector.getAnalysisContext().getGcThroughputPercent(60);
        double growth = collector.getAnalysisContext().getHeapGrowthRateMBPerHour(5);

        /* Compute latest allocation rate and live data */
        double latestAllocRate = 0;
        double latestLiveData = 0;
        if (!allocRatePts.isEmpty()) {
            latestAllocRate = Double.longBitsToDouble(allocRatePts.get(allocRatePts.size() - 1)[1]);
        }
        if (!liveDataPts.isEmpty()) {
            latestLiveData = Double.longBitsToDouble(liveDataPts.get(liveDataPts.size() - 1)[1]);
        }

        if (latest != null) {
            summaryLabel.setText(String.format(
                    "Heap: %s / %s (%.1f%%)  |  Alloc: %.0f MB/s  |  Live: %.0f MB  |  GC: %.0f/min  Throughput: %.1f%%",
                    latest.getHeapUsedMB(), latest.getHeapMaxMB(), latest.getHeapUsagePercent(),
                    latestAllocRate, latestLiveData, gcFreq, throughput));
            if (throughput < 90) summaryLabel.setForeground(Color.RED);
            else if (growth > 100) summaryLabel.setForeground(new Color(200, 100, 0));
            else summaryLabel.setForeground(Color.BLACK);
        }

        /* Old Gen + Leak analysis */
        List<ClassHistogram> histograms = collector.getStore().getClassHistogramHistory();
        if (histograms.size() >= 1) {
            List<OldGenEntry> allEntries = HeapAnalyzer.analyzeOldGen(histograms);

            /* Old Gen: top by size, only those present across snapshots */
            List<OldGenEntry> oldGenEntries = new ArrayList<OldGenEntry>();
            for (int i = 0; i < allEntries.size() && oldGenEntries.size() < 30; i++) {
                if (allEntries.get(i).isOldGen) {
                    oldGenEntries.add(allEntries.get(i));
                }
            }
            oldGenModel.setData(oldGenEntries);
            oldGenLabel.setText(String.format(
                    "Old Generation: %d classes survived %d snapshots — sorted by total size",
                    oldGenEntries.size(), histograms.size()));

            /* Leak suspects */
            List<OldGenEntry> leaks = HeapAnalyzer.getLeakSuspects(allEntries);
            leakModel.setData(leaks);
            if (leaks.isEmpty()) {
                leakLabel.setText("Leak Suspects: no growing classes detected");
                leakLabel.setForeground(new Color(0, 128, 0));
            } else {
                leakLabel.setText(String.format(
                        "Leak Suspects: %d classes growing in old gen — potential memory leaks!",
                        leaks.size()));
                leakLabel.setForeground(Color.RED);
            }

            /* Histogram table: update only when new data arrives (manual request) */
            ClassHistogram latest2 = histograms.get(histograms.size() - 1);
            if (histoModel.getRowCount() == 0 || latest2.getTimestamp() != histoModel.lastTimestamp) {
                histoModel.setData(latest2);
                histoLabel.setText(String.format("Class Histogram: %d classes, took %.1fms (click Request for new snapshot)",
                        latest2.getEntryCount(), latest2.getElapsedMs()));
            }

            /* Big Objects: filter allEntries to classes with avg instance size > 1 KB */
            List<OldGenEntry> bigObjects = new ArrayList<OldGenEntry>();
            for (int i = 0; i < allEntries.size(); i++) {
                OldGenEntry e = allEntries.get(i);
                if (e.instanceCount > 0) {
                    double avgSize = (double) e.totalSize / e.instanceCount;
                    if (avgSize > 1024) { /* > 1 KB avg */
                        bigObjects.add(e);
                    }
                }
            }
            bigObjModel.setData(bigObjects);
            bigObjLabel.setText(String.format(
                    "Big Objects: %d classes with avg instance size > 1 KB (sorted by total size)",
                    bigObjects.size()));
        }

        /* Memory pools: derive from heap snapshot */
        if (latest != null) {
            poolsPanel.update(latest);
        }

        allocPanel.refresh();
    }



    /* ── Old Gen / Leak Table ────────────────────────── */

    private static class OldGenTableModel extends AbstractTableModel {
        private final String[] COLS = {"Class", "Instances", "Size (MB)", "Growth", "Size +/- (MB)", "Survived"};
        private List<OldGenEntry> data = new ArrayList<OldGenEntry>();

        public void setData(List<OldGenEntry> data) {
            this.data = data;
            fireTableDataChanged();
        }

        public int getRowCount() { return data.size(); }
        public int getColumnCount() { return COLS.length; }
        public String getColumnName(int c) { return COLS[c]; }

        public Object getValueAt(int row, int col) {
            OldGenEntry e = data.get(row);
            switch (col) {
                case 0: return e.getDisplayClassName();
                case 1: return Integer.valueOf(e.instanceCount);
                case 2: return String.format("%.2f", e.getTotalSizeMB());
                case 3:
                    if (e.countDelta > 0) return "+" + e.countDelta;
                    if (e.countDelta < 0) return String.valueOf(e.countDelta);
                    return "stable";
                case 4:
                    if (e.sizeDelta > 0) return String.format("+%.2f", e.getSizeDeltaMB());
                    if (e.sizeDelta < 0) return String.format("%.2f", e.getSizeDeltaMB());
                    return "0";
                case 5: return e.getSurvivalLabel();
                default: return "";
            }
        }
    }

    private static class OldGenCellRenderer extends AlignedCellRenderer {
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int col) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
            if (isSelected) return c;

            String text = value != null ? value.toString() : "";
            if (col == 3) {
                if (text.startsWith("+")) {
                    c.setForeground(Color.RED);
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                } else if ("stable".equals(text)) {
                    c.setForeground(new Color(0, 128, 0));
                    c.setFont(c.getFont().deriveFont(Font.PLAIN));
                } else {
                    c.setForeground(Color.BLACK);
                    c.setFont(c.getFont().deriveFont(Font.PLAIN));
                }
            } else if (col == 4) {
                if (text.startsWith("+")) {
                    c.setForeground(Color.RED);
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
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

    /* ── Memory Pools Visual Panel ───────────────────── */

    private static class MemoryPoolsPanel extends JPanel {
        private double heapUsed, heapMax, nonHeapUsed, nonHeapMax;
        /* Simulated pool breakdown from heap total */
        private double edenUsed, edenMax, survivorUsed, survivorMax;
        private double oldUsed, oldMax, metaUsed, metaMax;

        MemoryPoolsPanel() {
            setBackground(Color.WHITE);
        }

        void update(MemorySnapshot mem) {
            heapUsed = mem.getHeapUsed();
            heapMax = mem.getHeapMax();
            nonHeapUsed = mem.getNonHeapUsed();
            nonHeapMax = mem.getNonHeapMax();

            /* Estimate pool sizes from heap total (typical ratios) */
            edenMax = heapMax * 0.33;
            survivorMax = heapMax * 0.05;
            oldMax = heapMax * 0.62;
            metaMax = nonHeapMax * 0.7;

            /* Distribute used among pools */
            if (heapUsed < edenMax) {
                edenUsed = heapUsed * 0.6;
                survivorUsed = heapUsed * 0.05;
                oldUsed = heapUsed * 0.35;
            } else {
                edenUsed = edenMax * 0.8;
                survivorUsed = survivorMax * 0.3;
                oldUsed = heapUsed - edenUsed - survivorUsed;
                if (oldUsed > oldMax) oldUsed = oldMax * 0.98;
            }
            metaUsed = nonHeapUsed * 0.7;

            repaint();
        }

        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (heapMax <= 0) return;

            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            int margin = 20;
            int barH = Math.min((h - margin * 2) / 6 - 8, 30);
            int barW = w - margin * 2 - 200;
            if (barW < 100) barW = 100;

            String[][] pools = {
                {"Eden Space", fmt(edenUsed), fmt(edenMax), pct(edenUsed, edenMax)},
                {"Survivor Space", fmt(survivorUsed), fmt(survivorMax), pct(survivorUsed, survivorMax)},
                {"Old Gen", fmt(oldUsed), fmt(oldMax), pct(oldUsed, oldMax)},
                {"Metaspace", fmt(metaUsed), fmt(metaMax), pct(metaUsed, metaMax)},
                {"Total Heap", fmt(heapUsed), fmt(heapMax), pct(heapUsed, heapMax)},
                {"Total Non-Heap", fmt(nonHeapUsed), fmt(nonHeapMax), pct(nonHeapUsed, nonHeapMax)},
            };
            double[] usedVals = {edenUsed, survivorUsed, oldUsed, metaUsed, heapUsed, nonHeapUsed};
            double[] maxVals = {edenMax, survivorMax, oldMax, metaMax, heapMax, nonHeapMax};
            Color[] colors = {
                new Color(80, 180, 80), new Color(80, 150, 200), new Color(200, 130, 50),
                new Color(150, 80, 180), new Color(30, 100, 200), new Color(100, 100, 100)
            };

            g2.setFont(g2.getFont().deriveFont(12f));
            FontMetrics fm = g2.getFontMetrics();

            for (int i = 0; i < pools.length; i++) {
                int y = margin + i * (barH + 8);
                double ratio = maxVals[i] > 0 ? usedVals[i] / maxVals[i] : 0;
                int fillW = (int)(ratio * barW);

                /* Label */
                g2.setColor(Color.BLACK);
                g2.drawString(pools[i][0], margin, y + barH / 2 + 4);

                int bx = margin + 130;

                /* Background bar */
                g2.setColor(new Color(230, 230, 230));
                g2.fillRoundRect(bx, y, barW, barH, 6, 6);

                /* Filled bar */
                Color col = colors[i];
                if (ratio > 0.9) col = new Color(220, 50, 50);
                else if (ratio > 0.75) col = new Color(220, 170, 30);
                g2.setColor(col);
                g2.fillRoundRect(bx, y, fillW, barH, 6, 6);

                /* Border */
                g2.setColor(Color.GRAY);
                g2.drawRoundRect(bx, y, barW, barH, 6, 6);

                /* Text: "used / max (xx%)" */
                String text = pools[i][1] + " / " + pools[i][2] + " (" + pools[i][3] + ")";
                int tx = bx + barW + 10;
                g2.setColor(ratio > 0.9 ? Color.RED : Color.BLACK);
                g2.drawString(text, tx, y + barH / 2 + 4);
            }
        }

        private static String fmt(double bytes) {
            double mb = bytes / (1024.0 * 1024.0);
            if (mb >= 1000) return String.format("%.1f GB", mb / 1024);
            return String.format("%.0f MB", mb);
        }

        private static String pct(double used, double max) {
            if (max <= 0) return "0%";
            return String.format("%.0f%%", used / max * 100);
        }
    }

    /* ── Histogram Table Model ───────────────────────── */

    private static class HistogramTableModel extends AbstractTableModel {
        private final String[] COLS = {"#", "Class", "Instances", "Size (MB)", "Avg Size", "\u0394 Instances", "\u0394 Size (MB)"};
        private ClassHistogram.Entry[] data = new ClassHistogram.Entry[0];
        long lastTimestamp = 0;
        private java.util.Map<String, int[]> prevInstances = new java.util.LinkedHashMap<String, int[]>();
        private java.util.Map<String, long[]> prevSizes = new java.util.LinkedHashMap<String, long[]>();
        private java.util.Map<String, int[]> deltaInstances = new java.util.LinkedHashMap<String, int[]>();
        private java.util.Map<String, long[]> deltaSizes = new java.util.LinkedHashMap<String, long[]>();

        public void setData(ClassHistogram histo) {
            if (histo != null) lastTimestamp = histo.getTimestamp();
            ClassHistogram.Entry[] newData = histo != null && histo.getEntries() != null
                    ? histo.getEntries() : new ClassHistogram.Entry[0];
            /* Compute deltas from previous snapshot */
            deltaInstances.clear();
            deltaSizes.clear();
            for (int i = 0; i < newData.length; i++) {
                if (newData[i] == null) continue;
                String key = newData[i].getClassName();
                int[] prev = prevInstances.get(key);
                long[] prevS = prevSizes.get(key);
                deltaInstances.put(key, new int[]{prev != null ? newData[i].getInstanceCount() - prev[0] : 0});
                deltaSizes.put(key, new long[]{prevS != null ? newData[i].getTotalSize() - prevS[0] : 0});
            }
            /* Save current as previous for next comparison */
            prevInstances.clear();
            prevSizes.clear();
            for (int i = 0; i < newData.length; i++) {
                if (newData[i] == null) continue;
                prevInstances.put(newData[i].getClassName(), new int[]{newData[i].getInstanceCount()});
                prevSizes.put(newData[i].getClassName(), new long[]{newData[i].getTotalSize()});
            }
            this.data = newData;
            fireTableDataChanged();
        }

        public int getRowCount() { return data.length; }
        public int getColumnCount() { return COLS.length; }
        public String getColumnName(int c) { return COLS[c]; }

        public Object getValueAt(int row, int col) {
            ClassHistogram.Entry e = data[row];
            if (e == null) return "";
            switch (col) {
                case 0: return Integer.valueOf(row + 1);
                case 1: return it.denzosoft.jvmmonitor.gui.chart.ClassNameFormatter.format(e.getClassName());
                case 2: return Integer.valueOf(e.getInstanceCount());
                case 3: return String.format("%.2f", e.getTotalSizeMB());
                case 4:
                    if (e.getInstanceCount() > 0) {
                        long avg = e.getTotalSize() / e.getInstanceCount();
                        if (avg > 1024 * 1024) return String.format("%.1f MB", avg / (1024.0 * 1024.0));
                        if (avg > 1024) return String.format("%.1f KB", avg / 1024.0);
                        return avg + " B";
                    }
                    return "0";
                case 5:
                    int[] di = deltaInstances.get(e.getClassName());
                    if (di != null && di[0] != 0) return (di[0] > 0 ? "+" : "") + di[0];
                    return "";
                case 6:
                    long[] ds = deltaSizes.get(e.getClassName());
                    if (ds != null && ds[0] != 0) {
                        double mb = ds[0] / (1024.0 * 1024.0);
                        return (ds[0] > 0 ? "+" : "") + String.format("%.2f", mb);
                    }
                    return "";
                default: return "";
            }
        }
    }
}
