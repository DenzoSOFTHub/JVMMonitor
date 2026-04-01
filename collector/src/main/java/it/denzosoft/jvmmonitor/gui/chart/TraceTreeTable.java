package it.denzosoft.jvmmonitor.gui.chart;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Tree-table for request traces. Each row is a method call with expandable children.
 * Columns: Method (indented + expand icon) | Duration | % | Context
 */
public class TraceTreeTable extends JPanel {

    private final JTable table;
    private final TreeTableModel model;

    public TraceTreeTable() {
        setLayout(new BorderLayout());
        model = new TreeTableModel();
        table = new JTable(model);
        table.setDefaultRenderer(Object.class, new TreeCellRenderer());
        table.setRowHeight(22);
        table.getColumnModel().getColumn(0).setPreferredWidth(400);
        table.getColumnModel().getColumn(1).setPreferredWidth(80);
        table.getColumnModel().getColumn(2).setPreferredWidth(60);
        table.getColumnModel().getColumn(3).setPreferredWidth(250);

        /* Click to expand/collapse */
        table.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                int col = table.columnAtPoint(e.getPoint());
                if (row >= 0 && col == 0) {
                    model.toggleExpand(row);
                }
            }
        });

        add(new JScrollPane(table), BorderLayout.CENTER);
    }

    /** Set trace data. Each TraceNode is a method call with children. */
    public void setTraces(List<TraceNode> roots) {
        model.setRoots(roots);
    }

    public void clear() {
        model.setRoots(new ArrayList<TraceNode>());
    }

    /* ── Node ────────────────────────────────── */

    public static class TraceNode {
        public final String method;
        public final double durationMs;
        public final double percent;
        public final String context;
        public final boolean isException;
        public final int depth;
        public final List<TraceNode> children = new ArrayList<TraceNode>();
        boolean expanded = true;

        public TraceNode(String method, double durationMs, double percent,
                         String context, boolean isException, int depth) {
            this.method = method;
            this.durationMs = durationMs;
            this.percent = percent;
            this.context = context != null ? context : "";
            this.isException = isException;
            this.depth = depth;
        }
    }

    /* ── Model ───────────────────────────────── */

    private static class TreeTableModel extends AbstractTableModel {
        private final String[] COLS = {"Method", "Duration", "%", "Context"};
        private List<TraceNode> roots = new ArrayList<TraceNode>();
        private List<TraceNode> visibleRows = new ArrayList<TraceNode>();

        public void setRoots(List<TraceNode> roots) {
            this.roots = roots;
            rebuildVisible();
        }

        public void toggleExpand(int row) {
            if (row < 0 || row >= visibleRows.size()) return;
            TraceNode node = visibleRows.get(row);
            if (!node.children.isEmpty()) {
                node.expanded = !node.expanded;
                rebuildVisible();
            }
        }

        private void rebuildVisible() {
            visibleRows.clear();
            for (int i = 0; i < roots.size(); i++) {
                addVisible(roots.get(i));
            }
            fireTableDataChanged();
        }

        private void addVisible(TraceNode node) {
            visibleRows.add(node);
            if (node.expanded) {
                for (int i = 0; i < node.children.size(); i++) {
                    addVisible(node.children.get(i));
                }
            }
        }

        public int getRowCount() { return visibleRows.size(); }
        public int getColumnCount() { return COLS.length; }
        public String getColumnName(int c) { return COLS[c]; }

        public Object getValueAt(int row, int col) {
            TraceNode n = visibleRows.get(row);
            switch (col) {
                case 0: return n;  /* render handles indentation + icon */
                case 1: return String.format("%.1f ms", n.durationMs);
                case 2: return String.format("%.0f%%", n.percent);
                case 3: return n.context;
                default: return "";
            }
        }
    }

    /* ── Renderer ─────────────────────────────── */

    private static class TreeCellRenderer extends DefaultTableCellRenderer {
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int col) {

            if (col == 0 && value instanceof TraceNode) {
                TraceNode n = (TraceNode) value;
                /* Build indented label with expand icon */
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < n.depth; i++) sb.append("    ");
                if (!n.children.isEmpty()) {
                    sb.append(n.expanded ? "\u25BC " : "\u25B6 "); /* ▼ or ▶ */
                } else {
                    sb.append("  ");
                }
                sb.append(n.method);

                Component c = super.getTableCellRendererComponent(table, sb.toString(),
                        isSelected, hasFocus, row, col);
                if (!isSelected) {
                    if (n.isException) {
                        c.setForeground(Color.RED);
                        c.setFont(c.getFont().deriveFont(Font.BOLD));
                    } else if (n.method.contains("executeQuery") || n.method.contains("PreparedStatement")
                            || n.method.contains("SELECT") || n.method.contains("INSERT")) {
                        c.setForeground(new Color(0, 100, 180));
                        c.setFont(c.getFont().deriveFont(Font.PLAIN));
                    } else if (n.depth == 0) {
                        c.setForeground(Color.BLACK);
                        c.setFont(c.getFont().deriveFont(Font.BOLD));
                    } else {
                        c.setForeground(Color.BLACK);
                        c.setFont(c.getFont().deriveFont(Font.PLAIN));
                    }
                }
                setHorizontalAlignment(SwingConstants.LEFT);
                return c;
            }

            Component c = super.getTableCellRendererComponent(table, value,
                    isSelected, hasFocus, row, col);
            if (!isSelected) {
                c.setForeground(Color.BLACK);
                c.setFont(c.getFont().deriveFont(Font.PLAIN));

                if (col == 1 || col == 2) {
                    setHorizontalAlignment(SwingConstants.RIGHT);
                    try {
                        String text = value.toString().replace(" ms", "").replace("%", "");
                        double v = Double.parseDouble(text);
                        if (col == 1 && v > 100) { c.setForeground(Color.RED); c.setFont(c.getFont().deriveFont(Font.BOLD)); }
                        else if (col == 1 && v > 20) { c.setForeground(new Color(200, 100, 0)); }
                    } catch (NumberFormatException e) { /* ignore */ }
                } else {
                    setHorizontalAlignment(SwingConstants.LEFT);
                }
            }
            return c;
        }
    }
}
