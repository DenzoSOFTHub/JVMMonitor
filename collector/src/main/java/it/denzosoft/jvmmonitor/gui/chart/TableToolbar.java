package it.denzosoft.jvmmonitor.gui.chart;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.FileWriter;

/**
 * Reusable toolbar for JTable: search/filter bar + CSV export button.
 * Wraps a JTable in a panel with the toolbar on top.
 */
public class TableToolbar {

    /**
     * Wrap a JTable with a search bar and export button.
     * Returns a JPanel containing the toolbar + scrolled table.
     * The table MUST have a RowSorter (call setAutoCreateRowSorter(true) before).
     */
    public static JPanel wrap(final JTable table) {
        return wrap(table, null);
    }

    public static JPanel wrap(final JTable table, String borderTitle) {
        JPanel panel = new JPanel(new BorderLayout(0, 3));
        if (borderTitle != null) {
            panel.setBorder(BorderFactory.createTitledBorder(borderTitle));
        }

        /* Toolbar */
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        toolbar.add(new JLabel("Filter:"));
        final JTextField searchField = new JTextField(20);
        toolbar.add(searchField);

        JButton clearBtn = new JButton("X");
        clearBtn.setMargin(new Insets(0, 4, 0, 4));
        clearBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                searchField.setText("");
            }
        });
        toolbar.add(clearBtn);

        JButton exportBtn = new JButton("Export CSV");
        exportBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                exportCsv(table);
            }
        });
        toolbar.add(exportBtn);

        /* Row count label */
        final JLabel countLabel = new JLabel("");
        toolbar.add(Box.createHorizontalStrut(10));
        toolbar.add(countLabel);

        panel.add(toolbar, BorderLayout.NORTH);
        panel.add(new JScrollPane(table), BorderLayout.CENTER);

        /* Search filter */
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { applyFilter(); }
            public void removeUpdate(DocumentEvent e) { applyFilter(); }
            public void changedUpdate(DocumentEvent e) { applyFilter(); }

            private void applyFilter() {
                String text = searchField.getText().trim();
                if (table.getRowSorter() instanceof TableRowSorter) {
                    TableRowSorter sorter = (TableRowSorter) table.getRowSorter();
                    if (text.isEmpty()) {
                        sorter.setRowFilter(null);
                    } else {
                        try {
                            sorter.setRowFilter(RowFilter.regexFilter("(?i)" + text));
                        } catch (java.util.regex.PatternSyntaxException ex) {
                            /* Invalid regex — ignore */
                        }
                    }
                }
                countLabel.setText(table.getRowCount() + " rows");
            }
        });

        /* Initial count */
        countLabel.setText(table.getRowCount() + " rows");

        return panel;
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
                fw.write(escapeCsv(model.getColumnName(c)));
            }
            fw.write('\n');

            /* Rows (using view order for filtering) */
            for (int r = 0; r < table.getRowCount(); r++) {
                int modelRow = table.convertRowIndexToModel(r);
                for (int c = 0; c < model.getColumnCount(); c++) {
                    if (c > 0) fw.write(',');
                    Object val = model.getValueAt(modelRow, c);
                    fw.write(escapeCsv(val != null ? val.toString() : ""));
                }
                fw.write('\n');
            }
            fw.close();
            JOptionPane.showMessageDialog(table, "Exported " + table.getRowCount() + " rows to " +
                    fc.getSelectedFile().getName());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(table, "Export failed: " + ex.getMessage());
        }
    }

    private static String escapeCsv(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
