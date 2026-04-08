/*
 * JVMMonitor - Platform Implementation (Windows)
 */
#ifdef _WIN32

#include "jvmmon/platform.h"

/* ── Socket ─────────────────────────────────────────── */

int jvmmon_socket_init(void) {
    WSADATA wsa;
    return WSAStartup(MAKEWORD(2, 2), &wsa) == 0 ? 0 : -1;
}

void jvmmon_socket_cleanup(void) {
    WSACleanup();
}

jvmmon_socket_t jvmmon_socket_create(void) {
    SOCKET s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (s == INVALID_SOCKET) return JVMMON_INVALID_SOCKET;
    return (jvmmon_socket_t)s;
}

int jvmmon_socket_connect(jvmmon_socket_t sock, const char *host, int port) {
    struct addrinfo hints, *res, *rp;
    char port_str[16];
    int ret;

    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_TCP;

    _snprintf(port_str, sizeof(port_str) - 1, "%d", port);
    port_str[sizeof(port_str) - 1] = '\0';
    ret = getaddrinfo(host, port_str, &hints, &res);
    if (ret != 0) return -1;

    for (rp = res; rp != NULL; rp = rp->ai_next) {
        if (connect((SOCKET)sock, rp->ai_addr, (int)rp->ai_addrlen) == 0) {
            freeaddrinfo(res);
            return 0;
        }
    }

    freeaddrinfo(res);
    return -1;
}

int jvmmon_socket_send(jvmmon_socket_t sock, const void *buf, int len) {
    const char *p = (const char *)buf;
    int remaining = len;
    while (remaining > 0) {
        int sent = send((SOCKET)sock, p, remaining, 0);
        if (sent == SOCKET_ERROR || sent <= 0) return -1;
        p += sent;
        remaining -= sent;
    }
    return len;
}

int jvmmon_socket_recv(jvmmon_socket_t sock, void *buf, int len) {
    char *p = (char *)buf;
    int remaining = len;
    while (remaining > 0) {
        int received = recv((SOCKET)sock, p, remaining, 0);
        if (received == SOCKET_ERROR || received <= 0) return -1;
        p += received;
        remaining -= received;
    }
    return len;
}

void jvmmon_socket_close(jvmmon_socket_t sock) {
    if (sock != JVMMON_INVALID_SOCKET) {
        closesocket((SOCKET)sock);
    }
}

int jvmmon_socket_set_nodelay(jvmmon_socket_t sock, int enabled) {
    BOOL flag = enabled ? TRUE : FALSE;
    return setsockopt((SOCKET)sock, IPPROTO_TCP, TCP_NODELAY,
                      (const char *)&flag, sizeof(flag));
}

jvmmon_socket_t jvmmon_socket_listen(const char *host, int port, int backlog) {
    SOCKET s;
    BOOL opt = TRUE;
    struct sockaddr_in addr;

    s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (s == INVALID_SOCKET) return JVMMON_INVALID_SOCKET;

    setsockopt(s, SOL_SOCKET, SO_REUSEADDR, (const char *)&opt, sizeof(opt));

    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((u_short)port);

    if (host == NULL || strcmp(host, "0.0.0.0") == 0) {
        addr.sin_addr.s_addr = INADDR_ANY;
    } else {
        addr.sin_addr.s_addr = inet_addr(host);
        if (addr.sin_addr.s_addr == INADDR_NONE) {
            closesocket(s);
            return JVMMON_INVALID_SOCKET;
        }
    }

    if (bind(s, (struct sockaddr *)&addr, sizeof(addr)) == SOCKET_ERROR) {
        closesocket(s);
        return JVMMON_INVALID_SOCKET;
    }

    if (listen(s, backlog) == SOCKET_ERROR) {
        closesocket(s);
        return JVMMON_INVALID_SOCKET;
    }

    return (jvmmon_socket_t)s;
}

jvmmon_socket_t jvmmon_socket_accept(jvmmon_socket_t server_sock) {
    SOCKET s = accept((SOCKET)server_sock, NULL, NULL);
    if (s == INVALID_SOCKET) return JVMMON_INVALID_SOCKET;
    return (jvmmon_socket_t)s;
}

/* ── Thread ─────────────────────────────────────────── */

typedef struct {
    jvmmon_thread_fn fn;
    void *arg;
} win_thread_ctx_t;

static DWORD WINAPI win_thread_trampoline(LPVOID param) {
    win_thread_ctx_t ctx = *(win_thread_ctx_t *)param;
    free(param);
    ctx.fn(ctx.arg);
    return 0;
}

int jvmmon_thread_create(jvmmon_thread_t *thread, jvmmon_thread_fn fn, void *arg) {
    win_thread_ctx_t *ctx = (win_thread_ctx_t *)malloc(sizeof(win_thread_ctx_t));
    if (ctx == NULL) return -1;
    ctx->fn = fn;
    ctx->arg = arg;
    thread->handle = CreateThread(NULL, 0, win_thread_trampoline, ctx, 0, NULL);
    if (thread->handle == NULL) {
        free(ctx);
        return -1;
    }
    return 0;
}

void jvmmon_thread_join(jvmmon_thread_t *thread) {
    if (thread->handle != NULL) {
        WaitForSingleObject(thread->handle, INFINITE);
        CloseHandle(thread->handle);
        thread->handle = NULL;
    }
}

uint64_t jvmmon_thread_id(void) {
    return (uint64_t)GetCurrentThreadId();
}

/* ── Mutex ──────────────────────────────────────────── */

void jvmmon_mutex_init(jvmmon_mutex_t *m) {
    InitializeCriticalSection(&m->cs);
}

void jvmmon_mutex_lock(jvmmon_mutex_t *m) {
    EnterCriticalSection(&m->cs);
}

void jvmmon_mutex_unlock(jvmmon_mutex_t *m) {
    LeaveCriticalSection(&m->cs);
}

void jvmmon_mutex_destroy(jvmmon_mutex_t *m) {
    DeleteCriticalSection(&m->cs);
}

/* ── Timing ─────────────────────────────────────────── */

static LARGE_INTEGER perf_freq;
static int perf_freq_init = 0;

static void ensure_perf_freq(void) {
    if (!perf_freq_init) {
        QueryPerformanceFrequency(&perf_freq);
        perf_freq_init = 1;
    }
}

uint64_t jvmmon_time_nanos(void) {
    LARGE_INTEGER counter;
    ensure_perf_freq();
    QueryPerformanceCounter(&counter);
    return (uint64_t)((double)counter.QuadPart / (double)perf_freq.QuadPart * 1000000000.0);
}

uint64_t jvmmon_time_millis(void) {
    FILETIME ft;
    ULARGE_INTEGER uli;
    GetSystemTimeAsFileTime(&ft);
    uli.LowPart = ft.dwLowDateTime;
    uli.HighPart = ft.dwHighDateTime;
    /* FILETIME is 100ns intervals since 1601-01-01. Convert to Unix millis. */
    return (uint64_t)((uli.QuadPart - 116444736000000000ULL) / 10000ULL);
}

void jvmmon_sleep_ms(int ms) {
    Sleep((DWORD)ms);
}

/* ── Atomics ────────────────────────────────────────── */

int32_t jvmmon_atomic_load(volatile int32_t *ptr) {
    MemoryBarrier();
    int32_t val = *ptr;
    MemoryBarrier();
    return val;
}

void jvmmon_atomic_store(volatile int32_t *ptr, int32_t val) {
    MemoryBarrier();
    *ptr = val;
    MemoryBarrier();
}

int32_t jvmmon_atomic_add(volatile int32_t *ptr, int32_t val) {
    return InterlockedExchangeAdd((volatile LONG *)ptr, (LONG)val) + val;
}

int32_t jvmmon_atomic_cas(volatile int32_t *ptr, int32_t expected, int32_t desired) {
    return (int32_t)InterlockedCompareExchange(
        (volatile LONG *)ptr, (LONG)desired, (LONG)expected);
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
    HMODULE jvm = GetModuleHandleA("jvm");
    if (jvm != NULL) {
        void *addr = (void *)GetProcAddress(jvm, symbol);
        if (addr != NULL) return addr;
    }
    /* Fallback: try main module */
    jvm = GetModuleHandleA(NULL);
    if (jvm != NULL) {
        return (void *)GetProcAddress(jvm, symbol);
    }
    return NULL;
}

/* ── Process ────────────────────────────────────────── */

int jvmmon_getpid(void) {
    return (int)GetCurrentProcessId();
}

int jvmmon_gethostname(char *buf, int len) {
    DWORD size = (DWORD)len;
    if (GetComputerNameA(buf, &size)) return 0;
    return -1;
}

#endif /* _WIN32 */
