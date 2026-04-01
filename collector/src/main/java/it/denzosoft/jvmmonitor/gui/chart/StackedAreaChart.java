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
 * Stacked area chart for showing category distributions over time
 * (e.g., thread states: RUNNABLE, BLOCKED, WAITING stacked).
 */
public class StackedAreaChart extends JPanel {

    private final String title;
    private final List<String> categoryNames = new ArrayList<String>();
    private final List<Color> categoryColors = new ArrayList<Color>();

    /** Each snapshot is: long timestamp, then one int per category (count). */
    private final List<long[]> snapshots = new ArrayList<long[]>();
    private static final int MAX_SNAPSHOTS = 300;

    private final SimpleDateFormat tooltipFmt = new SimpleDateFormat("HH:mm:ss");
    private int mouseX = -1;

    public StackedAreaChart(String title) {
        this.title = title;
        setBackground(Color.WHITE);
        ToolTipManager.sharedInstance().registerComponent(this);
        addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseMoved(MouseEvent e) {
                mouseX = e.getX();
                updateTooltip(mouseX);
                repaint();
            }
        });
    }

    public String getToolTipText(MouseEvent event) {
        return super.getToolTipText();
    }

    private void updateTooltip(int mx) {
        int w = getWidth();
        int marginL = 50, marginR = 10;
        int graphW = w - marginL - marginR;
        if (mx < marginL || mx > marginL + graphW || snapshots.size() < 2 || graphW <= 0) {
            setToolTipText(null);
            return;
        }
        double ratio = (double)(mx - marginL) / graphW;
        int idx = (int)(ratio * (snapshots.size() - 1));
        if (idx < 0) idx = 0;
        if (idx >= snapshots.size()) idx = snapshots.size() - 1;
        long[] snap = snapshots.get(idx);
        StringBuilder sb = new StringBuilder("<html><b>");
        sb.append(tooltipFmt.format(new Date(snap[0]))).append("</b><br>");
        int nCat = categoryNames.size();
        int total = 0;
        for (int c = 0; c < nCat && c + 1 < snap.length; c++) {
            total += (int) snap[c + 1];
        }
        for (int c = 0; c < nCat && c + 1 < snap.length; c++) {
            Color col = categoryColors.get(c);
            String hex = String.format("%02x%02x%02x", col.getRed(), col.getGreen(), col.getBlue());
            sb.append("<font color='#").append(hex).append("'>\u25CF</font> ");
            sb.append(categoryNames.get(c)).append(": <b>").append(snap[c + 1]).append("</b><br>");
        }
        sb.append("Total: <b>").append(total).append("</b>");
        sb.append("</html>");
        setToolTipText(sb.toString());
    }

    private static String colorHex(Color c) {
        return String.format("%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    public void addCategory(String name, Color color) {
        categoryNames.add(name);
        categoryColors.add(color);
    }

    /** Add a snapshot: values[i] = count for category i. */
    public void addSnapshot(long timestamp, int[] values) {
        long[] snap = new long[1 + values.length];
        snap[0] = timestamp;
        for (int i = 0; i < values.length; i++) {
            snap[i + 1] = values[i];
        }
        snapshots.add(snap);
        if (snapshots.size() > MAX_SNAPSHOTS) {
            snapshots.remove(0);
        }
        repaint();
    }

    public void clearData() {
        snapshots.clear();
        repaint();
    }

    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth(), h = getHeight();
        int marginL = 50, marginR = 10, marginT = 15, marginB = 38;
        int graphW = w - marginL - marginR;
        int graphH = h - marginT - marginB;

        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, w, h);

        if (snapshots.size() < 2 || categoryNames.isEmpty()) {
            g2.setColor(Color.GRAY);
            g2.drawString("Waiting for data...", w / 2 - 45, h / 2);
            return;
        }

        int nCat = categoryNames.size();
        int nPts = snapshots.size();

        /* Find time range and max total */
        long minTs = snapshots.get(0)[0];
        long maxTs = snapshots.get(nPts - 1)[0];
        long tsRange = maxTs - minTs;
        if (tsRange <= 0) tsRange = 1;

        int maxTotal = 1;
        for (int i = 0; i < nPts; i++) {
            int total = 0;
            for (int c = 0; c < nCat; c++) {
                total += (int) snapshots.get(i)[c + 1];
            }
            if (total > maxTotal) maxTotal = total;
        }

        /* Y-axis grid + labels (right-aligned) */
        g2.setFont(g2.getFont().deriveFont(10f));
        FontMetrics fm = g2.getFontMetrics();
        for (int i = 0; i <= 4; i++) {
            int y = marginT + graphH - (graphH * i / 4);
            int val = maxTotal * i / 4;
            g2.setColor(Color.GRAY);
            String label = String.valueOf(val);
            int labelW = fm.stringWidth(label);
            g2.drawString(label, marginL - labelW - 4, y + 4);
            g2.setColor(new Color(230, 230, 230));
            g2.drawLine(marginL, y, marginL + graphW, y);
        }

        /* X-axis time labels */
        g2.setColor(Color.GRAY);
        int numXLabels = Math.max(2, Math.min(6, graphW / 80));
        for (int i = 0; i <= numXLabels; i++) {
            long ts = minTs + (tsRange * i / numXLabels);
            int x = marginL + (graphW * i / numXLabels);
            String timeLabel = tooltipFmt.format(new Date(ts));
            int tlw = fm.stringWidth(timeLabel);
            g2.drawString(timeLabel, x - tlw / 2, marginT + graphH + 14);
            g2.setColor(new Color(200, 200, 200));
            g2.drawLine(x, marginT + graphH, x, marginT + graphH + 3);
            g2.setColor(Color.GRAY);
        }

        g2.setColor(Color.LIGHT_GRAY);
        g2.drawRect(marginL, marginT, graphW, graphH);

        /* Draw stacked areas bottom-up: last category at bottom, first at top */
        int[][] cumY = new int[nPts][nCat + 1]; /* cumulative Y for each point */
        int[] xPts = new int[nPts];

        for (int i = 0; i < nPts; i++) {
            xPts[i] = marginL + (int) ((snapshots.get(i)[0] - minTs) * graphW / tsRange);
            cumY[i][0] = marginT + graphH; /* baseline */
            for (int c = 0; c < nCat; c++) {
                int val = (int) snapshots.get(i)[c + 1];
                int pixelH = (int) ((double) val / maxTotal * graphH);
                cumY[i][c + 1] = cumY[i][c] - pixelH;
            }
        }

        /* Fill each category band */
        for (int c = 0; c < nCat; c++) {
            int[] fx = new int[nPts * 2 + 2];
            int[] fy = new int[nPts * 2 + 2];
            /* Top edge (left to right) */
            for (int i = 0; i < nPts; i++) {
                fx[i] = xPts[i];
                fy[i] = cumY[i][c + 1];
            }
            /* Bottom edge (right to left) */
            for (int i = 0; i < nPts; i++) {
                fx[nPts + i] = xPts[nPts - 1 - i];
                fy[nPts + i] = cumY[nPts - 1 - i][c];
            }
            Color col = categoryColors.get(c);
            g2.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 160));
            g2.fillPolygon(fx, fy, nPts * 2);

            /* Outline */
            g2.setColor(col.darker());
            g2.setStroke(new BasicStroke(1f));
            for (int i = 1; i < nPts; i++) {
                g2.drawLine(xPts[i - 1], cumY[i - 1][c + 1], xPts[i], cumY[i][c + 1]);
            }
        }

        /* Title */
        g2.setColor(Color.BLACK);
        g2.setFont(g2.getFont().deriveFont(11f));
        g2.drawString(title, marginL + 5, h - 5);

        /* Legend */
        g2.setFont(g2.getFont().deriveFont(10f));
        int lx = marginL + graphW - 10;
        for (int c = nCat - 1; c >= 0; c--) {
            String lbl = categoryNames.get(c);
            int tw = g2.getFontMetrics().stringWidth(lbl);
            lx -= tw + 20;
            g2.setColor(categoryColors.get(c));
            g2.fillRect(lx, marginT + 5, 12, 8);
            g2.setColor(Color.DARK_GRAY);
            g2.drawString(lbl, lx + 14, marginT + 13);
        }

        /* ── Cursor line at mouse position ──────── */
        if (mouseX >= marginL && mouseX <= marginL + graphW) {
            g2.setColor(new Color(100, 100, 100, 120));
            g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_BEVEL, 0, new float[]{3, 3}, 0));
            g2.drawLine(mouseX, marginT, mouseX, marginT + graphH);
            g2.setStroke(new BasicStroke(1f));

            /* Dots at category boundaries */
            double ratio = (double)(mouseX - marginL) / graphW;
            int idx = (int)(ratio * (nPts - 1));
            if (idx >= 0 && idx < nPts) {
                for (int c = 0; c < nCat; c++) {
                    int cy = cumY[idx][c + 1];
                    g2.setColor(categoryColors.get(c));
                    g2.fillOval(mouseX - 4, cy - 4, 8, 8);
                    g2.setColor(Color.WHITE);
                    g2.drawOval(mouseX - 4, cy - 4, 8, 8);
                }
            }
        }
    }
}
