package it.denzosoft.jvmmonitor.gui.chart;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Horizontal bar chart for showing ranked items (e.g., top exception classes).
 */
public class BarChart extends JPanel {

    private final String title;
    private final List<BarEntry> entries = new ArrayList<BarEntry>();
    private Color barColor = new Color(60, 130, 200);

    public BarChart(String title) {
        this.title = title;
        setBackground(Color.WHITE);
    }

    public void setBarColor(Color c) {
        this.barColor = c;
    }

    public void setData(List<String> labels, List<Integer> values) {
        entries.clear();
        for (int i = 0; i < labels.size(); i++) {
            entries.add(new BarEntry(labels.get(i), values.get(i)));
        }
        repaint();
    }

    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth(), h = getHeight();
        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, w, h);

        if (entries.isEmpty()) {
            g2.setColor(Color.GRAY);
            g2.drawString("No data", w / 2 - 20, h / 2);
            return;
        }

        int marginL = 10, marginR = 10, marginT = 20, marginB = 20;
        int graphW = w - marginL - marginR;
        int graphH = h - marginT - marginB;

        /* Title */
        g2.setColor(Color.DARK_GRAY);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 11f));
        g2.drawString(title, marginL, marginT - 5);

        /* Find max value */
        int maxVal = 1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).value > maxVal) maxVal = entries.get(i).value;
        }

        int maxBars = Math.min(entries.size(), 10);
        int barH = Math.min(graphH / maxBars - 4, 22);
        int labelW = Math.min(graphW / 3, 180);
        int barAreaW = graphW - labelW - 40;

        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 11f));
        FontMetrics fm = g2.getFontMetrics();

        for (int i = 0; i < maxBars; i++) {
            BarEntry e = entries.get(i);
            int y = marginT + i * (barH + 4);
            int bw = (int) ((double) e.value / maxVal * barAreaW);
            if (bw < 1) bw = 1;

            /* Label (right-aligned) */
            String label = e.label;
            if (fm.stringWidth(label) > labelW) {
                while (label.length() > 3 && fm.stringWidth(label + "...") > labelW) {
                    label = label.substring(0, label.length() - 1);
                }
                label = label + "...";
            }
            g2.setColor(Color.DARK_GRAY);
            g2.drawString(label, marginL, y + barH - 3);

            /* Bar */
            int bx = marginL + labelW + 5;
            float hue = (float) i / maxBars * 0.6f;
            g2.setColor(new Color(Color.HSBtoRGB(hue, 0.5f, 0.85f)));
            g2.fillRoundRect(bx, y, bw, barH, 4, 4);

            /* Value text */
            g2.setColor(Color.DARK_GRAY);
            g2.drawString(String.valueOf(e.value), bx + bw + 4, y + barH - 3);
        }
    }

    private static class BarEntry {
        final String label;
        final int value;
        BarEntry(String label, int value) {
            this.label = label;
            this.value = value;
        }
    }
}
