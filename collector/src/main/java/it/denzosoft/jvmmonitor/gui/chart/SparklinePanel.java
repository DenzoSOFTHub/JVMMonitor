package it.denzosoft.jvmmonitor.gui.chart;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Tiny inline sparkline chart for dashboard widgets.
 * Shows a simple polyline in a small area.
 */
public class SparklinePanel extends JPanel {

    private final List<Double> values = new ArrayList<Double>();
    private Color lineColor = new Color(30, 100, 200);
    private Color fillColor = new Color(30, 100, 200, 40);
    private int maxPoints = 60;

    public SparklinePanel() {
        setPreferredSize(new Dimension(200, 35));
        setOpaque(false);
    }

    public void setColors(Color line, Color fill) {
        this.lineColor = line;
        this.fillColor = fill;
    }

    public void setMaxPoints(int max) {
        this.maxPoints = max;
    }

    public void addValue(double v) {
        values.add(v);
        if (values.size() > maxPoints) {
            values.remove(0);
        }
        repaint();
    }

    public void clearValues() {
        values.clear();
        repaint();
    }

    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (values.size() < 2) return;

        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth(), h = getHeight();
        int pad = 2;
        int gw = w - pad * 2, gh = h - pad * 2;

        double maxV = 0.001;
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) > maxV) maxV = values.get(i);
        }

        int n = values.size();
        int[] xp = new int[n];
        int[] yp = new int[n];

        for (int i = 0; i < n; i++) {
            xp[i] = pad + (int) ((double) i / (n - 1) * gw);
            yp[i] = pad + gh - (int) (values.get(i) / maxV * gh);
        }

        /* Fill */
        int[] fx = new int[n + 2];
        int[] fy = new int[n + 2];
        System.arraycopy(xp, 0, fx, 0, n);
        System.arraycopy(yp, 0, fy, 0, n);
        fx[n] = xp[n - 1]; fy[n] = pad + gh;
        fx[n + 1] = xp[0]; fy[n + 1] = pad + gh;
        g2.setColor(fillColor);
        g2.fillPolygon(fx, fy, n + 2);

        /* Line */
        g2.setColor(lineColor);
        g2.setStroke(new BasicStroke(1.5f));
        for (int i = 1; i < n; i++) {
            g2.drawLine(xp[i - 1], yp[i - 1], xp[i], yp[i]);
        }
    }
}
