package it.denzosoft.jvmmonitor.model;

/**
 * Network state snapshot: per-socket details + aggregate TCP counters.
 */
public final class NetworkSnapshot {

    private final long timestamp;

    /* Aggregate TCP counters (from /proc/net/snmp) */
    private final long activeOpens;
    private final long passiveOpens;
    private final long inSegments;
    private final long outSegments;
    private final long retransSegments;
    private final long inErrors;
    private final long outResets;
    private final long currentEstablished;

    /* Per-socket details */
    private final SocketInfo[] sockets;

    public NetworkSnapshot(long timestamp,
                           long activeOpens, long passiveOpens,
                           long inSegments, long outSegments,
                           long retransSegments, long inErrors, long outResets,
                           long currentEstablished, SocketInfo[] sockets) {
        this.timestamp = timestamp;
        this.activeOpens = activeOpens;
        this.passiveOpens = passiveOpens;
        this.inSegments = inSegments;
        this.outSegments = outSegments;
        this.retransSegments = retransSegments;
        this.inErrors = inErrors;
        this.outResets = outResets;
        this.currentEstablished = currentEstablished;
        this.sockets = sockets;
    }

    public long getTimestamp() { return timestamp; }
    public long getActiveOpens() { return activeOpens; }
    public long getPassiveOpens() { return passiveOpens; }
    public long getInSegments() { return inSegments; }
    public long getOutSegments() { return outSegments; }
    public long getRetransSegments() { return retransSegments; }
    public long getInErrors() { return inErrors; }
    public long getOutResets() { return outResets; }
    public long getCurrentEstablished() { return currentEstablished; }
    public SocketInfo[] getSockets() { return sockets; }
    public int getSocketCount() { return sockets != null ? sockets.length : 0; }

    public int countByState(int state) {
        int count = 0;
        if (sockets != null) {
            for (int i = 0; i < sockets.length; i++) {
                if (sockets[i] != null && sockets[i].state == state) count++;
            }
        }
        return count;
    }

    public int getListenCount() { return countByState(SocketInfo.STATE_LISTEN); }
    public int getEstablishedCount() { return countByState(SocketInfo.STATE_ESTABLISHED); }
    public int getCloseWaitCount() { return countByState(SocketInfo.STATE_CLOSE_WAIT); }
    public int getTimeWaitCount() { return countByState(SocketInfo.STATE_TIME_WAIT); }

    public static final class SocketInfo {
        public static final int STATE_ESTABLISHED = 1;
        public static final int STATE_SYN_SENT    = 2;
        public static final int STATE_SYN_RECV    = 3;
        public static final int STATE_FIN_WAIT1   = 4;
        public static final int STATE_FIN_WAIT2   = 5;
        public static final int STATE_TIME_WAIT   = 6;
        public static final int STATE_CLOSE       = 7;
        public static final int STATE_CLOSE_WAIT  = 8;
        public static final int STATE_LAST_ACK    = 9;
        public static final int STATE_LISTEN     = 10;
        public static final int STATE_CLOSING    = 11;

        public final long localAddr;
        public final int localPort;
        public final long remoteAddr;
        public final int remotePort;
        public final int state;
        public final long txQueue;
        public final long rxQueue;
        public final long bytesIn;
        public final long bytesOut;
        public final int requestCount;
        public final String serviceName;

        public SocketInfo(long localAddr, int localPort, long remoteAddr, int remotePort,
                          int state, long txQueue, long rxQueue) {
            this(localAddr, localPort, remoteAddr, remotePort, state, txQueue, rxQueue, 0, 0, 0, "");
        }

        public SocketInfo(long localAddr, int localPort, long remoteAddr, int remotePort,
                          int state, long txQueue, long rxQueue,
                          long bytesIn, long bytesOut, int requestCount, String serviceName) {
            this.localAddr = localAddr;
            this.localPort = localPort;
            this.remoteAddr = remoteAddr;
            this.remotePort = remotePort;
            this.state = state;
            this.txQueue = txQueue;
            this.rxQueue = rxQueue;
            this.bytesIn = bytesIn;
            this.bytesOut = bytesOut;
            this.requestCount = requestCount;
            this.serviceName = serviceName != null ? serviceName : "";
        }

        public String getLocalAddrString() {
            return ipToString(localAddr) + ":" + localPort;
        }

        public String getRemoteAddrString() {
            return ipToString(remoteAddr) + ":" + remotePort;
        }

        public String getStateName() {
            switch (state) {
                case STATE_ESTABLISHED: return "ESTABLISHED";
                case STATE_SYN_SENT:    return "SYN_SENT";
                case STATE_SYN_RECV:    return "SYN_RECV";
                case STATE_FIN_WAIT1:   return "FIN_WAIT1";
                case STATE_FIN_WAIT2:   return "FIN_WAIT2";
                case STATE_TIME_WAIT:   return "TIME_WAIT";
                case STATE_CLOSE:       return "CLOSE";
                case STATE_CLOSE_WAIT:  return "CLOSE_WAIT";
                case STATE_LAST_ACK:    return "LAST_ACK";
                case STATE_LISTEN:      return "LISTEN";
                case STATE_CLOSING:     return "CLOSING";
                default: return "UNKNOWN(" + state + ")";
            }
        }

        /** True if this socket has pending data in rx or tx queue. */
        public boolean hasBackpressure() {
            return txQueue > 0 || rxQueue > 0;
        }

        public String getDirection() {
            if (state == STATE_LISTEN) return "LISTEN";
            /* Well-known remote ports = outgoing; high remote port = incoming */
            if (remotePort > 0 && remotePort < 10000) return "OUT";
            if (localPort > 0 && localPort < 10000) return "IN";
            return "OUT";
        }

        public String getDestination() {
            if (state == STATE_LISTEN) return "*:" + localPort;
            String svc = serviceName.isEmpty() ? portToService(remotePort) : serviceName;
            return getRemoteAddrString() + (svc.isEmpty() ? "" : " (" + svc + ")");
        }

        public String formatBytes(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
            if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
            return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        }

        private static String portToService(int port) {
            switch (port) {
                /* Web / REST / SOAP */
                case 80: return "HTTP";
                case 443: return "HTTPS";
                case 8080: return "HTTP";
                case 8443: return "HTTPS";
                case 8081: return "HTTP-ALT";
                case 9443: return "HTTPS-ALT";
                /* Database */
                case 3306: return "MySQL";
                case 5432: return "PostgreSQL";
                case 1521: return "Oracle";
                case 1433: return "SQL Server";
                case 27017: return "MongoDB";
                case 5984: return "CouchDB";
                case 9200: return "Elasticsearch";
                case 7474: return "Neo4j";
                /* Cache / Queue */
                case 6379: return "Redis";
                case 11211: return "Memcached";
                case 9092: return "Kafka";
                case 5672: return "RabbitMQ";
                case 61616: return "ActiveMQ";
                case 2181: return "ZooKeeper";
                /* Mail */
                case 25: return "SMTP";
                case 465: return "SMTPS";
                case 587: return "SMTP-SUB";
                case 110: return "POP3";
                case 995: return "POP3S";
                case 143: return "IMAP";
                case 993: return "IMAPS";
                /* File Transfer */
                case 21: return "FTP";
                case 22: return "SFTP/SSH";
                case 990: return "FTPS";
                /* LDAP / Directory */
                case 389: return "LDAP";
                case 636: return "LDAPS";
                /* Other */
                case 1099: return "JMX/RMI";
                case 8009: return "AJP";
                case 9090: return "JVMMonitor";
                case 53: return "DNS";
                case 514: return "Syslog";
                case 161: return "SNMP";
                default: return "";
            }
        }

        private static String ipToString(long addr) {
            if (addr == 0) return "0.0.0.0";
            /* /proc/net/tcp stores IP in host byte order on little-endian */
            return (addr & 0xFF) + "." + ((addr >> 8) & 0xFF) + "." +
                   ((addr >> 16) & 0xFF) + "." + ((addr >> 24) & 0xFF);
        }
    }
}
