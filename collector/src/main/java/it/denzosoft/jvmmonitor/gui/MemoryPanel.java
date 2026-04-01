package it.denzosoft.jvmmonitor.gui;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.model.MemorySnapshot;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class MemoryPanel extends JPanel {

    private final JVMMonitorCollector collector;
    private final HeapGraph heapGraph;
    private final JLabel heapLabel;
    private final JLabel nonHeapLabel;
    private final JLabel growthLabel;

    public MemoryPanel(JVMMonitorCollector collector) {
        this.collector = collector;
        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        /* Info bar */
        JPanel infoBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 5));
        heapLabel = new JLabel("Heap: N/A");
        heapLabel.setFont(heapLabel.getFont().deriveFont(Font.BOLD, 14f));
        nonHeapLabel = new JLabel("Non-Heap: N/A");
        growthLabel = new JLabel("Growth: N/A");
        infoBar.add(heapLabel);
        infoBar.add(nonHeapLabel);
        infoBar.add(growthLabel);
        add(infoBar, BorderLayout.NORTH);

        /* Heap graph */
        heapGraph = new HeapGraph();
        add(heapGraph, BorderLayout.CENTER);
    }

    public void refresh() {
        MemorySnapshot latest = collector.getStore().getLatestMemorySnapshot();
        if (latest != null) {
            heapLabel.setText(String.format("Heap: %s / %s (%.1f%%)",
                    latest.getHeapUsedMB(), latest.getHeapMaxMB(),
                    latest.getHeapUsagePercent()));
            nonHeapLabel.setText(String.format("Non-Heap: %.1f MB",
                    latest.getNonHeapUsed() / (1024.0 * 1024.0)));
            double growth = collector.getAnalysisContext().getHeapGrowthRateMBPerHour(5);
            growthLabel.setText(String.format("Growth: %.0f MB/h", growth));
            if (growth > 100) growthLabel.setForeground(Color.RED);
            else if (growth > 20) growthLabel.setForeground(new Color(200, 100, 0));
            else growthLabel.setForeground(Color.BLACK);
        }

        long now = System.currentTimeMillis();
        List<MemorySnapshot> snapshots = collector.getStore().getMemorySnapshots(
                now - 300000, now); /* last 5 min */
        heapGraph.setData(snapshots);
    }

    /**
     * Simple line graph showing heap usage over time.
     */
    private static class HeapGraph extends JPanel {
        private List<MemorySnapshot> data = new ArrayList<MemorySnapshot>();

        public void setData(List<MemorySnapshot> data) {
            this.data = data;
            repaint();
        }

        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            int margin = 50;

            /* Background */
            g2.setColor(Color.WHITE);
            g2.fillRect(0, 0, w, h);

            /* Border */
            g2.setColor(Color.LIGHT_GRAY);
            g2.drawRect(margin, 10, w - margin - 10, h - margin - 10);

            if (data == null || data.size() < 2) {
                g2.setColor(Color.GRAY);
                g2.drawString("Waiting for data...", w / 2 - 50, h / 2);
                return;
            }

            /* Find max heap */
            long maxHeap = 0;
            for (int i = 0; i < data.size(); i++) {
                if (data.get(i).getHeapMax() > maxHeap) {
                    maxHeap = data.get(i).getHeapMax();
                }
            }
            if (maxHeap == 0) return;

            long minTs = data.get(0).getTimestamp();
            long maxTs = data.get(data.size() - 1).getTimestamp();
            long tsRange = maxTs - minTs;
            if (tsRange <= 0) tsRange = 1;

            int graphW = w - margin - 20;
            int graphH = h - margin - 20;

            /* Y-axis labels */
            g2.setColor(Color.GRAY);
            g2.setFont(g2.getFont().deriveFont(10f));
            for (int i = 0; i <= 4; i++) {
                int y = 10 + graphH - (graphH * i / 4);
                long mb = maxHeap * i / 4 / (1024 * 1024);
                g2.drawString(mb + " MB", 2, y + 4);
                g2.setColor(new Color(230, 230, 230));
                g2.drawLine(margin, y, margin + graphW, y);
                g2.setColor(Color.GRAY);
            }

            /* Max heap line (red dashed) */
            g2.setColor(new Color(255, 100, 100, 100));
            g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_BEVEL, 0, new float[]{4, 4}, 0));
            int maxY = 10;
            g2.drawLine(margin, maxY, margin + graphW, maxY);
            g2.setStroke(new BasicStroke(1f));

            /* Heap used line (blue) */
            g2.setColor(new Color(30, 100, 200));
            g2.setStroke(new BasicStroke(2f));
            int prevX = -1, prevY = -1;
            for (int i = 0; i < data.size(); i++) {
                MemorySnapshot s = data.get(i);
                int x = margin + (int) ((s.getTimestamp() - minTs) * graphW / tsRange);
                int y = 10 + graphH - (int) (s.getHeapUsed() * graphH / maxHeap);
                if (prevX >= 0) {
                    g2.drawLine(prevX, prevY, x, y);
                }
                prevX = x;
                prevY = y;
            }

            /* Fill under the line */
            if (data.size() >= 2) {
                int[] xPoints = new int[data.size() + 2];
                int[] yPoints = new int[data.size() + 2];
                for (int i = 0; i < data.size(); i++) {
                    MemorySnapshot s = data.get(i);
                    xPoints[i] = margin + (int) ((s.getTimestamp() - minTs) * graphW / tsRange);
                    yPoints[i] = 10 + graphH - (int) (s.getHeapUsed() * graphH / maxHeap);
                }
                xPoints[data.size()] = xPoints[data.size() - 1];
                yPoints[data.size()] = 10 + graphH;
                xPoints[data.size() + 1] = xPoints[0];
                yPoints[data.size() + 1] = 10 + graphH;
                g2.setColor(new Color(30, 100, 200, 40));
                g2.fillPolygon(xPoints, yPoints, data.size() + 2);
            }

            /* Labels */
            g2.setColor(Color.BLACK);
            g2.setFont(g2.getFont().deriveFont(11f));
            g2.drawString("Heap Usage (last 5 min)", margin + 5, h - 25);
        }
    }
}
