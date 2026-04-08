package it.denzosoft.jvmmonitor.gui;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.gui.chart.StackedAreaChart;
import it.denzosoft.jvmmonitor.gui.chart.TimeSeriesChart;
import it.denzosoft.jvmmonitor.model.*;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Dashboard with 6 real-time mini-charts in a 2x3 grid:
 * Row 1: Thread States | CPU Usage | Allocation Rate
 * Row 2: Disk I/O     | Network   | Heap Usage
 * Plus summary bar and alarms at bottom.
 */
public class DashboardPanel extends JPanel {

    private final JVMMonitorCollector collector;
    private final JLabel summaryLabel;

    /* Row 1 */
    private final StackedAreaChart threadChart;
    private final TimeSeriesChart cpuChart;
    private final TimeSeriesChart allocChart;

    /* Row 2 */
    private final TimeSeriesChart diskChart;
    private final TimeSeriesChart networkChart;
    private final TimeSeriesChart heapChart;

    /* Alarms */
    private final DefaultListModel alarmListModel;

    public DashboardPanel(JVMMonitorCollector collector) {
        this.collector = collector;
        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        summaryLabel = new JLabel("JVMMonitor v1.1.0 — not connected");
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.BOLD, 13f));
        add(summaryLabel, BorderLayout.NORTH);

        /* ── Thread States (stacked area) ──────── */
        threadChart = new StackedAreaChart("Threads");
        threadChart.addCategory("BLOCKED", new Color(220, 50, 50));
        threadChart.addCategory("TIMED_WAIT", new Color(180, 140, 200));
        threadChart.addCategory("WAITING", new Color(220, 170, 30));
        threadChart.addCategory("RUNNABLE", new Color(50, 160, 50));

        /* ── CPU Usage ─────────────────────────── */
        cpuChart = new TimeSeriesChart("CPU Usage", "%");
        cpuChart.defineSeries("System", new Color(200, 80, 80), false);
        cpuChart.defineSeries("JVM", new Color(30, 130, 200), true);
        cpuChart.setFixedMaxY(100);
        cpuChart.setShowLegend(true);

        /* ── Allocation Rate ───────────────────── */
        allocChart = new TimeSeriesChart("Allocation Rate", "MB/s");
        allocChart.defineSeries("Alloc", new Color(220, 100, 30), true);
        allocChart.setShowLegend(false);

        /* ── Disk I/O ──────────────────────────── */
        diskChart = new TimeSeriesChart("Disk I/O", "ctx/s");
        diskChart.defineSeries("Vol CtxSw", new Color(100, 150, 50), true);
        diskChart.defineSeries("Invol CtxSw", new Color(200, 80, 80), false);
        diskChart.setShowLegend(true);

        /* ── Network ───────────────────────────── */
        networkChart = new TimeSeriesChart("Network", "seg/s");
        networkChart.defineSeries("In", new Color(50, 150, 50), true);
        networkChart.defineSeries("Out", new Color(200, 130, 30), true);
        networkChart.setShowLegend(true);

        /* ── Heap Usage ────────────────────────── */
        heapChart = new TimeSeriesChart("Heap Usage", "MB");
        heapChart.defineSeries("Used", new Color(30, 100, 200), true);
        heapChart.defineSeries("Live Set", new Color(150, 150, 150), false);
        heapChart.defineSeries("Max", new Color(200, 50, 50), false);
        heapChart.setShowLegend(true);

        /* 2x3 grid */
        JPanel chartsGrid = new JPanel(new GridLayout(2, 3, 5, 5));
        chartsGrid.add(threadChart);
        chartsGrid.add(cpuChart);
        chartsGrid.add(allocChart);
        chartsGrid.add(diskChart);
        chartsGrid.add(networkChart);
        chartsGrid.add(heapChart);

        /* Alarms at bottom */
        alarmListModel = new DefaultListModel();
        JList alarmList = new JList(alarmListModel);
        alarmList.setCellRenderer(new AlarmCellRenderer());
        JScrollPane alarmScroll = new JScrollPane(alarmList);
        alarmScroll.setBorder(BorderFactory.createTitledBorder("Active Alarms"));
        alarmScroll.setPreferredSize(new Dimension(0, 80));

        add(chartsGrid, BorderLayout.CENTER);
        add(alarmScroll, BorderLayout.SOUTH);
    }

    public void updateData() {
        if (collector.getStore() == null) return;
        long now = System.currentTimeMillis();

        /* ── Thread states ──────────────────────── */
        List<ThreadInfo> threads = collector.getStore().getLatestThreadInfo();
        if (threads == null) threads = new ArrayList<ThreadInfo>();
        int runnable = 0, blocked = 0, waiting = 0, timedWait = 0;
        for (int i = 0; i < threads.size(); i++) {
            switch (threads.get(i).getState()) {
                case ThreadInfo.STATE_RUNNABLE: runnable++; break;
                case ThreadInfo.STATE_BLOCKED: blocked++; break;
                case ThreadInfo.STATE_WAITING: waiting++; break;
                case ThreadInfo.STATE_TIMED_WAITING: timedWait++; break;
            }
        }
        if (threads.size() > 0) {
            threadChart.addSnapshot(now, new int[]{blocked, timedWait, waiting, runnable});
        }

        /* ── CPU Usage ──────────────────────────── */
        long from = now - 300000;
        List<CpuUsageSnapshot> cpuHistory = collector.getStore().getCpuUsageHistory(from, now);
        List<long[]> sysPts = new ArrayList<long[]>();
        List<long[]> jvmPts = new ArrayList<long[]>();
        for (int i = 0; i < cpuHistory.size(); i++) {
            CpuUsageSnapshot s = cpuHistory.get(i);
            sysPts.add(TimeSeriesChart.point(s.getTimestamp(), s.getSystemCpuPercent()));
            jvmPts.add(TimeSeriesChart.point(s.getTimestamp(), s.getProcessCpuPercent()));
        }
        cpuChart.setSeriesData("System", sysPts);
        cpuChart.setSeriesData("JVM", jvmPts);

        /* ── Allocation Rate ────────────────────── */
        List<GcEvent> gcEvents = collector.getStore().getGcEvents(from, now);
        List<long[]> allocPts = new ArrayList<long[]>();
        for (int i = 1; i < gcEvents.size(); i++) {
            GcEvent e = gcEvents.get(i);
            GcEvent prev = gcEvents.get(i - 1);
            if (e.getHeapBefore() > 0 && prev.getHeapAfter() > 0) {
                long allocated = e.getHeapBefore() - prev.getHeapAfter();
                long dtMs = e.getTimestamp() - prev.getTimestamp();
                if (dtMs > 0 && allocated > 0) {
                    double mbPerSec = (allocated / (1024.0 * 1024.0)) / (dtMs / 1000.0);
                    allocPts.add(TimeSeriesChart.point(e.getTimestamp(), mbPerSec));
                }
            }
        }
        allocChart.setSeriesData("Alloc", allocPts);

        /* ── Disk I/O (context switches as proxy) ── */
        List<OsMetrics> osHistory = collector.getStore().getOsMetricsHistory(from, now);
        List<long[]> volPts = new ArrayList<long[]>();
        List<long[]> involPts = new ArrayList<long[]>();
        for (int i = 0; i < osHistory.size(); i++) {
            OsMetrics m = osHistory.get(i);
            volPts.add(TimeSeriesChart.point(m.getTimestamp(), m.getVoluntaryContextSwitches()));
            involPts.add(TimeSeriesChart.point(m.getTimestamp(), m.getInvoluntaryContextSwitches()));
        }
        diskChart.setSeriesData("Vol CtxSw", volPts);
        diskChart.setSeriesData("Invol CtxSw", involPts);

        /* ── Network — segment rate from aggregate TCP counters (/proc/self/net/snmp).
         * Per-socket bytes are not available via /proc, so we show inSegs/outSegs
         * delta per second which is always populated by the network module. */
        List<NetworkSnapshot> netHistory = collector.getStore().getNetworkHistory(from, now);
        List<long[]> inPts = new ArrayList<long[]>();
        List<long[]> outPts = new ArrayList<long[]>();
        long prevInSegs = -1, prevOutSegs = -1, prevTs = 0;
        for (int i = 0; i < netHistory.size(); i++) {
            NetworkSnapshot n = netHistory.get(i);
            long inSegs = n.getInSegments();
            long outSegs = n.getOutSegments();
            if (prevInSegs >= 0 && n.getTimestamp() > prevTs) {
                double dtSec = (n.getTimestamp() - prevTs) / 1000.0;
                long deltaIn = inSegs - prevInSegs;
                long deltaOut = outSegs - prevOutSegs;
                if (deltaIn < 0) deltaIn = 0;
                if (deltaOut < 0) deltaOut = 0;
                inPts.add(TimeSeriesChart.point(n.getTimestamp(), deltaIn / dtSec));
                outPts.add(TimeSeriesChart.point(n.getTimestamp(), deltaOut / dtSec));
            }
            prevInSegs = inSegs;
            prevOutSegs = outSegs;
            prevTs = n.getTimestamp();
        }
        networkChart.setSeriesData("In", inPts);
        networkChart.setSeriesData("Out", outPts);

        /* ── Heap Usage ─────────────────────────── */
        List<MemorySnapshot> memHistory = collector.getStore().getMemorySnapshots(from, now);
        List<long[]> heapUsed = new ArrayList<long[]>();
        List<long[]> heapMax = new ArrayList<long[]>();
        for (int i = 0; i < memHistory.size(); i++) {
            MemorySnapshot s = memHistory.get(i);
            heapUsed.add(TimeSeriesChart.point(s.getTimestamp(), s.getHeapUsed() / (1024.0 * 1024.0)));
            heapMax.add(TimeSeriesChart.point(s.getTimestamp(), s.getHeapMax() / (1024.0 * 1024.0)));
        }
        /* Live Set: the minimum level of occupied heap.
         * Rule: starts = first heap used value.
         * While heap grows, live set stays flat.
         * When a Full GC reduces heap BELOW current live set, live set drops to that value.
         * Live set NEVER increases. */
        List<long[]> liveSetPts = new ArrayList<long[]>();
        if (memHistory.size() >= 2) {
            /* Initialize live set to first heap used value */
            double liveSet = memHistory.get(0).getHeapUsed() / (1024.0 * 1024.0);

            /* Index Full GC events by timestamp for quick lookup */
            List<GcEvent> gcEvts = collector.getStore().getGcEvents(from, now);
            int gcIdx = 0;

            for (int i = 0; i < memHistory.size(); i++) {
                MemorySnapshot s = memHistory.get(i);

                /* Process any Full GC events that occurred up to this snapshot's time */
                while (gcIdx < gcEvts.size() && gcEvts.get(gcIdx).getTimestamp() <= s.getTimestamp()) {
                    GcEvent gc = gcEvts.get(gcIdx);
                    if (gc.getGcType() == GcEvent.TYPE_FULL && gc.getHeapAfter() > 0) {
                        double afterGcMB = gc.getHeapAfter() / (1024.0 * 1024.0);
                        /* Only drop — never increase */
                        if (afterGcMB < liveSet) {
                            liveSet = afterGcMB;
                        }
                    }
                    gcIdx++;
                }

                liveSetPts.add(TimeSeriesChart.point(s.getTimestamp(), liveSet));
            }
        }
        if (liveSetPts.isEmpty() && memHistory.size() >= 2) {
            double initHeap = memHistory.get(0).getHeapUsed() / (1024.0 * 1024.0);
            liveSetPts.add(TimeSeriesChart.point(memHistory.get(0).getTimestamp(), initHeap));
            liveSetPts.add(TimeSeriesChart.point(
                    memHistory.get(memHistory.size() - 1).getTimestamp(), initHeap));
        }

        heapChart.setSeriesData("Used", heapUsed);
        heapChart.setSeriesData("Live Set", liveSetPts);
        heapChart.setSeriesData("Max", heapMax);

        /* ── Summary bar ────────────────────────── */
        MemorySnapshot latestMem = collector.getStore().getLatestMemorySnapshot();
        CpuUsageSnapshot latestCpu = collector.getStore().getLatestCpuUsage();
        StringBuilder sb = new StringBuilder();
        if (latestMem != null) {
            sb.append(String.format("Heap: %s/%s (%.0f%%)  ",
                    latestMem.getHeapUsedMB(), latestMem.getHeapMaxMB(), latestMem.getHeapUsagePercent()));
        }
        if (latestCpu != null) {
            sb.append(String.format("CPU: %.0f%% sys / %.0f%% jvm  ",
                    latestCpu.getSystemCpuPercent(), latestCpu.getProcessCpuPercent()));
        }
        sb.append(String.format("Threads: %d  GC: %d  Exc: %d  ",
                threads.size(), collector.getStore().getGcEventCount(),
                collector.getStore().getExceptionCount()));
        List<AlarmEvent> alarms = collector.getStore().getActiveAlarms();
        if (!alarms.isEmpty()) {
            sb.append(String.format("Alarms: %d", alarms.size()));
        }
        summaryLabel.setText(sb.toString());

        /* ── Alarms ─────────────────────────────── */
        alarmListModel.clear();
        for (int i = 0; i < alarms.size(); i++) {
            AlarmEvent a = alarms.get(i);
            alarmListModel.addElement(AlarmEvent.severityToString(a.getSeverity()) +
                    " " + a.getMessage());
        }
    }

    public void render() {
        repaint();
    }

    public void refresh() {
        updateData();
        render();
    }

    private static class AlarmCellRenderer extends DefaultListCellRenderer {
        public Component getListCellRendererComponent(JList list, Object value,
                int index, boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                    list, value, index, isSelected, cellHasFocus);
            String text = value.toString();
            if (text.startsWith("CRITICAL")) {
                label.setForeground(Color.RED);
                label.setFont(label.getFont().deriveFont(Font.BOLD));
            } else if (text.startsWith("WARNING")) {
                label.setForeground(new Color(200, 100, 0));
            }
            return label;
        }
    }
}
