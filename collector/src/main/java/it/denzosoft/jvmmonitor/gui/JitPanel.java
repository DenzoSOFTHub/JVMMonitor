package it.denzosoft.jvmmonitor.gui;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.gui.chart.TimeSeriesChart;
import it.denzosoft.jvmmonitor.model.JitEvent;

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
 * JIT Compiler panel.
 * Top: Cumulative compiled methods line chart.
 * Bottom: Recent JIT events table.
 */
public class JitPanel extends JPanel {

    private final JVMMonitorCollector collector;
    private final TimeSeriesChart compiledChart;
    private final JitTableModel tableModel;
    private final JLabel summaryLabel;

    public JitPanel(JVMMonitorCollector collector) {
        this.collector = collector;
        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        summaryLabel = new JLabel("JIT: no data");
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.BOLD, 13f));
        add(summaryLabel, BorderLayout.NORTH);

        compiledChart = new TimeSeriesChart("JIT Compilations (5 min)", "total compiled");
        compiledChart.defineSeries("Compiled", new Color(30, 140, 100), true);
        compiledChart.defineSeries("Deoptimized", new Color(220, 50, 50), false);
        compiledChart.setShowLegend(true);

        tableModel = new JitTableModel();
        JTable table = new JTable(tableModel);
        table.setDefaultRenderer(Object.class, new JitCellRenderer());
        table.setAutoCreateRowSorter(true);

        final JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                compiledChart, new JScrollPane(table));
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
        List<JitEvent> events = collector.getStore().getJitEvents(now - 300000, now);

        /* Build cumulative chart data */
        List<long[]> compiledPts = new ArrayList<long[]>();
        List<long[]> deoptPts = new ArrayList<long[]>();

        int cumCompiled = 0, cumDeopt = 0;
        for (int i = 0; i < events.size(); i++) {
            JitEvent e = events.get(i);
            if (e.getEventType() == JitEvent.COMPILED) {
                cumCompiled++;
                compiledPts.add(TimeSeriesChart.point(e.getTimestamp(), e.getTotalCompiled()));
            } else {
                cumDeopt++;
                deoptPts.add(TimeSeriesChart.point(e.getTimestamp(), e.getTotalCompiled()));
            }
        }
        compiledChart.setSeriesData("Compiled", compiledPts);
        compiledChart.setSeriesData("Deoptimized", deoptPts);

        /* Summary */
        int totalCompiled = 0;
        if (!events.isEmpty()) {
            totalCompiled = events.get(events.size() - 1).getTotalCompiled();
        }
        summaryLabel.setText(String.format(
                "JIT: %d compiled, %d deoptimized in last 5 min  |  Total compiled: %d",
                cumCompiled, cumDeopt, totalCompiled));

        /* Table */
        tableModel.setData(events);
    }

    private static class JitTableModel extends AbstractTableModel {
        private final String[] COLS = {"Time", "Type", "Class", "Method", "Code Size"};
        private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
        private List<JitEvent> data = new ArrayList<JitEvent>();

        public void setData(List<JitEvent> data) {
            this.data = data;
            fireTableDataChanged();
        }

        public int getRowCount() { return data.size(); }
        public int getColumnCount() { return COLS.length; }
        public String getColumnName(int c) { return COLS[c]; }

        public Object getValueAt(int row, int col) {
            JitEvent e = data.get(data.size() - 1 - row);
            switch (col) {
                case 0: return sdf.format(new Date(e.getTimestamp()));
                case 1: return e.getTypeName();
                case 2: return e.getClassName();
                case 3: return e.getMethodName();
                case 4: return e.getCodeSize() > 0 ? e.getCodeSize() + " bytes" : "";
                default: return "";
            }
        }
    }

    private static class JitCellRenderer extends AlignedCellRenderer {
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int col) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
            if (col == 1 && !isSelected) {
                c.setForeground("COMPILED".equals(value) ? new Color(0, 128, 0) : Color.RED);
            } else if (!isSelected) {
                c.setForeground(Color.BLACK);
            }
            return c;
        }
    }
}
