/*
 * JVMMonitor - TCP Transport Implementation
 * Agent is the SERVER: listens on a port, accepts one client at a time.
 * When a client connects, sends handshake, then streams ring buffer data.
 * When client disconnects, goes back to accepting.
 */
#include "jvmmon/transport.h"
#include "jvmmon/protocol.h"
#include <string.h>

#define HEARTBEAT_INTERVAL_MS  5000

static void disconnect_client(transport_ctx_t *ctx) {
    jvmmon_atomic_store(&ctx->connected, 0);
    if (ctx->client_sock != JVMMON_INVALID_SOCKET) {
        jvmmon_socket_close(ctx->client_sock);
        ctx->client_sock = JVMMON_INVALID_SOCKET;
    }
}

static int send_handshake(transport_ctx_t *ctx) {
    uint8_t buf[JVMMON_HEADER_SIZE + JVMMON_MAX_PAYLOAD];
    uint8_t *payload = buf + JVMMON_HEADER_SIZE;
    int off = 0;
    uint16_t hlen, jlen;

    off += protocol_encode_u32(payload + off, ctx->agent_version);
    off += protocol_encode_u32(payload + off, ctx->pid);

    hlen = (uint16_t)strlen(ctx->hostname);
    off += protocol_encode_string(payload + off, ctx->hostname, hlen);

    jlen = (uint16_t)strlen(ctx->jvm_info);
    off += protocol_encode_string(payload + off, ctx->jvm_info, jlen);

    protocol_encode_header(buf, JVMMON_MSG_HANDSHAKE, (uint32_t)off);

    return jvmmon_socket_send(ctx->client_sock, buf, JVMMON_HEADER_SIZE + off);
}

/* ── Accept thread: listens for client connections ──── */

static void *accept_thread_fn(void *arg) {
    transport_ctx_t *ctx = (transport_ctx_t *)arg;

    while (jvmmon_atomic_load(&ctx->running)) {
        /* Wait for a client to connect */
        jvmmon_socket_t client = jvmmon_socket_accept(ctx->server_sock);
        if (client == JVMMON_INVALID_SOCKET) {
            if (jvmmon_atomic_load(&ctx->running)) {
                jvmmon_sleep_ms(100);
            }
            continue;
        }

        /* Disconnect previous client if any */
        if (jvmmon_atomic_load(&ctx->connected)) {
            disconnect_client(ctx);
            jvmmon_sleep_ms(50);
        }

        jvmmon_socket_set_nodelay(client, 1);
        ctx->client_sock = client;

        /* Send handshake with agent info */
        if (send_handshake(ctx) < 0) {
            jvmmon_socket_close(client);
            ctx->client_sock = JVMMON_INVALID_SOCKET;
            continue;
        }

        jvmmon_atomic_store(&ctx->connected, 1);
    }

    return NULL;
}

/* ── Send thread: ring buffer → TCP ────────────────── */

static void *send_thread_fn(void *arg) {
    transport_ctx_t *ctx = (transport_ctx_t *)arg;
    uint8_t pop_buf[RING_BUFFER_SLOT_SIZE];
    uint64_t last_send_time = 0;

    while (jvmmon_atomic_load(&ctx->running)) {
        if (!jvmmon_atomic_load(&ctx->connected)) {
            /* No client connected — drain ring buffer to avoid filling up */
            while (ring_buffer_pop(ctx->ring, pop_buf) > 0) {
                /* discard when no client */
            }
            jvmmon_sleep_ms(50);
            continue;
        }

        int len = ring_buffer_pop(ctx->ring, pop_buf);
        if (len > 0) {
            if (jvmmon_socket_send(ctx->client_sock, pop_buf, len) < 0) {
                disconnect_client(ctx);
                continue;
            }
            last_send_time = jvmmon_time_millis();
        } else {
            /* Send heartbeat if idle */
            uint64_t now = jvmmon_time_millis();
            if (now - last_send_time >= HEARTBEAT_INTERVAL_MS) {
                uint8_t hb_buf[JVMMON_HEADER_SIZE + 8];
                uint8_t *hb_payload = hb_buf + JVMMON_HEADER_SIZE;
                int hoff = 0;
                hoff += protocol_encode_u64(hb_payload + hoff, now);
                protocol_encode_header(hb_buf, JVMMON_MSG_HEARTBEAT, (uint32_t)hoff);

                if (jvmmon_socket_send(ctx->client_sock, hb_buf, JVMMON_HEADER_SIZE + hoff) < 0) {
                    disconnect_client(ctx);
                    continue;
                }
                last_send_time = now;
            }
            jvmmon_sleep_ms(10);  /* 100 Hz polling when idle — balances latency vs CPU */
        }
    }

    return NULL;
}

/* ── Recv thread: reads commands from client ────────── */

static void *recv_thread_fn(void *arg) {
    transport_ctx_t *ctx = (transport_ctx_t *)arg;
    uint8_t header_buf[JVMMON_HEADER_SIZE];
    uint8_t payload_buf[JVMMON_MAX_PAYLOAD];

    while (jvmmon_atomic_load(&ctx->running)) {
        if (!jvmmon_atomic_load(&ctx->connected)) {
            jvmmon_sleep_ms(100);
            continue;
        }

        if (jvmmon_socket_recv(ctx->client_sock, header_buf, JVMMON_HEADER_SIZE) < 0) {
            disconnect_client(ctx);
            continue;
        }

        uint32_t payload_len = 0;
        int msg_type = protocol_validate_header(header_buf, &payload_len);
        if (msg_type < 0) {
            disconnect_client(ctx);
            continue;
        }

        if (payload_len > 0) {
            if (jvmmon_socket_recv(ctx->client_sock, payload_buf, (int)payload_len) < 0) {
                disconnect_client(ctx);
                continue;
            }
        }

        if (msg_type == JVMMON_MSG_COMMAND && ctx->on_command != NULL) {
            if (payload_len >= 1) {
                uint8_t cmd_type = protocol_decode_u8(payload_buf);
                ctx->on_command(cmd_type, payload_buf + 1,
                                payload_len - 1, ctx->command_user_data);
            }
        }
    }

    return NULL;
}

/* ── Public API ─────────────────────────────────────── */

int transport_init(transport_ctx_t *ctx, ring_buffer_t *ring, int port) {
    memset(ctx, 0, sizeof(transport_ctx_t));
    ctx->ring = ring;
    ctx->port = port;
    ctx->server_sock = JVMMON_INVALID_SOCKET;
    ctx->client_sock = JVMMON_INVALID_SOCKET;
    ctx->connected = 0;
    ctx->running = 0;
    ctx->on_command = NULL;
    ctx->command_user_data = NULL;
    ctx->agent_version = 1;
    ctx->pid = 0;
    return 0;
}

int transport_start(transport_ctx_t *ctx) {
    /* Create listening socket */
    ctx->server_sock = jvmmon_socket_listen("0.0.0.0", ctx->port, 2);
    if (ctx->server_sock == JVMMON_INVALID_SOCKET) {
        return -1;
    }

    jvmmon_atomic_store(&ctx->running, 1);

    if (jvmmon_thread_create(&ctx->accept_thread, accept_thread_fn, ctx) != 0) {
        jvmmon_socket_close(ctx->server_sock);
        jvmmon_atomic_store(&ctx->running, 0);
        return -1;
    }

    if (jvmmon_thread_create(&ctx->send_thread, send_thread_fn, ctx) != 0) {
        jvmmon_atomic_store(&ctx->running, 0);
        jvmmon_thread_join(&ctx->accept_thread);
        jvmmon_socket_close(ctx->server_sock);
        return -1;
    }

    if (jvmmon_thread_create(&ctx->recv_thread, recv_thread_fn, ctx) != 0) {
        jvmmon_atomic_store(&ctx->running, 0);
        jvmmon_thread_join(&ctx->accept_thread);
        jvmmon_thread_join(&ctx->send_thread);
        jvmmon_socket_close(ctx->server_sock);
        return -1;
    }

    return 0;
}

void transport_stop(transport_ctx_t *ctx) {
    jvmmon_atomic_store(&ctx->running, 0);
    disconnect_client(ctx);
    if (ctx->server_sock != JVMMON_INVALID_SOCKET) {
        jvmmon_socket_close(ctx->server_sock);
        ctx->server_sock = JVMMON_INVALID_SOCKET;
    }
    jvmmon_thread_join(&ctx->accept_thread);
    jvmmon_thread_join(&ctx->send_thread);
    jvmmon_thread_join(&ctx->recv_thread);
}

void transport_destroy(transport_ctx_t *ctx) {
    if (ctx->server_sock != JVMMON_INVALID_SOCKET) {
        jvmmon_socket_close(ctx->server_sock);
        ctx->server_sock = JVMMON_INVALID_SOCKET;
    }
    if (ctx->client_sock != JVMMON_INVALID_SOCKET) {
        jvmmon_socket_close(ctx->client_sock);
        ctx->client_sock = JVMMON_INVALID_SOCKET;
    }
}

int transport_is_connected(transport_ctx_t *ctx) {
    return jvmmon_atomic_load(&ctx->connected);
}

int transport_get_port(transport_ctx_t *ctx) {
    return ctx->port;
}

void transport_set_command_callback(transport_ctx_t *ctx,
                                    transport_command_cb cb, void *user_data) {
    ctx->on_command = cb;
    ctx->command_user_data = user_data;
}
