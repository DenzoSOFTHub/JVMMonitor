package it.denzosoft.jvmmonitor.gui;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.analysis.CpuProfileAggregator;
import it.denzosoft.jvmmonitor.analysis.CpuProfileAggregator.MethodNode;
import it.denzosoft.jvmmonitor.gui.chart.FlameGraph;
import it.denzosoft.jvmmonitor.gui.chart.TraceTreeTable;
import it.denzosoft.jvmmonitor.model.CpuSample;

import javax.swing.*;
import it.denzosoft.jvmmonitor.gui.chart.CsvExporter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import it.denzosoft.jvmmonitor.gui.chart.AlignedCellRenderer;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.*;
import java.util.List;

/**
 * CPU Profiler panel with start/stop recording.
 * Records CPU samples while active, then shows:
 * - Call tree (JTree) sorted by total sample count
 * - Hot methods table sorted by self sample count
 */
public class CpuProfilerPanel extends JPanel {

    private final JVMMonitorCollector collector;
    private final CpuProfileAggregator aggregator = new CpuProfileAggregator();
    private final JLabel statusLabel;
    private final JButton startBtn;
    private final JButton stopBtn;
    private final JButton clearBtn;
    private final TraceTreeTable callTreeTable;
    private final HotMethodTableModel tableModel;
    private final FlameGraph flameGraph;

    private volatile boolean recording = false;
    private long recordingStartTs = 0;
    private long recordingStopTs = 0;
    private int recordedSamples = 0;

    public CpuProfilerPanel(JVMMonitorCollector collector) {
        this.collector = collector;
        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        /* Control bar */
        JPanel controlBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));

        startBtn = new JButton("Start Recording");
        startBtn.setBackground(new Color(50, 160, 50));
        startBtn.setForeground(Color.WHITE);
        startBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { startRecording(); }
        });

        stopBtn = new JButton("Stop Recording");
        stopBtn.setBackground(new Color(200, 50, 50));
        stopBtn.setForeground(Color.WHITE);
        stopBtn.setEnabled(false);
        stopBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { stopRecording(); }
        });

        clearBtn = new JButton("Clear");
        clearBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { clearProfile(); }
        });

        statusLabel = new JLabel("CPU Profiler: idle");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 13f));

        controlBar.add(startBtn);
        controlBar.add(stopBtn);
        controlBar.add(clearBtn);
        controlBar.add(Box.createHorizontalStrut(20));
        controlBar.add(statusLabel);
        add(controlBar, BorderLayout.NORTH);

        /* Call tree (tree-table) */
        callTreeTable = new TraceTreeTable();

        /* Hot methods table */
        tableModel = new HotMethodTableModel();
        JTable table = new JTable(tableModel);
        table.setDefaultRenderer(Object.class, new HotMethodRenderer());
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(20);
        CsvExporter.install(table);

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Hot Methods (by self samples — where time is actually spent)"));

        /* Flame graph */
        flameGraph = new FlameGraph();
        JScrollPane flameScroll = new JScrollPane(flameGraph);
        flameScroll.setBorder(BorderFactory.createTitledBorder("Flame Graph (width = CPU time, bottom = caller, top = callee)"));
        flameGraph.setPreferredSize(new Dimension(0, 600));

        /* Tabbed views */
        JTabbedPane viewTabs = new JTabbedPane();
        viewTabs.addTab("Flame Graph", flameScroll);
        viewTabs.addTab("Call Tree", callTreeTable);
        viewTabs.addTab("Hot Methods", tableScroll);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, viewTabs, new JPanel());
        split.setResizeWeight(1.0);
        split.setDividerSize(0);
        add(split, BorderLayout.CENTER);
    }

    private void startRecording() {
        recording = true;
        recordingStartTs = System.currentTimeMillis();
        recordingStopTs = 0;
        recordedSamples = 0;
        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        statusLabel.setText("RECORDING... (samples are being collected)");
        statusLabel.setForeground(Color.RED);

        /* Clear previous results */
        callTreeTable.clear();
        tableModel.setData(new ArrayList<MethodNode>(), 0);
    }

    private void stopRecording() {
        recording = false;
        recordingStopTs = System.currentTimeMillis();
        startBtn.setEnabled(true);
        stopBtn.setEnabled(false);

        /* Aggregate the recorded samples */
        List<CpuSample> samples = collector.getStore().getCpuSamples(recordingStartTs, recordingStopTs);
        recordedSamples = samples.size();
        resolveNames(samples);
        aggregator.aggregate(samples);

        double durationSec = (recordingStopTs - recordingStartTs) / 1000.0;
        statusLabel.setText(String.format(
                "Recorded: %d samples in %.1fs  |  %d unique methods",
                recordedSamples, durationSec, aggregator.getTopMethods(999999).size()));
        statusLabel.setForeground(new Color(0, 100, 0));

        updateViews();

        /* Build flame graph */
        flameGraph.buildFromSamples(samples);
    }

    private void clearProfile() {
        recording = false;
        recordedSamples = 0;
        startBtn.setEnabled(true);
        stopBtn.setEnabled(false);
        statusLabel.setText("CPU Profiler: idle");
        statusLabel.setForeground(Color.BLACK);
        callTreeTable.clear();
        tableModel.setData(new ArrayList<MethodNode>(), 0);
        flameGraph.clear();
    }

    public void refresh() {
        if (recording) {
            /* Live update: aggregate and refresh views every refresh cycle */
            long now = System.currentTimeMillis();
            List<CpuSample> samples = collector.getStore().getCpuSamples(recordingStartTs, now);
            recordedSamples = samples.size();
            double elapsed = (now - recordingStartTs) / 1000.0;
            statusLabel.setText(String.format("RECORDING... %d samples (%.0fs elapsed)", recordedSamples, elapsed));

            if (recordedSamples > 0) {
                resolveNames(samples);
                aggregator.aggregate(samples);
                updateViews();
                flameGraph.buildFromSamples(samples);
            }
        }
    }

    /** Try to resolve method names from the connection's METHOD_INFO cache. */
    private void resolveNames(List<CpuSample> samples) {
        it.denzosoft.jvmmonitor.net.AgentConnection conn = collector.getConnection();
        if (conn == null) return;
        java.util.Map<Long, String[]> cache = conn.getMethodNameCache();
        if (cache == null || cache.isEmpty()) return;
        for (int s = 0; s < samples.size(); s++) {
            CpuSample.StackFrame[] frames = samples.get(s).getFrames();
            if (frames == null) continue;
            for (int f = 0; f < frames.length; f++) {
                if (frames[f].getClassName() == null) {
                    String[] names = cache.get(Long.valueOf(frames[f].getMethodId()));
                    if (names != null) {
                        frames[f].setClassName(names[0]);
                        frames[f].setMethodName(names[1]);
                    }
                }
            }
        }
    }

    private void updateViews() {
        int totalSamples = aggregator.getTotalSamples();

        /* Build call tree as TraceTreeTable nodes */
        List<TraceTreeTable.TraceNode> roots = new ArrayList<TraceTreeTable.TraceNode>();
        if (totalSamples > 0) {
            List<MethodNode> topByTotal = aggregator.getRootNodes(30);
            Set<String> visited = new HashSet<String>();
            for (int i = 0; i < topByTotal.size(); i++) {
                MethodNode node = topByTotal.get(i);
                TraceTreeTable.TraceNode tn = buildTraceNode(node, totalSamples, visited, 0);
                roots.add(tn);
            }
        }
        callTreeTable.setTraces(roots);

        /* Hot methods table */
        tableModel.setData(aggregator.getTopMethods(50), totalSamples);
    }

    private TraceTreeTable.TraceNode buildTraceNode(MethodNode node, int totalSamples,
                                                     Set<String> visited, int depth) {
        TraceTreeTable.TraceNode tn = new TraceTreeTable.TraceNode(
                node.displayName,
                0, /* no duration for sampling — use self% instead */
                node.totalPercent(totalSamples),
                "self: " + node.selfCount + " (" + String.format("%.1f%%", node.selfPercent(totalSamples)) + ")",
                false, depth);
        /* Override duration to show sample count for clarity */
        /* The "Duration" column will show selfCount, "%" will show totalPercent */

        if (depth < 5 && visited.add(node.key)) {
            List<Map.Entry<String, int[]>> childEntries =
                    new ArrayList<Map.Entry<String, int[]>>(node.children.entrySet());
            Collections.sort(childEntries, new Comparator<Map.Entry<String, int[]>>() {
                public int compare(Map.Entry<String, int[]> a, Map.Entry<String, int[]> b) {
                    return b.getValue()[0] - a.getValue()[0];
                }
            });
            for (int i = 0; i < Math.min(childEntries.size(), 10); i++) {
                MethodNode child = aggregator.getNode(childEntries.get(i).getKey());
                if (child != null) {
                    tn.children.add(buildTraceNode(child, totalSamples, visited, depth + 1));
                }
            }
            visited.remove(node.key);
        }
        return tn;
    }

    /* ── Table model ──────────────────────────────────── */

    private static class HotMethodTableModel extends AbstractTableModel {
        private final String[] COLS = {"#", "Method", "Self", "Self %", "Total", "Total %"};
        private List<MethodNode> data = new ArrayList<MethodNode>();
        private int totalSamples = 0;

        public void setData(List<MethodNode> data, int totalSamples) {
            this.data = data;
            this.totalSamples = totalSamples;
            fireTableDataChanged();
        }

        public int getRowCount() { return data.size(); }
        public int getColumnCount() { return COLS.length; }
        public String getColumnName(int c) { return COLS[c]; }

        public Object getValueAt(int row, int col) {
            MethodNode m = data.get(row);
            switch (col) {
                case 0: return Integer.valueOf(row + 1);
                case 1: return m.displayName;
                case 2: return Integer.valueOf(m.selfCount);
                case 3: return String.format("%.1f%%", m.selfPercent(totalSamples));
                case 4: return Integer.valueOf(m.totalCount);
                case 5: return String.format("%.1f%%", m.totalPercent(totalSamples));
                default: return "";
            }
        }
    }

    /* ── Renderers ────────────────────────────────────── */

    private static class HotMethodRenderer extends AlignedCellRenderer {
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int col) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
            if (!isSelected && (col == 3 || col == 5)) {
                try {
                    double pct = Double.parseDouble(value.toString().replace("%", ""));
                    if (pct > 20) c.setForeground(Color.RED);
                    else if (pct > 10) c.setForeground(new Color(200, 100, 0));
                    else if (pct > 5) c.setForeground(new Color(0, 128, 0));
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

}
