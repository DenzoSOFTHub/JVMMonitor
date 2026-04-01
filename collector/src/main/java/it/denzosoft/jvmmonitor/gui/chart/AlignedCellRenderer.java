package it.denzosoft.jvmmonitor.gui.chart;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;

/**
 * Base cell renderer that right-aligns numeric values and left-aligns text.
 * Subclass this and override colorize() to add custom coloring.
 */
public class AlignedCellRenderer extends DefaultTableCellRenderer {

    public Component getTableCellRendererComponent(JTable table, Object value,
            boolean isSelected, boolean hasFocus, int row, int col) {
        Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);

        /* Align: right for numbers, left for text */
        if (value instanceof Number) {
            setHorizontalAlignment(SwingConstants.RIGHT);
        } else if (value != null && isNumericText(value.toString())) {
            setHorizontalAlignment(SwingConstants.RIGHT);
        } else {
            setHorizontalAlignment(SwingConstants.LEFT);
        }

        if (!isSelected) {
            c.setForeground(Color.BLACK);
            c.setFont(c.getFont().deriveFont(Font.PLAIN));
            colorize(c, table, value, row, col);
        }
        return c;
    }

    /** Override in subclasses to apply custom coloring. */
    protected void colorize(Component c, JTable table, Object value, int row, int col) {
    }

    private static boolean isNumericText(String s) {
        if (s == null || s.isEmpty()) return false;
        /* Detect: "123", "12.3%", "45.6 MB", "+123", "-45", "1.2 KB", "0" */
        char first = s.charAt(0);
        if (first == '+' || first == '-') {
            return s.length() > 1 && (Character.isDigit(s.charAt(1)) || s.charAt(1) == '.');
        }
        if (Character.isDigit(first)) return true;
        if ("stable".equals(s) || "yes".equals(s) || "no".equals(s) || "NO".equals(s) || s.isEmpty()) return false;
        return false;
    }
}
