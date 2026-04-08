package it.denzosoft.jvmmonitor.gui;

import it.denzosoft.jvmmonitor.JVMMonitorCollector;
import it.denzosoft.jvmmonitor.gui.chart.AlignedCellRenderer;
import it.denzosoft.jvmmonitor.model.InstrumentationEvent;
import it.denzosoft.jvmmonitor.net.AgentConnection;

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
            diskIoProbe, socketIoProbe, schedulingProbe, jmsOpsProbe, captureParamsCheck;
    private final JTextField maxValueLengthField;
    private final JButton startBtn, stopBtn;

    /* Sub-tab models */
    private final TracesTableModel tracesModel;
    private final RunningTableModel runningModel;
    private final ProfilerTableModel profilerModel;
    private final SqlAggregateModel sqlAggModel;
    private final JdbcTableModel jdbcModel;
    private final ConnectionMonitorModel connMonModel;
    private final HttpProfilerModel httpModel;
    private final DiskIoModel diskIoModel;
    private final SocketIoModel socketIoModel;
    private final TraceEventTableModel traceEventModel;

    /* Track open connections: connId -> open event. Capped to prevent unbounded growth
     * from leaked connections in the monitored application. */
    private static final int MAX_OPEN_CONNECTIONS = 1000;
    private final Map openConnections = new LinkedHashMap(64, 0.75f, false) {
        protected boolean removeEldestEntry(Map.Entry eldest) {
            return size() > MAX_OPEN_CONNECTIONS;
        }
    };

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
        schedulingProbe = new JCheckBox("Scheduling (Timer/Executor/@Scheduled)", false);
        jmsOpsProbe = new JCheckBox("JMS Extended (connection/session/queue)", false);
        row2.add(jdbcProbe);
        row2.add(springProbe);
        row2.add(httpProbe);
        row2.add(messagingProbe);
        row2.add(mailProbe);
        row2.add(cacheProbe);
        configPanel.add(row2);

        JPanel row2b = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        row2b.add(new JLabel("I/O & Other:"));
        row2b.add(diskIoProbe);
        row2b.add(socketIoProbe);
        row2b.add(schedulingProbe);
        row2b.add(jmsOpsProbe);
        row2b.add(Box.createHorizontalStrut(20));
        captureParamsCheck = new JCheckBox("Capture params & return values", false);
        captureParamsCheck.setToolTipText("Serialize method input parameters and return values as JSON (adds overhead)");
        row2b.add(captureParamsCheck);
        row2b.add(new JLabel("Max value length:"));
        maxValueLengthField = new JTextField("500", 5);
        maxValueLengthField.setToolTipText("Max chars per value (-1 = unlimited)");
        row2b.add(maxValueLengthField);
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

        /* Traces: last 200 completed traces, newest first */
        tracesModel = new TracesTableModel();
        final JTable tracesTable = new JTable(tracesModel);
        tracesTable.setDefaultRenderer(Object.class, new AlignedCellRenderer());
        tracesTable.setAutoCreateRowSorter(true);
        tracesTable.setRowHeight(20);
        CsvExporter.install(tracesTable);
        /* Column widths: Time=90, Type=65, Duration=75, Descriptor=rest, Exc=40, SQL=40, Thread=120 */
        tracesTable.getColumnModel().getColumn(0).setPreferredWidth(90);
        tracesTable.getColumnModel().getColumn(0).setMaxWidth(110);
        tracesTable.getColumnModel().getColumn(1).setPreferredWidth(90);
        tracesTable.getColumnModel().getColumn(1).setMaxWidth(120);
        tracesTable.getColumnModel().getColumn(2).setPreferredWidth(90);
        tracesTable.getColumnModel().getColumn(2).setMaxWidth(110);
        tracesTable.getColumnModel().getColumn(3).setPreferredWidth(400); /* Descriptor */
        tracesTable.getColumnModel().getColumn(4).setPreferredWidth(65);
        tracesTable.getColumnModel().getColumn(4).setMaxWidth(80);
        tracesTable.getColumnModel().getColumn(5).setPreferredWidth(40);
        tracesTable.getColumnModel().getColumn(5).setMaxWidth(55);
        tracesTable.getColumnModel().getColumn(6).setPreferredWidth(40);
        tracesTable.getColumnModel().getColumn(6).setMaxWidth(55);
        tracesTable.getColumnModel().getColumn(7).setPreferredWidth(132);
        tracesTable.getColumnModel().getColumn(7).setMaxWidth(174);
        /* Force Descriptor column left-aligned (AlignedCellRenderer right-aligns numbers) */
        javax.swing.table.DefaultTableCellRenderer leftRenderer = new javax.swing.table.DefaultTableCellRenderer();
        leftRenderer.setHorizontalAlignment(SwingConstants.LEFT);
        tracesTable.getColumnModel().getColumn(3).setCellRenderer(leftRenderer);
        /* Double-click opens trace tree dialog */
        tracesTable.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = tracesTable.getSelectedRow();
                    if (row >= 0) {
                        row = tracesTable.convertRowIndexToModel(row);
                        openTraceDialog(tracesModel.getEventAt(row));
                    }
                }
            }
        });
        subTabs.addTab("Traces", new JScrollPane(tracesTable));

        /* Running: in-progress traces, oldest first */
        runningModel = new RunningTableModel();
        JTable runningTable = new JTable(runningModel);
        runningTable.setDefaultRenderer(Object.class, new AlignedCellRenderer());
        runningTable.setAutoCreateRowSorter(true);
        runningTable.setRowHeight(20);
        /* Column widths: Time=90, Type=90, Elapsed=80, Descriptor=rest, Thread=120 */
        runningTable.getColumnModel().getColumn(0).setPreferredWidth(90);
        runningTable.getColumnModel().getColumn(0).setMaxWidth(110);
        runningTable.getColumnModel().getColumn(1).setPreferredWidth(90);
        runningTable.getColumnModel().getColumn(1).setMaxWidth(120);
        runningTable.getColumnModel().getColumn(2).setPreferredWidth(90);
        runningTable.getColumnModel().getColumn(2).setMaxWidth(110);
        runningTable.getColumnModel().getColumn(3).setPreferredWidth(500);
        runningTable.getColumnModel().getColumn(4).setPreferredWidth(132);
        runningTable.getColumnModel().getColumn(4).setMaxWidth(174);
        javax.swing.table.DefaultTableCellRenderer leftRenderer2 = new javax.swing.table.DefaultTableCellRenderer();
        leftRenderer2.setHorizontalAlignment(SwingConstants.LEFT);
        runningTable.getColumnModel().getColumn(3).setCellRenderer(leftRenderer2);
        subTabs.addTab("Running", new JScrollPane(runningTable));

        /* Method Profiler: aggregated by method, sorted by total time */
        profilerModel = new ProfilerTableModel();
        JTable profilerTable = new JTable(profilerModel);
        profilerTable.setDefaultRenderer(Object.class, new ProfilerCellRenderer());
        profilerTable.setAutoCreateRowSorter(true);
        profilerTable.setRowHeight(18);
        CsvExporter.install(profilerTable);
        /* Column widths: #=35, Descriptor=fills, Total=80, Calls=50, Avg=80, Max=80, Alloc=65, Exc=40, SQL=40 */
        profilerTable.getColumnModel().getColumn(0).setPreferredWidth(35);
        profilerTable.getColumnModel().getColumn(0).setMaxWidth(45);
        profilerTable.getColumnModel().getColumn(1).setPreferredWidth(280);
        profilerTable.getColumnModel().getColumn(2).setPreferredWidth(80);
        profilerTable.getColumnModel().getColumn(2).setMaxWidth(100);
        profilerTable.getColumnModel().getColumn(3).setPreferredWidth(50);
        profilerTable.getColumnModel().getColumn(3).setMaxWidth(65);
        profilerTable.getColumnModel().getColumn(4).setPreferredWidth(80);
        profilerTable.getColumnModel().getColumn(4).setMaxWidth(100);
        profilerTable.getColumnModel().getColumn(5).setPreferredWidth(80);
        profilerTable.getColumnModel().getColumn(5).setMaxWidth(100);
        profilerTable.getColumnModel().getColumn(6).setPreferredWidth(65);
        profilerTable.getColumnModel().getColumn(6).setMaxWidth(80);
        profilerTable.getColumnModel().getColumn(7).setPreferredWidth(40);
        profilerTable.getColumnModel().getColumn(7).setMaxWidth(55);
        profilerTable.getColumnModel().getColumn(8).setPreferredWidth(40);
        profilerTable.getColumnModel().getColumn(8).setMaxWidth(55);
        /* Descriptor left-aligned */
        javax.swing.table.DefaultTableCellRenderer profLeftR = new javax.swing.table.DefaultTableCellRenderer();
        profLeftR.setHorizontalAlignment(SwingConstants.LEFT);
        profilerTable.getColumnModel().getColumn(1).setCellRenderer(profLeftR);
        subTabs.addTab("Method Profiler", new JScrollPane(profilerTable));


        /* JDBC Monitor with 2 sub-tabs */
        JTabbedPane jdbcTabs = new JTabbedPane();

        /* Sub-tab 1: SQL Aggregate (grouped by SQL command) */
        sqlAggModel = new SqlAggregateModel();
        JTable sqlAggTable = new JTable(sqlAggModel);
        sqlAggTable.setDefaultRenderer(Object.class, new JdbcCellRenderer());
        sqlAggTable.setAutoCreateRowSorter(true);
        sqlAggTable.setRowHeight(18);
        CsvExporter.install(sqlAggTable);
        /* SQL Statistics cols: #=35, SQL Command=fills, Total Duration=85, Executions=65, Avg=80, Min=80, Max=80 */
        sqlAggTable.getColumnModel().getColumn(0).setPreferredWidth(35); sqlAggTable.getColumnModel().getColumn(0).setMaxWidth(45);
        sqlAggTable.getColumnModel().getColumn(1).setPreferredWidth(350);
        sqlAggTable.getColumnModel().getColumn(2).setPreferredWidth(85); sqlAggTable.getColumnModel().getColumn(2).setMaxWidth(105);
        sqlAggTable.getColumnModel().getColumn(3).setPreferredWidth(65); sqlAggTable.getColumnModel().getColumn(3).setMaxWidth(80);
        sqlAggTable.getColumnModel().getColumn(4).setPreferredWidth(80); sqlAggTable.getColumnModel().getColumn(4).setMaxWidth(100);
        sqlAggTable.getColumnModel().getColumn(5).setPreferredWidth(80); sqlAggTable.getColumnModel().getColumn(5).setMaxWidth(100);
        sqlAggTable.getColumnModel().getColumn(6).setPreferredWidth(80); sqlAggTable.getColumnModel().getColumn(6).setMaxWidth(100);
        jdbcTabs.addTab("SQL Statistics", new JScrollPane(sqlAggTable));

        /* Sub-tab 2: SQL Events (last 100) */
        jdbcModel = new JdbcTableModel();
        JTable jdbcTable = new JTable(jdbcModel);
        jdbcTable.setDefaultRenderer(Object.class, new JdbcCellRenderer());
        jdbcTable.setAutoCreateRowSorter(true);
        jdbcTable.setRowHeight(18);
        CsvExporter.install(jdbcTable);
        /* SQL Events cols: Time=90, Type=70, Duration=80, SQL/Context=fills, Thread=110 */
        jdbcTable.getColumnModel().getColumn(0).setPreferredWidth(90); jdbcTable.getColumnModel().getColumn(0).setMaxWidth(110);
        jdbcTable.getColumnModel().getColumn(1).setPreferredWidth(70); jdbcTable.getColumnModel().getColumn(1).setMaxWidth(90);
        jdbcTable.getColumnModel().getColumn(2).setPreferredWidth(80); jdbcTable.getColumnModel().getColumn(2).setMaxWidth(100);
        jdbcTable.getColumnModel().getColumn(3).setPreferredWidth(400);
        jdbcTable.getColumnModel().getColumn(4).setPreferredWidth(132); jdbcTable.getColumnModel().getColumn(4).setMaxWidth(174);
        jdbcTabs.addTab("SQL Events", new JScrollPane(jdbcTable));

        /* Sub-tab 3: Connection Monitor (open/unclosed connections) */
        connMonModel = new ConnectionMonitorModel();
        JTable connMonTable = new JTable(connMonModel);
        connMonTable.setDefaultRenderer(Object.class, new ConnMonCellRenderer());
        connMonTable.setAutoCreateRowSorter(true);
        connMonTable.setRowHeight(18);
        CsvExporter.install(connMonTable);
        /* Connection Monitor cols: ConnID=80, OpenedAt=90, Duration=80, Class=180, URL=fills, Thread=110 */
        connMonTable.getColumnModel().getColumn(0).setPreferredWidth(80); connMonTable.getColumnModel().getColumn(0).setMaxWidth(100);
        connMonTable.getColumnModel().getColumn(1).setPreferredWidth(90); connMonTable.getColumnModel().getColumn(1).setMaxWidth(110);
        connMonTable.getColumnModel().getColumn(2).setPreferredWidth(80); connMonTable.getColumnModel().getColumn(2).setMaxWidth(100);
        connMonTable.getColumnModel().getColumn(3).setPreferredWidth(180);
        connMonTable.getColumnModel().getColumn(4).setPreferredWidth(250);
        connMonTable.getColumnModel().getColumn(5).setPreferredWidth(132); connMonTable.getColumnModel().getColumn(5).setMaxWidth(174);
        jdbcTabs.addTab("Connection Monitor", new JScrollPane(connMonTable));

        subTabs.addTab("JDBC Monitor", jdbcTabs);

        /* HTTP Request Profiler */
        httpModel = new HttpProfilerModel();
        JTable httpTable = new JTable(httpModel);
        httpTable.setDefaultRenderer(Object.class, new HttpCellRenderer());
        httpTable.setAutoCreateRowSorter(true);
        httpTable.setRowHeight(18);
        CsvExporter.install(httpTable);
        /* HTTP cols: #=35, URL=fills, Requests=60, Total/Avg/P50/P95/P99/Max=70, Errors=50 */
        httpTable.getColumnModel().getColumn(0).setPreferredWidth(35); httpTable.getColumnModel().getColumn(0).setMaxWidth(45);
        httpTable.getColumnModel().getColumn(1).setPreferredWidth(280);
        httpTable.getColumnModel().getColumn(2).setPreferredWidth(60); httpTable.getColumnModel().getColumn(2).setMaxWidth(75);
        for (int hc = 3; hc <= 8; hc++) {
            httpTable.getColumnModel().getColumn(hc).setPreferredWidth(70);
            httpTable.getColumnModel().getColumn(hc).setMaxWidth(90);
        }
        httpTable.getColumnModel().getColumn(9).setPreferredWidth(50); httpTable.getColumnModel().getColumn(9).setMaxWidth(65);
        subTabs.addTab("HTTP Profiler", new JScrollPane(httpTable));

        /* Disk I/O Monitor */
        diskIoModel = new DiskIoModel();
        JTable diskIoTable = new JTable(diskIoModel);
        diskIoTable.setDefaultRenderer(Object.class, new AlignedCellRenderer());
        diskIoTable.setAutoCreateRowSorter(true);
        diskIoTable.setRowHeight(18);
        CsvExporter.install(diskIoTable);
        /* Disk I/O: first col (File/Op) wide, rest compact */
        diskIoTable.getColumnModel().getColumn(0).setPreferredWidth(300);
        for (int dc = 1; dc < diskIoTable.getColumnCount(); dc++) {
            diskIoTable.getColumnModel().getColumn(dc).setPreferredWidth(80);
            diskIoTable.getColumnModel().getColumn(dc).setMaxWidth(110);
        }
        subTabs.addTab("Disk I/O", new JScrollPane(diskIoTable));

        /* Socket I/O Monitor */
        socketIoModel = new SocketIoModel();
        JTable socketIoTable = new JTable(socketIoModel);
        socketIoTable.setDefaultRenderer(Object.class, new AlignedCellRenderer());
        socketIoTable.setAutoCreateRowSorter(true);
        socketIoTable.setRowHeight(18);
        CsvExporter.install(socketIoTable);
        /* Socket I/O: first col (Address/Op) wide, rest compact */
        socketIoTable.getColumnModel().getColumn(0).setPreferredWidth(300);
        for (int sc = 1; sc < socketIoTable.getColumnCount(); sc++) {
            socketIoTable.getColumnModel().getColumn(sc).setPreferredWidth(80);
            socketIoTable.getColumnModel().getColumn(sc).setMaxWidth(110);
        }
        subTabs.addTab("Socket I/O", new JScrollPane(socketIoTable));

        /* All events (raw) */
        traceEventModel = new TraceEventTableModel();
        JTable eventsTable = new JTable(traceEventModel);
        eventsTable.setDefaultRenderer(Object.class, new AlignedCellRenderer());
        eventsTable.setAutoCreateRowSorter(true);
        eventsTable.setRowHeight(18);
        CsvExporter.install(eventsTable);
        /* Events: Time=90, Type=65, Duration=80, Class.Method=200, Context=fills, Thread=100, Params=80, Return=80, Depth=40 */
        eventsTable.getColumnModel().getColumn(0).setPreferredWidth(90); eventsTable.getColumnModel().getColumn(0).setMaxWidth(110);
        eventsTable.getColumnModel().getColumn(1).setPreferredWidth(65); eventsTable.getColumnModel().getColumn(1).setMaxWidth(85);
        eventsTable.getColumnModel().getColumn(2).setPreferredWidth(80); eventsTable.getColumnModel().getColumn(2).setMaxWidth(100);
        eventsTable.getColumnModel().getColumn(3).setPreferredWidth(200);
        eventsTable.getColumnModel().getColumn(4).setPreferredWidth(132); eventsTable.getColumnModel().getColumn(4).setMaxWidth(174);
        eventsTable.getColumnModel().getColumn(5).setPreferredWidth(250);
        eventsTable.getColumnModel().getColumn(6).setPreferredWidth(80); eventsTable.getColumnModel().getColumn(6).setMaxWidth(120);
        eventsTable.getColumnModel().getColumn(7).setPreferredWidth(80); eventsTable.getColumnModel().getColumn(7).setMaxWidth(120);
        eventsTable.getColumnModel().getColumn(8).setPreferredWidth(40); eventsTable.getColumnModel().getColumn(8).setMaxWidth(55);
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
                if (schedulingProbe.isSelected()) probes.add("scheduling");
                if (jmsOpsProbe.isSelected()) probes.add("jms_ops");
                boolean captureParams = captureParamsCheck.isSelected();
                int maxLen = 500;
                try { maxLen = Integer.parseInt(maxValueLengthField.getText().trim()); }
                catch (NumberFormatException nfe) { /* use default */ }
                conn.sendInstrumentationConfig(pkgs,
                        probes.toArray(new String[probes.size()]),
                        captureParams, maxLen);
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

    public void updateData() {
        if (recording) {
            long now = System.currentTimeMillis();
            List events = collector.getStore()
                    .getInstrumentationEvents(recordStartTs, now);
            analyzeEvents(events);
            tracesModel.update(events);
            runningModel.update(events);
            double elapsed = (now - recordStartTs) / 1000.0;
            statusLabel.setText(String.format("RECORDING... %d events (%.0fs)", events.size(), elapsed));
        }
    }

    public void render() {
        repaint();
    }

    public void refresh() {
        updateData();
        render();
    }

    private void analyzeEvents(List<InstrumentationEvent> events) {
        /* Method profiler: aggregate by descriptor */
        Map<String, long[]> methodStats = new LinkedHashMap<String, long[]>();
        /* key -> {totalDuration, count, maxDuration, exceptionCount, sqlCount} */

        /* JDBC events */
        List<InstrumentationEvent> jdbcEvents = new ArrayList<InstrumentationEvent>();

        /* Trace tree: group by root traceId */
        Map<Long, List<InstrumentationEvent>> traces = new LinkedHashMap<Long, List<InstrumentationEvent>>();

        for (int i = 0; i < events.size(); i++) {
            InstrumentationEvent e = events.get(i);

            /* Profiler aggregation (only exit events with duration) */
            if (e.getDurationNanos() > 0) {
                String key = getTraceDescriptor(e);
                long[] stats = methodStats.get(key);
                if (stats == null) { stats = new long[6]; methodStats.put(key, stats); }
                stats[0] += e.getDurationNanos();
                stats[1]++;
                if (e.getDurationNanos() > stats[2]) stats[2] = e.getDurationNanos();
                if (e.isException()) stats[3]++;
                if (e.getEventType() == InstrumentationEvent.TYPE_JDBC_QUERY) stats[4]++;
                stats[5] += e.getAllocatedBytes();
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

        /* Update raw events table */
        int showStart = Math.max(0, events.size() - 100);
        traceEventModel.setData(events.subList(showStart, events.size()));
    }


    /* ── Profiler Table ──────────────────────────────── */

    private static class ProfilerTableModel extends AbstractTableModel {
        private final String[] COLS = {"#", "Descriptor", "Total Time", "Calls", "Avg", "Max", "Alloc (MB)", "Exc", "SQL"};
        private List data = new ArrayList();

        public void setData(List data) {
            this.data = data;
            fireTableDataChanged();
        }

        public int getRowCount() { return data.size(); }
        public int getColumnCount() { return COLS.length; }
        public String getColumnName(int c) { return COLS[c]; }

        public Object getValueAt(int row, int col) {
            if (row < 0 || row >= data.size()) return "";
            java.util.Map.Entry e = (java.util.Map.Entry) data.get(row);
            long[] s = (long[]) e.getValue();
            switch (col) {
                case 0: return Integer.valueOf(row + 1);
                case 1: return e.getKey();
                case 2: return String.format("%.1f ms", s[0] / 1000000.0);
                case 3: return Long.valueOf(s[1]);
                case 4: return String.format("%.1f ms", s[1] > 0 ? s[0] / s[1] / 1000000.0 : 0);
                case 5: return String.format("%.1f ms", s[2] / 1000000.0);
                case 6: return s.length > 5 && s[5] > 0 ? String.format("%.2f", s[5] / (1024.0 * 1024.0)) : "";
                case 7: return s[3] > 0 ? Long.valueOf(s[3]) : "";
                case 8: return s.length > 4 && s[4] > 0 ? Long.valueOf(s[4]) : "";
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
            } else if (col == 7 && value instanceof Long) {
                c.setForeground(Color.RED);
                c.setFont(c.getFont().deriveFont(Font.BOLD));
            } else if (col == 8 && value instanceof Long) {
                c.setForeground(new Color(0, 100, 180));
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
            int idx = data.size() - 1 - row;
            if (idx < 0 || idx >= data.size()) return "";
            InstrumentationEvent e = data.get(idx);
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
        private final String[] COLS = {"Time", "Type", "Thread", "Class.Method", "Duration", "Context", "Params", "Return", "Depth"};
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
            int idx = data.size() - 1 - row;
            if (idx < 0 || idx >= data.size()) return "";
            InstrumentationEvent e = data.get(idx);
            switch (col) {
                case 0: return sdf.format(new Date(e.getTimestamp()));
                case 1: return e.getEventTypeName();
                case 2: return e.getThreadName();
                case 3: return e.getFullMethodName();
                case 4: return e.getDurationNanos() > 0 ? String.format("%.1f ms", e.getDurationMs()) : "";
                case 5: return e.getContext();
                case 6: return e.hasParams() ? e.getParamsJson() : "";
                case 7: return e.hasReturnValue() ? e.getReturnValueJson() : "";
                case 8: return Integer.valueOf(e.getDepth());
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
        public Object getValueAt(int row, int col) {
            if (row < 0 || row >= data.size()) return "";
            Object[] r = (Object[]) data.get(row);
            return (col >= 0 && col < r.length) ? r[col] : "";
        }
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
        public Object getValueAt(int row, int col) {
            if (row < 0 || row >= data.size()) return "";
            Object[] r = (Object[]) data.get(row);
            return (col >= 0 && col < r.length) ? r[col] : "";
        }
    }

    /* ── Trace analysis dialog (5 tabs) ── */

    private List allCurrentEvents = new ArrayList();

    /** Node wrapper: event + computed inner time. */
    private static class TraceNode {
        final InstrumentationEvent event;
        double innerMs;
        boolean expanded = true;
        final List children = new ArrayList();
        TraceNode(InstrumentationEvent e) { this.event = e; this.innerMs = e.getDurationMs(); }
    }

    private static void flattenTree(TraceNode node, List out) {
        out.add(node);
        if (node.expanded) {
            for (int i = 0; i < node.children.size(); i++) {
                flattenTree((TraceNode) node.children.get(i), out);
            }
        }
    }

    private void openTraceDialog(InstrumentationEvent root) {
        if (root == null) return;
        List traceEvents = collectTraceEvents(root);
        long rootStartTs = root.getTimestamp() - (long)(root.getDurationMs());

        /* Build TraceNode tree with inner time */
        TraceNode rootNode = new TraceNode(root);
        java.util.Map nodeMap = new java.util.HashMap();
        nodeMap.put(new Long(root.getTraceId()), rootNode);
        java.util.Collections.sort(traceEvents, new java.util.Comparator() {
            public int compare(Object a, Object b) {
                InstrumentationEvent ea = (InstrumentationEvent) a;
                InstrumentationEvent eb = (InstrumentationEvent) b;
                int dd = ea.getDepth() - eb.getDepth();
                return dd != 0 ? dd : (ea.getTimestamp() < eb.getTimestamp() ? -1 : 1);
            }
        });
        for (int i = 0; i < traceEvents.size(); i++) {
            InstrumentationEvent e = (InstrumentationEvent) traceEvents.get(i);
            if (e.getTraceId() == root.getTraceId()) continue;
            TraceNode tn = new TraceNode(e);
            nodeMap.put(new Long(e.getTraceId()), tn);
            TraceNode pn = (TraceNode) nodeMap.get(new Long(e.getParentTraceId()));
            if (pn != null) { pn.children.add(tn); pn.innerMs -= e.getDurationMs(); }
            else { rootNode.children.add(tn); rootNode.innerMs -= e.getDurationMs(); }
        }
        if (rootNode.innerMs < 0) rootNode.innerMs = 0;

        /* Tab 1: Call Tree as real TreeTable with columns */
        final List treeRows = new ArrayList(); /* flat List<TraceNode> */
        flattenTree(rootNode, treeRows);
        final double rootDur = root.getDurationMs() > 0 ? root.getDurationMs() : 1;
        final long traceStartTs = rootStartTs;

        final javax.swing.table.AbstractTableModel treeModel = new javax.swing.table.AbstractTableModel() {
            private final String[] C = {"Descriptor", "Type", "Offset", "Inner", "Duration", "CPU", "Alloc (MB)", "Blocked", "Waited", "%"};
            public int getRowCount() { return treeRows.size(); }
            public int getColumnCount() { return C.length; }
            public String getColumnName(int c) { return C[c]; }
            public Object getValueAt(int row, int col) {
                if (row < 0 || row >= treeRows.size()) return "";
                TraceNode n = (TraceNode) treeRows.get(row);
                switch (col) {
                    case 0: return n;
                    case 1: return getDisplayType(n.event);
                    case 2: return "+" + (n.event.getTimestamp() - traceStartTs) + " ms";
                    case 3: return String.format("%.3f", Math.max(n.innerMs, 0));
                    case 4: return String.format("%.3f", n.event.getDurationMs());
                    case 5: return n.event.getCpuTimeNs() > 0 ? String.format("%.3f", n.event.getCpuTimeMs()) : "";
                    case 6: return n.event.getAllocatedBytes() > 0 ? String.format("%.2f", n.event.getAllocatedMB()) : "";
                    case 7: return n.event.getBlockedTimeMs() > 0 ? n.event.getBlockedTimeMs() + " ms" : "";
                    case 8: return n.event.getWaitedTimeMs() > 0 ? n.event.getWaitedTimeMs() + " ms" : "";
                    case 9: return String.format("%.1f%%", n.event.getDurationMs() / rootDur * 100);
                    default: return "";
                }
            }
        };

        final JTable treeTable = new JTable(treeModel);
        treeTable.setRowHeight(22);
        /* Cols: Descriptor=fills, Type=60, Offset=60, Inner=60, Duration=60, CPU=60, Alloc=60, Blocked=55, Waited=55, %=75 */
        treeTable.getColumnModel().getColumn(0).setPreferredWidth(250);
        for (int tc = 1; tc <= 6; tc++) {
            treeTable.getColumnModel().getColumn(tc).setPreferredWidth(60);
            treeTable.getColumnModel().getColumn(tc).setMaxWidth(78);
        }
        treeTable.getColumnModel().getColumn(7).setPreferredWidth(55);
        treeTable.getColumnModel().getColumn(7).setMaxWidth(70);
        treeTable.getColumnModel().getColumn(8).setPreferredWidth(55);
        treeTable.getColumnModel().getColumn(8).setMaxWidth(70);
        treeTable.getColumnModel().getColumn(9).setPreferredWidth(75);
        treeTable.getColumnModel().getColumn(9).setMaxWidth(110);

        /* Descriptor column: indented with expand/collapse icons, left-aligned */
        treeTable.getColumnModel().getColumn(0).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            public Component getTableCellRendererComponent(JTable t, Object value,
                    boolean isSel, boolean hasFocus, int row, int col) {
                if (value instanceof TraceNode) {
                    TraceNode n = (TraceNode) value;
                    StringBuilder sb = new StringBuilder();
                    for (int d = 0; d < n.event.getDepth(); d++) sb.append("   ");
                    if (!n.children.isEmpty()) sb.append(n.expanded ? "\u25BC " : "\u25B6 ");
                    else sb.append("  ");
                    sb.append(getTraceDescriptor(n.event));
                    Component c = super.getTableCellRendererComponent(t, sb.toString(), isSel, hasFocus, row, col);
                    if (!isSel) {
                        if (n.event.isException()) { c.setForeground(Color.RED); c.setFont(c.getFont().deriveFont(Font.BOLD)); }
                        else if (n.event.getEventType() == InstrumentationEvent.TYPE_JDBC_QUERY) c.setForeground(new Color(0, 100, 180));
                        else if (n.event.getDepth() == 0) c.setFont(c.getFont().deriveFont(Font.BOLD));
                    }
                    setHorizontalAlignment(SwingConstants.LEFT);
                    return c;
                }
                return super.getTableCellRendererComponent(t, value, isSel, hasFocus, row, col);
            }
        });
        /* Numeric columns right-aligned */
        javax.swing.table.DefaultTableCellRenderer rAlign = new javax.swing.table.DefaultTableCellRenderer();
        rAlign.setHorizontalAlignment(SwingConstants.RIGHT);
        for (int ci = 2; ci <= 8; ci++) treeTable.getColumnModel().getColumn(ci).setCellRenderer(rAlign);

        /* % column: filled bar renderer */
        treeTable.getColumnModel().getColumn(9).setCellRenderer(new javax.swing.table.TableCellRenderer() {
            public Component getTableCellRendererComponent(JTable t, Object value,
                    boolean isSel, boolean hasFocus, int row, int col) {
                double pct = 0;
                if (value != null) {
                    try {
                        String s = value.toString().replace("%", "").trim();
                        pct = Double.parseDouble(s);
                    } catch (NumberFormatException e) { /* ignore */ }
                }
                final double fpct = pct;
                final boolean fsel = isSel;
                return new JPanel() {
                    protected void paintComponent(Graphics g) {
                        super.paintComponent(g);
                        int w = getWidth(), h = getHeight();
                        /* Background */
                        g.setColor(fsel ? javax.swing.UIManager.getColor("Table.selectionBackground") : Color.WHITE);
                        g.fillRect(0, 0, w, h);
                        /* Bar */
                        int barW = (int)(fpct / 100.0 * (w - 4));
                        if (barW < 1 && fpct > 0) barW = 1;
                        Color barColor = fpct > 50 ? new Color(220, 60, 60) :
                                         fpct > 20 ? new Color(220, 160, 30) :
                                                     new Color(60, 150, 60);
                        g.setColor(new Color(barColor.getRed(), barColor.getGreen(), barColor.getBlue(), 140));
                        g.fillRect(2, 3, barW, h - 6);
                        /* Border */
                        g.setColor(new Color(180, 180, 180));
                        g.drawRect(2, 3, w - 5, h - 7);
                        /* Text */
                        g.setColor(fsel ? Color.WHITE : Color.BLACK);
                        g.setFont(g.getFont().deriveFont(10f));
                        String lbl = String.format("%.1f%%", fpct);
                        java.awt.FontMetrics fm = g.getFontMetrics();
                        g.drawString(lbl, (w - fm.stringWidth(lbl)) / 2, h / 2 + fm.getAscent() / 2 - 1);
                    }
                };
            }
        });

        /* Click col 0 to expand/collapse */
        treeTable.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent me) {
                int row = treeTable.rowAtPoint(me.getPoint());
                int col = treeTable.columnAtPoint(me.getPoint());
                if (row >= 0 && col == 0 && row < treeRows.size()) {
                    TraceNode n = (TraceNode) treeRows.get(row);
                    if (!n.children.isEmpty()) {
                        n.expanded = !n.expanded;
                        treeRows.clear();
                        flattenTree(rootNode, treeRows);
                        treeModel.fireTableDataChanged();
                    }
                }
            }
        });

        /* Tab 2: Aggregation */
        java.util.Map aggMap = new java.util.LinkedHashMap();
        aggregateNodes(rootNode, aggMap);
        List aggRows = new ArrayList(aggMap.entrySet());
        java.util.Collections.sort(aggRows, new java.util.Comparator() {
            public int compare(Object a, Object b) {
                double[] va = (double[]) ((java.util.Map.Entry) a).getValue();
                double[] vb = (double[]) ((java.util.Map.Entry) b).getValue();
                return vb[1] > va[1] ? 1 : (vb[1] < va[1] ? -1 : 0);
            }
        });
        String[][] aggData = new String[aggRows.size()][5];
        for (int i = 0; i < aggRows.size(); i++) {
            java.util.Map.Entry entry = (java.util.Map.Entry) aggRows.get(i);
            double[] v = (double[]) entry.getValue();
            aggData[i] = new String[]{(String)entry.getKey(), String.valueOf((int)v[0]),
                String.format("%.3f",v[1]), String.format("%.3f",v[2]),
                String.format("%.3f",v[0]>0?v[1]/v[0]:0)};
        }
        JTable aggTable = new JTable(aggData, new String[]{"Method","Count","Inner (ms)","Total (ms)","Avg Inner"});
        aggTable.setDefaultRenderer(Object.class, new AlignedCellRenderer());
        aggTable.setRowHeight(18); aggTable.getColumnModel().getColumn(0).setPreferredWidth(350);

        /* Tab 3: SQL */
        List sqlList = new ArrayList();
        for (int i = 0; i < traceEvents.size(); i++) {
            InstrumentationEvent e = (InstrumentationEvent) traceEvents.get(i);
            if (e.getEventType() == InstrumentationEvent.TYPE_JDBC_QUERY) sqlList.add(e);
        }
        java.util.Collections.sort(sqlList, new java.util.Comparator() {
            public int compare(Object a, Object b) {
                return ((InstrumentationEvent)a).getTimestamp()<((InstrumentationEvent)b).getTimestamp()?-1:1;
            }
        });
        String[][] sqlData = new String[sqlList.size()][4];
        for (int i = 0; i < sqlList.size(); i++) {
            InstrumentationEvent e = (InstrumentationEvent) sqlList.get(i);
            sqlData[i] = new String[]{"+"+(e.getTimestamp()-rootStartTs)+" ms",
                String.format("%.3f",e.getDurationMs()), e.getContext()!=null?e.getContext():"",
                e.isException()?"ERROR":"OK"};
        }
        JTable sqlTable = new JTable(sqlData, new String[]{"Offset","Duration (ms)","SQL","Status"});
        sqlTable.setDefaultRenderer(Object.class, new AlignedCellRenderer());
        sqlTable.setRowHeight(18);
        sqlTable.getColumnModel().getColumn(0).setPreferredWidth(80); sqlTable.getColumnModel().getColumn(0).setMaxWidth(100);
        sqlTable.getColumnModel().getColumn(1).setPreferredWidth(80); sqlTable.getColumnModel().getColumn(1).setMaxWidth(100);
        sqlTable.getColumnModel().getColumn(2).setPreferredWidth(400);
        sqlTable.getColumnModel().getColumn(3).setPreferredWidth(55); sqlTable.getColumnModel().getColumn(3).setMaxWidth(70);

        /* Tab 4: Exceptions */
        List excList = new ArrayList();
        for (int i = 0; i < traceEvents.size(); i++) {
            InstrumentationEvent e = (InstrumentationEvent) traceEvents.get(i);
            if (e.isException()) excList.add(e);
        }
        String[][] excData = new String[excList.size()][4];
        for (int i = 0; i < excList.size(); i++) {
            InstrumentationEvent e = (InstrumentationEvent) excList.get(i);
            excData[i] = new String[]{"+"+(e.getTimestamp()-rootStartTs)+" ms",
                e.getDisplayClassName()+"."+e.getMethodName(), e.getContext()!=null?e.getContext():"",
                String.format("%.3f",e.getDurationMs())};
        }
        JTable excTable = new JTable(excData, new String[]{"Offset","Location","Context","Duration (ms)"});
        excTable.setDefaultRenderer(Object.class, new AlignedCellRenderer()); excTable.setRowHeight(18);

        /* Tab 5: HTTP (sub-tabs: Parameters, Headers) */
        JTabbedPane httpTabs = new JTabbedPane();
        JTextArea paramsArea = new JTextArea(); paramsArea.setEditable(false);
        paramsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        StringBuilder pSb = new StringBuilder();
        for (int i = 0; i < traceEvents.size(); i++) {
            InstrumentationEvent e = (InstrumentationEvent) traceEvents.get(i);
            if (e.hasParams()) pSb.append(e.getDisplayClassName()).append(".").append(e.getMethodName())
                .append(":\n  ").append(e.getParamsJson()).append("\n\n");
        }
        if (pSb.length()==0) pSb.append("No parameters captured.\nEnable 'Capture params & return values'.");
        paramsArea.setText(pSb.toString()); paramsArea.setCaretPosition(0);
        httpTabs.addTab("Parameters", new JScrollPane(paramsArea));
        JTextArea headersArea = new JTextArea(); headersArea.setEditable(false);
        headersArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        StringBuilder hSb = new StringBuilder();
        for (int i = 0; i < traceEvents.size(); i++) {
            InstrumentationEvent e = (InstrumentationEvent) traceEvents.get(i);
            int t = e.getEventType();
            if (t==InstrumentationEvent.TYPE_HTTP_REQUEST || t==InstrumentationEvent.TYPE_HTTP_RESPONSE) {
                hSb.append(e.getEventTypeName()).append(": ").append(e.getContext()).append("\n");
                if (e.hasReturnValue()) hSb.append("  Response: ").append(e.getReturnValueJson()).append("\n");
            }
        }
        if (hSb.length()==0) hSb.append("No HTTP events in this trace.");
        headersArea.setText(hSb.toString()); headersArea.setCaretPosition(0);
        httpTabs.addTab("Headers", new JScrollPane(headersArea));

        /* Assemble */
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Call Tree", new JScrollPane(treeTable));
        tabs.addTab("Aggregation", new JScrollPane(aggTable));
        tabs.addTab("SQL ("+sqlList.size()+")", new JScrollPane(sqlTable));
        tabs.addTab("Exceptions ("+excList.size()+")", new JScrollPane(excTable));
        tabs.addTab("HTTP", httpTabs);
        /* Bottom: summary + export buttons */
        JPanel bottomPanel = new JPanel(new BorderLayout());
        JLabel sLbl = new JLabel(String.format("  %s  |  %.3f ms  |  %d events  |  %d SQL  |  %d exc  |  %s",
                getTraceDescriptor(root), root.getDurationMs(), traceEvents.size(),
                sqlList.size(), excList.size(), root.getThreadName()!=null?root.getThreadName():"?"));
        sLbl.setFont(sLbl.getFont().deriveFont(Font.BOLD));
        bottomPanel.add(sLbl, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 2));
        final List fTraceEvents = traceEvents;
        final InstrumentationEvent fRoot = root;
        final List fSqlList = sqlList;
        final List fExcList = excList;
        JButton jsonBtn = new JButton("Export JSON");
        jsonBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent ev) {
                JFileChooser fc = new JFileChooser();
                fc.setSelectedFile(new java.io.File("trace-" + fRoot.getTraceId() + ".json"));
                if (fc.showSaveDialog(InstrumentationPanel.this) == JFileChooser.APPROVE_OPTION) {
                    exportTraceJson(fc.getSelectedFile(), fRoot, fTraceEvents);
                }
            }
        });
        JButton reportBtn = new JButton("Save Report");
        reportBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent ev) {
                JFileChooser fc = new JFileChooser();
                fc.setSelectedFile(new java.io.File("trace-" + fRoot.getTraceId() + ".txt"));
                if (fc.showSaveDialog(InstrumentationPanel.this) == JFileChooser.APPROVE_OPTION) {
                    exportTraceReport(fc.getSelectedFile(), fRoot, fTraceEvents, fSqlList, fExcList);
                }
            }
        });
        btnPanel.add(jsonBtn);
        btnPanel.add(reportBtn);
        bottomPanel.add(btnPanel, BorderLayout.EAST);

        JDialog dlg = new JDialog((java.awt.Frame) SwingUtilities.getWindowAncestor(InstrumentationPanel.this),
                "Trace \u2014 "+getTraceDescriptor(root), false);
        dlg.setLayout(new BorderLayout());
        dlg.add(tabs, BorderLayout.CENTER);
        dlg.add(bottomPanel, BorderLayout.SOUTH);
        dlg.setSize(950, 620); dlg.setLocationRelativeTo(InstrumentationPanel.this); dlg.setVisible(true);
    }

    private List collectTraceEvents(InstrumentationEvent root) {
        java.util.Set ids = new java.util.HashSet();
        ids.add(new Long(root.getTraceId()));
        boolean found = true;
        while (found) {
            found = false;
            for (int i = 0; i < allCurrentEvents.size(); i++) {
                InstrumentationEvent e = (InstrumentationEvent) allCurrentEvents.get(i);
                Long tid = new Long(e.getTraceId()); Long pid = new Long(e.getParentTraceId());
                if (ids.contains(tid)||ids.contains(pid)) { if (ids.add(tid)) found = true; }
            }
        }
        List r = new ArrayList();
        for (int i = 0; i < allCurrentEvents.size(); i++) {
            InstrumentationEvent e = (InstrumentationEvent) allCurrentEvents.get(i);
            if (ids.contains(new Long(e.getTraceId()))) r.add(e);
        }
        return r;
    }

    private javax.swing.tree.DefaultMutableTreeNode buildJTreeNode(TraceNode tn) {
        String lbl = String.format("%.3f ms (inner: %.3f ms)  %s  %s%s",
                tn.event.getDurationMs(), Math.max(tn.innerMs, 0),
                getDisplayType(tn.event), getTraceDescriptor(tn.event),
                tn.event.isException() ? " [EXC]" : "");
        javax.swing.tree.DefaultMutableTreeNode n = new javax.swing.tree.DefaultMutableTreeNode(lbl);
        for (int i = 0; i < tn.children.size(); i++)
            n.add(buildJTreeNode((TraceNode) tn.children.get(i)));
        return n;
    }

    private void aggregateNodes(TraceNode tn, java.util.Map map) {
        String key = tn.event.getDisplayClassName() + "." + tn.event.getMethodName();
        double[] v = (double[]) map.get(key);
        if (v == null) { v = new double[3]; map.put(key, v); }
        v[0]++; v[1] += Math.max(tn.innerMs, 0); v[2] += tn.event.getDurationMs();
        for (int i = 0; i < tn.children.size(); i++)
            aggregateNodes((TraceNode) tn.children.get(i), map);
    }

    private void exportTraceJson(java.io.File file, InstrumentationEvent root, List events) {
        java.io.BufferedWriter w = null;
        try {
            w = new java.io.BufferedWriter(new java.io.FileWriter(file));
            w.write("{\n");
            w.write("  \"descriptor\": \"" + esc(getTraceDescriptor(root)) + "\",\n");
            w.write("  \"traceId\": " + root.getTraceId() + ",\n");
            w.write("  \"timestamp\": " + root.getTimestamp() + ",\n");
            w.write("  \"durationMs\": " + root.getDurationMs() + ",\n");
            w.write("  \"thread\": \"" + esc(root.getThreadName()) + "\",\n");
            w.write("  \"events\": [\n");
            for (int i = 0; i < events.size(); i++) {
                InstrumentationEvent e = (InstrumentationEvent) events.get(i);
                if (i > 0) w.write(",\n");
                w.write("    {\"traceId\":" + e.getTraceId()
                    + ",\"parentTraceId\":" + e.getParentTraceId()
                    + ",\"depth\":" + e.getDepth()
                    + ",\"type\":\"" + e.getEventTypeName() + "\""
                    + ",\"class\":\"" + esc(e.getClassName()) + "\""
                    + ",\"method\":\"" + esc(e.getMethodName()) + "\""
                    + ",\"durationMs\":" + e.getDurationMs()
                    + ",\"allocBytes\":" + e.getAllocatedBytes()
                    + ",\"blockedMs\":" + e.getBlockedTimeMs()
                    + ",\"waitedMs\":" + e.getWaitedTimeMs()
                    + ",\"isException\":" + e.isException()
                    + ",\"context\":\"" + esc(e.getContext()) + "\""
                    + "}");
            }
            w.write("\n  ]\n}\n");
            w.close(); w = null;
            JOptionPane.showMessageDialog(this, "Exported " + events.size() + " events to " + file.getName());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Export failed: " + ex.getMessage());
        } finally {
            if (w != null) { try { w.close(); } catch (Exception ignored) {} }
        }
    }

    private void exportTraceReport(java.io.File file, InstrumentationEvent root, List events, List sqlList, List excList) {
        java.io.BufferedWriter w = null;
        try {
            w = new java.io.BufferedWriter(new java.io.FileWriter(file));
            w.write("=== Trace Report ===\n\n");
            w.write("Descriptor: " + getTraceDescriptor(root) + "\n");
            w.write("Duration:   " + String.format("%.3f ms", root.getDurationMs()) + "\n");
            w.write("Thread:     " + (root.getThreadName() != null ? root.getThreadName() : "?") + "\n");
            w.write("Events:     " + events.size() + "\n");
            w.write("SQL:        " + sqlList.size() + "\n");
            w.write("Exceptions: " + excList.size() + "\n");
            w.write("TraceId:    " + root.getTraceId() + "\n\n");

            w.write("=== Call Tree ===\n\n");
            for (int i = 0; i < events.size(); i++) {
                InstrumentationEvent e = (InstrumentationEvent) events.get(i);
                StringBuilder indent = new StringBuilder();
                for (int d = 0; d < e.getDepth(); d++) indent.append("  ");
                w.write(indent.toString() + String.format("%.3f ms", e.getDurationMs())
                    + "  " + getDisplayType(e) + "  " + getTraceDescriptor(e)
                    + (e.isException() ? " [EXCEPTION]" : "")
                    + (e.getAllocatedBytes() > 0 ? String.format("  alloc=%.2f MB", e.getAllocatedMB()) : "")
                    + (e.getBlockedTimeMs() > 0 ? "  blocked=" + e.getBlockedTimeMs() + "ms" : "")
                    + (e.getWaitedTimeMs() > 0 ? "  waited=" + e.getWaitedTimeMs() + "ms" : "")
                    + "\n");
            }

            if (!sqlList.isEmpty()) {
                w.write("\n=== SQL Commands ===\n\n");
                long startTs = root.getTimestamp() - (long)(root.getDurationMs());
                for (int i = 0; i < sqlList.size(); i++) {
                    InstrumentationEvent e = (InstrumentationEvent) sqlList.get(i);
                    w.write("  +" + (e.getTimestamp() - startTs) + "ms  "
                        + String.format("%.3f ms", e.getDurationMs()) + "  "
                        + (e.getContext() != null ? e.getContext() : "") + "\n");
                }
            }

            if (!excList.isEmpty()) {
                w.write("\n=== Exceptions ===\n\n");
                for (int i = 0; i < excList.size(); i++) {
                    InstrumentationEvent e = (InstrumentationEvent) excList.get(i);
                    w.write("  " + e.getDisplayClassName() + "." + e.getMethodName()
                        + "  " + (e.getContext() != null ? e.getContext() : "") + "\n");
                }
            }

            w.close(); w = null;
            JOptionPane.showMessageDialog(this, "Report saved to " + file.getName());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage());
        } finally {
            if (w != null) { try { w.close(); } catch (Exception ignored) {} }
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /** Returns display type: URL when context is a URL/API path, otherwise the event type name. */
    static String getDisplayType(InstrumentationEvent e) {
        String ctx = e.getContext();
        int type = e.getEventType();
        if (type == InstrumentationEvent.TYPE_HTTP_REQUEST || type == InstrumentationEvent.TYPE_HTTP_RESPONSE) {
            return "URL";
        }
        if (ctx != null && (ctx.startsWith("/") || ctx.startsWith("GET ") || ctx.startsWith("POST ")
                || ctx.startsWith("PUT ") || ctx.startsWith("DELETE ") || ctx.startsWith("PATCH ")
                || ctx.startsWith("http"))) {
            return "URL";
        }
        return e.getEventTypeName();
    }

    static String getTraceDescriptor(InstrumentationEvent e) {
        String ctx = e.getContext();
        int type = e.getEventType();
        String cls = e.getDisplayClassName();
        String method = e.getMethodName();
        if (type == InstrumentationEvent.TYPE_HTTP_REQUEST || type == InstrumentationEvent.TYPE_HTTP_RESPONSE) {
            if (ctx != null && ctx.length() > 0) return ctx;
        }
        if (type == InstrumentationEvent.TYPE_JDBC_QUERY) {
            if (ctx != null && ctx.length() > 0) return ctx.length() > 80 ? ctx.substring(0, 80) + "..." : ctx;
        }
        if (ctx != null && ctx.startsWith("/")) return ctx;
        if (ctx != null && ctx.startsWith("http")) return ctx;
        if (method != null && (method.equals("execute") || method.equals("run")
                || method.equals("schedule") || method.contains("Scheduled"))) return cls;
        if (method != null && (method.equals("onMessage") || method.equals("receive") || method.equals("send")))
            return (type == InstrumentationEvent.TYPE_METHOD_EXIT ? "JMS " : "") + cls;
        if (ctx != null && ctx.length() > 0 && !ctx.equals("\"\""))
            return ctx.length() > 80 ? ctx.substring(0, 80) + "..." : ctx;
        return (cls != null ? cls : "?") + "." + (method != null ? method : "?");
    }

    /* ── Traces tab: last 200 completed traces, newest first ── */

    /** Row in Traces table — event + pre-computed child counts. */
    private static class TraceRow {
        final InstrumentationEvent event;
        int exceptionCount;
        int sqlCount;
        long totalAllocBytes;
        TraceRow(InstrumentationEvent e) { this.event = e; }
    }

    private class TracesTableModel extends AbstractTableModel {
        private final String[] COLS = {"End Time", "Type", "Duration (ms)", "Descriptor", "Alloc (MB)", "Exc", "SQL", "Thread"};
        private List data = new ArrayList(); /* List<TraceRow> */
        private static final int MAX_TRACES = 200;

        public void update(List events) {
            allCurrentEvents = events; /* cache for tree building */

            /* Index events by parentTraceId (parent -> children relationship) */
            java.util.Map childrenOf = new java.util.HashMap(); /* Long(parentId) -> List<Event> */
            for (int i = 0; i < events.size(); i++) {
                InstrumentationEvent e = (InstrumentationEvent) events.get(i);
                Long pid = new Long(e.getParentTraceId());
                if (pid.longValue() != 0) { /* skip root-level events with no parent */
                    addToMap(childrenOf, pid, e);
                }
            }

            List completed = new ArrayList();
            for (int i = 0; i < events.size(); i++) {
                InstrumentationEvent e = (InstrumentationEvent) events.get(i);
                if (e.getDurationNanos() > 0) completed.add(e);
            }
            int start = completed.size() > MAX_TRACES ? completed.size() - MAX_TRACES : 0;
            List trimmed = new ArrayList();
            for (int i = completed.size() - 1; i >= start; i--) {
                InstrumentationEvent e = (InstrumentationEvent) completed.get(i);
                TraceRow tr = new TraceRow(e);
                /* Count exceptions and SQL in this trace's descendants.
                 * Walk the tree: children of traceId X are in childrenOf[X]. */
                java.util.Set visited = new java.util.HashSet();
                java.util.List queue = new java.util.ArrayList();
                queue.add(new Long(e.getTraceId()));
                /* Also count root event itself */
                if (e.isException()) tr.exceptionCount++;
                if (e.getEventType() == InstrumentationEvent.TYPE_JDBC_QUERY) tr.sqlCount++;
                tr.totalAllocBytes += e.getAllocatedBytes();
                while (!queue.isEmpty()) {
                    Long id = (Long) queue.remove(queue.size() - 1);
                    if (!visited.add(id)) continue;
                    List kids = (List) childrenOf.get(id);
                    if (kids == null) continue;
                    for (int c = 0; c < kids.size(); c++) {
                        InstrumentationEvent ce = (InstrumentationEvent) kids.get(c);
                        if (ce.isException()) tr.exceptionCount++;
                        if (ce.getEventType() == InstrumentationEvent.TYPE_JDBC_QUERY) tr.sqlCount++;
                        tr.totalAllocBytes += ce.getAllocatedBytes();
                        Long childId = new Long(ce.getTraceId());
                        if (!visited.contains(childId)) queue.add(childId);
                    }
                }
                trimmed.add(tr);
            }
            data = trimmed;
            fireTableDataChanged();
        }

        private void addToMap(java.util.Map map, Long key, Object val) {
            List list = (List) map.get(key);
            if (list == null) { list = new java.util.ArrayList(); map.put(key, list); }
            list.add(val);
        }

        public InstrumentationEvent getEventAt(int row) {
            if (row < 0 || row >= data.size()) return null;
            return ((TraceRow) data.get(row)).event;
        }

        public int getRowCount() { return data.size(); }
        public int getColumnCount() { return COLS.length; }
        public String getColumnName(int c) { return COLS[c]; }

        public Object getValueAt(int row, int col) {
            if (row < 0 || row >= data.size()) return "";
            TraceRow tr = (TraceRow) data.get(row);
            InstrumentationEvent e = tr.event;
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm:ss.SSS");
            switch (col) {
                case 0: return sdf.format(new java.util.Date(e.getTimestamp()));
                case 1: return getDisplayType(e);
                case 2: return String.format("%.3f", e.getDurationMs());
                case 3: return getTraceDescriptor(e);
                case 4: return tr.totalAllocBytes > 0 ? String.format("%.2f", tr.totalAllocBytes / (1024.0 * 1024.0)) : "";
                case 5: return Integer.valueOf(tr.exceptionCount);
                case 6: return Integer.valueOf(tr.sqlCount);
                case 7: return e.getThreadName() != null ? e.getThreadName() : "";
                default: return "";
            }
        }
    }

    /* ── Running tab: in-progress traces (no duration yet), oldest first ── */

    private class RunningTableModel extends AbstractTableModel {
        private final String[] COLS = {"Start Time", "Type", "Elapsed (ms)", "Descriptor", "Thread"};
        private List data = new ArrayList();

        public void update(List events) {
            long now = System.currentTimeMillis();
            List running = new ArrayList();
            for (int i = 0; i < events.size(); i++) {
                InstrumentationEvent e = (InstrumentationEvent) events.get(i);
                if (e.getDurationMs() > 1000 && (now - e.getTimestamp()) < 10000) {
                    running.add(e);
                }
            }
            data = running;
            fireTableDataChanged();
        }

        public int getRowCount() { return data.size(); }
        public int getColumnCount() { return COLS.length; }
        public String getColumnName(int c) { return COLS[c]; }

        public Object getValueAt(int row, int col) {
            if (row < 0 || row >= data.size()) return "";
            InstrumentationEvent e = (InstrumentationEvent) data.get(row);
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm:ss.SSS");
            switch (col) {
                case 0: return sdf.format(new java.util.Date(e.getTimestamp()));
                case 1: return getDisplayType(e);
                case 2: return String.format("%.0f", (System.currentTimeMillis() - e.getTimestamp()));
                case 3: return getTraceDescriptor(e);
                case 4: return e.getThreadName() != null ? e.getThreadName() : "";
                default: return "";
            }
        }
    }
}
