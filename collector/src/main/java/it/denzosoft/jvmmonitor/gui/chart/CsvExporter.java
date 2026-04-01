package it.denzosoft.jvmmonitor.gui.chart;

import javax.swing.*;
import javax.swing.table.TableModel;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.FileWriter;

/**
 * Adds a right-click context menu with "Export to CSV" to any JTable.
 * Call CsvExporter.install(table) after creating any JTable.
 */
public final class CsvExporter {

    private CsvExporter() {}

    /** Install right-click CSV export on a JTable. */
    public static void install(final JTable table) {
        final JPopupMenu popup = new JPopupMenu();
        JMenuItem exportItem = new JMenuItem("Export to CSV...");
        exportItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                exportCsv(table);
            }
        });
        popup.add(exportItem);

        table.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) { showPopup(e); }
            public void mouseReleased(MouseEvent e) { showPopup(e); }
            private void showPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    popup.show(e.getComponent(), e.getX(), e.getY());
                }
            }
        });
    }

    private static void exportCsv(JTable table) {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("export.csv"));
        if (fc.showSaveDialog(table) != JFileChooser.APPROVE_OPTION) return;

        try {
            TableModel model = table.getModel();
            FileWriter fw = new FileWriter(fc.getSelectedFile());

            /* Header */
            for (int c = 0; c < model.getColumnCount(); c++) {
                if (c > 0) fw.write(',');
                fw.write(escape(model.getColumnName(c)));
            }
            fw.write('\n');

            /* Rows (view order for filtering support) */
            for (int r = 0; r < table.getRowCount(); r++) {
                int modelRow = table.convertRowIndexToModel(r);
                for (int c = 0; c < model.getColumnCount(); c++) {
                    if (c > 0) fw.write(',');
                    Object val = model.getValueAt(modelRow, c);
                    fw.write(escape(val != null ? val.toString() : ""));
                }
                fw.write('\n');
            }
            fw.close();
            JOptionPane.showMessageDialog(table,
                    "Exported " + table.getRowCount() + " rows to " + fc.getSelectedFile().getName());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(table, "Export failed: " + ex.getMessage());
        }
    }

    private static String escape(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
