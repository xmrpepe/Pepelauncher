/* pepelauncher compositor: start Xwayland with inherited socketpairs (see xwayland_launch.h). */
#define _GNU_SOURCE
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include "xwayland_launch.h"

extern char **environ;

static char *substitute(const char *in, int wl, int wm, int ready) {
    char buf[4096];
    size_t o = 0;
    for (const char *p = in; *p && o < sizeof buf - 16;) {
        int v = -1;
        size_t skip = 0;
        if (!strncmp(p, "{WAYLAND_SOCKET}", 16)) { v = wl; skip = 16; }
        else if (!strncmp(p, "{WM_FD}", 7)) { v = wm; skip = 7; }
        else if (!strncmp(p, "{DISPLAY_FD}", 12)) { v = ready; skip = 12; }
        if (skip) {
            o += (size_t) snprintf(buf + o, sizeof buf - o, "%d", v);
            p += skip;
        } else {
            buf[o++] = *p++;
        }
    }
    buf[o] = 0;
    return strdup(buf);
}

static char **substitute_all(const char *const in[], int wl, int wm, int ready) {
    size_t n = 0;
    while (in[n]) n++;
    char **out = calloc(n + 1, sizeof *out);
    for (size_t i = 0; i < n; i++) out[i] = substitute(in[i], wl, wm, ready);
    return out;
}

static void free_all(char **v) {
    if (!v) return;
    for (char **p = v; *p; p++) free(*p);
    free(v);
}

pid_t pepe_xwayland_spawn(const char *const argv[], const char *const envp[],
                          int *wm_fd, int *wl_fd, int *ready_fd) {
    int wl[2], wm[2], ready[2];
    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, wl) < 0) return -1;
    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, wm) < 0) {
        close(wl[0]); close(wl[1]);
        return -1;
    }
    if (pipe2(ready, O_CLOEXEC) < 0) {
        close(wl[0]); close(wl[1]); close(wm[0]); close(wm[1]);
        return -1;
    }
    /* Prepared before fork: only async-signal-safe calls happen in the child. */
    char **child_argv = substitute_all(argv, wl[1], wm[1], ready[1]);
    char **child_envp = envp ? substitute_all(envp, wl[1], wm[1], ready[1]) : NULL;

    pid_t pid = fork();
    if (pid == 0) {
        fcntl(wl[1], F_SETFD, 0);
        fcntl(wm[1], F_SETFD, 0);
        fcntl(ready[1], F_SETFD, 0);
        if (child_envp) execve(child_argv[0], child_argv, child_envp);
        else execve(child_argv[0], child_argv, environ);
        _exit(127);
    }
    free_all(child_argv);
    free_all(child_envp);
    close(wl[1]);
    close(wm[1]);
    close(ready[1]);
    if (pid < 0) {
        close(wl[0]); close(wm[0]); close(ready[0]);
        return -1;
    }
    *wl_fd = wl[0];
    *wm_fd = wm[0];
    *ready_fd = ready[0];
    return pid;
}
