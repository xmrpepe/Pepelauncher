/*
 * pepelauncher compositor: a small Wayland compositor in which every toplevel window is shown
 * by its own Android Activity (task). Platform independent; android.c and host/main.c provide
 * the window targets and the upcalls.
 *
 * Threading: everything here runs on the compositor thread (the wl_event_loop). Other threads
 * talk to it only through pepe_post() (command queue + eventfd).
 */
#pragma once

#include <stdbool.h>
#include <stdint.h>
#include <pthread.h>
#include <wayland-server-core.h>
#include <xkbcommon/xkbcommon.h>

#include "pepe_api.h"

struct pepe_server;
struct pepe_window;
struct pepe_xwm;

/* ---- surfaces ---- */

enum pepe_role {
    ROLE_NONE = 0,
    ROLE_XDG_TOPLEVEL,
    ROLE_XDG_POPUP,
    ROLE_SUBSURFACE,
    ROLE_XWAYLAND,
    ROLE_CURSOR,
};

struct pepe_surface_state {
    struct wl_resource *buffer;      /* attached wl_buffer (may be NULL = unmap) */
    bool buffer_attached;            /* attach() called since last commit */
    int32_t dx, dy;
    int32_t scale;
    bool has_src;
    double src_x, src_y, src_w, src_h; /* viewport source, buffer coordinates / scale */
    int32_t dst_w, dst_h;            /* viewport destination, -1 = unset */
    struct wl_list frame_callbacks;  /* wl_resource links */
};

struct pepe_surface {
    struct pepe_server *server;
    struct wl_resource *resource;
    enum pepe_role role;
    void *role_data;                 /* xdg_surface / subsurface / xwayland window */

    struct pepe_surface_state pending;
    struct wl_listener pending_buffer_destroy; /* clears pending.buffer if the client destroys it */
    /* committed state */
    /* SHM contents are copied at commit and the wl_buffer released at once, so clients can reuse
     * buffers freely and rendering never touches client memory (no SIGBUS handling needed). */
    int32_t buffer_width, buffer_height; /* in buffer pixels */
    uint32_t *pixels_copy;           /* ARGB8888, premultiplied, as in wl_shm */
    int32_t copy_stride;             /* in pixels */
    bool copy_has_alpha;
    int32_t scale;
    bool has_src;
    double src_x, src_y, src_w, src_h;
    int32_t dst_w, dst_h;
    int32_t width, height;           /* logical size */
    bool mapped;
    bool entered;                    /* wl_surface.enter(output) sent */

    struct wl_list frame_callbacks;  /* committed, waiting for the next frame */
    struct wl_list subsurfaces;      /* pepe_subsurface.parent_link, bottom → top */
    struct wl_list subsurfaces_pending;

    struct wl_resource *viewport;
    struct wl_resource *fractional_scale;
    struct wl_signal destroy_signal;
};

struct pepe_subsurface {
    struct pepe_surface *surface;
    struct pepe_surface *parent;
    struct wl_resource *resource;
    struct wl_list parent_link;
    struct wl_list pending_link;
    int32_t x, y, pending_x, pending_y;
    bool sync;
    bool has_cache;                  /* sync mode: a commit is cached until the parent commits */
    struct wl_listener surface_destroy;
};

/* ---- xdg-shell ---- */

struct pepe_xdg_surface {
    struct pepe_surface *surface;
    struct wl_listener surface_destroy;
    struct wl_resource *resource;
    struct wl_resource *toplevel;    /* xdg_toplevel resource, or NULL */
    struct wl_resource *popup;       /* xdg_popup resource, or NULL */
    struct pepe_window *window;      /* toplevel: the window it owns; popup: the window it is in */
    bool configured;
    uint32_t configure_serial;
    bool has_geometry, pending_has_geometry;
    int32_t geo_x, geo_y, geo_w, geo_h;
    int32_t pending_geo_x, pending_geo_y, pending_geo_w, pending_geo_h;

    /* popup */
    struct pepe_xdg_surface *parent;
    struct wl_list popups;           /* children popups, link = popup_link */
    struct wl_list popup_link;
    int32_t popup_x, popup_y;        /* relative to the parent's window geometry origin */
    bool grab;

    /* toplevel */
    char *title;
    char *app_id;
    struct pepe_xdg_surface *toplevel_parent;
    int32_t min_w, min_h, max_w, max_h;
};

/* ---- windows: one Android task each ---- */

struct pepe_window {
    struct pepe_server *server;
    struct wl_list children;                 /* dialogs attached to this window: child_link */
    struct wl_list child_link;
    struct wl_list link;
    uint32_t id;

    struct pepe_surface *surface;        /* the root surface */
    struct pepe_xdg_surface *xdg;        /* Wayland toplevel, or NULL */
    struct pepe_xwm_window *xwin;        /* X11 toplevel, or NULL */
    struct pepe_window *parent;          /* transient-for, when attached to the parent */

    char *title;
    char *app_id;

    /* the Android side */
    void *target;
    int32_t target_w, target_h;          /* pixels */
    double scale;                        /* pixels per logical unit (requested) */
    double render_scale;                 /* scale actually used: smaller when the client is
                                          * larger than its task (e.g. a GTK minimum width) */
    int32_t width, height;               /* logical size we configured */
    bool focused;
    bool announced;                      /* toplevel_added upcall sent */
    bool closing;
    bool redraw_scheduled;
    struct wl_event_source *redraw_idle;
    struct wl_event_source *kill_timer;
};

/* ---- seat ---- */

struct pepe_seat {
    struct wl_global *global;
    struct wl_list pointers, keyboards, touches; /* wl_resource links */

    struct xkb_context *xkb;
    struct xkb_keymap *keymap;
    struct xkb_state *state;
    int keymap_fd;
    uint32_t keymap_size;
    uint32_t mods_depressed, mods_latched, mods_locked, group;

    struct pepe_surface *pointer_focus;
    double pointer_x, pointer_y;         /* surface-local */
    uint32_t buttons_down;
    struct pepe_surface *keyboard_focus;
    struct pepe_surface *touch_focus[16];

    /* clipboard */
    struct wl_list data_devices;         /* wl_resource links */
    struct wl_resource *selection_source; /* client data source owning the selection */
    char *android_clipboard;             /* text offered to clients when no client owns it */
    struct wl_event_source *selection_read;
    int selection_read_fd;
    char *selection_buf;
    size_t selection_len;

    /* text-input-v3 */
    struct wl_list text_inputs;          /* wl_resource links */
    struct wl_resource *text_input_active;
};

/* ---- server ---- */

struct pepe_server {
    struct wl_display *display;
    struct wl_event_loop *loop;
    const struct pepe_platform *platform;
    char *socket_path;

    struct wl_list windows;              /* pepe_window.link */
    uint32_t next_window_id;
    struct pepe_seat seat;
    double default_scale;
    int32_t output_scale;                /* integer wl_output scale advertised */
    int32_t screen_w, screen_h;          /* logical size of the output */
    bool dialogs_own_task;
    struct wl_list outputs;              /* wl_resource links */

    /* command queue */
    pthread_mutex_t lock;
    struct pepe_cmd *queue_head, *queue_tail;
    int wake_fd;
    struct wl_event_source *wake_source;

    struct pepe_xwm *xwm;
    struct wl_client *xwayland_client;
};

/* server.c */
uint32_t pepe_next_serial(struct pepe_server *s);
uint32_t pepe_now_ms(void);
void pepe_log(struct pepe_server *s, const char *fmt, ...) __attribute__((format(printf, 2, 3)));

/* surface.c */
void pepe_surface_globals_init(struct pepe_server *s);
struct pepe_surface *pepe_surface_from_resource(struct wl_resource *r);
void pepe_surface_send_frame_done(struct pepe_surface *s, uint32_t time);
bool pepe_surface_accepts_input(struct pepe_surface *s, double x, double y);
void pepe_surface_set_preferred_scale(struct pepe_surface *s, double scale);

/* xdg.c */
void pepe_xdg_init(struct pepe_server *s);
void pepe_xdg_configure_toplevel(struct pepe_window *w);
void pepe_xdg_close(struct pepe_window *w);
void pepe_xdg_dismiss_popups(struct pepe_xdg_surface *xdg);
void pepe_xdg_surface_committed(struct pepe_surface *surface);

/* window.c */
struct pepe_window *pepe_window_create(struct pepe_server *s, struct pepe_surface *surface);
void pepe_window_destroy(struct pepe_window *w);
struct pepe_window *pepe_window_find(struct pepe_server *s, uint32_t id);
struct pepe_window *pepe_window_for_surface(struct pepe_surface *surface);
void pepe_window_schedule_redraw(struct pepe_window *w);
void pepe_window_set_title(struct pepe_window *w, const char *title);
void pepe_window_set_app_id(struct pepe_window *w, const char *app_id);
void pepe_window_announce(struct pepe_window *w);
void pepe_window_configure(struct pepe_window *w);
void pepe_window_get_geometry(struct pepe_window *w, int32_t *x, int32_t *y, int32_t *width, int32_t *height);

/* render.c: the scene is every visible surface of a window in paint order. */
struct pepe_scene_item {
    struct pepe_surface *surface;
    int32_t x, y; /* logical position, window-geometry coordinates */
};
struct pepe_scene {
    struct pepe_scene_item *items;
    size_t count, capacity;
};
void pepe_scene_build(struct pepe_window *w, struct pepe_scene *scene);
void pepe_scene_finish(struct pepe_scene *scene);
void pepe_render_window(struct pepe_window *w);
double pepe_window_fit_scale(struct pepe_window *w, int32_t px_w, int32_t px_h);
/* Topmost surface under window-geometry coordinates (x, y); returns surface-local coordinates. */
struct pepe_surface *pepe_surface_at(struct pepe_window *w, double x, double y, double *sx, double *sy);

/* seat.c */
void pepe_seat_init(struct pepe_server *s);
void pepe_seat_finish(struct pepe_server *s);
void pepe_seat_surface_destroyed(struct pepe_server *s, struct pepe_surface *surface);
void pepe_seat_pointer_motion(struct pepe_server *s, struct pepe_window *w, double x, double y);
void pepe_seat_pointer_button(struct pepe_server *s, struct pepe_window *w, uint32_t button, bool pressed);
void pepe_seat_pointer_axis(struct pepe_server *s, struct pepe_window *w, double dx, double dy);
void pepe_seat_pointer_leave(struct pepe_server *s);
void pepe_seat_touch(struct pepe_server *s, struct pepe_window *w, int type, int slot, double x, double y);
void pepe_seat_key(struct pepe_server *s, uint32_t evdev_key, bool pressed);
void pepe_seat_text(struct pepe_server *s, const char *utf8);
void pepe_seat_preedit(struct pepe_server *s, const char *utf8);
void pepe_seat_delete_surrounding(struct pepe_server *s, uint32_t before, uint32_t after);
void pepe_seat_set_keyboard_focus(struct pepe_server *s, struct pepe_surface *surface);
void pepe_seat_set_android_clipboard(struct pepe_server *s, const char *text);
/* Sends the current clipboard (client selection or Android text) to a data device. */
void pepe_seat_offer_selection(struct pepe_server *s, struct wl_resource *device);

/* output.c */
void pepe_output_init(struct pepe_server *s);
void pepe_output_update(struct pepe_server *s);

/* xwm.c (X11 window manager for Xwayland) */
int pepe_xwm_start(struct pepe_server *s, int wm_fd, int wayland_fd, int ready_fd);
void pepe_xwm_destroy(struct pepe_server *s);
void pepe_xwm_close(struct pepe_xwm_window *xw);
void pepe_xwm_kill(struct pepe_xwm_window *xw);
void pepe_xwm_configure(struct pepe_xwm_window *xw, int32_t width, int32_t height);
void pepe_xwm_focus(struct pepe_xwm_window *xw, bool focused);
void pepe_xwm_shell_init(struct pepe_server *s);
void pepe_xwm_surface_destroyed(struct pepe_server *s, struct pepe_surface *surface);
/* The window an Xwayland surface is shown in (its own, or the one a menu belongs to). */
struct pepe_window *pepe_xwm_window_of(struct pepe_surface *surface);
void pepe_xwm_surface_committed(struct pepe_surface *surface);
/* Override-redirect X windows (menus, tooltips) drawn inside window w: */
void pepe_xwm_for_each_overlay(struct pepe_window *w,
                               void (*fn)(struct pepe_surface *, int32_t x, int32_t y, void *), void *data);
