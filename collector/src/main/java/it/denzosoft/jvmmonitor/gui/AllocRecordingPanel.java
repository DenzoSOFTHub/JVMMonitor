package it.denzosoft.jvmmonitor.gui;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.model.AllocationEvent;

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
 * Allocation recording panel with start/stop.
 * When recording, collects all allocation events.
 * On stop, aggregates by class and shows a table sorted by total allocated size.
 */
public class AllocRecordingPanel extends JPanel {

    private final JVMMonitorCollector collector;
    private final JLabel statusLabel;
    private final JButton startBtn;
    private final JButton stopBtn;
    private final JButton clearBtn;
    private final AllocTableModel tableModel;

    private volatile boolean recording = false;
    private long recordStartTs = 0;
    private long recordStopTs = 0;

    public AllocRecordingPanel(JVMMonitorCollector collector) {
        this.collector = collector;
        setLayout(new BorderLayout(5, 5));

        /* Control bar */
        JPanel controlBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));

        startBtn = new JButton("Start Recording");
        startBtn.setBackground(new Color(50, 160, 50));
        startBtn.setForeground(Color.WHITE);
        startBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { startRecording(); }
        });

        stopBtn = new JButton("Stop & Analyze");
        stopBtn.setBackground(new Color(200, 50, 50));
        stopBtn.setForeground(Color.WHITE);
        stopBtn.setEnabled(false);
        stopBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { stopRecording(); }
        });

        clearBtn = new JButton("Clear");
        clearBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { clearResults(); }
        });

        statusLabel = new JLabel("Press Start to begin recording object allocations");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 13f));

        controlBar.add(startBtn);
        controlBar.add(stopBtn);
        controlBar.add(clearBtn);
        controlBar.add(Box.createHorizontalStrut(20));
        controlBar.add(statusLabel);
        add(controlBar, BorderLayout.NORTH);

        /* Table */
        tableModel = new AllocTableModel();
        JTable table = new JTable(tableModel);
        table.setDefaultRenderer(Object.class, new AllocCellRenderer());
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(18);
        CsvExporter.install(table);
        table.getColumnModel().getColumn(0).setPreferredWidth(40);
        table.getColumnModel().getColumn(1).setPreferredWidth(300);
        table.getColumnModel().getColumn(2).setPreferredWidth(80);
        table.getColumnModel().getColumn(3).setPreferredWidth(100);
        table.getColumnModel().getColumn(4).setPreferredWidth(80);
        table.getColumnModel().getColumn(5).setPreferredWidth(250);
        add(new JScrollPane(table), BorderLayout.CENTER);
    }

    private void startRecording() {
        recording = true;
        recordStartTs = System.currentTimeMillis();
        recordStopTs = 0;
        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        statusLabel.setText("RECORDING allocations...");
        statusLabel.setForeground(Color.RED);
        tableModel.clear();
    }

    private void stopRecording() {
        recording = false;
        recordStopTs = System.currentTimeMillis();
        startBtn.setEnabled(true);
        stopBtn.setEnabled(false);

        /* Aggregate allocations */
        List<AllocationEvent> events = collector.getStore().getAllocationEvents(recordStartTs, recordStopTs);
        Map<String, AllocAggregate> aggregates = new LinkedHashMap<String, AllocAggregate>();

        for (int i = 0; i < events.size(); i++) {
            AllocationEvent e = events.get(i);
            String key = e.getClassName();
            AllocAggregate agg = aggregates.get(key);
            if (agg == null) {
                agg = new AllocAggregate(e.getDisplayClassName());
                aggregates.put(key, agg);
            }
            agg.count++;
            agg.totalSize += e.getSize();
            /* Track top allocation site */
            String site = e.getAllocSite();
            if (site != null && !site.isEmpty()) {
                int[] sc = agg.sites.get(site);
                if (sc == null) { sc = new int[]{0}; agg.sites.put(site, sc); }
                sc[0]++;
            }
        }

        /* Sort by total size desc */
        List<AllocAggregate> sorted = new ArrayList<AllocAggregate>(aggregates.values());
        Collections.sort(sorted, new Comparator<AllocAggregate>() {
            public int compare(AllocAggregate a, AllocAggregate b) {
                return Long.compare(b.totalSize, a.totalSize);
            }
        });

        /* Find top site for each */
        for (int i = 0; i < sorted.size(); i++) {
            AllocAggregate agg = sorted.get(i);
            String topSite = "";
            int topCount = 0;
            for (Map.Entry<String, int[]> entry : agg.sites.entrySet()) {
                if (entry.getValue()[0] > topCount) {
                    topCount = entry.getValue()[0];
                    topSite = entry.getKey();
                }
            }
            agg.topAllocSite = topSite;
        }

        tableModel.setData(sorted, events.size());

        double durationSec = (recordStopTs - recordStartTs) / 1000.0;
        long totalBytes = 0;
        for (int i = 0; i < sorted.size(); i++) totalBytes += sorted.get(i).totalSize;

        statusLabel.setText(String.format(
                "Recorded: %d allocations in %.1fs  |  %d classes  |  Total: %s  |  Rate: %.0f alloc/s",
                events.size(), durationSec, sorted.size(),
                formatBytes(totalBytes), events.size() / durationSec));
        statusLabel.setForeground(new Color(0, 100, 0));
    }

    private void clearResults() {
        recording = false;
        startBtn.setEnabled(true);
        stopBtn.setEnabled(false);
        statusLabel.setText("Press Start to begin recording object allocations");
        statusLabel.setForeground(Color.BLACK);
        tableModel.clear();
    }

    public void refresh() {
        if (recording) {
            long now = System.currentTimeMillis();
            int count = collector.getStore().getAllocationEvents(recordStartTs, now).size();
            double elapsed = (now - recordStartTs) / 1000.0;
            statusLabel.setText(String.format("RECORDING... %d allocations (%.0fs elapsed)", count, elapsed));
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /* ── Aggregate ───────────────────────────────────── */

    private static class AllocAggregate {
        final String className;
        int count;
        long totalSize;
        String topAllocSite = "";
        final Map<String, int[]> sites = new LinkedHashMap<String, int[]>();

        AllocAggregate(String className) { this.className = className; }
    }

    /* ── Table Model ─────────────────────────────────── */

    private static class AllocTableModel extends AbstractTableModel {
        private final String[] COLS = {"#", "Class", "Instances", "Total Allocated", "Avg Size", "Top Allocation Site"};
        private List<AllocAggregate> data = new ArrayList<AllocAggregate>();
        private int totalAllocs = 0;

        public void setData(List<AllocAggregate> data, int totalAllocs) {
            this.data = data;
            this.totalAllocs = totalAllocs;
            fireTableDataChanged();
        }

        public void clear() {
            data.clear();
            totalAllocs = 0;
            fireTableDataChanged();
        }

        public int getRowCount() { return data.size(); }
        public int getColumnCount() { return COLS.length; }
        public String getColumnName(int c) { return COLS[c]; }

        public Object getValueAt(int row, int col) {
            AllocAggregate a = data.get(row);
            switch (col) {
                case 0: return Integer.valueOf(row + 1);
                case 1: return a.className;
                case 2: return Integer.valueOf(a.count);
                case 3: return formatBytes(a.totalSize);
                case 4:
                    long avg = a.count > 0 ? a.totalSize / a.count : 0;
                    return formatBytes(avg);
                case 5: return a.topAllocSite;
                default: return "";
            }
        }

        private static String formatBytes(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
    }

    /* ── Cell Renderer ───────────────────────────────── */

    private static class AllocCellRenderer extends AlignedCellRenderer {
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int col) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
            if (isSelected) return c;

            c.setFont(c.getFont().deriveFont(Font.PLAIN));
            c.setForeground(Color.BLACK);

            if (col == 2 && value instanceof Integer) {
                int count = ((Integer) value).intValue();
                if (count > 1000) { c.setForeground(Color.RED); c.setFont(c.getFont().deriveFont(Font.BOLD)); }
                else if (count > 100) { c.setForeground(new Color(200, 100, 0)); c.setFont(c.getFont().deriveFont(Font.BOLD)); }
            } else if (col == 3) {
                String text = value.toString();
                if (text.contains("MB")) { c.setForeground(Color.RED); c.setFont(c.getFont().deriveFont(Font.BOLD)); }
                else if (text.contains("KB")) { c.setForeground(new Color(200, 100, 0)); }
            }
            return c;
        }
    }
}
