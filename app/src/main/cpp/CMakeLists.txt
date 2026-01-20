cmake_minimum_required(VERSION 3.22.1)

project(hy_mt_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---- llama.cpp (located at repo root: /llama.cpp) ----
get_filename_component(REPO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../" ABSOLUTE)
set(LLAMA_CPP_DIR "${REPO_ROOT}/llama.cpp")

# Reduce build size/time for Android app use
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_HTTPLIB OFF CACHE BOOL "" FORCE)

# Prefer static llama/ggml and link into our JNI shared library
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_CPP_DIR}" llama_cpp_build)

foreach(_tgt llama ggml ggml-base ggml-cpu)
    if (TARGET ${_tgt})
        target_compile_options(${_tgt} PRIVATE
                $<$<CONFIG:Debug>:-O3>
        )
    endif()
endforeach()

add_library(hy_mt_jni SHARED
        hy_mt/hy_mt_jni.cpp
)

target_compile_options(hy_mt_jni PRIVATE
        $<$<CONFIG:Debug>:-O3>
)

target_include_directories(hy_mt_jni PRIVATE
        "${LLAMA_CPP_DIR}/include"
)

target_link_libraries(hy_mt_jni
        llama
        android
        log
)
