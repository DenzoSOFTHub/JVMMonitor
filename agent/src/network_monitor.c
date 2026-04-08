/*
 * JVMMonitor - Network Monitor Implementation
 * Reads /proc/self/net/tcp (and tcp6) for per-socket details,
 * and /proc/self/net/snmp for aggregate TCP counters.
 */
#include "jvmmon/network_monitor.h"
#include "jvmmon/log.h"
#include "jvmmon/protocol.h"
#include <string.h>
#include <stdio.h>

#ifndef _WIN32

typedef struct {
    uint32_t local_addr;
    uint16_t local_port;
    uint32_t remote_addr;
    uint16_t remote_port;
    uint8_t  state;
    uint32_t tx_queue;
    uint32_t rx_queue;
    uint8_t  is_ipv6;
} socket_entry_t;

typedef struct {
    int64_t active_opens;
    int64_t passive_opens;
    int64_t in_segs;
    int64_t out_segs;
    int64_t retrans_segs;
    int64_t in_errs;
    int64_t out_rsts;
    int64_t curr_estab;
} tcp_counters_t;

static int read_tcp_sockets(socket_entry_t *entries, int max_entries, const char *path, int is_v6) {
    FILE *f = fopen(path, "r");
    if (f == NULL) return 0;

    char line[512];
    int count = 0;

    /* Skip header */
    if (fgets(line, sizeof(line), f) == NULL) { fclose(f); return 0; }

    while (fgets(line, sizeof(line), f) != NULL && count < max_entries) {
        unsigned int local_addr, local_port, remote_addr, remote_port;
        unsigned int state, tx_q, rx_q;

        /* Parse: sl local_address remote_address st tx_queue:rx_queue ... */
        int sl;
        if (sscanf(line, " %d: %X:%X %X:%X %X %X:%X",
                   &sl, &local_addr, &local_port, &remote_addr, &remote_port,
                   &state, &tx_q, &rx_q) >= 8) {
            entries[count].local_addr = local_addr;
            entries[count].local_port = (uint16_t)local_port;
            entries[count].remote_addr = remote_addr;
            entries[count].remote_port = (uint16_t)remote_port;
            entries[count].state = (uint8_t)state;
            entries[count].tx_queue = tx_q;
            entries[count].rx_queue = rx_q;
            entries[count].is_ipv6 = (uint8_t)is_v6;
            count++;
        }
    }
    fclose(f);
    return count;
}

static void read_tcp_counters(tcp_counters_t *out) {
    memset(out, 0, sizeof(tcp_counters_t));
    FILE *f = fopen("/proc/self/net/snmp", "r");
    if (f == NULL) {
        f = fopen("/proc/net/snmp", "r");
        if (f == NULL) return;
    }

    char line[1024];
    while (fgets(line, sizeof(line), f) != NULL) {
        if (strncmp(line, "Tcp:", 4) != 0) continue;
        /* Next line is the header, this line has field names.
         * The line after has values. Read the values line. */
        char header[1024];
        strncpy(header, line, sizeof(header) - 1);
        if (fgets(line, sizeof(line), f) == NULL) break;
        if (strncmp(line, "Tcp:", 4) != 0) break;

        /* Parse: Tcp: RtoAlgorithm RtoMin RtoMax MaxConn ActiveOpens PassiveOpens
         *        AttemptFails EstabResets CurrEstab InSegs OutSegs RetransSegs InErrs OutRsts ... */
        long long vals[16];
        int n = sscanf(line + 5,
            "%lld %lld %lld %lld %lld %lld %lld %lld %lld %lld %lld %lld %lld %lld",
            &vals[0], &vals[1], &vals[2], &vals[3], &vals[4], &vals[5],
            &vals[6], &vals[7], &vals[8], &vals[9], &vals[10], &vals[11],
            &vals[12], &vals[13]);
        if (n >= 14) {
            out->active_opens  = vals[4];
            out->passive_opens = vals[5];
            out->curr_estab    = vals[8];
            out->in_segs       = vals[9];
            out->out_segs      = vals[10];
            out->retrans_segs  = vals[11];
            out->in_errs       = vals[12];
            out->out_rsts      = vals[13];
        }
        break;
    }
    fclose(f);
}

static void collect_and_send(network_monitor_t *nm) {
    socket_entry_t sockets[MAX_SOCKETS];
    int count = 0;

    count += read_tcp_sockets(sockets + count, MAX_SOCKETS - count,
                              "/proc/self/net/tcp", 0);
    count += read_tcp_sockets(sockets + count, MAX_SOCKETS - count,
                              "/proc/self/net/tcp6", 1);

    tcp_counters_t counters;
    read_tcp_counters(&counters);

    /* Encode payload */
    uint8_t payload[JVMMON_MAX_PAYLOAD];
    int off = 0;

    off += protocol_encode_u64(payload + off, jvmmon_time_millis());

    /* TCP counters */
    off += protocol_encode_i64(payload + off, counters.active_opens);
    off += protocol_encode_i64(payload + off, counters.passive_opens);
    off += protocol_encode_i64(payload + off, counters.in_segs);
    off += protocol_encode_i64(payload + off, counters.out_segs);
    off += protocol_encode_i64(payload + off, counters.retrans_segs);
    off += protocol_encode_i64(payload + off, counters.in_errs);
    off += protocol_encode_i64(payload + off, counters.out_rsts);
    off += protocol_encode_i64(payload + off, counters.curr_estab);

    /* Socket list — cap to fit within MAX_PAYLOAD (each socket = 21 bytes,
     * header = 74 bytes, so max ~386 sockets per message. Use 350 for safety.) */
    int send_count = count < 350 ? count : 350;
    off += protocol_encode_u16(payload + off, (uint16_t)send_count);

    int i;
    for (i = 0; i < send_count && off < (int)(JVMMON_MAX_PAYLOAD - 22); i++) {
        socket_entry_t *s = &sockets[i];
        off += protocol_encode_u32(payload + off, s->local_addr);
        off += protocol_encode_u16(payload + off, s->local_port);
        off += protocol_encode_u32(payload + off, s->remote_addr);
        off += protocol_encode_u16(payload + off, s->remote_port);
        off += protocol_encode_u8(payload + off, s->state);
        off += protocol_encode_u32(payload + off, s->tx_queue);
        off += protocol_encode_u32(payload + off, s->rx_queue);
    }

    agent_send_message(JVMMON_MSG_NETWORK, payload, (uint32_t)off);
    LOG_DEBUG("Network: %d sockets, segs in=%lld out=%lld retrans=%lld",
              count, (long long)counters.in_segs, (long long)counters.out_segs,
              (long long)counters.retrans_segs);
}

static void *poll_fn(void *arg) {
    network_monitor_t *nm = (network_monitor_t *)arg;
    while (jvmmon_atomic_load(&nm->running)) {
        collect_and_send(nm);
        jvmmon_sleep_ms(nm->interval_ms);
    }
    return NULL;
}

#else /* Windows stub */

static void *poll_fn(void *arg) { (void)arg; return NULL; }

#endif

network_monitor_t *network_monitor_create(jvmmon_agent_t *agent, int interval_ms) {
    network_monitor_t *nm = (network_monitor_t *)jvmmon_calloc(1, sizeof(network_monitor_t));
    if (!nm) return NULL;
    nm->agent = agent;
    nm->interval_ms = interval_ms > 0 ? interval_ms : 5000;
    return nm;
}

void network_monitor_destroy(network_monitor_t *nm) {
    if (nm) {
        if (jvmmon_atomic_load(&nm->running)) network_monitor_deactivate(0, nm);
        jvmmon_free(nm);
    }
}

int network_monitor_activate(int level, const char *target, void *ctx) {
    network_monitor_t *nm = (network_monitor_t *)ctx;
    (void)level; (void)target;
    jvmmon_atomic_store(&nm->running, 1);
    jvmmon_thread_create(&nm->poll_thread, poll_fn, nm);
    LOG_INFO("Network monitor activated");
    return 0;
}

int network_monitor_deactivate(int level, void *ctx) {
    network_monitor_t *nm = (network_monitor_t *)ctx;
    (void)level;
    jvmmon_atomic_store(&nm->running, 0);
    jvmmon_thread_join(&nm->poll_thread);
    LOG_INFO("Network monitor deactivated");
    return 0;
}
