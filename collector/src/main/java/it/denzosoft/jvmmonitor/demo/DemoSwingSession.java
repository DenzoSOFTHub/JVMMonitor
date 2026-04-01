package it.denzosoft.jvmmonitor.demo;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.gui.MainFrame;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Launches a DemoAgent + Swing GUI session.
 * Starts the demo agent in-process, then opens the GUI and
 * auto-connects to it. Optionally takes periodic screenshots.
 *
 * Usage:
 *   java -cp jvmmonitor.jar it.denzosoft.jvmmonitor.demo.DemoSwingSession [screenshotDir]
 */
public class DemoSwingSession {

    private static final int PORT = 19999;

    public static void main(String[] args) throws Exception {
        final String screenshotDir = args.length > 0 ? args[0] : null;

        /* 1. Start demo agent */
        final DemoAgent agent = new DemoAgent(PORT);
        Thread agentThread = new Thread(new Runnable() {
            public void run() {
                try { agent.run(); } catch (IOException e) { /* stopped */ }
            }
        }, "demo-agent");
        agentThread.setDaemon(true);
        agentThread.start();
        Thread.sleep(1000);

        /* 2. Launch GUI on EDT */
        final JVMMonitorCollector[] collectorHolder = new JVMMonitorCollector[1];
        final JFrame[] frameHolder = new JFrame[1];

        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception e) { /* fallback */ }

                MainFrame frame = new MainFrame();
                frame.setSize(1200, 800);
                frame.setLocationRelativeTo(null);
                frame.setVisible(true);
                frameHolder[0] = frame;
            }
        });

        /* 3. Auto-connect via the collector */
        /* Use reflection to access the collector from MainFrame,
           or just create a parallel connection for the demo */
        Thread.sleep(500);

        /* Connect through the MainFrame's internal collector.
           MainFrame creates its own JVMMonitorCollector, so we simulate
           a user clicking "Connect" by using the connect dialog logic. */
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                /* Trigger connect programmatically */
                triggerConnect(frameHolder[0], PORT);
            }
        });

        /* Wait for connection + data flow */
        Thread.sleep(3000);

        /* 4. Screenshot loop */
        if (screenshotDir != null) {
            new File(screenshotDir).mkdirs();

            /* Take screenshots of each tab */
            String[] tabNames = {
                "dashboard", "memory", "gc_analysis", "threads", "exceptions",
                "network", "integration", "messaging", "locks", "cpu_usage",
                "cpu_profiler", "instrumentation", "system", "debugger", "tools"
            };

            for (int round = 0; round < 4; round++) {
                /* Switch to each tab and screenshot */
                final JFrame frame = frameHolder[0];
                for (int t = 0; t < tabNames.length; t++) {
                    final int tabIndex = t;
                    SwingUtilities.invokeAndWait(new Runnable() {
                        public void run() {
                            JTabbedPane tabs = findTabbedPane(frame);
                            if (tabs != null && tabIndex < tabs.getTabCount()) {
                                tabs.setSelectedIndex(tabIndex);
                            }
                        }
                    });
                    Thread.sleep(500);

                    String filename = String.format("%s/round%d_%s.png",
                            screenshotDir, round + 1, tabNames[t]);
                    captureWindow(frame, filename);
                }

                if (round < 3) {
                    System.out.println("Round " + (round + 1) + " screenshots taken. Waiting 15s...");
                    Thread.sleep(15000);
                }
            }

            System.out.println("All screenshots saved to " + screenshotDir);
        } else {
            /* No screenshots, just run for 3 minutes */
            Thread.sleep(180000);
        }

        /* Cleanup */
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                frameHolder[0].dispose();
            }
        });
        System.exit(0);
    }

    private static void triggerConnect(JFrame frame, final int port) {
        /* Find the MainFrame's collector via reflection and connect */
        try {
            java.lang.reflect.Field collectorField = frame.getClass().getDeclaredField("collector");
            collectorField.setAccessible(true);
            final JVMMonitorCollector collector = (JVMMonitorCollector) collectorField.get(frame);

            new Thread(new Runnable() {
                public void run() {
                    try {
                        collector.connect("127.0.0.1", port);
                    } catch (IOException e) {
                        System.err.println("Auto-connect failed: " + e.getMessage());
                    }
                }
            }).start();
        } catch (Exception e) {
            System.err.println("Could not auto-connect: " + e.getMessage());
        }
    }

    private static JTabbedPane findTabbedPane(Container container) {
        for (Component c : container.getComponents()) {
            if (c instanceof JTabbedPane) return (JTabbedPane) c;
            if (c instanceof Container) {
                JTabbedPane found = findTabbedPane((Container) c);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void captureWindow(JFrame frame, String filename) {
        try {
            BufferedImage image = new BufferedImage(
                    frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
            frame.paint(image.getGraphics());
            javax.imageio.ImageIO.write(image, "png", new File(filename));
        } catch (Exception e) {
            System.err.println("Screenshot failed: " + e.getMessage());
        }
    }
}
