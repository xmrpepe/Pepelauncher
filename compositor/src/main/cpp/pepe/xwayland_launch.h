/* Spawning Xwayland (inside proot) with the file descriptors the compositor needs. */
#pragma once
#include <sys/types.h>

/*
 * Runs argv (with envp, or the current environment when NULL) in a child process that inherits:
 *   - the client end of a Wayland socketpair  → replaces "{WAYLAND_SOCKET}" in argv/envp
 *   - the client end of the X WM socketpair   → replaces "{WM_FD}"
 *   - the write end of a readiness pipe        → replaces "{DISPLAY_FD}" (Xwayland -displayfd)
 * The compositor keeps *wm_fd, *wl_fd and *ready_fd. Returns the child's pid, or -1.
 */
pid_t pepe_xwayland_spawn(const char *const argv[], const char *const envp[],
                          int *wm_fd, int *wl_fd, int *ready_fd);
