#
# JVMMonitor - Makefile
# Builds both Linux (.so) and Windows (.dll) from Linux via cross-compilation.
#
# Usage:
#   make              Build everything (Linux + Windows + Java)
#   make linux        Build Linux agent only
#   make windows      Build Windows agent only
#   make java         Build Java collector only
#   make clean        Clean all build artifacts
#
# Prerequisites:
#   Linux:   gcc
#   Windows: x86_64-w64-mingw32-gcc (apt install gcc-mingw-w64-x86-64)
#   Java:    javac, mvn
#

# --- Detect JDK location ---
JAVA_HOME ?= $(shell dirname $(shell dirname $(shell readlink -f $(shell which javac 2>/dev/null) 2>/dev/null) 2>/dev/null) 2>/dev/null)
ifeq ($(JAVA_HOME),)
  JAVA_HOME := /usr/lib/jvm/default-java
endif

# --- JNI include paths ---
JNI_INCLUDE     := $(JAVA_HOME)/include
JNI_INCLUDE_OS  := $(JNI_INCLUDE)/linux
JNI_INCLUDE_WIN := $(JNI_INCLUDE)/win32

# --- Compilers ---
CC_LINUX   := gcc
CC_WINDOWS := x86_64-w64-mingw32-gcc

# --- Directories ---
AGENT_SRC  := agent/src
AGENT_INC  := agent/include
DIST_DIR   := dist

# --- Source files ---
COMMON_SRC := $(AGENT_SRC)/protocol.c \
              $(AGENT_SRC)/ring_buffer.c \
              $(AGENT_SRC)/log.c \
              $(AGENT_SRC)/transport.c \
              $(AGENT_SRC)/cpu_sampler.c \
              $(AGENT_SRC)/gc_listener.c \
              $(AGENT_SRC)/thread_monitor.c \
              $(AGENT_SRC)/memory_monitor.c \
              $(AGENT_SRC)/class_analyzer.c \
              $(AGENT_SRC)/alarm_engine.c \
              $(AGENT_SRC)/module_registry.c \
              $(AGENT_SRC)/jmx_reader.c \
              $(AGENT_SRC)/exception_tracker.c \
              $(AGENT_SRC)/os_metrics.c \
              $(AGENT_SRC)/jit_tracker.c \
              $(AGENT_SRC)/class_histo.c \
              $(AGENT_SRC)/finalizer_tracker.c \
              $(AGENT_SRC)/safepoint_tracker.c \
              $(AGENT_SRC)/native_mem.c \
              $(AGENT_SRC)/gc_detail.c \
              $(AGENT_SRC)/thread_cpu.c \
              $(AGENT_SRC)/classloader_tracker.c \
              $(AGENT_SRC)/string_table.c \
              $(AGENT_SRC)/network_monitor.c \
              $(AGENT_SRC)/lock_monitor.c \
              $(AGENT_SRC)/crash_handler.c \
              $(AGENT_SRC)/agent.c

LINUX_SRC   := $(AGENT_SRC)/platform_linux.c
WINDOWS_SRC := $(AGENT_SRC)/platform_win32.c

# --- Flags ---
CFLAGS_COMMON := -std=c11 -Wall -Wextra -O2 \
                 -I$(AGENT_INC) -I$(JNI_INCLUDE)

CFLAGS_LINUX   := $(CFLAGS_COMMON) -I$(JNI_INCLUDE_OS) \
                  -fPIC -fvisibility=hidden -D_GNU_SOURCE
LDFLAGS_LINUX  := -shared -lpthread -ldl

CFLAGS_WINDOWS := $(CFLAGS_COMMON) -I$(AGENT_INC)/win32 \
                  -D_WIN32
LDFLAGS_WINDOWS := -shared -lws2_32 -static-libgcc

# --- Targets ---
LINUX_OUT   := $(DIST_DIR)/linux/jvmmonitor.so
WINDOWS_OUT := $(DIST_DIR)/windows/jvmmonitor.dll
JAVA_OUT    := $(DIST_DIR)/jvmmonitor.jar

# --- Test sources ---
TEST_C_SRC  := agent/test/test_main.c
TEST_C_DEPS := $(AGENT_SRC)/protocol.c $(AGENT_SRC)/ring_buffer.c $(AGENT_SRC)/platform_linux.c
TEST_C_OUT  := $(DIST_DIR)/test/test_agent

# =============================================================

.PHONY: all linux windows java clean test test-c test-java

all: linux windows java
	@echo ""
	@echo "=== Build complete ==="
	@echo "  Linux agent:   $(LINUX_OUT)"
	@echo "  Windows agent: $(WINDOWS_OUT)"
	@echo "  Collector:     $(JAVA_OUT)"

linux: $(LINUX_OUT)

windows: $(WINDOWS_OUT)

java: $(JAVA_OUT)

$(LINUX_OUT): $(COMMON_SRC) $(LINUX_SRC)
	@mkdir -p $(dir $@)
	$(CC_LINUX) $(CFLAGS_LINUX) -o $@ $(COMMON_SRC) $(LINUX_SRC) $(LDFLAGS_LINUX)
	@echo "Built: $@"

$(WINDOWS_OUT): $(COMMON_SRC) $(WINDOWS_SRC)
	@mkdir -p $(dir $@)
	$(CC_WINDOWS) $(CFLAGS_WINDOWS) -o $@ $(COMMON_SRC) $(WINDOWS_SRC) $(LDFLAGS_WINDOWS)
	@echo "Built: $@"

$(JAVA_OUT):
	@mkdir -p $(DIST_DIR)
	cd collector && mvn clean package -q
	@cp collector/target/jvmmonitor-collector-*-SNAPSHOT.jar $(JAVA_OUT) 2>/dev/null || \
	 cp collector/target/jvmmonitor-collector-*.jar $(JAVA_OUT)
	@echo "Built: $@"

test: test-c test-java
	@echo ""
	@echo "=== All tests completed ==="

test-c: $(TEST_C_OUT)
	@echo ""
	@echo "=== Running C agent tests ==="
	$(TEST_C_OUT)

$(TEST_C_OUT): $(TEST_C_SRC) $(TEST_C_DEPS)
	@mkdir -p $(dir $@)
	$(CC_LINUX) -std=c11 -Wall -Wextra -g -D_GNU_SOURCE \
		-I$(AGENT_INC) -I$(JNI_INCLUDE) -I$(JNI_INCLUDE_OS) \
		-o $@ $(TEST_C_SRC) $(TEST_C_DEPS) -lpthread -ldl
	@echo "Built: $@"

test-java:
	@echo ""
	@echo "=== Running Java collector tests ==="
	cd collector && mvn test

clean:
	rm -rf $(DIST_DIR) build build-win
	cd collector && mvn clean -q 2>/dev/null || true
	@echo "Clean."
