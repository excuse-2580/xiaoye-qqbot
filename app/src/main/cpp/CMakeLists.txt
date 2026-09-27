cmake_minimum_required(VERSION 3.22.1)
project(ggml-jni CXX)

# ---------------------------------------------------------------------------
# llama.cpp 源码位置（git submodule 或 scripts/fetch-llama-cpp.sh 拉下来的）
# ---------------------------------------------------------------------------
# 路径说明（从 app/src/main/cpp 往上数）：
#   ..        → app/src/main
#   ../..     → app/src
#   ../../..  → app
#   ../../../.. → android-native   ← llama.cpp 就放在这一层的 third_party/
set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp"
    CACHE PATH "llama.cpp 源码目录")

if(NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "\n\n"
        "  找不到 llama.cpp 源码：${LLAMA_DIR}\n\n"
        "  请先执行（项目根目录）：\n"
        "      ./scripts/fetch-llama-cpp.sh\n\n"
        "  或者用 submodule：\n"
        "      git submodule add https://github.com/ggml-org/llama.cpp third_party/llama.cpp\n"
        "      git submodule update --init --recursive\n\n")
endif()

# ---------------------------------------------------------------------------
# llama.cpp 的编译开关
# 这些必须在 add_subdirectory 之前设置，否则不生效
# ---------------------------------------------------------------------------
set(BUILD_SHARED_LIBS          OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS          OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS          OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES       OFF CACHE BOOL "" FORCE)

# Android 上必须关掉这两个：
#   OpenMP  —— NDK 不把它列为稳定原生 API，CMake 装不上依赖
#   llamafile —— 后端不支持 Android
set(GGML_OPENMP                OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE             OFF CACHE BOOL "" FORCE)

# 想试 GPU 加速就把它打开（成不成看手机 GPU 驱动，翻车了再关回来）
#   ./gradlew assembleDebug -PggmlVulkan=true
option(GGML_VULKAN "用 Vulkan 跑 GPU 推理" OFF)

set(GGML_STATIC                ON  CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL            OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_subdirectory(${LLAMA_DIR} ${CMAKE_BINARY_DIR}/llama.cpp)

# ---------------------------------------------------------------------------
# 目标：单个 libggml-jni.so
# ---------------------------------------------------------------------------
add_library(ggml-jni SHARED ggml_jni.cpp)

target_include_directories(ggml-jni PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/src
    ${LLAMA_DIR}/ggml/include
)

target_link_libraries(ggml-jni PRIVATE
    llama
    android
    log
)

target_compile_options(ggml-jni PRIVATE -O3 -fexceptions -frtti)
set_target_properties(ggml-jni PROPERTIES CXX_STANDARD 17)
