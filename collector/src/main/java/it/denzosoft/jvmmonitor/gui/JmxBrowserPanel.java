package it.denzosoft.jvmmonitor.gui;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.net.AgentConnection;

import javax.swing.*;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Top-level JMX Browser panel.
 *
 * Layout:
 *   - NORTH: Refresh button + status label
 *   - CENTER: JTree showing MBeans grouped by domain
 *     └ On node click: requests MBean attributes from agent
 *     └ Composite attributes ("Foo.bar") are expanded into sub-branches
 *
 * The panel is loaded on demand (Refresh button) to avoid continuous
 * traffic. Memory and CPU chart data arrive via their own dedicated
 * message types and are not affected by this panel.
 */
public class JmxBrowserPanel extends JPanel implements AgentConnection.JmxListener {

    private final JVMMonitorCollector collector;
    private final JTree mbeanTree;
    private final DefaultMutableTreeNode rootNode;
    private final DefaultTreeModel treeModel;
    private final JLabel statusLabel;
    private final JButton refreshBtn;

    /* Map of MBean name -> tree node (leaf) so we can populate attributes later */
    private final Map<String, DefaultMutableTreeNode> mbeanNodes =
            new HashMap<String, DefaultMutableTreeNode>();

    public JmxBrowserPanel(JVMMonitorCollector collector) {
        this.collector = collector;
        setLayout(new BorderLayout(5, 5));

        /* ── Top bar: Refresh + status ── */
        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));
        refreshBtn = new JButton("Refresh");
        refreshBtn.setBackground(new Color(50, 130, 200));
        refreshBtn.setForeground(Color.WHITE);
        refreshBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { refresh(); }
        });
        topBar.add(refreshBtn);

        statusLabel = new JLabel("Press Refresh to load MBeans from the agent.");
        topBar.add(statusLabel);
        add(topBar, BorderLayout.NORTH);

        /* ── Tree ── */
        rootNode = new DefaultMutableTreeNode("MBeans");
        treeModel = new DefaultTreeModel(rootNode);
        mbeanTree = new JTree(treeModel);
        mbeanTree.setRootVisible(false);
        mbeanTree.setShowsRootHandles(true);

        mbeanTree.addTreeSelectionListener(new TreeSelectionListener() {
            public void valueChanged(TreeSelectionEvent e) {
                TreePath path = e.getPath();
                if (path == null) return;
                DefaultMutableTreeNode node =
                        (DefaultMutableTreeNode) path.getLastPathComponent();
                Object user = node.getUserObject();
                if (user instanceof MBeanRef) {
                    loadAttributes((MBeanRef) user, node);
                }
            }
        });

        add(new JScrollPane(mbeanTree), BorderLayout.CENTER);
    }

    /** Trigger MBean list refresh. */
    public void refresh() {
        AgentConnection conn = collector.getConnection();
        if (conn == null || !conn.isConnected()) {
            statusLabel.setText("Not connected to any agent.");
            return;
        }
        /* Exclusive: only one JMX listener is supported at a time.
         * This panel is currently the only user of setJmxListener(). */
        conn.setJmxListener(this);
        rootNode.removeAllChildren();
        mbeanNodes.clear();
        treeModel.reload();
        statusLabel.setText("Loading MBean list...");
        try {
            conn.jmxListMBeans();
        } catch (Exception ex) {
            statusLabel.setText("Request failed: " + ex.getMessage());
        }
    }

    /* ── JmxListener callbacks (run on network thread) ── */

    public void onMBeanList(final List<String> mbeanNames) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() { buildTree(mbeanNames); }
        });
    }

    public void onMBeanAttributes(final String mbeanName, final List<String[]> attrs) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() { populateAttributes(mbeanName, attrs); }
        });
    }

    /* ── Tree building ── */

    private void buildTree(List<String> names) {
        rootNode.removeAllChildren();
        mbeanNodes.clear();

        /* Group by domain (before ':') */
        Map<String, DefaultMutableTreeNode> domainNodes =
                new HashMap<String, DefaultMutableTreeNode>();
        List<String> sorted = new ArrayList<String>(names);
        Collections.sort(sorted);

        for (int i = 0; i < sorted.size(); i++) {
            String full = sorted.get(i);
            int colon = full.indexOf(':');
            String domain = colon > 0 ? full.substring(0, colon) : "(default)";
            String rest = colon > 0 ? full.substring(colon + 1) : full;

            DefaultMutableTreeNode dNode = domainNodes.get(domain);
            if (dNode == null) {
                dNode = new DefaultMutableTreeNode(domain);
                domainNodes.put(domain, dNode);
                rootNode.add(dNode);
            }

            DefaultMutableTreeNode leaf = new DefaultMutableTreeNode(new MBeanRef(full, rest));
            /* Placeholder child so the tree shows the expand handle */
            leaf.add(new DefaultMutableTreeNode("(double-click to load attributes)"));
            dNode.add(leaf);
            mbeanNodes.put(full, leaf);
        }

        treeModel.reload();
        statusLabel.setText(sorted.size() + " MBeans loaded across " + domainNodes.size() + " domains");
        /* Expand first level (domains) */
        for (int i = 0; i < mbeanTree.getRowCount(); i++) {
            mbeanTree.expandRow(i);
        }
    }

    private void loadAttributes(MBeanRef ref, DefaultMutableTreeNode node) {
        if (ref.loaded) return;
        ref.loaded = true;
        AgentConnection conn = collector.getConnection();
        if (conn == null || !conn.isConnected()) return;
        node.removeAllChildren();
        node.add(new DefaultMutableTreeNode("loading..."));
        treeModel.reload(node);
        try {
            conn.jmxGetAttrs(ref.fullName);
        } catch (Exception ex) {
            node.removeAllChildren();
            node.add(new DefaultMutableTreeNode("error: " + ex.getMessage()));
            treeModel.reload(node);
        }
    }

    private void populateAttributes(String mbeanName, List<String[]> attrs) {
        DefaultMutableTreeNode node = mbeanNodes.get(mbeanName);
        if (node == null) return;
        node.removeAllChildren();

        /* Group composite attributes: key "Foo.bar" → branch "Foo" with leaf "bar = val" */
        Map<String, DefaultMutableTreeNode> compositeNodes =
                new HashMap<String, DefaultMutableTreeNode>();

        for (int i = 0; i < attrs.size(); i++) {
            String[] kv = attrs.get(i);
            String key = kv[0];
            String val = kv[1];

            int dot = key.indexOf('.');
            if (dot > 0) {
                String parent = key.substring(0, dot);
                String child = key.substring(dot + 1);
                DefaultMutableTreeNode pNode = compositeNodes.get(parent);
                if (pNode == null) {
                    pNode = new DefaultMutableTreeNode(parent + " (composite)");
                    compositeNodes.put(parent, pNode);
                    node.add(pNode);
                }
                pNode.add(new DefaultMutableTreeNode(child + " = " + val));
            } else {
                node.add(new DefaultMutableTreeNode(key + " = " + val));
            }
        }

        if (node.getChildCount() == 0) {
            node.add(new DefaultMutableTreeNode("(no readable attributes)"));
        }

        treeModel.reload(node);
        /* Expand this MBean and all composite groups under it */
        TreePath base = new TreePath(node.getPath());
        mbeanTree.expandPath(base);
        for (DefaultMutableTreeNode child : compositeNodes.values()) {
            mbeanTree.expandPath(new TreePath(child.getPath()));
        }
    }

    /* ── MainFrame hook: this panel has no background refresh ── */
    public void updateData() {
        /* Null out JMX listener when disconnected to avoid stale references */
        AgentConnection conn = collector.getConnection();
        if (conn == null || !conn.isConnected()) {
            if (conn != null) {
                conn.setJmxListener(null);
            }
        }
    }

    /** Tree node user object identifying an MBean. */
    private static final class MBeanRef {
        final String fullName;  /* "java.lang:type=Memory" */
        final String label;     /* display label after the colon */
        boolean loaded;

        MBeanRef(String fullName, String label) {
            this.fullName = fullName;
            this.label = label;
        }
        public String toString() { return label; }
    }
}
