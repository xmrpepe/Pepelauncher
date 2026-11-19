/* pepelauncher compositor: server setup, event loop and the cross-thread command queue. */
#define _GNU_SOURCE
#include <errno.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/eventfd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include "pepe.h"

struct sync_cmd {
    struct pepe_cmd cmd; /* first member: the queue holds &sync_cmd.cmd */
    pthread_cond_t done_cond;
    bool done;
};

uint32_t pepe_next_serial(struct pepe_server *s) {
    return wl_display_next_serial(s->display);
}

uint32_t pepe_now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint32_t) (ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}

void pepe_log(struct pepe_server *s, const char *fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    if (s && s->platform && s->platform->log) s->platform->log(s->platform->data, buf);
    else fprintf(stderr, "pepe: %s\n", buf);
}

struct pepe_cmd *pepe_cmd_new(enum pepe_cmd_type type, uint32_t window) {
    struct pepe_cmd *c = calloc(1, sizeof *c);
    c->type = type;
    c->window = window;
    return c;
}

static void enqueue(struct pepe_server *s, struct pepe_cmd *cmd) {
    cmd->next = NULL;
    pthread_mutex_lock(&s->lock);
    if (s->queue_tail) s->queue_tail->next = cmd;
    else s->queue_head = cmd;
    s->queue_tail = cmd;
    pthread_mutex_unlock(&s->lock);
    uint64_t one = 1;
    ssize_t r = write(s->wake_fd, &one, sizeof one);
    (void) r;
}

void pepe_post(struct pepe_server *s, struct pepe_cmd *cmd) {
    enqueue(s, cmd);
}

/* Marks a queued command whose poster waits for completion (it is embedded in a sync_cmd). */
#define SYNC_FLAG 0x10000

void pepe_post_sync(struct pepe_server *s, struct pepe_cmd *cmd) {
    struct sync_cmd *sc = calloc(1, sizeof *sc);
    sc->cmd = *cmd; /* takes over cmd->text */
    free(cmd);
    pthread_cond_init(&sc->done_cond, NULL);
    sc->cmd.type |= SYNC_FLAG;
    enqueue(s, &sc->cmd);
    pthread_mutex_lock(&s->lock);
    while (!sc->done) pthread_cond_wait(&sc->done_cond, &s->lock);
    pthread_mutex_unlock(&s->lock);
    pthread_cond_destroy(&sc->done_cond);
    free(sc);
}

/* Android sends pixel coordinates; convert with the scale the last frame was drawn at. */
static void to_logical(struct pepe_window *w, double *x, double *y) {
    double scale = w->render_scale > 0 ? w->render_scale : (w->scale > 0 ? w->scale : 1.0);
    *x /= scale;
    *y /= scale;
}

static void handle_cmd(struct pepe_server *s, struct pepe_cmd *c) {
    struct pepe_window *w = c->window ? pepe_window_find(s, c->window) : NULL;
    switch (c->type) {
    case PEPE_CMD_ATTACH:
        if (!w) {
            /* The window went away before its Activity attached: hand the target back. */
            if (c->ptr && s->platform->target_release) s->platform->target_release(s->platform->data, c->ptr);
            break;
        }
        if (w->target && w->target != c->ptr && s->platform->target_release)
            s->platform->target_release(s->platform->data, w->target);
        w->target = c->ptr;
        w->target_w = c->i0;
        w->target_h = c->i1;
        if (c->d0 > 0) w->scale = c->d0;
        pepe_window_configure(w);
        pepe_window_schedule_redraw(w);
        break;
    case PEPE_CMD_DETACH:
        if (w && w->target) {
            if (s->platform->target_release) s->platform->target_release(s->platform->data, w->target);
            w->target = NULL;
        }
        break;
    case PEPE_CMD_RESIZE:
        if (!w) break;
        w->target_w = c->i0;
        w->target_h = c->i1;
        if (c->d0 > 0) w->scale = c->d0;
        pepe_window_configure(w);
        pepe_window_schedule_redraw(w);
        break;
    case PEPE_CMD_FOCUS:
        if (!w) break;
        w->focused = c->i0 != 0;
        if (w->xdg) pepe_xdg_configure_toplevel(w);
        if (w->xwin) pepe_xwm_focus(w->xwin, w->focused);
        if (w->focused) pepe_seat_set_keyboard_focus(s, w->surface);
        else if (s->seat.keyboard_focus == w->surface) pepe_seat_set_keyboard_focus(s, NULL);
        break;
    case PEPE_CMD_CLOSE:
        if (!w) break;
        w->closing = true;
        if (w->xdg) pepe_xdg_close(w);
        if (w->xwin) pepe_xwm_close(w->xwin);
        break;
    case PEPE_CMD_KILL:
        if (!w) break;
        if (w->xwin) {
            pepe_xwm_kill(w->xwin);
        } else if (w->surface) {
            struct wl_client *client = wl_resource_get_client(w->surface->resource);
            if (client == s->xwayland_client) break;
            pepe_log(s, "killing unresponsive client of window %u", w->id);
            wl_client_destroy(client);
        }
        break;
    case PEPE_CMD_POINTER_MOTION:
        if (!w) break;
        to_logical(w, &c->d0, &c->d1);
        pepe_seat_pointer_motion(s, w, c->d0, c->d1);
        break;
    case PEPE_CMD_POINTER_BUTTON:
        if (w) pepe_seat_pointer_button(s, w, (uint32_t) c->i0, c->i1 != 0);
        break;
    case PEPE_CMD_POINTER_AXIS:
        if (!w) break;
        to_logical(w, &c->d0, &c->d1);
        pepe_seat_pointer_axis(s, w, c->d0, c->d1);
        break;
    case PEPE_CMD_POINTER_LEAVE:
        pepe_seat_pointer_leave(s);
        break;
    case PEPE_CMD_TOUCH:
        if (!w) break;
        to_logical(w, &c->d0, &c->d1);
        pepe_seat_touch(s, w, c->i0, c->i1, c->d0, c->d1);
        break;
    case PEPE_CMD_KEY:
        pepe_seat_key(s, (uint32_t) c->i0, c->i1 != 0);
        break;
    case PEPE_CMD_TEXT:
        if (c->text) pepe_seat_text(s, c->text);
        break;
    case PEPE_CMD_PREEDIT:
        pepe_seat_preedit(s, c->text ? c->text : "");
        break;
    case PEPE_CMD_DELETE_SURROUNDING:
        pepe_seat_delete_surrounding(s, (uint32_t) c->i0, (uint32_t) c->i1);
        break;
    case PEPE_CMD_CLIPBOARD:
        pepe_seat_set_android_clipboard(s, c->text);
        break;
    case PEPE_CMD_SETTINGS:
        if (c->d0 > 0) s->default_scale = c->d0;
        if (c->i0 > 0 && c->i1 > 0) {
            s->screen_w = (int32_t) (c->i0 / s->default_scale);
            s->screen_h = (int32_t) (c->i1 / s->default_scale);
        }
        s->dialogs_own_task = c->i2 != 0;
        pepe_output_update(s);
        break;
    case PEPE_CMD_XWAYLAND:
        pepe_xwm_start(s, c->i0, c->i1, c->i2);
        break;
    case PEPE_CMD_STOP:
        wl_display_terminate(s->display);
        break;
    }
}

static int on_wake(int fd, uint32_t mask, void *data) {
    struct pepe_server *s = data;
    uint64_t count;
    ssize_t r = read(fd, &count, sizeof count);
    (void) r;
    (void) mask;
    for (;;) {
        pthread_mutex_lock(&s->lock);
        struct pepe_cmd *c = s->queue_head;
        if (c) {
            s->queue_head = c->next;
            if (!s->queue_head) s->queue_tail = NULL;
        }
        pthread_mutex_unlock(&s->lock);
        if (!c) break;

        bool sync = (c->type & SYNC_FLAG) != 0;
        c->type &= ~SYNC_FLAG;
        handle_cmd(s, c);
        free(c->text);
        c->text = NULL;
        if (sync) {
            struct sync_cmd *sc = (struct sync_cmd *) c;
            pthread_mutex_lock(&s->lock);
            sc->done = true;
            pthread_cond_signal(&sc->done_cond);
            pthread_mutex_unlock(&s->lock);
        } else {
            free(c);
        }
    }
    wl_display_flush_clients(s->display);
    return 0;
}

/* Binds the listening socket ourselves: the path is fixed (bind-mounted into proot) rather than
 * derived from XDG_RUNTIME_DIR, and stale sockets from a killed process are replaced. */
static int bind_socket(const char *path) {
    struct sockaddr_un addr = { .sun_family = AF_UNIX };
    if (strlen(path) >= sizeof addr.sun_path) return -1;
    strcpy(addr.sun_path, path);
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    unlink(path);
    if (bind(fd, (struct sockaddr *) &addr, sizeof addr) < 0 || listen(fd, 128) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

struct pepe_server *pepe_server_create(const char *socket_path, const struct pepe_platform *platform,
                                       double scale, int32_t screen_w_px, int32_t screen_h_px) {
    struct pepe_server *s = calloc(1, sizeof *s);
    s->platform = platform;
    s->socket_path = strdup(socket_path);
    s->default_scale = scale > 0 ? scale : 1.0;
    s->screen_w = screen_w_px > 0 ? (int32_t) (screen_w_px / s->default_scale) : 1280;
    s->screen_h = screen_h_px > 0 ? (int32_t) (screen_h_px / s->default_scale) : 720;
    s->dialogs_own_task = true;
    s->next_window_id = 1;
    wl_list_init(&s->windows);
    wl_list_init(&s->outputs);
    pthread_mutex_init(&s->lock, NULL);

    s->display = wl_display_create();
    if (!s->display) goto fail;
    s->loop = wl_display_get_event_loop(s->display);

    int fd = bind_socket(socket_path);
    if (fd < 0 || wl_display_add_socket_fd(s->display, fd) < 0) {
        pepe_log(s, "cannot listen on %s: %s", socket_path, strerror(errno));
        if (fd >= 0) close(fd);
        goto fail;
    }

    s->wake_fd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    s->wake_source = wl_event_loop_add_fd(s->loop, s->wake_fd, WL_EVENT_READABLE, on_wake, s);

    wl_display_init_shm(s->display);
    pepe_surface_globals_init(s);
    pepe_xdg_init(s);
    pepe_output_init(s);
    pepe_seat_init(s);
    pepe_xwm_shell_init(s);
    pepe_log(s, "listening on %s (scale %.2f)", socket_path, s->default_scale);
    return s;

fail:
    if (s->display) wl_display_destroy(s->display);
    free(s->socket_path);
    free(s);
    return NULL;
}

void pepe_server_run(struct pepe_server *s) {
    wl_display_run(s->display);
}

void pepe_server_destroy(struct pepe_server *s) {
    pepe_xwm_destroy(s);
    wl_display_destroy_clients(s->display);
    struct pepe_window *w, *tmp;
    wl_list_for_each_safe(w, tmp, &s->windows, link) pepe_window_destroy(w);
    pepe_seat_finish(s);
    wl_event_source_remove(s->wake_source);
    close(s->wake_fd);
    wl_display_destroy(s->display);
    unlink(s->socket_path);
    free(s->socket_path);
    pthread_mutex_destroy(&s->lock);
    free(s);
}
