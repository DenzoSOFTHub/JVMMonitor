package it.denzosoft.jvmmonitor.gui;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.net.AgentConnection;
import it.denzosoft.jvmmonitor.protocol.EventMessage;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.*;
import java.util.List;

/**
 * Class Browser panel: enter a package prefix, get all loaded classes,
 * displayed as a navigable tree. Click a class to decompile and view source.
 */
public class ClassBrowserPanel extends JPanel {

    private final JVMMonitorCollector collector;
    private final JTextField packageField;
    private final JTree classTree;
    private final DefaultMutableTreeNode rootNode;
    private final DefaultTreeModel treeModel;
    private final JTextArea sourceArea;
    private final JLabel statusLabel;

    /* Accumulated class names from agent responses */
    private final List receivedClasses = new ArrayList();

    public ClassBrowserPanel(JVMMonitorCollector collector) {
        this.collector = collector;
        setLayout(new BorderLayout(5, 5));

        /* Top: package filter + search button */
        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));
        topBar.add(new JLabel("Package:"));
        packageField = new JTextField("com.myapp", 25);
        packageField.setToolTipText("Enter package prefix (e.g., com.myapp, it.listpa). Leave empty for all classes.");
        topBar.add(packageField);

        JButton searchBtn = new JButton("Load Classes");
        searchBtn.setBackground(new Color(50, 130, 200));
        searchBtn.setForeground(Color.WHITE);
        searchBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { loadClasses(); }
        });
        topBar.add(searchBtn);

        statusLabel = new JLabel("");
        topBar.add(statusLabel);
        add(topBar, BorderLayout.NORTH);

        /* Left: class tree */
        rootNode = new DefaultMutableTreeNode("Classes");
        treeModel = new DefaultTreeModel(rootNode);
        classTree = new JTree(treeModel);
        classTree.setRootVisible(false);
        classTree.setShowsRootHandles(true);
        classTree.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    TreePath path = classTree.getPathForLocation(e.getX(), e.getY());
                    if (path != null) {
                        classTree.setSelectionPath(path);
                        onClassSelected();
                    }
                }
            }
        });

        /* Right: source code viewer */
        sourceArea = new JTextArea();
        sourceArea.setEditable(false);
        sourceArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        sourceArea.setText("Select a class from the tree to view its decompiled source.");

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                new JScrollPane(classTree), new JScrollPane(sourceArea));
        split.setDividerLocation(300);
        split.setResizeWeight(0.3);
        add(split, BorderLayout.CENTER);
    }

    private void loadClasses() {
        AgentConnection conn = collector.getConnection();
        if (conn == null || !conn.isConnected()) {
            JOptionPane.showMessageDialog(this, "Not connected to any agent.",
                    "Not Connected", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String pkg = packageField.getText().trim();
        statusLabel.setText("Loading...");
        receivedClasses.clear();

        /* Register listener for CLASS_INFO responses */
        conn.setDiagnosticListener(new AgentConnection.DiagnosticListener() {
            public void onDiagnosticMessage(it.denzosoft.jvmmonitor.protocol.MessageType type, EventMessage msg) {
                if (type == it.denzosoft.jvmmonitor.protocol.MessageType.CLASS_INFO) {
                    parseClassList(msg);
                }
            }
        });

        try {
            conn.listClasses(pkg);
            /* Wait for response */
            new Thread(new Runnable() {
                public void run() {
                    try { Thread.sleep(2000); } catch (InterruptedException e) { /* ignore */ }
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() { buildTree(); }
                    });
                }
            }).start();
        } catch (Exception e) {
            statusLabel.setText("Failed: " + e.getMessage());
        }
    }

    private void parseClassList(EventMessage msg) {
        int off = 8; /* skip timestamp */
        if (off + 2 > msg.getPayloadLength()) return;
        int count = msg.readU16(off); off += 2;
        for (int i = 0; i < count && off + 2 <= msg.getPayloadLength(); i++) {
            String name = msg.readString(off);
            off += msg.stringFieldLength(off);
            if (name != null && name.length() > 0) {
                receivedClasses.add(name);
            }
        }
    }

    private void buildTree() {
        rootNode.removeAllChildren();

        /* Sort classes alphabetically */
        Collections.sort(receivedClasses);

        /* Separate outer classes from inner classes (contains '$') */
        List outerClasses = new ArrayList();
        /* Map: outer class full name -> list of inner class full names */
        Map innerClassMap = new HashMap();

        for (int i = 0; i < receivedClasses.size(); i++) {
            String fullName = (String) receivedClasses.get(i);
            int lastDot = fullName.lastIndexOf('.');
            String simpleName = lastDot > 0 ? fullName.substring(lastDot + 1) : fullName;

            if (simpleName.contains("$")) {
                /* Inner class — group under outer class */
                int dollarInFull = fullName.indexOf('$');
                String outerName = fullName.substring(0, dollarInFull);
                List inners = (List) innerClassMap.get(outerName);
                if (inners == null) { inners = new ArrayList(); innerClassMap.put(outerName, inners); }
                inners.add(fullName);
            } else {
                outerClasses.add(fullName);
            }
        }

        /* Build package tree with outer classes only */
        Map packageNodes = new HashMap();

        for (int i = 0; i < outerClasses.size(); i++) {
            String fullName = (String) outerClasses.get(i);
            int lastDot = fullName.lastIndexOf('.');
            String pkg = lastDot > 0 ? fullName.substring(0, lastDot) : "(default)";
            String simpleName = lastDot > 0 ? fullName.substring(lastDot + 1) : fullName;

            DefaultMutableTreeNode pkgNode = (DefaultMutableTreeNode) packageNodes.get(pkg);
            if (pkgNode == null) {
                pkgNode = new DefaultMutableTreeNode(pkg);
                packageNodes.put(pkg, pkgNode);
                rootNode.add(pkgNode);
            }

            /* Show inner class count in label if any */
            List inners = (List) innerClassMap.get(fullName);
            String label = inners != null ? simpleName + " (+" + inners.size() + " inner)" : simpleName;
            pkgNode.add(new DefaultMutableTreeNode(label));
        }

        treeModel.reload();

        /* Expand all */
        for (int i = 0; i < classTree.getRowCount(); i++) {
            classTree.expandRow(i);
        }

        statusLabel.setText(receivedClasses.size() + " classes loaded");
    }

    private void onClassSelected() {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) classTree.getLastSelectedPathComponent();
        if (node == null || !node.isLeaf()) return;

        /* Reconstruct full class name: parent(package) + "." + node(simpleName) */
        DefaultMutableTreeNode parent = (DefaultMutableTreeNode) node.getParent();
        if (parent == null || parent == rootNode) return;

        String pkg = parent.getUserObject().toString();
        String label = node.getUserObject().toString();
        /* Strip inner class count from label: "MyClass (+3 inner)" -> "MyClass" */
        int parenIdx = label.indexOf(" (+");
        String simpleName = parenIdx > 0 ? label.substring(0, parenIdx) : label;
        final String fullName = "(default)".equals(pkg) ? simpleName : pkg + "." + simpleName;

        sourceArea.setText("Decompiling " + fullName + "...");

        /* Find inner classes for this outer class */
        final List innerNames = new ArrayList();
        for (int i = 0; i < receivedClasses.size(); i++) {
            String cls = (String) receivedClasses.get(i);
            if (cls.startsWith(fullName + "$")) {
                innerNames.add(cls);
            }
        }

        /* Try to decompile using the integrated DenzoSOFT decompiler */
        AgentConnection conn = collector.getConnection();
        if (conn == null || !conn.isConnected()) {
            sourceArea.setText("Not connected to agent.");
            return;
        }

        try {
            /* Request bytecode for outer class + all inner classes */
            conn.debugGetClassBytes(fullName);
            for (int i = 0; i < innerNames.size(); i++) {
                conn.debugGetClassBytes((String) innerNames.get(i));
            }

            String info = "// Decompilation requested for: " + fullName + "\n";
            if (!innerNames.isEmpty()) {
                info += "// Including " + innerNames.size() + " inner class(es):\n";
                for (int i = 0; i < innerNames.size(); i++) {
                    info += "//   " + innerNames.get(i) + "\n";
                }
            }
            info += "\n// The decompiled source will appear in the Source Viewer tab.\n";
            info += "// Switch to Source Viewer to see the result.\n";
            sourceArea.setText(info);
        } catch (Exception e) {
            sourceArea.setText("Failed to request class: " + e.getMessage());
        }
    }
}
