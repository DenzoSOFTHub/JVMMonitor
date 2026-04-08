/*
 * JVMMonitor - Platform Implementation (Linux/POSIX)
 */
#ifndef _WIN32

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "jvmmon/platform.h"
#include <time.h>

/* ── Socket ─────────────────────────────────────────── */

int jvmmon_socket_init(void) {
    /* No-op on Linux */
    signal(SIGPIPE, SIG_IGN);
    return 0;
}

void jvmmon_socket_cleanup(void) {
    /* No-op on Linux */
}

jvmmon_socket_t jvmmon_socket_create(void) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return JVMMON_INVALID_SOCKET;
    return (jvmmon_socket_t)fd;
}

int jvmmon_socket_connect(jvmmon_socket_t sock, const char *host, int port) {
    struct addrinfo hints, *res, *rp;
    char port_str[16];
    int ret;

    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;

    snprintf(port_str, sizeof(port_str), "%d", port);
    ret = getaddrinfo(host, port_str, &hints, &res);
    if (ret != 0) return -1;

    for (rp = res; rp != NULL; rp = rp->ai_next) {
        if (connect((int)sock, rp->ai_addr, rp->ai_addrlen) == 0) {
            freeaddrinfo(res);
            return 0;
        }
    }

    freeaddrinfo(res);
    return -1;
}

int jvmmon_socket_send(jvmmon_socket_t sock, const void *buf, int len) {
    const uint8_t *p = (const uint8_t *)buf;
    int remaining = len;
    while (remaining > 0) {
        ssize_t sent = send((int)sock, p, remaining, MSG_NOSIGNAL);
        if (sent < 0) {
            if (errno == EINTR) continue;       /* interrupted by signal, retry */
            if (errno == EAGAIN || errno == EWOULDBLOCK) continue; /* transient */
            return -1;
        }
        if (sent == 0) return -1; /* peer closed */
        p += sent;
        remaining -= (int)sent;
    }
    return len;
}

int jvmmon_socket_recv(jvmmon_socket_t sock, void *buf, int len) {
    uint8_t *p = (uint8_t *)buf;
    int remaining = len;
    while (remaining > 0) {
        ssize_t received = recv((int)sock, p, remaining, 0);
        if (received < 0) {
            if (errno == EINTR) continue;       /* interrupted by signal, retry */
            if (errno == EAGAIN || errno == EWOULDBLOCK) continue; /* transient */
            return -1;
        }
        if (received == 0) return -1; /* peer closed */
        p += received;
        remaining -= (int)received;
    }
    return len;
}

void jvmmon_socket_close(jvmmon_socket_t sock) {
    if (sock != JVMMON_INVALID_SOCKET) {
        close((int)sock);
    }
}

int jvmmon_socket_set_nodelay(jvmmon_socket_t sock, int enabled) {
    int flag = enabled ? 1 : 0;
    return setsockopt((int)sock, IPPROTO_TCP, TCP_NODELAY, &flag, sizeof(flag));
}

jvmmon_socket_t jvmmon_socket_listen(const char *host, int port, int backlog) {
    int fd, opt = 1;
    struct sockaddr_in addr;

    fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return JVMMON_INVALID_SOCKET;

    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t)port);

    if (host == NULL || strcmp(host, "0.0.0.0") == 0) {
        addr.sin_addr.s_addr = INADDR_ANY;
    } else {
        if (inet_pton(AF_INET, host, &addr.sin_addr) <= 0) {
            close(fd);
            return JVMMON_INVALID_SOCKET;
        }
    }

    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        close(fd);
        return JVMMON_INVALID_SOCKET;
    }

    if (listen(fd, backlog) < 0) {
        close(fd);
        return JVMMON_INVALID_SOCKET;
    }

    return (jvmmon_socket_t)fd;
}

jvmmon_socket_t jvmmon_socket_accept(jvmmon_socket_t server_sock) {
    int fd = accept((int)server_sock, NULL, NULL);
    if (fd < 0) return JVMMON_INVALID_SOCKET;
    return (jvmmon_socket_t)fd;
}

/* ── Thread ─────────────────────────────────────────── */

int jvmmon_thread_create(jvmmon_thread_t *thread, jvmmon_thread_fn fn, void *arg) {
    return pthread_create(&thread->handle, NULL, fn, arg);
}

void jvmmon_thread_join(jvmmon_thread_t *thread) {
    pthread_join(thread->handle, NULL);
}

uint64_t jvmmon_thread_id(void) {
    return (uint64_t)pthread_self();
}

/* ── Mutex ──────────────────────────────────────────── */

void jvmmon_mutex_init(jvmmon_mutex_t *m) {
    pthread_mutex_init(&m->mtx, NULL);
}

void jvmmon_mutex_lock(jvmmon_mutex_t *m) {
    pthread_mutex_lock(&m->mtx);
}

void jvmmon_mutex_unlock(jvmmon_mutex_t *m) {
    pthread_mutex_unlock(&m->mtx);
}

void jvmmon_mutex_destroy(jvmmon_mutex_t *m) {
    pthread_mutex_destroy(&m->mtx);
}

/* ── Timing ─────────────────────────────────────────── */

uint64_t jvmmon_time_nanos(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
}

uint64_t jvmmon_time_millis(void) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (uint64_t)ts.tv_sec * 1000ULL + (uint64_t)ts.tv_nsec / 1000000ULL;
}

void jvmmon_sleep_ms(int ms) {
    struct timespec ts;
    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (ms % 1000) * 1000000L;
    nanosleep(&ts, NULL);
}

/* ── Atomics ────────────────────────────────────────── */

int32_t jvmmon_atomic_load(volatile int32_t *ptr) {
    int32_t val = *ptr;
    __sync_synchronize();
    return val;
}

void jvmmon_atomic_store(volatile int32_t *ptr, int32_t val) {
    __sync_synchronize();
    *ptr = val;
    __sync_synchronize();
}

int32_t jvmmon_atomic_add(volatile int32_t *ptr, int32_t val) {
    return __sync_add_and_fetch(ptr, val);
}

int32_t jvmmon_atomic_cas(volatile int32_t *ptr, int32_t expected, int32_t desired) {
    return __sync_val_compare_and_swap(ptr, expected, desired);
}

/* ── Memory ─────────────────────────────────────────── */

void *jvmmon_alloc(size_t size) {
    return malloc(size);
}

void *jvmmon_calloc(size_t count, size_t size) {
    return calloc(count, size);
}

void jvmmon_free(void *ptr) {
    free(ptr);
}

/* ── Dynamic Library ────────────────────────────────── */

void *jvmmon_dlsym(const char *symbol) {
    return dlsym(RTLD_DEFAULT, symbol);
}

/* ── Process ────────────────────────────────────────── */

int jvmmon_getpid(void) {
    return (int)getpid();
}

int jvmmon_gethostname(char *buf, int len) {
    return gethostname(buf, (size_t)len);
}

#endif /* !_WIN32 */
