#!/bin/bash
# Desktop smoke test of the compositor with real clients from an Arch rootfs (see docs/BUILDING.md).
# Needs: a host proot build (PROOT_DIR with proot + loader), a configured rootfs under WORK/arch with
# foot, mousepad, xorg-xwayland and xorg-xeyes installed, and a built pepe-host (HOST_BIN).
# Checks the step-5 milestones: foot gets a window, two mousepads get two windows, xeyes works
# through Xwayland, and closing a window removes it. Snapshots land in $OUT.
set -u
PROOT_DIR=${PROOT_DIR:?}; WORK=${WORK:?}; HOST_BIN=${HOST_BIN:?}; OUT=${OUT:-/tmp/pepe-smoke}
rm -rf "$OUT"; mkdir -p "$OUT" "$WORK/tmp/xdg"; chmod 700 "$WORK/tmp/xdg"
FIFO="$OUT/cmd"; mkfifo "$FIFO"
PROOT=( /usr/bin/env PROOT_LOADER="$PROOT_DIR/loader" PROOT_TMP_DIR="$WORK/proot-tmp" "$PROOT_DIR/proot" --kill-on-exit
        --link2symlink -0 --sysvipc -r "$WORK/arch" -b /dev -b /proc -b /sys -b "$WORK/tmp:/tmp"
        -b "$WORK/tmp:/dev/shm" -w /root /usr/bin/env -i HOME=/root PATH=/usr/bin LANG=C.UTF-8
        XDG_RUNTIME_DIR=/tmp/xdg GLYCIN_DISABLE_SANDBOX=i-know-the-risks )
rm -f "$WORK/tmp/.X0-lock" "$WORK/tmp/.X11-unix/X0"
"$HOST_BIN" "$WORK/tmp/xdg/wayland-0" "$OUT" "$FIFO" 2 720x1280 -- "${PROOT[@]}" 'WAYLAND_SOCKET={WAYLAND_SOCKET}' \
    /usr/bin/Xwayland :0 -rootless -noreset -wm '{WM_FD}' -displayfd '{DISPLAY_FD}' > "$OUT/events" 2> "$OUT/compositor.log" &
COMP=$!
sleep 4
client() { "${PROOT[@]}" WAYLAND_DISPLAY=wayland-0 DISPLAY=:0 GDK_BACKEND=wayland,x11 "$@" > "$OUT/$1.log" 2>&1 & }
client foot; sleep 6
client mousepad; sleep 8
client mousepad; sleep 8
client xeyes; sleep 6
fail=0
check() { if grep -q "$1" "$OUT/events"; then echo "ok   $2"; else echo "FAIL $2"; fail=1; fi; }
check "added .* 'foot'" "foot opens a window"
[ "$(grep -c "added .*org.xfce.mousepad" "$OUT/events")" -ge 2 ] && echo "ok   two mousepads, two windows" || { echo "FAIL two mousepads, two windows"; fail=1; }
check "added .* 'XEyes'" "xeyes opens a window through Xwayland"
xeyes_id=$(grep "added .* 'XEyes'" "$OUT/events" | awk '{print $2}')
[ -n "$xeyes_id" ] && echo "close $xeyes_id" > "$FIFO"; sleep 3
check "removed $xeyes_id" "closing a window removes it"
echo quit > "$FIFO"; sleep 1; kill $COMP 2>/dev/null; pkill -f "$PROOT_DIR/proot" 2>/dev/null
echo "snapshots: $OUT/window-*.png"
exit $fail
