package it.denzosoft.jvmmonitor.gui;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.gui.chart.AlignedCellRenderer;
import it.denzosoft.jvmmonitor.model.InstrumentationEvent;
import it.denzosoft.jvmmonitor.net.AgentConnection;

import it.denzosoft.jvmmonitor.gui.chart.TraceTreeTable;
import javax.swing.*;
import it.denzosoft.jvmmonitor.gui.chart.CsvExporter;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/**
 * Instrumentation panel with:
 * - Probe configuration (packages, JDBC, Spring, etc.)
 * - Start/stop controls
 * - Sub-tabs: Method Profiler, Request Tracer, JDBC Monitor
 */
public class InstrumentationPanel extends JPanel {

    private final JVMMonitorCollector collector;
    private final JLabel statusLabel;
    private final JTextField packagesField;
    private final JCheckBox jdbcProbe, springProbe, httpProbe, messagingProbe, mailProbe, cacheProbe,
            diskIoProbe, socketIoProbe;
    private final JButton startBtn, stopBtn;

    /* Sub-tab models */
    private final ProfilerTableModel profilerModel;
    private final TraceTreeTable traceTreeTable;
    private final SqlAggregateModel sqlAggModel;
    private final JdbcTableModel jdbcModel;
    private final ConnectionMonitorModel connMonModel;
    private final HttpProfilerModel httpModel;
    private final DiskIoModel diskIoModel;
    private final SocketIoModel socketIoModel;
    private final TraceEventTableModel traceEventModel;

    /* Track open connections: connId -> open event */
    private final Map<String, ConnInfo> openConnections = new LinkedHashMap<String, ConnInfo>();

    private volatile boolean recording = false;
    private long recordStartTs = 0;

    public InstrumentationPanel(JVMMonitorCollector collector) {
        this.collector = collector;
        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        /* ── Config bar ──────────────────────────── */
        JPanel configPanel = new JPanel();
        configPanel.setLayout(new BoxLayout(configPanel, BoxLayout.Y_AXIS));
        configPanel.setBorder(BorderFactory.createTitledBorder("Instrumentation Configuration"));

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        row1.add(new JLabel("Application packages:"));
        packagesField = new JTextField("com.myapp", 30);
        row1.add(packagesField);
        configPanel.add(row1);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        row2.add(new JLabel("Probes:"));
        jdbcProbe = new JCheckBox("JDBC/JPA", true);
        springProbe = new JCheckBox("Spring (Controller/Service)", true);
        httpProbe = new JCheckBox("HTTP Client", true);
        messagingProbe = new JCheckBox("JMS/Kafka/RabbitMQ", true);
        mailProbe = new JCheckBox("JavaMail (SMTP/IMAP)", false);
        cacheProbe = new JCheckBox("Cache (Redis/Memcached)", false);
        diskIoProbe = new JCheckBox("Disk I/O (File read/write)", false);
        socketIoProbe = new JCheckBox("Socket I/O (TCP read/write)", false);
        row2.add(jdbcProbe);
        row2.add(springProbe);
        row2.add(httpProbe);
        row2.add(messagingProbe);
        row2.add(mailProbe);
        row2.add(cacheProbe);
        configPanel.add(row2);

        JPanel row2b = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        row2b.add(new JLabel("I/O Probes:"));
        row2b.add(diskIoProbe);
        row2b.add(socketIoProbe);
        configPanel.add(row2b);

        JPanel row3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        startBtn = new JButton("Start Instrumentation");
        startBtn.setBackground(new Color(50, 160, 50));
        startBtn.setForeground(Color.WHITE);
        startBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { startInstrumentation(); }
        });
        stopBtn = new JButton("Stop");
        stopBtn.setBackground(new Color(200, 50, 50));
        stopBtn.setForeground(Color.WHITE);
        stopBtn.setEnabled(false);
        stopBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { stopInstrumentation(); }
        });
        statusLabel = new JLabel("Configure packages and probes, then press Start");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 12f));
        row3.add(startBtn);
        row3.add(stopBtn);
        row3.add(Box.createHorizontalStrut(20));
        row3.add(statusLabel);
        configPanel.add(row3);

        add(configPanel, BorderLayout.NORTH);

        /* ── Sub-tabs ────────────────────────────── */
        JTabbedPane subTabs = new JTabbedPane();

        /* Method Profiler: aggregated by method, sorted by total time */
        profilerModel = new ProfilerTableModel();
        JTable profilerTable = new JTable(profilerModel);
        profilerTable.setDefaultRenderer(Object.class, new ProfilerCellRenderer());
        profilerTable.setAutoCreateRowSorter(true);
        profilerTable.setRowHeight(18);
        CsvExporter.install(profilerTable);
        subTabs.addTab("Method Profiler", new JScrollPane(profilerTable));

        /* Request Tracer: tree-table with expandable nodes */
        traceTreeTable = new TraceTreeTable();
        subTabs.addTab("Request Tracer", traceTreeTable);

        /* JDBC Monitor with 2 sub-tabs */
        JTabbedPane jdbcTabs = new JTabbedPane();

        /* Sub-tab 1: SQL Aggregate (grouped by SQL command) */
        sqlAggModel = new SqlAggregateModel();
        JTable sqlAggTable = new JTable(sqlAggModel);
        sqlAggTable.setDefaultRenderer(Object.class, new JdbcCellRenderer());
        sqlAggTable.setAutoCreateRowSorter(true);
        sqlAggTable.setRowHeight(18);
        CsvExporter.install(sqlAggTable);
        jdbcTabs.addTab("SQL Statistics", new JScrollPane(sqlAggTable));

        /* Sub-tab 2: SQL Events (last 100) */
        jdbcModel = new JdbcTableModel();
        JTable jdbcTable = new JTable(jdbcModel);
        jdbcTable.setDefaultRenderer(Object.class, new JdbcCellRenderer());
        jdbcTable.setAutoCreateRowSorter(true);
        jdbcTable.setRowHeight(18);
        CsvExporter.install(jdbcTable);
        jdbcTabs.addTab("SQL Events", new JScrollPane(jdbcTable));

        /* Sub-tab 3: Connection Monitor (open/unclosed connections) */
        connMonModel = new ConnectionMonitorModel();
        JTable connMonTable = new JTable(connMonModel);
        connMonTable.setDefaultRenderer(Object.class, new ConnMonCellRenderer());
        connMonTable.setAutoCreateRowSorter(true);
        connMonTable.setRowHeight(18);
        CsvExporter.install(connMonTable);
        jdbcTabs.addTab("Connection Monitor", new JScrollPane(connMonTable));

        subTabs.addTab("JDBC Monitor", jdbcTabs);

        /* HTTP Request Profiler */
        httpModel = new HttpProfilerModel();
        JTable httpTable = new JTable(httpModel);
        httpTable.setDefaultRenderer(Object.class, new HttpCellRenderer());
        httpTable.setAutoCreateRowSorter(true);
        httpTable.setRowHeight(18);
        CsvExporter.install(httpTable);
        subTabs.addTab("HTTP Profiler", new JScrollPane(httpTable));

        /* Disk I/O Monitor */
        diskIoModel = new DiskIoModel();
        JTable diskIoTable = new JTable(diskIoModel);
        diskIoTable.setDefaultRenderer(Object.class, new AlignedCellRenderer());
        diskIoTable.setAutoCreateRowSorter(true);
        diskIoTable.setRowHeight(18);
        CsvExporter.install(diskIoTable);
        subTabs.addTab("Disk I/O", new JScrollPane(diskIoTable));

        /* Socket I/O Monitor */
        socketIoModel = new SocketIoModel();
        JTable socketIoTable = new JTable(socketIoModel);
        socketIoTable.setDefaultRenderer(Object.class, new AlignedCellRenderer());
        socketIoTable.setAutoCreateRowSorter(true);
        socketIoTable.setRowHeight(18);
        CsvExporter.install(socketIoTable);
        subTabs.addTab("Socket I/O", new JScrollPane(socketIoTable));

        /* All events (raw) */
        traceEventModel = new TraceEventTableModel();
        JTable eventsTable = new JTable(traceEventModel);
        eventsTable.setDefaultRenderer(Object.class, new AlignedCellRenderer());
        eventsTable.setAutoCreateRowSorter(true);
        eventsTable.setRowHeight(18);
        CsvExporter.install(eventsTable);
        subTabs.addTab("All Events", new JScrollPane(eventsTable));

        add(subTabs, BorderLayout.CENTER);
    }

    private void startInstrumentation() {
        recording = true;
        recordStartTs = System.currentTimeMillis();
        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        statusLabel.setText("RECORDING... collecting instrumentation events");
        statusLabel.setForeground(Color.RED);

        /* Send config to agent */
        AgentConnection conn = collector.getConnection();
        if (conn != null && conn.isConnected()) {
            try {
                String[] pkgs = packagesField.getText().split("[,;\\s]+");
                List<String> probes = new ArrayList<String>();
                if (jdbcProbe.isSelected()) probes.add("jdbc");
                if (springProbe.isSelected()) probes.add("spring");
                if (httpProbe.isSelected()) probes.add("http");
                if (messagingProbe.isSelected()) probes.add("messaging");
                if (mailProbe.isSelected()) probes.add("mail");
                if (cacheProbe.isSelected()) probes.add("cache");
                if (diskIoProbe.isSelected()) probes.add("disk_io");
                if (socketIoProbe.isSelected()) probes.add("socket_io");
                conn.sendInstrumentationConfig(pkgs, probes.toArray(new String[probes.size()]));
                conn.startInstrumentation();
            } catch (Exception ex) {
                statusLabel.setText("Failed to send config: " + ex.getMessage());
            }
        }
    }

    private void stopInstrumentation() {
        recording = false;
        startBtn.setEnabled(true);
        stopBtn.setEnabled(false);

        AgentConnection conn = collector.getConnection();
        if (conn != null && conn.isConnected()) {
            try { conn.stopInstrumentation(); } catch (Exception ex) { /* ignore */ }
        }

        /* Analyze collected events */
        long now = System.currentTimeMillis();
        List<InstrumentationEvent> events = collector.getStore()
                .getInstrumentationEvents(recordStartTs, now);
        analyzeEvents(events);

        double durationSec = (now - recordStartTs) / 1000.0;
        statusLabel.setText(String.format("Collected: %d events in %.1fs  |  Analysis complete",
                events.size(), durationSec));
        statusLabel.setForeground(new Color(0, 100, 0));
    }

    public void refresh() {
        if (recording) {
            long now = System.currentTimeMillis();
            List<InstrumentationEvent> events = collector.getStore()
                    .getInstrumentationEvents(recordStartTs, now);
            analyzeEvents(events);
            double elapsed = (now - recordStartTs) / 1000.0;
            statusLabel.setText(String.format("RECORDING... %d events (%.0fs)", events.size(), elapsed));
        }
    }

    private void analyzeEvents(List<InstrumentationEvent> events) {
        /* Method profiler: aggregate by class.method */
        Map<String, long[]> methodStats = new LinkedHashMap<String, long[]>();
        /* key -> {totalDuration, count, maxDuration, exceptionCount} */

        /* JDBC events */
        List<InstrumentationEvent> jdbcEvents = new ArrayList<InstrumentationEvent>();

        /* Trace tree: group by root traceId */
        Map<Long, List<InstrumentationEvent>> traces = new LinkedHashMap<Long, List<InstrumentationEvent>>();

        for (int i = 0; i < events.size(); i++) {
            InstrumentationEvent e = events.get(i);

            /* Profiler aggregation (only exit events with duration) */
            if (e.getDurationNanos() > 0) {
                String key = e.getFullMethodName();
                long[] stats = methodStats.get(key);
                if (stats == null) { stats = new long[4]; methodStats.put(key, stats); }
                stats[0] += e.getDurationNanos();
                stats[1]++;
                if (e.getDurationNanos() > stats[2]) stats[2] = e.getDurationNanos();
                if (e.isException()) stats[3]++;
            }

            /* JDBC */
            if (e.getEventType() == InstrumentationEvent.TYPE_JDBC_QUERY
                || e.getEventType() == InstrumentationEvent.TYPE_JDBC_CONNECT
                || e.getEventType() == InstrumentationEvent.TYPE_JDBC_CLOSE) {
                jdbcEvents.add(e);
            }

            /* Traces: group by root trace (parentTraceId == 0 means root) */
            long rootId = e.getParentTraceId() == 0 ? e.getTraceId() : e.getParentTraceId();
            List<InstrumentationEvent> traceList = traces.get(Long.valueOf(rootId));
            if (traceList == null) { traceList = new ArrayList<InstrumentationEvent>(); traces.put(Long.valueOf(rootId), traceList); }
            traceList.add(e);
        }

        /* Update profiler table */
        List<Map.Entry<String, long[]>> sorted = new ArrayList<Map.Entry<String, long[]>>(methodStats.entrySet());
        Collections.sort(sorted, new Comparator<Map.Entry<String, long[]>>() {
            public int compare(Map.Entry<String, long[]> a, Map.Entry<String, long[]> b) {
                return Long.compare(b.getValue()[0], a.getValue()[0]);
            }
        });
        profilerModel.setData(sorted);

        /* Update JDBC: aggregate by SQL + last 100 events */
        sqlAggModel.aggregate(jdbcEvents);
        int jdbcStart = Math.max(0, jdbcEvents.size() - 100);
        jdbcModel.setData(jdbcEvents.subList(jdbcStart, jdbcEvents.size()));

        /* Track connection open/close for Connection Monitor */
        for (int i = 0; i < events.size(); i++) {
            InstrumentationEvent e = events.get(i);
            if (e.getEventType() == InstrumentationEvent.TYPE_JDBC_CONNECT) {
                String connId = String.valueOf(e.getTraceId());
                openConnections.put(connId, new ConnInfo(connId, e.getTimestamp(),
                        e.getClassName(), e.getContext(), e.getThreadName()));
            } else if (e.getEventType() == InstrumentationEvent.TYPE_JDBC_CLOSE) {
                String connId = String.valueOf(e.getTraceId());
                openConnections.remove(connId);
            }
        }
        connMonModel.setData(openConnections, System.currentTimeMillis());

        /* HTTP Profiler: aggregate root trace events (depth=0) by context (URL) */
        httpModel.aggregate(events);

        /* Disk I/O: filter events from File I/O probe */
        diskIoModel.aggregate(events);

        /* Socket I/O: filter events from Socket I/O probe */
        socketIoModel.aggregate(events);

        /* Update trace tree-table (last 10 traces) */
        List<TraceTreeTable.TraceNode> traceRoots = new ArrayList<TraceTreeTable.TraceNode>();
        List<Long> traceIds = new ArrayList<Long>(traces.keySet());
        int traceStart = Math.max(0, traceIds.size() - 10);
        for (int t = traceIds.size() - 1; t >= traceStart; t--) {
            Long tid = traceIds.get(t);
            List<InstrumentationEvent> traceEvents = traces.get(tid);
            if (traceEvents.isEmpty()) continue;

            /* Find root event */
            InstrumentationEvent rootEvt = traceEvents.get(0);
            for (int i = 0; i < traceEvents.size(); i++) {
                if (traceEvents.get(i).getDepth() == 0) { rootEvt = traceEvents.get(i); break; }
            }

            double rootDur = rootEvt.getDurationMs();
            TraceTreeTable.TraceNode rootNode = new TraceTreeTable.TraceNode(
                    rootEvt.getDisplayClassName() + "." + rootEvt.getMethodName(),
                    rootDur, 100.0, rootEvt.getContext(), rootEvt.isException(), 0);

            /* Build children sorted by depth */
            Collections.sort(traceEvents, new Comparator<InstrumentationEvent>() {
                public int compare(InstrumentationEvent a, InstrumentationEvent b) {
                    return a.getDepth() - b.getDepth();
                }
            });
            Map<Long, TraceTreeTable.TraceNode> nodeMap = new LinkedHashMap<Long, TraceTreeTable.TraceNode>();
            nodeMap.put(Long.valueOf(rootEvt.getTraceId()), rootNode);

            for (int i = 0; i < traceEvents.size(); i++) {
                InstrumentationEvent e = traceEvents.get(i);
                if (e == rootEvt) continue;

                double pct = rootDur > 0 ? e.getDurationMs() / rootDur * 100 : 0;
                TraceTreeTable.TraceNode childNode = new TraceTreeTable.TraceNode(
                        e.getDisplayClassName() + "." + e.getMethodName(),
                        e.getDurationMs(), pct, e.getContext(), e.isException(), e.getDepth());
                nodeMap.put(Long.valueOf(e.getTraceId()), childNode);

                TraceTreeTable.TraceNode parent = nodeMap.get(Long.valueOf(e.getParentTraceId()));
                if (parent != null) parent.children.add(childNode);
                else rootNode.children.add(childNode);
            }

            traceRoots.add(rootNode);
        }
        traceTreeTable.setTraces(traceRoots);

        /* Update raw events table */
        int showStart = Math.max(0, events.size() - 100);
        traceEventModel.setData(events.subList(showStart, events.size()));
    }


    /* ── Profiler Table ──────────────────────────────── */

    private static class ProfilerTableModel extends AbstractTableModel {
        private final String[] COLS = {"#", "Method", "Total Time", "Calls", "Avg", "Max", "Errors"};
        private List<Map.Entry<String, long[]>> data = new ArrayList<Map.Entry<String, long[]>>();

        public void setData(List<Map.Entry<String, long[]>> data) {
            this.data = data;
            fireTableDataChanged();
        }

        public int getRowCount() { return data.size(); }
        public int getColumnCount() { return COLS.length; }
        public String getColumnName(int c) { return COLS[c]; }

        public Object getValueAt(int row, int col) {
            Map.Entry<String, long[]> e = data.get(row);
            long[] s = e.getValue();
            switch (col) {
                case 0: return Integer.valueOf(row + 1);
                case 1: return e.getKey();
                case 2: return String.format("%.1f ms", s[0] / 1000000.0);
                case 3: return Long.valueOf(s[1]);
                case 4: return String.format("%.1f ms", s[1] > 0 ? s[0] / s[1] / 1000000.0 : 0);
                case 5: return String.format("%.1f ms", s[2] / 1000000.0);
                case 6: return s[3] > 0 ? Long.valueOf(s[3]) : "";
                default: return "";
            }
        }
    }

    private static class ProfilerCellRenderer extends AlignedCellRenderer {
        protected void colorize(Component c, JTable table, Object value, int row, int col) {
            if (col == 2 || col == 4 || col == 5) {
                try {
                    double ms = Double.parseDouble(value.toString().replace(" ms", ""));
                    if (ms > 100) { c.setForeground(Color.RED); c.setFont(c.getFont().deriveFont(Font.BOLD)); }
                    else if (ms > 20) { c.setForeground(new Color(200, 100, 0)); }
                } catch (NumberFormatException e) { /* ignore */ }
            } else if (col == 6 && value instanceof Long) {
                c.setForeground(Color.RED);
                c.setFont(c.getFont().deriveFont(Font.BOLD));
            }
        }
    }

    /* ── JDBC Table ──────────────────────────────────── */

    private static class JdbcTableModel extends AbstractTableModel {
        private final String[] COLS = {"Time", "Type", "Duration", "SQL / Context", "Thread"};
        private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
        private List<InstrumentationEvent> data = new ArrayList<InstrumentationEvent>();

        public void setData(List<InstrumentationEvent> data) {
            this.data = data;
            fireTableDataChanged();
        }

        public int getRowCount() { return data.size(); }
        public int getColumnCount() { return COLS.length; }
        public String getColumnName(int c) { return COLS[c]; }

        public Object getValueAt(int row, int col) {
            InstrumentationEvent e = data.get(data.size() - 1 - row);
            switch (col) {
                case 0: return sdf.format(new Date(e.getTimestamp()));
                case 1: return e.getEventTypeName();
                case 2: return String.format("%.1f ms", e.getDurationMs());
                case 3: return e.getContext();
                case 4: return e.getThreadName();
                default: return "";
            }
        }
    }

    private static class JdbcCellRenderer extends AlignedCellRenderer {
        protected void colorize(Component c, JTable table, Object value, int row, int col) {
            if (col == 2) {
                try {
                    double ms = Double.parseDouble(value.toString().replace(" ms", ""));
                    if (ms > 100) { c.setForeground(Color.RED); c.setFont(c.getFont().deriveFont(Font.BOLD)); }
                    else if (ms > 20) { c.setForeground(new Color(200, 100, 0)); }
                } catch (NumberFormatException e) { /* ignore */ }
            }
        }
    }

    /* ── Raw Events Table ────────────────────────────── */

    private static class TraceEventTableModel extends AbstractTableModel {
        private final String[] COLS = {"Time", "Type", "Thread", "Class.Method", "Duration", "Context", "Depth"};
        private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
        private List<InstrumentationEvent> data = new ArrayList<InstrumentationEvent>();

        public void setData(List<InstrumentationEvent> data) {
            this.data = data;
            fireTableDataChanged();
        }

        public int getRowCount() { return data.size(); }
        public int getColumnCount() { return COLS.length; }
        public String getColumnName(int c) { return COLS[c]; }

        public Object getValueAt(int row, int col) {
            InstrumentationEvent e = data.get(data.size() - 1 - row);
            switch (col) {
                case 0: return sdf.format(new Date(e.getTimestamp()));
                case 1: return e.getEventTypeName();
                case 2: return e.getThreadName();
                case 3: return e.getFullMethodName();
                case 4: return e.getDurationNanos() > 0 ? String.format("%.1f ms", e.getDurationMs()) : "";
                case 5: return e.getContext();
                case 6: return Integer.valueOf(e.getDepth());
                default: return "";
            }
        }
    }

    /* ── SQL Aggregate Model ─────────────────────────── */

    private static class SqlAggregateModel extends AbstractTableModel {
        private final String[] COLS = {"#", "SQL Command", "Total Duration", "Executions", "Avg", "Min", "Max"};
        private List<SqlAggregate> data = new ArrayList<SqlAggregate>();

        public void aggregate(List<InstrumentationEvent> jdbcEvents) {
            Map<String, SqlAggregate> map = new LinkedHashMap<String, SqlAggregate>();
            for (int i = 0; i < jdbcEvents.size(); i++) {
                InstrumentationEvent e = jdbcEvents.get(i);
                String sql = e.getContext();
                if (sql == null || sql.isEmpty()) sql = "(no SQL)";
                /* Normalize: trim whitespace, truncate for grouping */
                sql = sql.trim();
                if (sql.length() > 200) sql = sql.substring(0, 200);

                SqlAggregate agg = map.get(sql);
                if (agg == null) {
                    agg = new SqlAggregate(sql);
                    map.put(sql, agg);
                }
                double ms = e.getDurationMs();
                agg.totalMs += ms;
                agg.count++;
                if (ms < agg.minMs) agg.minMs = ms;
                if (ms > agg.maxMs) agg.maxMs = ms;
            }

            data = new ArrayList<SqlAggregate>(map.values());
            Collections.sort(data, new Comparator<SqlAggregate>() {
                public int compare(SqlAggregate a, SqlAggregate b) {
                    return Double.compare(b.totalMs, a.totalMs);
                }
            });
            fireTableDataChanged();
        }

        public int getRowCount() { return data.size(); }
        public int getColumnCount() { return COLS.length; }
        public String getColumnName(int c) { return COLS[c]; }

        public Object getValueAt(int row, int col) {
            SqlAggregate a = data.get(row);
            switch (col) {
                case 0: return Integer.valueOf(row + 1);
                case 1: return a.sql;
                case 2: return String.format("%.1f ms", a.totalMs);
                case 3: return Integer.valueOf(a.count);
                case 4: return String.format("%.1f ms", a.count > 0 ? a.totalMs / a.count : 0);
                case 5: return String.format("%.1f ms", a.minMs == Double.MAX_VALUE ? 0 : a.minMs);
                case 6: return String.format("%.1f ms", a.maxMs);
                default: return "";
            }
        }
    }

    private static class SqlAggregate {
        final String sql;
        double totalMs = 0;
        int count = 0;
        double minMs = Double.MAX_VALUE;
        double maxMs = 0;
        SqlAggregate(String sql) { this.sql = sql; }
    }

    /* ── Connection Info ─────────────────────────────── */

    private static class ConnInfo {
        final String connId;
        final long openTime;
        final String className;
        final String url;
        final String threadName;

        ConnInfo(String connId, long openTime, String className, String url, String threadName) {
            this.connId = connId;
            this.openTime = openTime;
            this.className = className != null ? className : "";
            this.url = url != null ? url : "";
            this.threadName = threadName != null ? threadName : "";
        }
    }

    /* ── Connection Monitor Model ────────────────────── */

    private static class ConnectionMonitorModel extends AbstractTableModel {
        private final String[] COLS = {"Connection ID", "Opened At", "Open Duration", "Class", "URL", "Thread"};
        private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
        private List<ConnInfo> data = new ArrayList<ConnInfo>();
        private long currentTime = 0;

        public void setData(Map<String, ConnInfo> openConns, long now) {
            this.currentTime = now;
            data = new ArrayList<ConnInfo>(openConns.values());
            /* Sort by open duration desc (longest open first) */
            Collections.sort(data, new Comparator<ConnInfo>() {
                public int compare(ConnInfo a, ConnInfo b) {
                    return Long.compare(a.openTime, b.openTime);
                }
            });
            fireTableDataChanged();
        }

        public int getRowCount() { return data.size(); }
        public int getColumnCount() { return COLS.length; }
        public String getColumnName(int c) { return COLS[c]; }

        public Object getValueAt(int row, int col) {
            ConnInfo c = data.get(row);
            switch (col) {
                case 0: return c.connId;
                case 1: return sdf.format(new Date(c.openTime));
                case 2:
                    long durMs = currentTime - c.openTime;
                    if (durMs < 1000) return durMs + " ms";
                    if (durMs < 60000) return String.format("%.1f s", durMs / 1000.0);
                    return String.format("%.1f min", durMs / 60000.0);
                case 3: return c.className;
                case 4: return c.url;
                case 5: return c.threadName;
                default: return "";
            }
        }
    }

    /* ── Connection Monitor Renderer ─────────────────── */

    private static class ConnMonCellRenderer extends AlignedCellRenderer {
        protected void colorize(Component c, JTable table, Object value, int row, int col) {
            if (col == 2) {
                /* Color long-open connections red */
                String text = value != null ? value.toString() : "";
                if (text.contains("min")) {
                    c.setForeground(Color.RED);
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                } else if (text.contains("s") && !text.contains("ms")) {
                    double secs = 0;
                    try { secs = Double.parseDouble(text.replace(" s", "")); } catch (NumberFormatException e) { }
                    if (secs > 30) {
                        c.setForeground(Color.RED);
                        c.setFont(c.getFont().deriveFont(Font.BOLD));
                    } else if (secs > 5) {
                        c.setForeground(new Color(200, 100, 0));
                    }
                }
            }
        }
    }

    /* ── HTTP Profiler Model ─────────────────────────── */

    private static class HttpProfilerModel extends AbstractTableModel {
        private final String[] COLS = {"#", "URL Pattern", "Requests", "Total (ms)", "Avg (ms)", "P50 (ms)", "P95 (ms)", "P99 (ms)", "Max (ms)", "Errors"};
        private List<HttpEndpoint> data = new ArrayList<HttpEndpoint>();

        public void aggregate(List<InstrumentationEvent> events) {
            Map<String, List<Double>> urlDurations = new LinkedHashMap<String, List<Double>>();
            Map<String, int[]> urlErrors = new LinkedHashMap<String, int[]>();

            for (int i = 0; i < events.size(); i++) {
                InstrumentationEvent e = events.get(i);
                /* Only root events (depth=0) with HTTP method in context */
                if (e.getDepth() == 0 && e.getDurationNanos() > 0) {
                    String ctx = e.getContext();
                    if (ctx == null || ctx.isEmpty()) continue;
                    /* Filter: must start with an HTTP method */
                    if (!ctx.startsWith("GET ") && !ctx.startsWith("POST ") &&
                        !ctx.startsWith("PUT ") && !ctx.startsWith("DELETE ") &&
                        !ctx.startsWith("PATCH ") && !ctx.startsWith("HEAD ") &&
                        !ctx.startsWith("OPTIONS ")) continue;
                    String url = ctx;

                    List<Double> durations = urlDurations.get(url);
                    if (durations == null) { durations = new ArrayList<Double>(); urlDurations.put(url, durations); }
                    durations.add(e.getDurationMs());

                    int[] errs = urlErrors.get(url);
                    if (errs == null) { errs = new int[]{0}; urlErrors.put(url, errs); }
                    if (e.isException()) errs[0]++;
                }
            }

            data = new ArrayList<HttpEndpoint>();
            for (Map.Entry<String, List<Double>> entry : urlDurations.entrySet()) {
                String url = entry.getKey();
                List<Double> durations = entry.getValue();
                Collections.sort(durations);
                int n = durations.size();
                double total = 0;
                for (int i = 0; i < n; i++) total += durations.get(i).doubleValue();
                double avg = n > 0 ? total / n : 0;
                double p50 = percentile(durations, 50);
                double p95 = percentile(durations, 95);
                double p99 = percentile(durations, 99);
                double max = n > 0 ? durations.get(n - 1).doubleValue() : 0;
                int errors = urlErrors.containsKey(url) ? urlErrors.get(url)[0] : 0;

                data.add(new HttpEndpoint(url, n, total, avg, p50, p95, p99, max, errors));
            }

            /* Sort by total time desc */
            Collections.sort(data, new Comparator<HttpEndpoint>() {
                public int compare(HttpEndpoint a, HttpEndpoint b) {
                    return Double.compare(b.totalMs, a.totalMs);
                }
            });
            fireTableDataChanged();
        }

        private static double percentile(List<Double> sorted, int pct) {
            if (sorted.isEmpty()) return 0;
            int idx = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
            if (idx < 0) idx = 0;
            if (idx >= sorted.size()) idx = sorted.size() - 1;
            return sorted.get(idx).doubleValue();
        }

        public int getRowCount() { return data.size(); }
        public int getColumnCount() { return COLS.length; }
        public String getColumnName(int c) { return COLS[c]; }

        public Object getValueAt(int row, int col) {
            HttpEndpoint e = data.get(row);
            switch (col) {
                case 0: return Integer.valueOf(row + 1);
                case 1: return e.url;
                case 2: return Integer.valueOf(e.requests);
                case 3: return String.format("%.0f", e.totalMs);
                case 4: return String.format("%.1f", e.avgMs);
                case 5: return String.format("%.1f", e.p50);
                case 6: return String.format("%.1f", e.p95);
                case 7: return String.format("%.1f", e.p99);
                case 8: return String.format("%.1f", e.maxMs);
                case 9: return e.errors > 0 ? Integer.valueOf(e.errors) : "";
                default: return "";
            }
        }
    }

    private static class HttpEndpoint {
        final String url;
        final int requests;
        final double totalMs, avgMs, p50, p95, p99, maxMs;
        final int errors;
        HttpEndpoint(String url, int requests, double totalMs, double avgMs,
                     double p50, double p95, double p99, double maxMs, int errors) {
            this.url = url; this.requests = requests; this.totalMs = totalMs;
            this.avgMs = avgMs; this.p50 = p50; this.p95 = p95; this.p99 = p99;
            this.maxMs = maxMs; this.errors = errors;
        }
    }

    private static class HttpCellRenderer extends AlignedCellRenderer {
        protected void colorize(Component c, JTable table, Object value, int row, int col) {
            if ((col >= 4 && col <= 8)) {
                try {
                    double ms = Double.parseDouble(value.toString());
                    if (ms > 200) { c.setForeground(Color.RED); c.setFont(c.getFont().deriveFont(Font.BOLD)); }
                    else if (ms > 50) { c.setForeground(new Color(200, 100, 0)); }
                } catch (NumberFormatException e) { /* ignore */ }
            } else if (col == 9 && value instanceof Integer) {
                c.setForeground(Color.RED);
                c.setFont(c.getFont().deriveFont(Font.BOLD));
            }
        }
    }

    /* ── Disk I/O Model ──────────────────────────────── */

    /**
     * Aggregates Disk I/O events by file path and thread.
     * Columns: Thread, Operation, File/Path, Bytes, Duration (ms), Count
     * Instrumented classes: FileInputStream, FileOutputStream, FileChannel, RandomAccessFile
     */
    private static class DiskIoModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Thread", "Operation", "File / Path", "Bytes", "Duration (ms)", "Count"};
        private List<Object[]> data = new ArrayList<Object[]>();

        public void aggregate(List<InstrumentationEvent> events) {
            /* key = thread + "|" + operation + "|" + path -> {totalBytes, totalDurationNanos, count} */
            Map<String, long[]> agg = new LinkedHashMap<String, long[]>();
            Map<String, String> threadNames = new LinkedHashMap<String, String>();

            for (int i = 0; i < events.size(); i++) {
                InstrumentationEvent e = events.get(i);
                int type = e.getEventType();
                if (type != InstrumentationEvent.TYPE_DISK_READ && type != InstrumentationEvent.TYPE_DISK_WRITE) continue;

                String op = type == InstrumentationEvent.TYPE_DISK_READ ? "READ" : "WRITE";
                String path = e.getContext();
                if (path == null || path.isEmpty()) path = e.getClassName() + "." + e.getMethodName();
                String key = e.getThreadName() + "|" + op + "|" + path;
                long[] stats = agg.get(key);
                if (stats == null) { stats = new long[3]; agg.put(key, stats); }

                /* Parse bytes from context: "path|bytes" or just "path" */
                long bytes = 0;
                int pipe = path.lastIndexOf('|');
                if (pipe > 0) {
                    try { bytes = Long.parseLong(path.substring(pipe + 1)); } catch (NumberFormatException ex) { /* ignore */ }
                    path = path.substring(0, pipe);
                }

                stats[0] += bytes;
                stats[1] += e.getDurationNanos();
                stats[2]++;
                threadNames.put(key, e.getThreadName());
            }

            List<Object[]> rows = new ArrayList<Object[]>();
            for (Map.Entry<String, long[]> entry : agg.entrySet()) {
                String[] parts = entry.getKey().split("\\|", 3);
                long[] stats = entry.getValue();
                rows.add(new Object[]{
                        parts[0],
                        parts[1],
                        parts.length > 2 ? parts[2] : "",
                        formatBytes(stats[0]),
                        String.format("%.1f", stats[1] / 1000000.0),
                        Long.valueOf(stats[2])
                });
            }
            /* Sort by total duration descending */
            Collections.sort(rows, new Comparator<Object[]>() {
                public int compare(Object[] a, Object[] b) {
                    try {
                        double da = Double.parseDouble(a[4].toString());
                        double db = Double.parseDouble(b[4].toString());
                        return Double.compare(db, da);
                    } catch (NumberFormatException e) { return 0; }
                }
            });
            data = rows;
            fireTableDataChanged();
        }

        private static String formatBytes(long bytes) {
            if (bytes > 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
            if (bytes > 1024) return String.format("%.1f KB", bytes / 1024.0);
            return bytes + " B";
        }

        public int getRowCount() { return data.size(); }
        public int getColumnCount() { return COLUMNS.length; }
        public String getColumnName(int col) { return COLUMNS[col]; }
        public Object getValueAt(int row, int col) { return data.get(row)[col]; }
    }

    /* ── Socket I/O Model ────────────────────────────── */

    /**
     * Aggregates Socket I/O events by remote address and thread.
     * Columns: Thread, Operation, Remote Address, Bytes, Duration (ms), Count
     * Instrumented classes: Socket, SocketChannel, SocketInputStream, SocketOutputStream
     */
    private static class SocketIoModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Thread", "Operation", "Remote Address", "Bytes", "Duration (ms)", "Count"};
        private List<Object[]> data = new ArrayList<Object[]>();

        public void aggregate(List<InstrumentationEvent> events) {
            /* key = thread + "|" + operation + "|" + remote -> {totalBytes, totalDurationNanos, count} */
            Map<String, long[]> agg = new LinkedHashMap<String, long[]>();

            for (int i = 0; i < events.size(); i++) {
                InstrumentationEvent e = events.get(i);
                int type = e.getEventType();
                if (type != InstrumentationEvent.TYPE_SOCKET_READ
                    && type != InstrumentationEvent.TYPE_SOCKET_WRITE
                    && type != InstrumentationEvent.TYPE_SOCKET_CONNECT
                    && type != InstrumentationEvent.TYPE_SOCKET_CLOSE) continue;

                String op;
                if (type == InstrumentationEvent.TYPE_SOCKET_READ) op = "READ";
                else if (type == InstrumentationEvent.TYPE_SOCKET_WRITE) op = "WRITE";
                else if (type == InstrumentationEvent.TYPE_SOCKET_CONNECT) op = "CONNECT";
                else op = "CLOSE";

                String remote = e.getContext();
                if (remote == null || remote.isEmpty()) remote = e.getClassName() + "." + e.getMethodName();
                String key = e.getThreadName() + "|" + op + "|" + remote;
                long[] stats = agg.get(key);
                if (stats == null) { stats = new long[3]; agg.put(key, stats); }

                /* Parse bytes from context: "addr:port|bytes" or just "addr:port" */
                long bytes = 0;
                int pipe = remote.lastIndexOf('|');
                if (pipe > 0) {
                    try { bytes = Long.parseLong(remote.substring(pipe + 1)); } catch (NumberFormatException ex) { /* ignore */ }
                    remote = remote.substring(0, pipe);
                }

                stats[0] += bytes;
                stats[1] += e.getDurationNanos();
                stats[2]++;
            }

            List<Object[]> rows = new ArrayList<Object[]>();
            for (Map.Entry<String, long[]> entry : agg.entrySet()) {
                String[] parts = entry.getKey().split("\\|", 3);
                long[] stats = entry.getValue();
                rows.add(new Object[]{
                        parts[0],
                        parts[1],
                        parts.length > 2 ? parts[2] : "",
                        formatBytes(stats[0]),
                        String.format("%.1f", stats[1] / 1000000.0),
                        Long.valueOf(stats[2])
                });
            }
            /* Sort by total duration descending */
            Collections.sort(rows, new Comparator<Object[]>() {
                public int compare(Object[] a, Object[] b) {
                    try {
                        double da = Double.parseDouble(a[4].toString());
                        double db = Double.parseDouble(b[4].toString());
                        return Double.compare(db, da);
                    } catch (NumberFormatException e) { return 0; }
                }
            });
            data = rows;
            fireTableDataChanged();
        }

        private static String formatBytes(long bytes) {
            if (bytes > 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
            if (bytes > 1024) return String.format("%.1f KB", bytes / 1024.0);
            return bytes + " B";
        }

        public int getRowCount() { return data.size(); }
        public int getColumnCount() { return COLUMNS.length; }
        public String getColumnName(int col) { return COLUMNS[col]; }
        public Object getValueAt(int row, int col) { return data.get(row)[col]; }
    }
}
