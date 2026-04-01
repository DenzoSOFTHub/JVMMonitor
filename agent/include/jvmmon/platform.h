/*
 * JVMMonitor - Platform Abstraction Layer
 * Cross-platform API for sockets, threads, mutex, timing, atomics.
 */
#ifndef JVMMON_PLATFORM_H
#define JVMMON_PLATFORM_H

#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#ifdef _WIN32
  #define WIN32_LEAN_AND_MEAN
  #include <windows.h>
  #include <winsock2.h>
  #include <ws2tcpip.h>
  #pragma comment(lib, "ws2_32.lib")
  #define JVMMON_EXPORT __declspec(dllexport)
#else
  #include <pthread.h>
  #include <unistd.h>
  #include <time.h>
  #include <sys/socket.h>
  #include <sys/types.h>
  #include <netinet/in.h>
  #include <netinet/tcp.h>
  #include <arpa/inet.h>
  #include <netdb.h>
  #include <dlfcn.h>
  #include <errno.h>
  #include <signal.h>
  #include <fcntl.h>
  #define JVMMON_EXPORT __attribute__((visibility("default")))
#endif

/* ── Socket ─────────────────────────────────────────── */

typedef intptr_t jvmmon_socket_t;
#define JVMMON_INVALID_SOCKET ((jvmmon_socket_t)-1)

int             jvmmon_socket_init(void);
void            jvmmon_socket_cleanup(void);
jvmmon_socket_t jvmmon_socket_create(void);
int             jvmmon_socket_connect(jvmmon_socket_t sock, const char *host, int port);
int             jvmmon_socket_send(jvmmon_socket_t sock, const void *buf, int len);
int             jvmmon_socket_recv(jvmmon_socket_t sock, void *buf, int len);
void            jvmmon_socket_close(jvmmon_socket_t sock);
int             jvmmon_socket_set_nodelay(jvmmon_socket_t sock, int enabled);
jvmmon_socket_t jvmmon_socket_listen(const char *host, int port, int backlog);
jvmmon_socket_t jvmmon_socket_accept(jvmmon_socket_t server_sock);

/* ── Thread ─────────────────────────────────────────── */

#ifdef _WIN32
typedef struct { HANDLE handle; } jvmmon_thread_t;
#else
typedef struct { pthread_t handle; } jvmmon_thread_t;
#endif

typedef void *(*jvmmon_thread_fn)(void *arg);

int      jvmmon_thread_create(jvmmon_thread_t *thread, jvmmon_thread_fn fn, void *arg);
void     jvmmon_thread_join(jvmmon_thread_t *thread);
uint64_t jvmmon_thread_id(void);

/* ── Mutex ──────────────────────────────────────────── */

#ifdef _WIN32
typedef struct { CRITICAL_SECTION cs; } jvmmon_mutex_t;
#else
typedef struct { pthread_mutex_t mtx; } jvmmon_mutex_t;
#endif

void jvmmon_mutex_init(jvmmon_mutex_t *m);
void jvmmon_mutex_lock(jvmmon_mutex_t *m);
void jvmmon_mutex_unlock(jvmmon_mutex_t *m);
void jvmmon_mutex_destroy(jvmmon_mutex_t *m);

/* ── Timing ─────────────────────────────────────────── */

uint64_t jvmmon_time_nanos(void);
uint64_t jvmmon_time_millis(void);
void     jvmmon_sleep_ms(int ms);

/* ── Atomics ────────────────────────────────────────── */

int32_t jvmmon_atomic_load(volatile int32_t *ptr);
void    jvmmon_atomic_store(volatile int32_t *ptr, int32_t val);
int32_t jvmmon_atomic_add(volatile int32_t *ptr, int32_t val);
int32_t jvmmon_atomic_cas(volatile int32_t *ptr, int32_t expected, int32_t desired);

/* ── Memory ─────────────────────────────────────────── */

void *jvmmon_alloc(size_t size);
void *jvmmon_calloc(size_t count, size_t size);
void  jvmmon_free(void *ptr);

/* ── Dynamic Library ────────────────────────────────── */

void *jvmmon_dlsym(const char *symbol);

/* ── Process ────────────────────────────────────────── */

int jvmmon_getpid(void);
int jvmmon_gethostname(char *buf, int len);

#endif /* JVMMON_PLATFORM_H */
