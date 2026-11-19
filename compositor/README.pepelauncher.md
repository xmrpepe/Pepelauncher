# pepelauncher compositor

A small Wayland compositor (C, NDK) in which every toplevel window is shown by its own Android
Activity and task. Platform independent core in `src/main/cpp/pepe/`; `android.c` is the app
backend, `host/main.c` a desktop test backend.

## Vendored third-party code (`src/main/cpp/third_party/`)

| Library | Version | License | What is vendored |
|---|---|---|---|
| libwayland-server | 1.26.0 (gitlab.freedesktop.org/wayland/wayland) | MIT | `src/` server sources + generated `wayland-protocol.c`, `wayland-server-protocol.h`, `wayland-version.h`; hand-written `config.h` |
| libffi | 3.8.1 (github.com/libffi/libffi) | MIT | core + aarch64/arm/x86-64 backends; `ffi.h` generated from `ffi.h.in` with the target chosen by the preprocessor; hand-written `fficonfig.h` |
| libxkbcommon | 1.13.2 (github.com/xkbcommon/libxkbcommon) | MIT | core library (no compose, x11, registry); bison-generated `parser.c/h`; hand-written `config.h` |
| libxcb | 1.17.0 (gitlab.freedesktop.org/xorg/lib/libxcb) | MIT | core sources; protocol bindings generated from xcb-proto 1.17.0 (`xproto`, `bigreq`, `xc_misc`, `render`, `shape`, `xfixes`, `composite`); `xcb_auth.c` replaced by `xcb_auth_stub.c` (connections are pre-opened socketpairs, so libXau is not needed) |

Generated protocol code in `src/main/cpp/generated/protocols/` comes from wayland-protocols 1.50
(MIT) via `wayland-scanner server-header` / `private-code`: xdg-shell, xdg-decoration-unstable-v1,
viewporter, fractional-scale-v1, text-input-unstable-v3, xwayland-shell-v1.

`src/main/cpp/generated/keymap_us.h` is the US (evdev/pc105) keymap compiled by xkbcommon from
xeyboard-config, with keycodes above 255 removed (Xwayland's xkbcomp rejects them).

### Regenerating

```sh
# wayland: builds wayland-scanner and the core protocol code
meson setup build -Dlibraries=true -Dscanner=true -Ddocumentation=false -Dtests=false && ninja -C build
build/src/wayland-scanner server-header <protocol.xml> <name>-protocol.h
build/src/wayland-scanner private-code  <protocol.xml> <name>-protocol.c
# xkbcommon: parser.c/parser.h from bison
meson setup build -Denable-x11=false -Denable-wayland=false -Denable-docs=false -Denable-tools=false -Denable-xkbregistry=false
# libxcb bindings
python3 libxcb/src/c_client.py -c "libxcb 1.17.0" -l "X Version 11" -s Library -p xcbproto xcbproto/src/<ext>.xml
```

## Desktop test backend

```sh
cmake -S compositor/host -B /tmp/pepe-host -G Ninja && ninja -C /tmp/pepe-host
mkfifo /tmp/cmd
/tmp/pepe-host/pepe-host <socket> <outdir> /tmp/cmd 2 720x1280 [-- <command that runs Xwayland>]
```

Each window is rendered into a 720x1280 buffer and saved as `<outdir>/window-<id>.png`;
commands written to the FIFO inject input (see `host/main.c`).
