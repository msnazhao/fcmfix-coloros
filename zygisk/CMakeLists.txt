# Builds the Zygisk library: one self-contained .so with the ART hook engine linked in and the
# hook dex embedded. NeoZygisk loads module libraries from a memfd, so there is no directory
# next to the library to load anything else from.
#
# 3.22 here keeps CMP0155 (module scanning for every C++20 source) off for our own sources,
# which only include lsplant.hpp. LSPlant's own CMakeLists asks for 3.28 and C++ modules.
cmake_minimum_required(VERSION 3.22)
project(fcmfix_zygisk LANGUAGES C CXX ASM)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if (NOT FCMFIX_DEX OR NOT EXISTS "${FCMFIX_DEX}")
    message(FATAL_ERROR "FCMFIX_DEX must point at the hook dex; run zygisk/build.sh")
endif ()

# --- ART hook engine: the LSPlant and Dobby commits Vector canary 3118 ships -----------------
set(LSPLANT_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(Plugin.SymbolResolver OFF CACHE BOOL "" FORCE)
add_subdirectory(external/dobby)
add_subdirectory(external/lsplant/lsplant/src/main/jni)
target_compile_options(lsplant_static PUBLIC
        -Wno-gnu-anonymous-struct
        -Wno-gnu-string-literal-operator-template)

# --- XZ decoder, for libart's .gnu_debugdata ------------------------------------------------
set(XZ_DIR external/xz-embedded)
add_library(xz_static STATIC
        ${XZ_DIR}/linux/lib/xz/xz_crc32.c
        ${XZ_DIR}/linux/lib/xz/xz_crc64.c
        ${XZ_DIR}/linux/lib/xz/xz_dec_lzma2.c
        ${XZ_DIR}/linux/lib/xz/xz_dec_stream.c)
target_include_directories(xz_static PUBLIC ${XZ_DIR}/linux/include/linux)
target_include_directories(xz_static PRIVATE ${XZ_DIR}/userspace)
target_compile_definitions(xz_static PUBLIC XZ_USE_CRC64)

# --- The module -----------------------------------------------------------------------------
add_library(${PROJECT_NAME} SHARED
        src/module.cpp
        src/elf_symbols.cpp
        src/dex_blob.S)
set_source_files_properties(src/dex_blob.S PROPERTIES
        COMPILE_DEFINITIONS "FCMFIX_DEX_PATH=\"${FCMFIX_DEX}\""
        OBJECT_DEPENDS "${FCMFIX_DEX}")
target_compile_options(${PROJECT_NAME} PRIVATE
        -Wall -Wextra -fvisibility=hidden -fvisibility-inlines-hidden)
# Only zygisk_module_entry is exported; nothing from the static libraries leaks out.
target_link_options(${PROJECT_NAME} PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections)
target_link_libraries(${PROJECT_NAME} PRIVATE lsplant_static dobby_static xz_static log)
