package it.denzosoft.jvmmonitor.gui.chart;

import it.denzosoft.jvmmonitor.model.CpuSample;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.*;
import java.util.List;

/**
 * Flame graph visualization for CPU profiling data.
 * Each horizontal bar represents a method; width = proportion of samples.
 * Stacked bottom-up: bottom = root caller, top = leaf method.
 */
public class FlameGraph extends JPanel {

    private FlameNode root;
    private int totalSamples = 0;
    private String tooltipText = null;
    private final List<RenderedRect> rects = new ArrayList<RenderedRect>();

    public FlameGraph() {
        setBackground(Color.WHITE);
        ToolTipManager.sharedInstance().registerComponent(this);
        addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseMoved(MouseEvent e) {
                updateTooltip(e.getX(), e.getY());
            }
        });
    }

    public String getToolTipText(MouseEvent event) {
        return tooltipText;
    }

    public void buildFromSamples(List<CpuSample> samples) {
        root = new FlameNode("all", 0);
        totalSamples = samples.size();

        for (int s = 0; s < samples.size(); s++) {
            CpuSample sample = samples.get(s);
            CpuSample.StackFrame[] frames = sample.getFrames();
            if (frames == null || frames.length == 0) continue;

            /* Build path from bottom (deepest caller) to top (leaf) */
            FlameNode current = root;
            for (int f = frames.length - 1; f >= 0; f--) {
                String name = frames[f].getDisplayName();
                FlameNode child = current.children.get(name);
                if (child == null) {
                    child = new FlameNode(name, 0);
                    current.children.put(name, child);
                }
                child.count++;
                current = child;
            }
        }
        root.count = totalSamples;
        repaint();
    }

    public void clear() {
        root = null;
        totalSamples = 0;
        rects.clear();
        repaint();
    }

    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth(), h = getHeight();
        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, w, h);
        rects.clear();

        if (root == null || totalSamples == 0) {
            g2.setColor(Color.GRAY);
            g2.drawString("No data. Start CPU Profiler recording first.", w / 2 - 120, h / 2);
            return;
        }

        int barH = 18;
        int maxDepth = getMaxDepth(root, 0);
        int totalH = (maxDepth + 1) * barH;

        /* Draw bottom-up: root at bottom */
        int baseY = Math.min(h - 5, totalH);
        g2.setFont(g2.getFont().deriveFont(11f));
        drawNode(g2, root, 0, w, baseY, barH, 0);
    }

    private void drawNode(Graphics2D g2, FlameNode node, int x, int width, int baseY, int barH, int depth) {
        if (width < 1) return;
        int y = baseY - (depth + 1) * barH;

        /* Color based on method type */
        Color col = getColor(node.name, depth);
        g2.setColor(col);
        g2.fillRect(x, y, width, barH - 1);
        g2.setColor(col.darker());
        g2.drawRect(x, y, width, barH - 1);

        /* Label */
        if (width > 30) {
            g2.setColor(Color.BLACK);
            String label = node.name;
            FontMetrics fm = g2.getFontMetrics();
            if (fm.stringWidth(label) > width - 4) {
                while (label.length() > 3 && fm.stringWidth(label + "...") > width - 4) {
                    label = label.substring(0, label.length() - 1);
                }
                label = label + "...";
            }
            g2.drawString(label, x + 2, y + barH - 5);
        }

        /* Store for tooltip hit testing */
        rects.add(new RenderedRect(x, y, width, barH, node));

        /* Children: proportional width */
        int childX = x;
        for (FlameNode child : node.children.values()) {
            int childW = (int)((double) child.count / node.count * width);
            if (childW < 1) childW = 1;
            drawNode(g2, child, childX, childW, baseY, barH, depth + 1);
            childX += childW;
        }
    }

    private void updateTooltip(int mx, int my) {
        for (int i = rects.size() - 1; i >= 0; i--) {
            RenderedRect r = rects.get(i);
            if (mx >= r.x && mx <= r.x + r.w && my >= r.y && my <= r.y + r.h) {
                double pct = totalSamples > 0 ? r.node.count * 100.0 / totalSamples : 0;
                tooltipText = String.format("<html><b>%s</b><br>%d samples (%.1f%%)</html>",
                        r.node.name, r.node.count, pct);
                return;
            }
        }
        tooltipText = null;
    }

    private static Color getColor(String name, int depth) {
        /* Color scheme: warm colors for application, cool for framework/JDK */
        if (name.startsWith("com.myapp") || name.startsWith("it.")) {
            return new Color(230, 100 + (depth * 15) % 80, 50);
        } else if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")) {
            return new Color(80, 150 + (depth * 10) % 60, 220);
        } else if (name.startsWith("org.spring") || name.startsWith("org.hibernate")) {
            return new Color(100, 200, 100 + (depth * 10) % 50);
        }
        float hue = (name.hashCode() & 0x7FFFFFFF) % 360 / 360.0f;
        return Color.getHSBColor(hue, 0.4f, 0.9f);
    }

    private static int getMaxDepth(FlameNode node, int depth) {
        int max = depth;
        for (FlameNode child : node.children.values()) {
            int d = getMaxDepth(child, depth + 1);
            if (d > max) max = d;
        }
        return max;
    }

    /* ── Node ────────────────────────────────────── */

    private static class FlameNode {
        final String name;
        int count;
        final Map<String, FlameNode> children = new LinkedHashMap<String, FlameNode>();

        FlameNode(String name, int count) {
            this.name = name;
            this.count = count;
        }
    }

    private static class RenderedRect {
        final int x, y, w, h;
        final FlameNode node;
        RenderedRect(int x, int y, int w, int h, FlameNode node) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.node = node;
        }
    }
}
