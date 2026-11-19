/*
 * pepelauncher compositor: a minimal X11 window manager for rootless Xwayland.
 *
 * Each managed X toplevel becomes a pepe_window (its own Android task), exactly like an
 * xdg_toplevel. Override-redirect windows (menus, tooltips, drop-downs) are drawn inside the
 * window they belong to. X windows are matched to their wl_surface with xwayland_shell_v1
 * serials (Xwayland ≥ 23.1) or the older WL_SURFACE_ID client message.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <xcb/composite.h>
#include <xcb/xcb.h>

#include "pepe.h"
#include "xwayland-shell-v1-protocol.h"

enum {
    ATOM_WL_SURFACE_ID, ATOM_WL_SURFACE_SERIAL, ATOM_WM_PROTOCOLS, ATOM_WM_DELETE_WINDOW,
    ATOM_WM_TAKE_FOCUS, ATOM_WM_STATE, ATOM_WM_NAME, ATOM_NET_WM_NAME, ATOM_UTF8_STRING,
    ATOM_WM_CLASS, ATOM_WM_TRANSIENT_FOR, ATOM_NET_ACTIVE_WINDOW, ATOM_NET_SUPPORTED,
    ATOM_NET_SUPPORTING_WM_CHECK, ATOM_NET_WM_PID, ATOM_WM_S0, ATOM_NET_WM_CM_S0,
    ATOM_NET_WM_WINDOW_TYPE, ATOM_NET_WM_WINDOW_TYPE_DIALOG, ATOM_NET_WM_STATE,
    ATOM_COUNT
};

static const char *ATOM_NAMES[ATOM_COUNT] = {
    "WL_SURFACE_ID", "WL_SURFACE_SERIAL", "WM_PROTOCOLS", "WM_DELETE_WINDOW",
    "WM_TAKE_FOCUS", "WM_STATE", "WM_NAME", "_NET_WM_NAME", "UTF8_STRING",
    "WM_CLASS", "WM_TRANSIENT_FOR", "_NET_ACTIVE_WINDOW", "_NET_SUPPORTED",
    "_NET_SUPPORTING_WM_CHECK", "_NET_WM_PID", "WM_S0", "_NET_WM_CM_S0",
    "_NET_WM_WINDOW_TYPE", "_NET_WM_WINDOW_TYPE_DIALOG", "_NET_WM_STATE",
};

struct pepe_xwm {
    struct pepe_server *server;
    xcb_connection_t *conn;
    xcb_screen_t *screen;
    xcb_window_t wm_window;
    xcb_atom_t atoms[ATOM_COUNT];
    int wm_fd, ready_fd;
    struct wl_event_source *ready_source, *x_source;
    struct wl_list windows;              /* pepe_xwm_window.link */
    struct pepe_xwm_window *focus;
    struct pepe_xwm_window *last_toplevel;
};

struct pepe_xwm_window {
    struct pepe_xwm *xwm;
    struct wl_list link;
    xcb_window_t id;
    int16_t x, y;
    uint16_t width, height;
    bool override_redirect;
    bool map_requested;                  /* managed toplevel the client asked to show */
    bool mapped;                         /* MapNotify seen */
    uint32_t surface_id;                 /* WL_SURFACE_ID (old protocol) */
    uint64_t serial;                     /* WL_SURFACE_SERIAL */
    struct pepe_surface *surface;
    struct wl_listener surface_destroy;
    struct pepe_window *window;          /* when it is a toplevel with its own task */
    xcb_window_t transient_for;
    bool delete_window, take_focus;
    char *title, *class_name;
};

/* xwayland_shell_v1 objects: serial ↔ wl_surface, waiting for the matching X window. */
struct shell_surface {
    struct wl_list link;
    struct wl_resource *resource;
    struct pepe_surface *surface;
    uint64_t serial;
    struct wl_listener surface_destroy;
};
static struct wl_list shell_surfaces = { &shell_surfaces, &shell_surfaces };

/* ---- helpers ---- */

#define XWM_DEBUG(xwm, ...) do { if (getenv("PEPE_DEBUG")) pepe_log((xwm)->server, __VA_ARGS__); } while (0)

static struct pepe_xwm_window *lookup(struct pepe_xwm *xwm, xcb_window_t id) {
    struct pepe_xwm_window *xw;
    wl_list_for_each(xw, &xwm->windows, link) if (xw->id == id) return xw;
    return NULL;
}

static char *get_string(struct pepe_xwm *xwm, xcb_window_t win, xcb_atom_t prop, bool *found) {
    xcb_get_property_reply_t *r = xcb_get_property_reply(
        xwm->conn, xcb_get_property(xwm->conn, 0, win, prop, XCB_GET_PROPERTY_TYPE_ANY, 0, 2048), NULL);
    char *out = NULL;
    if (r && r->type != XCB_ATOM_NONE) {
        int len = xcb_get_property_value_length(r);
        out = strndup(xcb_get_property_value(r), (size_t) len);
        if (found) *found = true;
    }
    free(r);
    return out;
}

static void read_title(struct pepe_xwm_window *xw) {
    struct pepe_xwm *xwm = xw->xwm;
    bool found = false;
    char *t = get_string(xwm, xw->id, xwm->atoms[ATOM_NET_WM_NAME], &found);
    if (!found) t = get_string(xwm, xw->id, XCB_ATOM_WM_NAME, NULL);
    free(xw->title);
    xw->title = t;
    if (xw->window) pepe_window_set_title(xw->window, t);
}

static void read_class(struct pepe_xwm_window *xw) {
    struct pepe_xwm *xwm = xw->xwm;
    xcb_get_property_reply_t *r = xcb_get_property_reply(
        xwm->conn, xcb_get_property(xwm->conn, 0, xw->id, XCB_ATOM_WM_CLASS, XCB_ATOM_STRING, 0, 512), NULL);
    if (r && r->type == XCB_ATOM_STRING) {
        /* "instance\0class\0": the class names the application. */
        const char *v = xcb_get_property_value(r);
        int len = xcb_get_property_value_length(r);
        size_t first = strnlen(v, (size_t) len);
        free(xw->class_name);
        xw->class_name = (int) first + 1 < len ? strndup(v + first + 1, (size_t) len - first - 1)
                                               : strndup(v, first);
        if (xw->window) pepe_window_set_app_id(xw->window, xw->class_name);
    }
    free(r);
}

static void read_protocols_and_transient(struct pepe_xwm_window *xw) {
    struct pepe_xwm *xwm = xw->xwm;
    xcb_get_property_reply_t *r = xcb_get_property_reply(
        xwm->conn, xcb_get_property(xwm->conn, 0, xw->id, xwm->atoms[ATOM_WM_PROTOCOLS], XCB_ATOM_ATOM, 0, 32), NULL);
    xw->delete_window = xw->take_focus = false;
    if (r && r->type == XCB_ATOM_ATOM) {
        xcb_atom_t *atoms = xcb_get_property_value(r);
        int n = xcb_get_property_value_length(r) / 4;
        for (int i = 0; i < n; i++) {
            if (atoms[i] == xwm->atoms[ATOM_WM_DELETE_WINDOW]) xw->delete_window = true;
            if (atoms[i] == xwm->atoms[ATOM_WM_TAKE_FOCUS]) xw->take_focus = true;
        }
    }
    free(r);
    r = xcb_get_property_reply(xwm->conn,
        xcb_get_property(xwm->conn, 0, xw->id, XCB_ATOM_WM_TRANSIENT_FOR, XCB_ATOM_WINDOW, 0, 1), NULL);
    xw->transient_for = (r && r->type == XCB_ATOM_WINDOW && xcb_get_property_value_length(r) >= 4)
                            ? *(xcb_window_t *) xcb_get_property_value(r) : XCB_WINDOW_NONE;
    free(r);
}

static void set_wm_state(struct pepe_xwm_window *xw, uint32_t state) {
    uint32_t data[2] = { state, XCB_WINDOW_NONE };
    xcb_change_property(xw->xwm->conn, XCB_PROP_MODE_REPLACE, xw->id, xw->xwm->atoms[ATOM_WM_STATE],
                        xw->xwm->atoms[ATOM_WM_STATE], 32, 2, data);
}

/* ---- windows ↔ surfaces ---- */

static void create_window(struct pepe_xwm_window *xw);

static void xw_surface_destroyed(struct wl_listener *l, void *data) {
    (void) data;
    struct pepe_xwm_window *xw = wl_container_of(l, xw, surface_destroy);
    wl_list_remove(&l->link);
    wl_list_init(&l->link);
    xw->surface = NULL;
    if (xw->window) {
        struct pepe_window *w = xw->window;
        xw->window = NULL;
        w->xwin = NULL;
        w->surface = NULL;
        pepe_window_destroy(w);
    }
}

static void associate(struct pepe_xwm_window *xw, struct pepe_surface *surface) {
    XWM_DEBUG(xw->xwm, "associate window 0x%x with surface %p (role %d)", xw->id, (void *) surface,
              surface ? (int) surface->role : -1);
    if (!surface || xw->surface == surface) return;
    if (surface->role != ROLE_NONE && surface->role != ROLE_XWAYLAND) return;
    if (xw->surface) {
        wl_list_remove(&xw->surface_destroy.link);
        wl_list_init(&xw->surface_destroy.link);
    }
    xw->surface = surface;
    surface->role = ROLE_XWAYLAND;
    surface->role_data = xw;
    xw->surface_destroy.notify = xw_surface_destroyed;
    wl_signal_add(&surface->destroy_signal, &xw->surface_destroy);
    create_window(xw);
}

static void try_associate(struct pepe_xwm_window *xw) {
    if (xw->surface) return;
    if (xw->serial) {
        struct shell_surface *ss;
        wl_list_for_each(ss, &shell_surfaces, link) {
            if (ss->serial == xw->serial && ss->surface) {
                associate(xw, ss->surface);
                return;
            }
        }
    }
    if (xw->surface_id && xw->xwm->server->xwayland_client) {
        struct wl_resource *r = wl_client_get_object(xw->xwm->server->xwayland_client, xw->surface_id);
        associate(xw, pepe_surface_from_resource(r));
    }
}

/* Gives a managed toplevel its own pepe_window (and thereby its own Android task). */
static void create_window(struct pepe_xwm_window *xw) {
    struct pepe_xwm *xwm = xw->xwm;
    if (xw->override_redirect || !xw->map_requested || !xw->surface || xw->window) return;
    struct pepe_window *w = pepe_window_create(xwm->server, xw->surface);
    if (!w) return;
    w->xwin = xw;
    xw->window = w;
    xwm->last_toplevel = xw;
    pepe_window_set_title(w, xw->title);
    pepe_window_set_app_id(w, xw->class_name);
    struct pepe_xwm_window *parent = xw->transient_for ? lookup(xwm, xw->transient_for) : NULL;
    if (parent && parent->window && !xwm->server->dialogs_own_task) {
        w->parent = parent->window;
        wl_list_insert(&parent->window->children, &w->child_link);
    }
    if (xw->surface->mapped) pepe_window_announce(w);
}

void pepe_xwm_surface_committed(struct pepe_surface *surface) {
    struct pepe_xwm_window *xw = surface->role_data;
    if (!xw) return;
    if (xw->window && surface->mapped && !xw->window->announced) pepe_window_announce(xw->window);
}

static struct pepe_window *owner_window(struct pepe_xwm_window *xw) {
    struct pepe_xwm *xwm = xw->xwm;
    struct pepe_xwm_window *t = xw->transient_for ? lookup(xwm, xw->transient_for) : NULL;
    for (int i = 0; t && !t->window && t->transient_for && i < 8; i++) t = lookup(xwm, t->transient_for);
    if (t && t->window) return t->window;
    if (xwm->focus && xwm->focus->window) return xwm->focus->window;
    if (xwm->last_toplevel && xwm->last_toplevel->window) return xwm->last_toplevel->window;
    return NULL;
}

struct pepe_window *pepe_xwm_window_of(struct pepe_surface *surface) {
    struct pepe_xwm_window *xw = surface ? surface->role_data : NULL;
    if (!xw) return NULL;
    if (xw->window) return xw->window;
    return xw->override_redirect ? owner_window(xw) : NULL;
}

void pepe_xwm_for_each_overlay(struct pepe_window *w, void (*fn)(struct pepe_surface *, int32_t, int32_t, void *), void *data) {
    if (!w->xwin) return;
    struct pepe_xwm *xwm = w->xwin->xwm;
    struct pepe_xwm_window *xw;
    wl_list_for_each(xw, &xwm->windows, link) {
        if (!xw->override_redirect || !xw->mapped || !xw->surface) continue;
        if (owner_window(xw) != w) continue;
        fn(xw->surface, xw->x - w->xwin->x, xw->y - w->xwin->y, data);
    }
}

void pepe_xwm_surface_destroyed(struct pepe_server *s, struct pepe_surface *surface) {
    (void) s;
    struct shell_surface *ss;
    wl_list_for_each(ss, &shell_surfaces, link) if (ss->surface == surface) ss->surface = NULL;
}

/* ---- requests from Android ---- */

void pepe_xwm_configure(struct pepe_xwm_window *xw, int32_t width, int32_t height) {
    if (width <= 0 || height <= 0) return;
    uint32_t values[] = { 0, 0, (uint32_t) width, (uint32_t) height, 0 };
    xcb_configure_window(xw->xwm->conn, xw->id,
                         XCB_CONFIG_WINDOW_X | XCB_CONFIG_WINDOW_Y | XCB_CONFIG_WINDOW_WIDTH |
                         XCB_CONFIG_WINDOW_HEIGHT | XCB_CONFIG_WINDOW_BORDER_WIDTH, values);
    xw->x = 0;
    xw->y = 0;
    xcb_flush(xw->xwm->conn);
}

void pepe_xwm_focus(struct pepe_xwm_window *xw, bool focused) {
    struct pepe_xwm *xwm = xw->xwm;
    if (!focused) {
        if (xwm->focus == xw) xwm->focus = NULL;
        return;
    }
    xwm->focus = xw;
    uint32_t above = XCB_STACK_MODE_ABOVE;
    xcb_configure_window(xwm->conn, xw->id, XCB_CONFIG_WINDOW_STACK_MODE, &above);
    if (xw->take_focus) {
        xcb_client_message_event_t ev = {
            .response_type = XCB_CLIENT_MESSAGE, .format = 32, .window = xw->id,
            .type = xwm->atoms[ATOM_WM_PROTOCOLS],
        };
        ev.data.data32[0] = xwm->atoms[ATOM_WM_TAKE_FOCUS];
        ev.data.data32[1] = XCB_CURRENT_TIME;
        xcb_send_event(xwm->conn, 0, xw->id, XCB_EVENT_MASK_NO_EVENT, (const char *) &ev);
    }
    xcb_set_input_focus(xwm->conn, XCB_INPUT_FOCUS_POINTER_ROOT, xw->id, XCB_CURRENT_TIME);
    xcb_change_property(xwm->conn, XCB_PROP_MODE_REPLACE, xwm->screen->root, xwm->atoms[ATOM_NET_ACTIVE_WINDOW],
                        XCB_ATOM_WINDOW, 32, 1, &xw->id);
    xcb_flush(xwm->conn);
}

void pepe_xwm_close(struct pepe_xwm_window *xw) {
    struct pepe_xwm *xwm = xw->xwm;
    if (xw->delete_window) {
        xcb_client_message_event_t ev = {
            .response_type = XCB_CLIENT_MESSAGE, .format = 32, .window = xw->id,
            .type = xwm->atoms[ATOM_WM_PROTOCOLS],
        };
        ev.data.data32[0] = xwm->atoms[ATOM_WM_DELETE_WINDOW];
        ev.data.data32[1] = XCB_CURRENT_TIME;
        xcb_send_event(xwm->conn, 0, xw->id, XCB_EVENT_MASK_NO_EVENT, (const char *) &ev);
    } else {
        xcb_kill_client(xwm->conn, xw->id);
    }
    xcb_flush(xwm->conn);
}

void pepe_xwm_kill(struct pepe_xwm_window *xw) {
    xcb_kill_client(xw->xwm->conn, xw->id);
    xcb_flush(xw->xwm->conn);
}

/* ---- X events ---- */

static void destroy_xw(struct pepe_xwm_window *xw) {
    struct pepe_xwm *xwm = xw->xwm;
    if (xwm->focus == xw) xwm->focus = NULL;
    if (xwm->last_toplevel == xw) xwm->last_toplevel = NULL;
    if (xw->window) {
        struct pepe_window *w = xw->window;
        xw->window = NULL;
        w->xwin = NULL;
        pepe_window_destroy(w);
    }
    if (xw->surface) {
        wl_list_remove(&xw->surface_destroy.link);
        if (xw->surface->role_data == xw) xw->surface->role_data = NULL;
    }
    wl_list_remove(&xw->link);
    free(xw->title);
    free(xw->class_name);
    free(xw);
}

static void unmanage(struct pepe_xwm_window *xw) {
    xw->map_requested = false;
    xw->mapped = false;
    if (xw->window) {
        struct pepe_window *w = xw->window;
        xw->window = NULL;
        w->xwin = NULL;
        pepe_window_destroy(w); /* the client closed it: finish the Android task */
    }
    if (xw->surface) {
        wl_list_remove(&xw->surface_destroy.link);
        wl_list_init(&xw->surface_destroy.link);
        if (xw->surface->role_data == xw) xw->surface->role_data = NULL;
        xw->surface = NULL;
    }
    xw->serial = 0;
    xw->surface_id = 0;
}

static void schedule_owner_redraw(struct pepe_xwm_window *xw) {
    struct pepe_window *w = xw->window ? xw->window : (xw->override_redirect ? owner_window(xw) : NULL);
    if (w) pepe_window_schedule_redraw(w);
}

static void handle_event(struct pepe_xwm *xwm, xcb_generic_event_t *ev) {
    XWM_DEBUG(xwm, "x event %d", ev->response_type & 0x7f);
    switch (ev->response_type & 0x7f) {
    case XCB_CREATE_NOTIFY: {
        xcb_create_notify_event_t *e = (void *) ev;
        if (e->window == xwm->wm_window || lookup(xwm, e->window)) break;
        struct pepe_xwm_window *xw = calloc(1, sizeof *xw);
        if (!xw) break;
        xw->xwm = xwm;
        xw->id = e->window;
        xw->x = e->x; xw->y = e->y;
        xw->width = e->width; xw->height = e->height;
        xw->override_redirect = e->override_redirect;
        wl_list_init(&xw->surface_destroy.link);
        wl_list_insert(xwm->windows.prev, &xw->link);
        uint32_t mask = XCB_EVENT_MASK_PROPERTY_CHANGE | XCB_EVENT_MASK_FOCUS_CHANGE;
        xcb_change_window_attributes(xwm->conn, e->window, XCB_CW_EVENT_MASK, &mask);
        break;
    }
    case XCB_DESTROY_NOTIFY: {
        xcb_destroy_notify_event_t *e = (void *) ev;
        struct pepe_xwm_window *xw = lookup(xwm, e->window);
        if (xw) destroy_xw(xw);
        break;
    }
    case XCB_MAP_REQUEST: {
        xcb_map_request_event_t *e = (void *) ev;
        struct pepe_xwm_window *xw = lookup(xwm, e->window);
        if (!xw) break;
        read_title(xw);
        read_class(xw);
        read_protocols_and_transient(xw);
        xw->map_requested = true;
        set_wm_state(xw, 1 /* NormalState */);
        xcb_map_window(xwm->conn, xw->id);
        xcb_flush(xwm->conn);
        try_associate(xw);
        create_window(xw);
        break;
    }
    case XCB_MAP_NOTIFY: {
        xcb_map_notify_event_t *e = (void *) ev;
        struct pepe_xwm_window *xw = lookup(xwm, e->window);
        if (!xw) break;
        xw->mapped = true;
        if (xw->override_redirect) {
            read_protocols_and_transient(xw);
            try_associate(xw);
        }
        schedule_owner_redraw(xw);
        break;
    }
    case XCB_UNMAP_NOTIFY: {
        xcb_unmap_notify_event_t *e = (void *) ev;
        struct pepe_xwm_window *xw = lookup(xwm, e->window);
        if (!xw) break;
        struct pepe_window *owner = xw->override_redirect ? owner_window(xw) : NULL;
        if (!xw->override_redirect) set_wm_state(xw, 0 /* WithdrawnState */);
        unmanage(xw);
        if (owner) pepe_window_schedule_redraw(owner);
        xcb_flush(xwm->conn);
        break;
    }
    case XCB_CONFIGURE_REQUEST: {
        xcb_configure_request_event_t *e = (void *) ev;
        struct pepe_xwm_window *xw = lookup(xwm, e->window);
        if (xw && xw->window && xw->window->width > 0) {
            /* A managed window fills its task. */
            pepe_xwm_configure(xw, xw->window->width, xw->window->height);
            break;
        }
        uint32_t values[7];
        int i = 0;
        uint16_t mask = e->value_mask & (XCB_CONFIG_WINDOW_X | XCB_CONFIG_WINDOW_Y | XCB_CONFIG_WINDOW_WIDTH |
                                         XCB_CONFIG_WINDOW_HEIGHT | XCB_CONFIG_WINDOW_BORDER_WIDTH);
        if (mask & XCB_CONFIG_WINDOW_X) values[i++] = (uint32_t) (xw && !xw->override_redirect ? 0 : e->x);
        if (mask & XCB_CONFIG_WINDOW_Y) values[i++] = (uint32_t) (xw && !xw->override_redirect ? 0 : e->y);
        if (mask & XCB_CONFIG_WINDOW_WIDTH) values[i++] = e->width;
        if (mask & XCB_CONFIG_WINDOW_HEIGHT) values[i++] = e->height;
        if (mask & XCB_CONFIG_WINDOW_BORDER_WIDTH) values[i++] = 0;
        xcb_configure_window(xwm->conn, e->window, mask, values);
        xcb_flush(xwm->conn);
        break;
    }
    case XCB_CONFIGURE_NOTIFY: {
        xcb_configure_notify_event_t *e = (void *) ev;
        struct pepe_xwm_window *xw = lookup(xwm, e->window);
        if (!xw) break;
        xw->x = e->x; xw->y = e->y;
        xw->width = e->width; xw->height = e->height;
        xw->override_redirect = e->override_redirect;
        if (xw->mapped) schedule_owner_redraw(xw);
        break;
    }
    case XCB_PROPERTY_NOTIFY: {
        xcb_property_notify_event_t *e = (void *) ev;
        struct pepe_xwm_window *xw = lookup(xwm, e->window);
        if (!xw) break;
        if (e->atom == xwm->atoms[ATOM_NET_WM_NAME] || e->atom == XCB_ATOM_WM_NAME) read_title(xw);
        else if (e->atom == XCB_ATOM_WM_CLASS) read_class(xw);
        else if (e->atom == xwm->atoms[ATOM_WM_PROTOCOLS] || e->atom == XCB_ATOM_WM_TRANSIENT_FOR)
            read_protocols_and_transient(xw);
        break;
    }
    case XCB_CLIENT_MESSAGE: {
        xcb_client_message_event_t *e = (void *) ev;
        struct pepe_xwm_window *xw = lookup(xwm, e->window);
        if (!xw) break;
        if (e->type == xwm->atoms[ATOM_WL_SURFACE_ID]) {
            xw->surface_id = e->data.data32[0];
            try_associate(xw);
        } else if (e->type == xwm->atoms[ATOM_WL_SURFACE_SERIAL]) {
            xw->serial = (uint64_t) e->data.data32[0] | ((uint64_t) e->data.data32[1] << 32);
            try_associate(xw);
        }
        break;
    }
    default:
        break;
    }
}

static int on_x_event(int fd, uint32_t mask, void *data) {
    (void) fd;
    struct pepe_xwm *xwm = data;
    if (mask & (WL_EVENT_HANGUP | WL_EVENT_ERROR)) {
        pepe_log(xwm->server, "Xwayland window manager connection closed");
        pepe_xwm_destroy(xwm->server);
        return 0;
    }
    xcb_generic_event_t *ev;
    int count = 0;
    while ((ev = xcb_poll_for_event(xwm->conn))) {
        handle_event(xwm, ev);
        free(ev);
        count++;
    }
    if (xcb_connection_has_error(xwm->conn)) {
        pepe_log(xwm->server, "Xwayland connection error");
        pepe_xwm_destroy(xwm->server);
        return 0;
    }
    xcb_flush(xwm->conn);
    return count;
}

/* Xwayland wrote its display number to -displayfd: it is ready for the WM connection. */
static int on_ready(int fd, uint32_t mask, void *data) {
    (void) mask;
    struct pepe_xwm *xwm = data;
    char buf[64];
    ssize_t n = read(fd, buf, sizeof buf - 1);
    if (n > 0 && !memchr(buf, '\n', (size_t) n)) return 0; /* partial line */
    wl_event_source_remove(xwm->ready_source);
    xwm->ready_source = NULL;
    close(fd);
    xwm->ready_fd = -1;
    if (n <= 0) {
        pepe_log(xwm->server, "Xwayland exited before it was ready");
        pepe_xwm_destroy(xwm->server);
        return 0;
    }
    buf[n] = 0;

    xwm->conn = xcb_connect_to_fd(xwm->wm_fd, NULL);
    if (xcb_connection_has_error(xwm->conn)) {
        pepe_log(xwm->server, "cannot connect to Xwayland");
        pepe_xwm_destroy(xwm->server);
        return 0;
    }
    xwm->screen = xcb_setup_roots_iterator(xcb_get_setup(xwm->conn)).data;

    xcb_intern_atom_cookie_t cookies[ATOM_COUNT];
    for (int i = 0; i < ATOM_COUNT; i++)
        cookies[i] = xcb_intern_atom(xwm->conn, 0, (uint16_t) strlen(ATOM_NAMES[i]), ATOM_NAMES[i]);
    for (int i = 0; i < ATOM_COUNT; i++) {
        xcb_intern_atom_reply_t *r = xcb_intern_atom_reply(xwm->conn, cookies[i], NULL);
        xwm->atoms[i] = r ? r->atom : XCB_ATOM_NONE;
        free(r);
    }

    xcb_window_t root = xwm->screen->root;
    /* Rootless Xwayland only gives manually redirected windows a wl_surface. */
    xcb_prefetch_extension_data(xwm->conn, &xcb_composite_id);
    xcb_composite_query_version_reply_t *cv = xcb_composite_query_version_reply(
        xwm->conn, xcb_composite_query_version(xwm->conn, 0, 4), NULL);
    free(cv);
    xcb_composite_redirect_subwindows(xwm->conn, root, XCB_COMPOSITE_REDIRECT_MANUAL);
    uint32_t mask_values = XCB_EVENT_MASK_SUBSTRUCTURE_NOTIFY | XCB_EVENT_MASK_SUBSTRUCTURE_REDIRECT |
                           XCB_EVENT_MASK_PROPERTY_CHANGE;
    xcb_change_window_attributes(xwm->conn, root, XCB_CW_EVENT_MASK, &mask_values);

    xwm->wm_window = xcb_generate_id(xwm->conn);
    xcb_create_window(xwm->conn, XCB_COPY_FROM_PARENT, xwm->wm_window, root, 0, 0, 1, 1, 0,
                      XCB_WINDOW_CLASS_INPUT_OUTPUT, XCB_COPY_FROM_PARENT, 0, NULL);
    xcb_change_property(xwm->conn, XCB_PROP_MODE_REPLACE, xwm->wm_window, xwm->atoms[ATOM_NET_WM_NAME],
                        xwm->atoms[ATOM_UTF8_STRING], 8, 12, "pepelauncher");
    xcb_change_property(xwm->conn, XCB_PROP_MODE_REPLACE, root, xwm->atoms[ATOM_NET_SUPPORTING_WM_CHECK],
                        XCB_ATOM_WINDOW, 32, 1, &xwm->wm_window);
    xcb_change_property(xwm->conn, XCB_PROP_MODE_REPLACE, xwm->wm_window, xwm->atoms[ATOM_NET_SUPPORTING_WM_CHECK],
                        XCB_ATOM_WINDOW, 32, 1, &xwm->wm_window);
    xcb_atom_t supported[] = { xwm->atoms[ATOM_NET_ACTIVE_WINDOW], xwm->atoms[ATOM_NET_WM_NAME],
                               xwm->atoms[ATOM_NET_SUPPORTING_WM_CHECK] };
    xcb_change_property(xwm->conn, XCB_PROP_MODE_REPLACE, root, xwm->atoms[ATOM_NET_SUPPORTED],
                        XCB_ATOM_ATOM, 32, 3, supported);
    xcb_set_selection_owner(xwm->conn, xwm->wm_window, xwm->atoms[ATOM_WM_S0], XCB_CURRENT_TIME);
    xcb_set_selection_owner(xwm->conn, xwm->wm_window, xwm->atoms[ATOM_NET_WM_CM_S0], XCB_CURRENT_TIME);
    xcb_flush(xwm->conn);

    xwm->x_source = wl_event_loop_add_fd(xwm->server->loop, xcb_get_file_descriptor(xwm->conn),
                                         WL_EVENT_READABLE, on_x_event, xwm);
    wl_event_source_check(xwm->x_source);
    pepe_log(xwm->server, "Xwayland ready on display :%s", strtok(buf, "\n"));
    return 0;
}

int pepe_xwm_start(struct pepe_server *s, int wm_fd, int wayland_fd, int ready_fd) {
    if (s->xwm) pepe_xwm_destroy(s);
    struct pepe_xwm *xwm = calloc(1, sizeof *xwm);
    if (!xwm) return -1;
    xwm->server = s;
    xwm->wm_fd = wm_fd;
    xwm->ready_fd = ready_fd;
    wl_list_init(&xwm->windows);
    s->xwm = xwm;
    s->xwayland_client = wl_client_create(s->display, wayland_fd);
    if (!s->xwayland_client) {
        pepe_log(s, "cannot create the Xwayland client");
        pepe_xwm_destroy(s);
        return -1;
    }
    xwm->ready_source = wl_event_loop_add_fd(s->loop, ready_fd, WL_EVENT_READABLE, on_ready, xwm);
    return 0;
}

void pepe_xwm_destroy(struct pepe_server *s) {
    struct pepe_xwm *xwm = s->xwm;
    if (!xwm) return;
    s->xwm = NULL;
    struct pepe_xwm_window *xw, *tmp;
    wl_list_for_each_safe(xw, tmp, &xwm->windows, link) destroy_xw(xw);
    if (xwm->ready_source) wl_event_source_remove(xwm->ready_source);
    if (xwm->x_source) wl_event_source_remove(xwm->x_source);
    if (xwm->conn) xcb_disconnect(xwm->conn); /* closes wm_fd */
    else if (xwm->wm_fd >= 0) close(xwm->wm_fd);
    if (xwm->ready_fd >= 0) close(xwm->ready_fd);
    free(xwm);
}

/* ---- xwayland_shell_v1 (only the Xwayland client may bind it) ---- */

static void shell_surface_destroy_req(struct wl_client *c, struct wl_resource *r) { (void) c; wl_resource_destroy(r); }

static void shell_surface_set_serial(struct wl_client *c, struct wl_resource *r, uint32_t lo, uint32_t hi) {
    (void) c;
    struct shell_surface *ss = wl_resource_get_user_data(r);
    ss->serial = (uint64_t) lo | ((uint64_t) hi << 32);
    struct pepe_server *s = ss->surface ? ss->surface->server : NULL;
    if (s && s->xwm) XWM_DEBUG(s->xwm, "xwayland surface serial %llu", (unsigned long long) ss->serial);
    if (!s || !s->xwm) return;
    struct pepe_xwm_window *xw;
    wl_list_for_each(xw, &s->xwm->windows, link) {
        if (xw->serial == ss->serial) {
            associate(xw, ss->surface);
            break;
        }
    }
}

static const struct xwayland_surface_v1_interface shell_surface_impl = {
    .set_serial = shell_surface_set_serial,
    .destroy = shell_surface_destroy_req,
};

static void shell_surface_surface_destroyed(struct wl_listener *l, void *data) {
    (void) data;
    struct shell_surface *ss = wl_container_of(l, ss, surface_destroy);
    wl_list_remove(&l->link);
    wl_list_init(&l->link);
    ss->surface = NULL;
}

static void shell_surface_resource_destroyed(struct wl_resource *r) {
    struct shell_surface *ss = wl_resource_get_user_data(r);
    wl_list_remove(&ss->link);
    wl_list_remove(&ss->surface_destroy.link);
    free(ss);
}

static void shell_destroy(struct wl_client *c, struct wl_resource *r) { (void) c; wl_resource_destroy(r); }

static void shell_get_surface(struct wl_client *c, struct wl_resource *r, uint32_t id, struct wl_resource *surface_res) {
    struct shell_surface *ss = calloc(1, sizeof *ss);
    struct wl_resource *res = wl_resource_create(c, &xwayland_surface_v1_interface, wl_resource_get_version(r), id);
    if (!ss || !res) { free(ss); wl_client_post_no_memory(c); return; }
    ss->resource = res;
    ss->surface = pepe_surface_from_resource(surface_res);
    ss->surface_destroy.notify = shell_surface_surface_destroyed;
    if (ss->surface) wl_signal_add(&ss->surface->destroy_signal, &ss->surface_destroy);
    else wl_list_init(&ss->surface_destroy.link);
    wl_list_insert(&shell_surfaces, &ss->link);
    wl_resource_set_implementation(res, &shell_surface_impl, ss, shell_surface_resource_destroyed);
}

static const struct xwayland_shell_v1_interface shell_impl = {
    .destroy = shell_destroy,
    .get_xwayland_surface = shell_get_surface,
};

static void bind_shell(struct wl_client *c, void *data, uint32_t version, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &xwayland_shell_v1_interface, version, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &shell_impl, data, NULL);
}

static struct wl_global *shell_global;

/* xwayland_shell_v1 is only visible to the Xwayland client; every other global to everyone. */
static bool global_filter(const struct wl_client *client, const struct wl_global *global, void *data) {
    struct pepe_server *s = data;
    if (global == shell_global) return client == s->xwayland_client;
    return true;
}

void pepe_xwm_shell_init(struct pepe_server *s) {
    shell_global = wl_global_create(s->display, &xwayland_shell_v1_interface, 1, s, bind_shell);
    wl_display_set_global_filter(s->display, global_filter, s);
}
