package it.denzosoft.jvmmonitor.gui.chart;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic time-series line/area chart with support for multiple series.
 * Each series has its own color and can optionally fill the area under the line.
 * Supports tooltip on mouse hover showing value at cursor position.
 */
public class TimeSeriesChart extends JPanel {

    private final String title;
    private final String yAxisLabel;
    private final Map<String, SeriesData> seriesMap = new LinkedHashMap<String, SeriesData>();
    private double fixedMaxY = -1;
    private boolean showLegend = true;

    /* Tooltip state */
    private int mouseX = -1, mouseY = -1;
    private String tooltipText = null;
    private final SimpleDateFormat tooltipDateFmt = new SimpleDateFormat("HH:mm:ss");

    public TimeSeriesChart(String title, String yAxisLabel) {
        this.title = title;
        this.yAxisLabel = yAxisLabel;
        setBackground(Color.WHITE);
        ToolTipManager.sharedInstance().registerComponent(this);

        addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseMoved(MouseEvent e) {
                mouseX = e.getX();
                mouseY = e.getY();
                updateTooltip();
                repaint(); /* redraw cursor line + dots */
            }
        });
    }

    public String getToolTipText(MouseEvent event) {
        return tooltipText;
    }

    private void updateTooltip() {
        int w = getWidth(), h = getHeight();
        int marginL = 55, marginR = 10, marginT = 10, marginB = 25;
        int graphW = w - marginL - marginR;
        int graphH = h - marginT - marginB;

        if (mouseX < marginL || mouseX > marginL + graphW || graphW <= 0) {
            tooltipText = null;
            return;
        }

        /* Find global time range */
        long minTs = Long.MAX_VALUE, maxTs = Long.MIN_VALUE;
        double maxY = 0;
        for (SeriesData sd : seriesMap.values()) {
            for (int i = 0; i < sd.points.size(); i++) {
                long[] p = sd.points.get(i);
                if (p[0] < minTs) minTs = p[0];
                if (p[0] > maxTs) maxTs = p[0];
                double v = Double.longBitsToDouble(p[1]);
                if (v > maxY) maxY = v;
            }
        }
        if (maxTs <= minTs) { tooltipText = null; return; }
        if (fixedMaxY > 0) maxY = fixedMaxY;

        /* Convert mouseX to timestamp */
        double ratio = (double)(mouseX - marginL) / graphW;
        long ts = minTs + (long)(ratio * (maxTs - minTs));

        StringBuilder sb = new StringBuilder("<html>");
        sb.append("<b>").append(tooltipDateFmt.format(new Date(ts))).append("</b><br>");

        /* Find nearest value in each series — with colored circle */
        for (SeriesData sd : seriesMap.values()) {
            double nearest = findNearestValue(sd.points, ts);
            if (!Double.isNaN(nearest)) {
                String hex = colorHex(sd.color);
                sb.append("<font color='#").append(hex).append("'>\u25CF</font> ");
                sb.append(sd.name).append(": <b>");
                if (nearest >= 1000) sb.append(String.format("%.0f", nearest));
                else if (nearest >= 10) sb.append(String.format("%.0f", nearest));
                else sb.append(String.format("%.1f", nearest));
                sb.append("</b><br>");
            }
        }
        sb.append("</html>");
        tooltipText = sb.toString();
    }

    private static double findNearestValue(List<long[]> points, long targetTs) {
        if (points.isEmpty()) return Double.NaN;
        int best = 0;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            long dist = Math.abs(points.get(i)[0] - targetTs);
            if (dist < bestDist) { bestDist = dist; best = i; }
        }
        return Double.longBitsToDouble(points.get(best)[1]);
    }

    private static String colorHex(Color c) {
        return String.format("%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    public void setFixedMaxY(double max) {
        this.fixedMaxY = max;
    }

    public void setShowLegend(boolean show) {
        this.showLegend = show;
    }

    public void defineSeries(String name, Color color, boolean fill) {
        seriesMap.put(name, new SeriesData(name, color, fill));
    }

    public void clearAllData() {
        for (SeriesData sd : seriesMap.values()) {
            sd.points.clear();
        }
        repaint();
    }

    public void setSeriesData(String name, List<long[]> timestampValuePairs) {
        SeriesData sd = seriesMap.get(name);
        if (sd == null) return;
        sd.points.clear();
        sd.points.addAll(timestampValuePairs);
        repaint();
    }

    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();
        int marginL = 60, marginR = 10, marginT = 18, marginB = 38;

        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, w, h);

        int graphW = w - marginL - marginR;
        int graphH = h - marginT - marginB;
        if (graphW < 10 || graphH < 10) return;

        /* Find global time range and max Y */
        long minTs = Long.MAX_VALUE, maxTs = Long.MIN_VALUE;
        double maxY = 0;
        boolean hasData = false;

        for (SeriesData sd : seriesMap.values()) {
            for (int i = 0; i < sd.points.size(); i++) {
                long[] p = sd.points.get(i);
                if (p[0] < minTs) minTs = p[0];
                if (p[0] > maxTs) maxTs = p[0];
                double v = Double.longBitsToDouble(p[1]);
                if (v > maxY) maxY = v;
                hasData = true;
            }
        }

        if (!hasData || maxTs <= minTs) {
            g2.setColor(Color.GRAY);
            g2.setFont(g2.getFont().deriveFont(11f));
            g2.drawString("Waiting for data...", w / 2 - 45, h / 2);
            return;
        }

        if (fixedMaxY > 0) maxY = fixedMaxY;
        if (maxY <= 0) maxY = 1;
        long tsRange = maxTs - minTs;

        /* Y-axis label (above the axis, no overlap with values) */
        if (yAxisLabel != null) {
            g2.setColor(Color.GRAY);
            g2.setFont(g2.getFont().deriveFont(9f));
            g2.drawString(yAxisLabel, marginL, marginT - 5);
        }

        /* Y-axis grid lines + value labels (right-aligned within left margin) */
        g2.setFont(g2.getFont().deriveFont(10f));
        FontMetrics fm = g2.getFontMetrics();
        for (int i = 0; i <= 4; i++) {
            int y = marginT + graphH - (graphH * i / 4);
            double val = maxY * i / 4;
            g2.setColor(Color.GRAY);
            String label;
            if (maxY >= 1000) label = String.format("%.0f", val);
            else if (maxY >= 10) label = String.format("%.0f", val);
            else label = String.format("%.1f", val);
            int labelW = fm.stringWidth(label);
            g2.drawString(label, marginL - labelW - 4, y + 4);
            g2.setColor(new Color(230, 230, 230));
            g2.drawLine(marginL, y, marginL + graphW, y);
        }

        /* X-axis time labels */
        g2.setFont(g2.getFont().deriveFont(10f));
        g2.setColor(Color.GRAY);
        int numXLabels = Math.max(2, Math.min(6, graphW / 80));
        for (int i = 0; i <= numXLabels; i++) {
            long ts = minTs + (tsRange * i / numXLabels);
            int x = marginL + (graphW * i / numXLabels);
            String timeLabel = tooltipDateFmt.format(new Date(ts));
            int tlw = fm.stringWidth(timeLabel);
            g2.drawString(timeLabel, x - tlw / 2, marginT + graphH + 14);
            /* Tick mark */
            g2.setColor(new Color(200, 200, 200));
            g2.drawLine(x, marginT + graphH, x, marginT + graphH + 3);
            g2.setColor(Color.GRAY);
        }

        /* Border */
        g2.setColor(Color.LIGHT_GRAY);
        g2.drawRect(marginL, marginT, graphW, graphH);

        /* Draw each series */
        for (SeriesData sd : seriesMap.values()) {
            if (sd.points.isEmpty()) continue;
            drawSeries(g2, sd, marginL, marginT, graphW, graphH, minTs, tsRange, maxY);
        }

        /* Title (below chart, left of time labels) */
        if (title != null) {
            g2.setColor(Color.BLACK);
            g2.setFont(g2.getFont().deriveFont(11f));
            g2.drawString(title, marginL + 5, h - 5);
        }

        /* Legend */
        if (showLegend && seriesMap.size() > 1) {
            g2.setFont(g2.getFont().deriveFont(10f));
            int lx = marginL + graphW - 10;
            int ly = marginT + 15;
            for (SeriesData sd : seriesMap.values()) {
                String lbl = sd.name;
                int tw = g2.getFontMetrics().stringWidth(lbl);
                lx -= tw + 20;
                g2.setColor(sd.color);
                g2.fillRect(lx, ly - 8, 12, 8);
                g2.setColor(Color.DARK_GRAY);
                g2.drawString(lbl, lx + 14, ly);
            }
        }

        /* ── Cursor line + dots at mouse position ──── */
        if (mouseX >= marginL && mouseX <= marginL + graphW) {
            /* Vertical cursor line */
            g2.setColor(new Color(100, 100, 100, 120));
            g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_BEVEL, 0, new float[]{3, 3}, 0));
            g2.drawLine(mouseX, marginT, mouseX, marginT + graphH);
            g2.setStroke(new BasicStroke(1f));

            /* Find nearest timestamp at cursor X */
            double ratio = (double)(mouseX - marginL) / graphW;
            long cursorTs = minTs + (long)(ratio * tsRange);

            /* Draw dot on each series at nearest point */
            for (SeriesData sd : seriesMap.values()) {
                if (sd.points.isEmpty()) continue;
                int bestIdx = 0;
                long bestDist = Long.MAX_VALUE;
                for (int i = 0; i < sd.points.size(); i++) {
                    long dist = Math.abs(sd.points.get(i)[0] - cursorTs);
                    if (dist < bestDist) { bestDist = dist; bestIdx = i; }
                }
                long[] pt = sd.points.get(bestIdx);
                double v = Double.longBitsToDouble(pt[1]);
                int px = marginL + (int)((pt[0] - minTs) * graphW / tsRange);
                int py = marginT + graphH - (int)(v / maxY * graphH);

                /* Filled circle with border */
                g2.setColor(sd.color);
                g2.fillOval(px - 5, py - 5, 10, 10);
                g2.setColor(Color.WHITE);
                g2.drawOval(px - 5, py - 5, 10, 10);
            }
        }
    }

    private void drawSeries(Graphics2D g2, SeriesData sd,
                             int marginL, int marginT, int graphW, int graphH,
                             long minTs, long tsRange, double maxY) {
        List<long[]> pts = sd.points;

        /* Line */
        g2.setColor(sd.color);
        g2.setStroke(new BasicStroke(2f));

        int[] xArr = new int[pts.size()];
        int[] yArr = new int[pts.size()];

        for (int i = 0; i < pts.size(); i++) {
            long[] p = pts.get(i);
            double v = Double.longBitsToDouble(p[1]);
            xArr[i] = marginL + (int) ((p[0] - minTs) * graphW / tsRange);
            yArr[i] = marginT + graphH - (int) (v / maxY * graphH);
        }

        for (int i = 1; i < pts.size(); i++) {
            g2.drawLine(xArr[i - 1], yArr[i - 1], xArr[i], yArr[i]);
        }

        /* Fill with gradient (top = color with alpha, bottom = transparent) */
        if (sd.fill && pts.size() >= 2) {
            int[] fx = new int[pts.size() + 2];
            int[] fy = new int[pts.size() + 2];
            System.arraycopy(xArr, 0, fx, 0, pts.size());
            System.arraycopy(yArr, 0, fy, 0, pts.size());
            fx[pts.size()] = xArr[pts.size() - 1];
            fy[pts.size()] = marginT + graphH;
            fx[pts.size() + 1] = xArr[0];
            fy[pts.size() + 1] = marginT + graphH;

            /* Gradient: series color (80 alpha) at top -> transparent at bottom */
            GradientPaint gp = new GradientPaint(
                    0, marginT, new Color(sd.color.getRed(), sd.color.getGreen(), sd.color.getBlue(), 80),
                    0, marginT + graphH, new Color(sd.color.getRed(), sd.color.getGreen(), sd.color.getBlue(), 10));
            Paint oldPaint = g2.getPaint();
            g2.setPaint(gp);
            g2.fillPolygon(fx, fy, pts.size() + 2);
            g2.setPaint(oldPaint);
        }

        g2.setStroke(new BasicStroke(1f));
    }

    /* Helper to build data points */
    public static long[] point(long timestamp, double value) {
        return new long[]{timestamp, Double.doubleToLongBits(value)};
    }

    private static class SeriesData {
        final String name;
        final Color color;
        final boolean fill;
        final List<long[]> points = new ArrayList<long[]>();

        SeriesData(String name, Color color, boolean fill) {
            this.name = name;
            this.color = color;
            this.fill = fill;
        }
    }
}
