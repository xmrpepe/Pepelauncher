# Vendored libraries for the pepelauncher compositor, built as static libraries.
# Generated sources (protocol code, xkbcommon's bison parser, xcb protocol bindings) are
# committed, so neither the Android build nor a host build needs meson, bison or python.
# See compositor/README.pepelauncher.md for versions and how they were generated.

set(TP ${CMAKE_CURRENT_SOURCE_DIR})

# ---- libffi 3.8.1 (MIT): needed by libwayland-server for request dispatch ----
if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64")
    set(FFI_ARCH aarch64)
    set(FFI_ARCH_SOURCES src/aarch64/ffi.c src/aarch64/sysv.S)
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "^arm|armv7")
    set(FFI_ARCH arm)
    set(FFI_ARCH_SOURCES src/arm/ffi.c src/arm/sysv.S)
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64|amd64")
    set(FFI_ARCH x86)
    set(FFI_ARCH_SOURCES src/x86/ffi64.c src/x86/unix64.S src/x86/ffiw64.c src/x86/win64.S)
else()
    message(FATAL_ERROR "libffi: unsupported processor ${CMAKE_SYSTEM_PROCESSOR}")
endif()
list(TRANSFORM FFI_ARCH_SOURCES PREPEND ${TP}/libffi/)
add_library(pepe_ffi STATIC
    ${TP}/libffi/src/prep_cif.c
    ${TP}/libffi/src/types.c
    ${TP}/libffi/src/raw_api.c
    ${TP}/libffi/src/java_raw_api.c
    ${TP}/libffi/src/closures.c
    ${FFI_ARCH_SOURCES})
target_include_directories(pepe_ffi PUBLIC ${TP}/libffi/include ${TP}/libffi/src/${FFI_ARCH})
target_compile_options(pepe_ffi PRIVATE -w)
set_target_properties(pepe_ffi PROPERTIES POSITION_INDEPENDENT_CODE ON)

# ---- libwayland-server 1.26.0 (MIT) ----
add_library(pepe_wayland_server STATIC
    ${TP}/wayland/src/wayland-util.c
    ${TP}/wayland/src/connection.c
    ${TP}/wayland/src/wayland-os.c
    ${TP}/wayland/src/wayland-protocol.c
    ${TP}/wayland/src/wayland-server.c
    ${TP}/wayland/src/wayland-shm.c
    ${TP}/wayland/src/event-loop.c)
target_include_directories(pepe_wayland_server PUBLIC ${TP}/wayland/src PRIVATE ${TP}/wayland)
target_compile_definitions(pepe_wayland_server PRIVATE _GNU_SOURCE)
target_compile_options(pepe_wayland_server PRIVATE -w -fvisibility=hidden)
target_link_libraries(pepe_wayland_server PUBLIC pepe_ffi)
set_target_properties(pepe_wayland_server PROPERTIES POSITION_INDEPENDENT_CODE ON)

# ---- libxkbcommon 1.13.2 (MIT), core library only ----
set(XKB ${TP}/xkbcommon/src)
add_library(pepe_xkbcommon STATIC
    ${XKB}/xkbcomp/action.c ${XKB}/xkbcomp/ast-build.c ${XKB}/xkbcomp/compat.c
    ${XKB}/xkbcomp/expr.c ${XKB}/xkbcomp/include.c ${XKB}/xkbcomp/keycodes.c
    ${XKB}/xkbcomp/keymap.c ${XKB}/xkbcomp/keymap-dump.c ${XKB}/xkbcomp/keywords.c
    ${TP}/xkbcommon/generated/parser.c
    ${XKB}/xkbcomp/rules.c ${XKB}/xkbcomp/scanner.c ${XKB}/xkbcomp/symbols.c
    ${XKB}/xkbcomp/types.c ${XKB}/xkbcomp/vmod.c ${XKB}/xkbcomp/xkbcomp.c
    ${XKB}/atom.c ${XKB}/context.c ${XKB}/context-priv.c ${XKB}/keysym.c
    ${XKB}/keysym-case-mappings.c ${XKB}/keysym-utf.c ${XKB}/keymap.c
    ${XKB}/keymap-compare.c ${XKB}/keymap-priv.c ${XKB}/rmlvo.c ${XKB}/scanner-utils.c
    ${XKB}/state.c ${XKB}/text.c ${XKB}/utf8.c ${XKB}/utf8-decoding.c ${XKB}/utils.c
    ${XKB}/utils-paths.c)
target_include_directories(pepe_xkbcommon
    PUBLIC ${TP}/xkbcommon/include
    PRIVATE ${TP}/xkbcommon ${XKB} ${TP}/xkbcommon/generated)
target_compile_options(pepe_xkbcommon PRIVATE -w -fvisibility=hidden -std=gnu11)
set_target_properties(pepe_xkbcommon PROPERTIES POSITION_INDEPENDENT_CODE ON)

# ---- libxcb 1.17.0 (MIT) with generated xproto/bigreq/xc_misc/render/shape/xfixes/composite ----
set(XCB ${TP}/xcb/src)
add_library(pepe_xcb STATIC
    ${XCB}/xcb_conn.c ${XCB}/xcb_ext.c ${XCB}/xcb_in.c ${XCB}/xcb_list.c ${XCB}/xcb_out.c
    ${XCB}/xcb_util.c ${XCB}/xcb_xid.c ${XCB}/xcb_auth_stub.c
    ${XCB}/xproto.c ${XCB}/bigreq.c ${XCB}/xc_misc.c ${XCB}/render.c ${XCB}/shape.c ${XCB}/xfixes.c ${XCB}/composite.c)
target_include_directories(pepe_xcb PUBLIC ${TP}/xcb/include PRIVATE ${TP}/xcb ${TP}/xcb/include/xcb ${XCB})
target_compile_definitions(pepe_xcb PRIVATE HAVE_CONFIG_H _GNU_SOURCE)
target_compile_options(pepe_xcb PRIVATE -w -fvisibility=hidden)
set_target_properties(pepe_xcb PROPERTIES POSITION_INDEPENDENT_CODE ON)
