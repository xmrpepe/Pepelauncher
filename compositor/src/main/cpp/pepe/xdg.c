/* pepelauncher compositor: xdg_wm_base (toplevels and popups) and xdg-decoration. */
#include <stdlib.h>
#include <string.h>

#include "pepe.h"
#include "xdg-decoration-unstable-v1-protocol.h"
#include "xdg-shell-protocol.h"

struct positioner {
    int32_t w, h;
    int32_t ax, ay, aw, ah;
    uint32_t anchor, gravity, constraints;
    int32_t ox, oy;
};

/* ---- positioner ---- */

static void positioner_destroy(struct wl_client *c, struct wl_resource *r) { (void) c; wl_resource_destroy(r); }
static void positioner_set_size(struct wl_client *c, struct wl_resource *r, int32_t w, int32_t h) {
    (void) c;
    struct positioner *p = wl_resource_get_user_data(r);
    if (w <= 0 || h <= 0) {
        wl_resource_post_error(r, XDG_POSITIONER_ERROR_INVALID_INPUT, "invalid size");
        return;
    }
    p->w = w;
    p->h = h;
}
static void positioner_set_anchor_rect(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y, int32_t w, int32_t h) {
    (void) c;
    struct positioner *p = wl_resource_get_user_data(r);
    p->ax = x; p->ay = y; p->aw = w; p->ah = h;
}
static void positioner_set_anchor(struct wl_client *c, struct wl_resource *r, uint32_t a) {
    (void) c;
    ((struct positioner *) wl_resource_get_user_data(r))->anchor = a;
}
static void positioner_set_gravity(struct wl_client *c, struct wl_resource *r, uint32_t g) {
    (void) c;
    ((struct positioner *) wl_resource_get_user_data(r))->gravity = g;
}
static void positioner_set_constraint(struct wl_client *c, struct wl_resource *r, uint32_t k) {
    (void) c;
    ((struct positioner *) wl_resource_get_user_data(r))->constraints = k;
}
static void positioner_set_offset(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y) {
    (void) c;
    struct positioner *p = wl_resource_get_user_data(r);
    p->ox = x;
    p->oy = y;
}
static void positioner_noop(struct wl_client *c, struct wl_resource *r) { (void) c; (void) r; }
static void positioner_set_parent_size(struct wl_client *c, struct wl_resource *r, int32_t w, int32_t h) {
    (void) c; (void) r; (void) w; (void) h;
}
static void positioner_set_parent_configure(struct wl_client *c, struct wl_resource *r, uint32_t serial) {
    (void) c; (void) r; (void) serial;
}

static const struct xdg_positioner_interface positioner_impl = {
    .destroy = positioner_destroy,
    .set_size = positioner_set_size,
    .set_anchor_rect = positioner_set_anchor_rect,
    .set_anchor = positioner_set_anchor,
    .set_gravity = positioner_set_gravity,
    .set_constraint_adjustment = positioner_set_constraint,
    .set_offset = positioner_set_offset,
    .set_reactive = positioner_noop,
    .set_parent_size = positioner_set_parent_size,
    .set_parent_configure = positioner_set_parent_configure,
};

static void positioner_free(struct wl_resource *r) { free(wl_resource_get_user_data(r)); }

static bool has(uint32_t e, uint32_t a, uint32_t b, uint32_t c) { return e == a || e == b || e == c; }

/*
 * Popup position relative to the parent's window geometry. Constrained to the toplevel's
 * window (bounds = [ox, oy, bw, bh] in the same coordinates): flip, then slide.
 */
static void place(const struct positioner *p, int32_t bx, int32_t by, int32_t bw, int32_t bh, int32_t *ox, int32_t *oy) {
    uint32_t anchor = p->anchor, gravity = p->gravity;
    for (int attempt = 0; attempt < 2; attempt++) {
        int32_t x = p->ax + p->aw / 2, y = p->ay + p->ah / 2;
        if (has(anchor, XDG_POSITIONER_ANCHOR_LEFT, XDG_POSITIONER_ANCHOR_TOP_LEFT, XDG_POSITIONER_ANCHOR_BOTTOM_LEFT)) x = p->ax;
        if (has(anchor, XDG_POSITIONER_ANCHOR_RIGHT, XDG_POSITIONER_ANCHOR_TOP_RIGHT, XDG_POSITIONER_ANCHOR_BOTTOM_RIGHT)) x = p->ax + p->aw;
        if (has(anchor, XDG_POSITIONER_ANCHOR_TOP, XDG_POSITIONER_ANCHOR_TOP_LEFT, XDG_POSITIONER_ANCHOR_TOP_RIGHT)) y = p->ay;
        if (has(anchor, XDG_POSITIONER_ANCHOR_BOTTOM, XDG_POSITIONER_ANCHOR_BOTTOM_LEFT, XDG_POSITIONER_ANCHOR_BOTTOM_RIGHT)) y = p->ay + p->ah;

        if (has(gravity, XDG_POSITIONER_GRAVITY_LEFT, XDG_POSITIONER_GRAVITY_TOP_LEFT, XDG_POSITIONER_GRAVITY_BOTTOM_LEFT)) x -= p->w;
        else if (!has(gravity, XDG_POSITIONER_GRAVITY_RIGHT, XDG_POSITIONER_GRAVITY_TOP_RIGHT, XDG_POSITIONER_GRAVITY_BOTTOM_RIGHT)) x -= p->w / 2;
        if (has(gravity, XDG_POSITIONER_GRAVITY_TOP, XDG_POSITIONER_GRAVITY_TOP_LEFT, XDG_POSITIONER_GRAVITY_TOP_RIGHT)) y -= p->h;
        else if (!has(gravity, XDG_POSITIONER_GRAVITY_BOTTOM, XDG_POSITIONER_GRAVITY_BOTTOM_LEFT, XDG_POSITIONER_GRAVITY_BOTTOM_RIGHT)) y -= p->h / 2;
        x += p->ox;
        y += p->oy;

        bool flip_y = (p->constraints & XDG_POSITIONER_CONSTRAINT_ADJUSTMENT_FLIP_Y) && bh > 0 &&
                      (y < by || y + p->h > by + bh);
        bool flip_x = (p->constraints & XDG_POSITIONER_CONSTRAINT_ADJUSTMENT_FLIP_X) && bw > 0 &&
                      (x < bx || x + p->w > bx + bw);
        if (attempt == 0 && (flip_x || flip_y)) {
            /* Mirror anchor and gravity on the overflowing axis and try once more. */
            static const uint32_t mirror_y[] = {0, 2, 1, 3, 4, 6, 5, 8, 7};
            static const uint32_t mirror_x[] = {0, 1, 2, 4, 3, 7, 8, 5, 6};
            if (flip_y && anchor < 9 && gravity < 9) { anchor = mirror_y[anchor]; gravity = mirror_y[gravity]; }
            if (flip_x && anchor < 9 && gravity < 9) { anchor = mirror_x[anchor]; gravity = mirror_x[gravity]; }
            int32_t fx, fy;
            struct positioner q = *p;
            q.anchor = anchor;
            q.gravity = gravity;
            q.constraints &= ~(XDG_POSITIONER_CONSTRAINT_ADJUSTMENT_FLIP_X | XDG_POSITIONER_CONSTRAINT_ADJUSTMENT_FLIP_Y);
            place(&q, bx, by, bw, bh, &fx, &fy);
            bool fits_y = !flip_y || (fy >= by && fy + p->h <= by + bh);
            bool fits_x = !flip_x || (fx >= bx && fx + p->w <= bx + bw);
            if (fits_x && fits_y) {
                *ox = fx;
                *oy = fy;
                return;
            }
            anchor = p->anchor;
            gravity = p->gravity;
            continue;
        }
        if ((p->constraints & XDG_POSITIONER_CONSTRAINT_ADJUSTMENT_SLIDE_X) && bw > 0) {
            if (x + p->w > bx + bw) x = bx + bw - p->w;
            if (x < bx) x = bx;
        }
        if ((p->constraints & XDG_POSITIONER_CONSTRAINT_ADJUSTMENT_SLIDE_Y) && bh > 0) {
            if (y + p->h > by + bh) y = by + bh - p->h;
            if (y < by) y = by;
        }
        *ox = x;
        *oy = y;
        return;
    }
}

/* ---- xdg_surface ---- */

static uint32_t send_configure(struct pepe_xdg_surface *xdg) {
    uint32_t serial = pepe_next_serial(xdg->surface->server);
    xdg_surface_send_configure(xdg->resource, serial);
    xdg->configure_serial = serial;
    return serial;
}

void pepe_xdg_configure_toplevel(struct pepe_window *w) {
    struct pepe_xdg_surface *xdg = w->xdg;
    if (!xdg || !xdg->toplevel) return;
    struct wl_array states;
    wl_array_init(&states);
    uint32_t *st;
    /* Edge-to-edge in its own task: maximized + tiled, so CSD clients drop shadows and corners. */
    st = wl_array_add(&states, sizeof *st); *st = XDG_TOPLEVEL_STATE_MAXIMIZED;
    if (wl_resource_get_version(xdg->toplevel) >= XDG_TOPLEVEL_STATE_TILED_LEFT_SINCE_VERSION) {
        st = wl_array_add(&states, sizeof *st); *st = XDG_TOPLEVEL_STATE_TILED_LEFT;
        st = wl_array_add(&states, sizeof *st); *st = XDG_TOPLEVEL_STATE_TILED_RIGHT;
        st = wl_array_add(&states, sizeof *st); *st = XDG_TOPLEVEL_STATE_TILED_TOP;
        st = wl_array_add(&states, sizeof *st); *st = XDG_TOPLEVEL_STATE_TILED_BOTTOM;
    }
    if (w->focused) {
        st = wl_array_add(&states, sizeof *st); *st = XDG_TOPLEVEL_STATE_ACTIVATED;
    }
    if (w->parent) wl_array_init(&states); /* attached dialogs keep their natural look */
    xdg_toplevel_send_configure(xdg->toplevel, w->width, w->height, &states);
    wl_array_release(&states);
    send_configure(xdg);
}

void pepe_xdg_close(struct pepe_window *w) {
    if (w->xdg && w->xdg->toplevel) xdg_toplevel_send_close(w->xdg->toplevel);
}

void pepe_xdg_dismiss_popups(struct pepe_xdg_surface *xdg) {
    struct pepe_xdg_surface *child, *tmp;
    wl_list_for_each_safe(child, tmp, &xdg->popups, popup_link) {
        pepe_xdg_dismiss_popups(child);
        if (child->popup) xdg_popup_send_popup_done(child->popup);
    }
}

void pepe_xdg_surface_committed(struct pepe_surface *surface) {
    struct pepe_xdg_surface *xdg = surface->role_data;
    if (!xdg) return;
    if (xdg->pending_has_geometry) {
        xdg->has_geometry = true;
        xdg->geo_x = xdg->pending_geo_x;
        xdg->geo_y = xdg->pending_geo_y;
        xdg->geo_w = xdg->pending_geo_w;
        xdg->geo_h = xdg->pending_geo_h;
    }
    if (!xdg->configured) {
        /* Initial commit: answer with the first configure. */
        xdg->configured = true;
        if (xdg->toplevel && xdg->window) pepe_window_configure(xdg->window);
        else if (xdg->popup) send_configure(xdg);
        return;
    }
    if (xdg->toplevel && xdg->window && surface->mapped && !xdg->window->announced && !xdg->window->parent) {
        pepe_window_announce(xdg->window);
    }
}

static void xdg_surface_destroy(struct wl_client *c, struct wl_resource *r) { (void) c; wl_resource_destroy(r); }

static void toplevel_resource_destroyed(struct wl_resource *r);
static void popup_resource_destroyed(struct wl_resource *r);
static const struct xdg_toplevel_interface toplevel_impl;
static const struct xdg_popup_interface popup_impl;

static void xdg_surface_get_toplevel(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct pepe_xdg_surface *xdg = wl_resource_get_user_data(r);
    struct pepe_surface *surface = xdg->surface;
    if (!surface || surface->role != ROLE_NONE) {
        wl_resource_post_error(r, XDG_WM_BASE_ERROR_ROLE, "surface already has a role");
        return;
    }
    xdg->toplevel = wl_resource_create(c, &xdg_toplevel_interface, wl_resource_get_version(r), id);
    if (!xdg->toplevel) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(xdg->toplevel, &toplevel_impl, xdg, toplevel_resource_destroyed);
    surface->role = ROLE_XDG_TOPLEVEL;
    surface->role_data = xdg;
    xdg->window = pepe_window_create(surface->server, surface);
    xdg->window->xdg = xdg;
    if (wl_resource_get_version(xdg->toplevel) >= XDG_TOPLEVEL_WM_CAPABILITIES_SINCE_VERSION) {
        struct wl_array caps;
        wl_array_init(&caps);
        xdg_toplevel_send_wm_capabilities(xdg->toplevel, &caps); /* no window menu, move, resize */
        wl_array_release(&caps);
    }
}

static void xdg_surface_get_popup(struct wl_client *c, struct wl_resource *r, uint32_t id,
                                  struct wl_resource *parent_res, struct wl_resource *positioner_res) {
    struct pepe_xdg_surface *xdg = wl_resource_get_user_data(r);
    struct pepe_xdg_surface *parent = parent_res ? wl_resource_get_user_data(parent_res) : NULL;
    struct positioner *p = wl_resource_get_user_data(positioner_res);
    if (!xdg->surface || xdg->surface->role != ROLE_NONE) {
        wl_resource_post_error(r, XDG_WM_BASE_ERROR_ROLE, "surface already has a role");
        return;
    }
    if (!parent) {
        wl_resource_post_error(r, XDG_WM_BASE_ERROR_INVALID_POPUP_PARENT, "popups need an xdg parent here");
        return;
    }
    xdg->popup = wl_resource_create(c, &xdg_popup_interface, wl_resource_get_version(r), id);
    if (!xdg->popup) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(xdg->popup, &popup_impl, xdg, popup_resource_destroyed);
    xdg->surface->role = ROLE_XDG_POPUP;
    xdg->surface->role_data = xdg;
    xdg->parent = parent;
    xdg->window = parent->window;
    wl_list_insert(parent->popups.prev, &xdg->popup_link);

    /* Constrain to the toplevel's window, expressed relative to the parent's geometry. */
    int32_t px = 0, py = 0;
    for (struct pepe_xdg_surface *q = parent; q && q->popup; q = q->parent) {
        px += q->popup_x;
        py += q->popup_y;
    }
    int32_t bw = xdg->window ? xdg->window->width : 0, bh = xdg->window ? xdg->window->height : 0;
    if (xdg->window && (bw <= 0 || bh <= 0)) {
        int32_t gx, gy;
        pepe_window_get_geometry(xdg->window, &gx, &gy, &bw, &bh);
    }
    int32_t x, y;
    place(p, -px, -py, bw, bh, &x, &y);
    xdg->popup_x = x;
    xdg->popup_y = y;
    xdg_popup_send_configure(xdg->popup, x, y, p->w, p->h);
}

static void xdg_surface_set_window_geometry(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y, int32_t w, int32_t h) {
    (void) c;
    struct pepe_xdg_surface *xdg = wl_resource_get_user_data(r);
    if (w <= 0 || h <= 0) {
        wl_resource_post_error(r, XDG_SURFACE_ERROR_INVALID_SIZE, "invalid geometry");
        return;
    }
    xdg->pending_has_geometry = true;
    xdg->pending_geo_x = x;
    xdg->pending_geo_y = y;
    xdg->pending_geo_w = w;
    xdg->pending_geo_h = h;
}

static void xdg_surface_ack_configure(struct wl_client *c, struct wl_resource *r, uint32_t serial) {
    (void) c; (void) r; (void) serial;
}

static const struct xdg_surface_interface xdg_surface_impl = {
    .destroy = xdg_surface_destroy,
    .get_toplevel = xdg_surface_get_toplevel,
    .get_popup = xdg_surface_get_popup,
    .set_window_geometry = xdg_surface_set_window_geometry,
    .ack_configure = xdg_surface_ack_configure,
};

static void xdg_surface_resource_destroyed(struct wl_resource *r) {
    struct pepe_xdg_surface *xdg = wl_resource_get_user_data(r);
    if (xdg->surface) {
        wl_list_remove(&xdg->surface_destroy.link);
        if (xdg->surface->role_data == xdg) xdg->surface->role_data = NULL;
    }
    free(xdg);
}

static void xdg_surface_surface_destroyed(struct wl_listener *l, void *data) {
    (void) data;
    struct pepe_xdg_surface *xdg = wl_container_of(l, xdg, surface_destroy);
    wl_list_remove(&l->link);
    wl_list_init(&l->link);
    xdg->surface = NULL;
}

/* ---- xdg_toplevel ---- */

static void toplevel_destroy(struct wl_client *c, struct wl_resource *r) { (void) c; wl_resource_destroy(r); }

static void toplevel_set_parent(struct wl_client *c, struct wl_resource *r, struct wl_resource *parent_res) {
    (void) c;
    struct pepe_xdg_surface *xdg = wl_resource_get_user_data(r);
    struct pepe_xdg_surface *parent = parent_res ? wl_resource_get_user_data(parent_res) : NULL;
    struct pepe_window *w = xdg->window;
    xdg->toplevel_parent = parent;
    if (!w || w->server->dialogs_own_task || w->announced) return;
    /* Dialogs attach to their parent's task instead of getting their own (a setting). */
    if (parent && parent->window && parent->window != w) {
        w->parent = parent->window;
        wl_list_insert(&parent->window->children, &w->child_link);
    }
}

static void toplevel_set_title(struct wl_client *c, struct wl_resource *r, const char *title) {
    (void) c;
    struct pepe_xdg_surface *xdg = wl_resource_get_user_data(r);
    if (xdg->window) pepe_window_set_title(xdg->window, title);
}

static void toplevel_set_app_id(struct wl_client *c, struct wl_resource *r, const char *app_id) {
    (void) c;
    struct pepe_xdg_surface *xdg = wl_resource_get_user_data(r);
    if (xdg->window) pepe_window_set_app_id(xdg->window, app_id);
}

static void toplevel_show_window_menu(struct wl_client *c, struct wl_resource *r, struct wl_resource *seat,
                                      uint32_t serial, int32_t x, int32_t y) {
    (void) c; (void) r; (void) seat; (void) serial; (void) x; (void) y;
}
static void toplevel_move(struct wl_client *c, struct wl_resource *r, struct wl_resource *seat, uint32_t serial) {
    (void) c; (void) r; (void) seat; (void) serial; /* Android owns window placement */
}
static void toplevel_resize(struct wl_client *c, struct wl_resource *r, struct wl_resource *seat, uint32_t serial, uint32_t edges) {
    (void) c; (void) r; (void) seat; (void) serial; (void) edges;
}
static void toplevel_set_max_size(struct wl_client *c, struct wl_resource *r, int32_t w, int32_t h) {
    (void) c;
    struct pepe_xdg_surface *xdg = wl_resource_get_user_data(r);
    xdg->max_w = w;
    xdg->max_h = h;
}
static void toplevel_set_min_size(struct wl_client *c, struct wl_resource *r, int32_t w, int32_t h) {
    (void) c;
    struct pepe_xdg_surface *xdg = wl_resource_get_user_data(r);
    xdg->min_w = w;
    xdg->min_h = h;
}
static void toplevel_state_request(struct wl_client *c, struct wl_resource *r) {
    (void) c;
    /* (un)maximize/fullscreen requests: the window always fills its task; just re-confirm. */
    struct pepe_xdg_surface *xdg = wl_resource_get_user_data(r);
    if (xdg->window && xdg->configured) pepe_xdg_configure_toplevel(xdg->window);
}
static void toplevel_set_fullscreen(struct wl_client *c, struct wl_resource *r, struct wl_resource *output) {
    (void) output;
    toplevel_state_request(c, r);
}
static void toplevel_set_minimized(struct wl_client *c, struct wl_resource *r) { (void) c; (void) r; }

static const struct xdg_toplevel_interface toplevel_impl = {
    .destroy = toplevel_destroy,
    .set_parent = toplevel_set_parent,
    .set_title = toplevel_set_title,
    .set_app_id = toplevel_set_app_id,
    .show_window_menu = toplevel_show_window_menu,
    .move = toplevel_move,
    .resize = toplevel_resize,
    .set_max_size = toplevel_set_max_size,
    .set_min_size = toplevel_set_min_size,
    .set_maximized = toplevel_state_request,
    .unset_maximized = toplevel_state_request,
    .set_fullscreen = toplevel_set_fullscreen,
    .unset_fullscreen = toplevel_state_request,
    .set_minimized = toplevel_set_minimized,
};

static void toplevel_resource_destroyed(struct wl_resource *r) {
    struct pepe_xdg_surface *xdg = wl_resource_get_user_data(r);
    xdg->toplevel = NULL;
    if (xdg->window) {
        struct pepe_window *w = xdg->window;
        xdg->window = NULL;
        w->xdg = NULL;
        pepe_window_destroy(w);
    }
    if (xdg->surface && xdg->surface->role_data == xdg) {
        xdg->surface->role_data = NULL;
        xdg->surface->mapped = false;
    }
}

/* ---- xdg_popup ---- */

static void popup_destroy(struct wl_client *c, struct wl_resource *r) { (void) c; wl_resource_destroy(r); }

static void popup_grab(struct wl_client *c, struct wl_resource *r, struct wl_resource *seat, uint32_t serial) {
    (void) c; (void) seat; (void) serial;
    struct pepe_xdg_surface *xdg = wl_resource_get_user_data(r);
    xdg->grab = true;
}

static void popup_reposition(struct wl_client *c, struct wl_resource *r, struct wl_resource *positioner_res, uint32_t token) {
    (void) c;
    struct pepe_xdg_surface *xdg = wl_resource_get_user_data(r);
    struct positioner *p = wl_resource_get_user_data(positioner_res);
    int32_t bw = xdg->window ? xdg->window->width : 0, bh = xdg->window ? xdg->window->height : 0;
    int32_t px = 0, py = 0;
    for (struct pepe_xdg_surface *q = xdg->parent; q && q->popup; q = q->parent) {
        px += q->popup_x;
        py += q->popup_y;
    }
    place(p, -px, -py, bw, bh, &xdg->popup_x, &xdg->popup_y);
    xdg_popup_send_repositioned(r, token);
    xdg_popup_send_configure(r, xdg->popup_x, xdg->popup_y, p->w, p->h);
    send_configure(xdg);
}

static const struct xdg_popup_interface popup_impl = {
    .destroy = popup_destroy,
    .grab = popup_grab,
    .reposition = popup_reposition,
};

static void popup_resource_destroyed(struct wl_resource *r) {
    struct pepe_xdg_surface *xdg = wl_resource_get_user_data(r);
    xdg->popup = NULL;
    wl_list_remove(&xdg->popup_link);
    wl_list_init(&xdg->popup_link);
    struct pepe_xdg_surface *child, *tmp;
    wl_list_for_each_safe(child, tmp, &xdg->popups, popup_link) {
        wl_list_remove(&child->popup_link);
        wl_list_init(&child->popup_link);
        child->parent = NULL;
    }
    if (xdg->window) pepe_window_schedule_redraw(xdg->window);
    xdg->window = NULL;
    if (xdg->surface && xdg->surface->role_data == xdg) {
        xdg->surface->role_data = NULL;
        xdg->surface->mapped = false;
    }
}

/* ---- xdg_wm_base ---- */

static void wm_base_destroy(struct wl_client *c, struct wl_resource *r) { (void) c; wl_resource_destroy(r); }

static void wm_base_create_positioner(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct positioner *p = calloc(1, sizeof *p);
    struct wl_resource *res = wl_resource_create(c, &xdg_positioner_interface, wl_resource_get_version(r), id);
    if (!p || !res) {
        free(p);
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(res, &positioner_impl, p, positioner_free);
}

static void wm_base_get_xdg_surface(struct wl_client *c, struct wl_resource *r, uint32_t id, struct wl_resource *surface_res) {
    struct pepe_surface *surface = pepe_surface_from_resource(surface_res);
    struct pepe_xdg_surface *xdg = calloc(1, sizeof *xdg);
    if (!xdg) {
        wl_client_post_no_memory(c);
        return;
    }
    xdg->resource = wl_resource_create(c, &xdg_surface_interface, wl_resource_get_version(r), id);
    if (!xdg->resource) {
        free(xdg);
        wl_client_post_no_memory(c);
        return;
    }
    xdg->surface = surface;
    wl_list_init(&xdg->popups);
    wl_list_init(&xdg->popup_link);
    xdg->surface_destroy.notify = xdg_surface_surface_destroyed;
    wl_signal_add(&surface->destroy_signal, &xdg->surface_destroy);
    wl_resource_set_implementation(xdg->resource, &xdg_surface_impl, xdg, xdg_surface_resource_destroyed);
}

static void wm_base_pong(struct wl_client *c, struct wl_resource *r, uint32_t serial) { (void) c; (void) r; (void) serial; }

static const struct xdg_wm_base_interface wm_base_impl = {
    .destroy = wm_base_destroy,
    .create_positioner = wm_base_create_positioner,
    .get_xdg_surface = wm_base_get_xdg_surface,
    .pong = wm_base_pong,
};

static void bind_wm_base(struct wl_client *c, void *data, uint32_t version, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &xdg_wm_base_interface, version, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(r, &wm_base_impl, data, NULL);
}

/* ---- xdg-decoration: clients always decorate themselves ---- */

static void decoration_destroy(struct wl_client *c, struct wl_resource *r) { (void) c; wl_resource_destroy(r); }
static void decoration_set_mode(struct wl_client *c, struct wl_resource *r, uint32_t mode) {
    (void) c; (void) mode;
    zxdg_toplevel_decoration_v1_send_configure(r, ZXDG_TOPLEVEL_DECORATION_V1_MODE_CLIENT_SIDE);
}
static void decoration_unset_mode(struct wl_client *c, struct wl_resource *r) {
    decoration_set_mode(c, r, 0);
}
static const struct zxdg_toplevel_decoration_v1_interface decoration_impl = {
    .destroy = decoration_destroy,
    .set_mode = decoration_set_mode,
    .unset_mode = decoration_unset_mode,
};

static void decoration_manager_destroy(struct wl_client *c, struct wl_resource *r) { (void) c; wl_resource_destroy(r); }
static void decoration_manager_get(struct wl_client *c, struct wl_resource *r, uint32_t id, struct wl_resource *toplevel) {
    (void) toplevel;
    struct wl_resource *d = wl_resource_create(c, &zxdg_toplevel_decoration_v1_interface, wl_resource_get_version(r), id);
    if (!d) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(d, &decoration_impl, NULL, NULL);
    zxdg_toplevel_decoration_v1_send_configure(d, ZXDG_TOPLEVEL_DECORATION_V1_MODE_CLIENT_SIDE);
}
static const struct zxdg_decoration_manager_v1_interface decoration_manager_impl = {
    .destroy = decoration_manager_destroy,
    .get_toplevel_decoration = decoration_manager_get,
};
static void bind_decoration(struct wl_client *c, void *data, uint32_t version, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &zxdg_decoration_manager_v1_interface, version, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(r, &decoration_manager_impl, data, NULL);
}

void pepe_xdg_init(struct pepe_server *s) {
    wl_global_create(s->display, &xdg_wm_base_interface, 6, s, bind_wm_base);
    wl_global_create(s->display, &zxdg_decoration_manager_v1_interface, 1, s, bind_decoration);
}
