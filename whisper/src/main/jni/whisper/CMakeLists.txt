cmake_minimum_required(VERSION 3.22)
project(bitacora_whisper C CXX)

set(CMAKE_CXX_STANDARD 17)
# whisper/src/main/jni/whisper → repo root → third_party/whisper.cpp (git submodule, tag v1.9.5)
get_filename_component(WHISPER_LIB_DIR "${CMAKE_SOURCE_DIR}/../../../../../third_party/whisper.cpp" ABSOLUTE)
if(NOT EXISTS "${WHISPER_LIB_DIR}/src/whisper.cpp")
    message(FATAL_ERROR "whisper.cpp submodule missing at ${WHISPER_LIB_DIR}: run git submodule update --init")
endif()

# The submodule is pinned to a release tag; whisper_version() reports it.
execute_process(COMMAND git -C "${WHISPER_LIB_DIR}" describe --tags --always OUTPUT_VARIABLE WHISPER_VERSION OUTPUT_STRIP_TRAILING_WHITESPACE ERROR_QUIET)
if(NOT WHISPER_VERSION)
    set(WHISPER_VERSION "unknown")
endif()
message(STATUS "whisper.cpp version: ${WHISPER_VERSION}")

set(SOURCE_FILES
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    ${WHISPER_LIB_DIR}/src/aneforge/whisper-aneforge.cpp
    ${CMAKE_SOURCE_DIR}/jni.c)

find_library(LOG_LIB log)
include(FetchContent)

# ggml options: CPU only, no OpenMP (not shipped by the NDK), no extra backends.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

function(build_library target_name)
    add_library(${target_name} SHARED ${SOURCE_FILES})
    target_compile_definitions(${target_name} PUBLIC GGML_USE_CPU)
    target_compile_definitions(${target_name} PRIVATE WHISPER_VERSION="${WHISPER_VERSION}")
    if(${target_name} STREQUAL "whisper_v8fp16_va")
        target_compile_options(${target_name} PRIVATE -march=armv8.2-a+fp16)
        set(GGML_COMPILE_OPTIONS -march=armv8.2-a+fp16)
    endif()
    # Optimised in every variant: debug whisper is unusably slow (skill voice-journal).
    target_compile_options(${target_name} PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections)
    target_link_options(${target_name} PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
    FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_LIB_DIR}/ggml)
    FetchContent_MakeAvailable(ggml)
    target_compile_options(ggml PRIVATE ${GGML_COMPILE_OPTIONS})
    target_link_libraries(${target_name} ${LOG_LIB} android ggml)
endfunction()

if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    build_library("whisper_v8fp16_va")
endif()
build_library("whisper")

include_directories(${WHISPER_LIB_DIR})
include_directories(${WHISPER_LIB_DIR}/src)
include_directories(${WHISPER_LIB_DIR}/include)
include_directories(${WHISPER_LIB_DIR}/ggml/include)
include_directories(${WHISPER_LIB_DIR}/ggml/src)
include_directories(${WHISPER_LIB_DIR}/ggml/src/ggml-cpu)
