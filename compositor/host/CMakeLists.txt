# Desktop test build of the compositor (see host/main.c). Not part of the app.
cmake_minimum_required(VERSION 3.22)
project(pepe_compositor_host C ASM)
include(${CMAKE_CURRENT_LIST_DIR}/../src/main/cpp/core.cmake)
find_package(ZLIB REQUIRED)
find_package(Threads REQUIRED)
add_executable(pepe-host main.c)
target_link_libraries(pepe-host pepe_core ZLIB::ZLIB Threads::Threads)
