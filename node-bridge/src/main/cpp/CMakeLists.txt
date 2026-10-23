# CMakeLists.txt —— 备选构建方式（本机默认走 tools/build-bridge.mjs，不需要 CMake/ninja）
#
# 用 NDK toolchain file 构建：
#   cmake -S . -B build -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake \
#         -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DCMAKE_BUILD_TYPE=Release
#   cmake --build build
#
# 注意：两个 .so 的链接语义不同（见 node_host_client.c 文件头）：
#   · libnode_bridge.so   只链 log/dl，不链 libnode（靠 dlopen）
#   · libnode_host_client.so 必须留下 DT_NEEDED libnode.so（但 napi_* 运行期解析）

cmake_minimum_required(VERSION 3.18)
project(node_bridge LANGUAGES C)

set(CMAKE_C_STANDARD 11)

# ── 引导件 ────────────────────────────────────────────────────────────────────
add_library(node_bridge SHARED node_bridge.c)
target_compile_options(node_bridge PRIVATE -Wall -Wextra -fvisibility=hidden)
target_link_options(node_bridge PRIVATE -Wl,-soname,libnode_bridge.so)
target_link_libraries(node_bridge PRIVATE log dl android)

# ── NAPI addon（需要 Node 头文件 + libnode.so）──────────────────────────────────
set(NODE_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../node-runtime/include/node" CACHE PATH "Node headers")
set(LIBNODE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../node-runtime/src/main/jniLibs/arm64-v8a" CACHE PATH "libnode.so 所在目录")

if(EXISTS "${NODE_INCLUDE_DIR}/node_api.h" AND EXISTS "${LIBNODE_DIR}/libnode.so")
  add_library(node_host_client SHARED node_host_client.c)
  target_include_directories(node_host_client PRIVATE "${NODE_INCLUDE_DIR}")
  target_compile_options(node_host_client PRIVATE -Wall -Wextra -fvisibility=hidden)
  target_link_options(node_host_client PRIVATE
    -Wl,-soname,libnode_host_client.so
    -Wl,--no-as-needed
    -L${LIBNODE_DIR}
    -l:libnode.so)
  target_link_libraries(node_host_client PRIVATE log)
else()
  message(WARNING "跳过 node_host_client：缺 ${NODE_INCLUDE_DIR}/node_api.h 或 ${LIBNODE_DIR}/libnode.so（先跑 tools/provision.mjs）")
endif()
