/*
 * JVMMonitor - TCP Transport Layer
 * Agent is the SERVER: listens on a port, accepts client connections.
 * Background threads for accept, send (ring buffer → TCP) and recv (TCP → command callback).
 */
#ifndef JVMMON_TRANSPORT_H
#define JVMMON_TRANSPORT_H

#include "platform.h"
#include "ring_buffer.h"

typedef void (*transport_command_cb)(uint8_t cmd_type, const uint8_t *payload,
                                     uint32_t payload_len, void *user_data);

typedef struct {
    ring_buffer_t      *ring;
    int                 port;
    jvmmon_socket_t     server_sock;   /* listening socket */
    jvmmon_socket_t     client_sock;   /* currently connected client */
    jvmmon_thread_t     accept_thread;
    jvmmon_thread_t     send_thread;
    jvmmon_thread_t     recv_thread;
    volatile int32_t    connected;
    volatile int32_t    running;
    transport_command_cb on_command;
    void               *command_user_data;
    /* Agent info (sent in handshake to connecting client) */
    uint32_t            agent_version;
    uint32_t            pid;
    char                hostname[256];
    char                jvm_info[512];
} transport_ctx_t;

int  transport_init(transport_ctx_t *ctx, ring_buffer_t *ring, int port);
int  transport_start(transport_ctx_t *ctx);
void transport_stop(transport_ctx_t *ctx);
void transport_destroy(transport_ctx_t *ctx);
int  transport_is_connected(transport_ctx_t *ctx);
int  transport_get_port(transport_ctx_t *ctx);
void transport_set_command_callback(transport_ctx_t *ctx,
                                    transport_command_cb cb, void *user_data);

#endif /* JVMMON_TRANSPORT_H */
