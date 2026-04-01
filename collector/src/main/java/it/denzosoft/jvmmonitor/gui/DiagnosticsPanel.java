package it.denzosoft.jvmmonitor.gui;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.model.Diagnosis;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;

public class DiagnosticsPanel extends JPanel {

    private final JVMMonitorCollector collector;
    private final JTextArea diagTextArea;
    private final JLabel countLabel;

    public DiagnosticsPanel(JVMMonitorCollector collector) {
        this.collector = collector;
        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        /* Top bar */
        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        countLabel = new JLabel("No issues detected");
        countLabel.setFont(countLabel.getFont().deriveFont(Font.BOLD, 14f));
        topBar.add(countLabel);

        JButton runBtn = new JButton("Run Diagnostics Now");
        runBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                runDiagnostics();
            }
        });
        topBar.add(runBtn);
        add(topBar, BorderLayout.NORTH);

        /* Diagnosis output */
        diagTextArea = new JTextArea();
        diagTextArea.setEditable(false);
        diagTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        diagTextArea.setMargin(new Insets(10, 10, 10, 10));
        add(new JScrollPane(diagTextArea), BorderLayout.CENTER);
    }

    public void refresh() {
        /* Auto-run diagnostics */
        List<Diagnosis> results = collector.getDiagnosisEngine().getLastDiagnoses();
        updateDisplay(results);
    }

    private void runDiagnostics() {
        List<Diagnosis> results = collector.getDiagnosisEngine().runDiagnostics();
        updateDisplay(results);
    }

    private void updateDisplay(List<Diagnosis> results) {
        if (results == null || results.isEmpty()) {
            countLabel.setText("No issues detected");
            countLabel.setForeground(new Color(0, 128, 0));
            diagTextArea.setText("System appears healthy. No issues detected.\n\n" +
                    "Diagnostics run automatically every 10 seconds.\n" +
                    "Click 'Run Diagnostics Now' to force a check.");
            return;
        }

        int critical = 0, warning = 0;
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).getSeverity() >= 2) critical++;
            else if (results.get(i).getSeverity() >= 1) warning++;
        }

        countLabel.setText(results.size() + " issue(s) found" +
                (critical > 0 ? " (" + critical + " CRITICAL)" : "") +
                (warning > 0 ? " (" + warning + " WARNING)" : ""));
        countLabel.setForeground(critical > 0 ? Color.RED :
                warning > 0 ? new Color(200, 100, 0) : Color.BLACK);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            if (i > 0) sb.append("\n");
            sb.append(results.get(i).toString());
        }
        diagTextArea.setText(sb.toString());
        diagTextArea.setCaretPosition(0);
    }
}
