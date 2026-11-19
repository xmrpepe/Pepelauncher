# The platform-independent compositor core plus its vendored dependencies.
# Included by src/main/cpp/CMakeLists.txt (Android) and host/CMakeLists.txt (desktop tests).
set(PEPE_CPP ${CMAKE_CURRENT_LIST_DIR})
add_subdirectory(${PEPE_CPP}/third_party ${CMAKE_BINARY_DIR}/third_party)

set(PEPE_PROTOCOLS ${PEPE_CPP}/generated/protocols)
add_library(pepe_core STATIC
    ${PEPE_CPP}/pepe/server.c
    ${PEPE_CPP}/pepe/surface.c
    ${PEPE_CPP}/pepe/window.c
    ${PEPE_CPP}/pepe/xdg.c
    ${PEPE_CPP}/pepe/render.c
    ${PEPE_CPP}/pepe/seat.c
    ${PEPE_CPP}/pepe/output.c
    ${PEPE_CPP}/pepe/xwm.c
    ${PEPE_CPP}/pepe/xwayland_launch.c
    ${PEPE_PROTOCOLS}/xdg-shell-protocol.c
    ${PEPE_PROTOCOLS}/xdg-decoration-unstable-v1-protocol.c
    ${PEPE_PROTOCOLS}/viewporter-protocol.c
    ${PEPE_PROTOCOLS}/fractional-scale-v1-protocol.c
    ${PEPE_PROTOCOLS}/text-input-unstable-v3-protocol.c
    ${PEPE_PROTOCOLS}/xwayland-shell-v1-protocol.c)
target_include_directories(pepe_core PUBLIC ${PEPE_CPP}/pepe ${PEPE_PROTOCOLS})
target_compile_options(pepe_core PRIVATE -Wall -Wextra -Wno-unused-parameter -std=gnu11 -fvisibility=hidden)
target_link_libraries(pepe_core PUBLIC pepe_wayland_server pepe_xkbcommon pepe_xcb m)
set_target_properties(pepe_core PROPERTIES POSITION_INDEPENDENT_CODE ON)
