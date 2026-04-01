cmake_minimum_required(VERSION 3.10)

find_package(JNI REQUIRED)

set(COMMON_SOURCES
    src/protocol.c
    src/ring_buffer.c
    src/log.c
    src/transport.c
    src/cpu_sampler.c
    src/gc_listener.c
    src/thread_monitor.c
    src/memory_monitor.c
    src/class_analyzer.c
    src/alarm_engine.c
    src/module_registry.c
    src/jmx_reader.c
    src/exception_tracker.c
    src/os_metrics.c
    src/jit_tracker.c
    src/class_histo.c
    src/finalizer_tracker.c
    src/safepoint_tracker.c
    src/native_mem.c
    src/gc_detail.c
    src/thread_cpu.c
    src/classloader_tracker.c
    src/string_table.c
    src/agent.c
)

if(WIN32)
    set(PLATFORM_SOURCES src/platform_win32.c)
else()
    set(PLATFORM_SOURCES src/platform_linux.c)
endif()

add_library(jvmmonitor SHARED ${COMMON_SOURCES} ${PLATFORM_SOURCES})

target_include_directories(jvmmonitor PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${JNI_INCLUDE_DIRS}
)

if(WIN32)
    target_link_libraries(jvmmonitor PRIVATE ws2_32)
    target_compile_definitions(jvmmonitor PRIVATE _CRT_SECURE_NO_WARNINGS)
else()
    target_link_libraries(jvmmonitor PRIVATE pthread dl)
    set_target_properties(jvmmonitor PROPERTIES
        C_VISIBILITY_PRESET hidden
    )
endif()

set_target_properties(jvmmonitor PROPERTIES
    PREFIX ""
    OUTPUT_NAME "jvmmonitor"
)
