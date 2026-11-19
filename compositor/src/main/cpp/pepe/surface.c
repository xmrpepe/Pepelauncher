/* pepelauncher compositor: wl_compositor, wl_surface, wl_region, wl_subcompositor,
 * wp_viewporter and wp_fractional_scale_manager_v1. */
#include <math.h>
#include <stdlib.h>
#include <string.h>

#include "pepe.h"
#include "fractional-scale-v1-protocol.h"
#include "viewporter-protocol.h"
#include "wayland-server-protocol.h"

static const struct wl_surface_interface surface_impl;

struct pepe_surface *pepe_surface_from_resource(struct wl_resource *r) {
    if (!r || !wl_resource_instance_of(r, &wl_surface_interface, &surface_impl)) return NULL;
    return wl_resource_get_user_data(r);
}

/* ---- frame callbacks ---- */

static void callback_unlink(struct wl_resource *r) {
    wl_list_remove(wl_resource_get_link(r));
}

void pepe_surface_send_frame_done(struct pepe_surface *s, uint32_t time) {
    struct wl_resource *cb, *tmp;
    wl_resource_for_each_safe(cb, tmp, &s->frame_callbacks) {
        wl_callback_send_done(cb, time);
        wl_resource_destroy(cb);
    }
    struct pepe_subsurface *sub;
    wl_list_for_each(sub, &s->subsurfaces, parent_link) pepe_surface_send_frame_done(sub->surface, time);
}

/* ---- wl_surface ---- */

static void pending_buffer_destroyed(struct wl_listener *l, void *data) {
    struct pepe_surface *s = wl_container_of(l, s, pending_buffer_destroy);
    (void) data;
    s->pending.buffer = NULL;
    wl_list_remove(&l->link);
    wl_list_init(&l->link);
}

static void surface_destroy(struct wl_client *c, struct wl_resource *r) {
    (void) c;
    wl_resource_destroy(r);
}

static void surface_attach(struct wl_client *c, struct wl_resource *r, struct wl_resource *buffer,
                           int32_t x, int32_t y) {
    (void) c;
    struct pepe_surface *s = wl_resource_get_user_data(r);
    wl_list_remove(&s->pending_buffer_destroy.link);
    wl_list_init(&s->pending_buffer_destroy.link);
    s->pending.buffer = buffer;
    if (buffer) wl_resource_add_destroy_listener(buffer, &s->pending_buffer_destroy);
    s->pending.buffer_attached = true;
    s->pending.dx = x;
    s->pending.dy = y;
}

static void surface_damage(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y, int32_t w, int32_t h) {
    (void) c; (void) r; (void) x; (void) y; (void) w; (void) h; /* every frame is redrawn in full */
}

static void surface_frame(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct pepe_surface *s = wl_resource_get_user_data(r);
    struct wl_resource *cb = wl_resource_create(c, &wl_callback_interface, 1, id);
    if (!cb) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(cb, NULL, NULL, callback_unlink);
    wl_list_insert(s->pending.frame_callbacks.prev, wl_resource_get_link(cb));
}

static void surface_set_opaque_region(struct wl_client *c, struct wl_resource *r, struct wl_resource *region) {
    (void) c; (void) r; (void) region;
}

static void surface_set_input_region(struct wl_client *c, struct wl_resource *r, struct wl_resource *region) {
    (void) c; (void) r; (void) region; /* input is accepted on the whole surface */
}

/* Copies the SHM buffer into the surface and releases it right away. */
static void apply_buffer(struct pepe_surface *s, struct wl_resource *buffer) {
    struct wl_shm_buffer *shm = buffer ? wl_shm_buffer_get(buffer) : NULL;
    if (!buffer || !shm) {
        if (buffer && !shm) {
            wl_resource_post_error(s->resource, WL_SURFACE_ERROR_INVALID_SIZE, "only wl_shm buffers are supported");
            return;
        }
        free(s->pixels_copy);
        s->pixels_copy = NULL;
        s->buffer_width = s->buffer_height = 0;
        s->mapped = false;
        return;
    }
    int32_t w = wl_shm_buffer_get_width(shm);
    int32_t h = wl_shm_buffer_get_height(shm);
    int32_t stride = wl_shm_buffer_get_stride(shm);
    uint32_t format = wl_shm_buffer_get_format(shm);
    if (w <= 0 || h <= 0) return;
    if (w != s->buffer_width || h != s->buffer_height || !s->pixels_copy) {
        free(s->pixels_copy);
        s->pixels_copy = malloc((size_t) w * h * 4);
        if (!s->pixels_copy) {
            wl_client_post_no_memory(wl_resource_get_client(s->resource));
            return;
        }
    }
    s->buffer_width = w;
    s->buffer_height = h;
    s->copy_stride = w;
    s->copy_has_alpha = format == WL_SHM_FORMAT_ARGB8888;

    wl_shm_buffer_begin_access(shm);
    const uint8_t *src = wl_shm_buffer_get_data(shm);
    for (int32_t y = 0; y < h; y++) memcpy(s->pixels_copy + (size_t) y * w, src + (size_t) y * stride, (size_t) w * 4);
    wl_shm_buffer_end_access(shm);
    wl_buffer_send_release(buffer);
    s->mapped = true;
}

static void update_size(struct pepe_surface *s) {
    int32_t scale = s->scale > 0 ? s->scale : 1;
    if (s->dst_w > 0 && s->dst_h > 0) {
        s->width = s->dst_w;
        s->height = s->dst_h;
    } else if (s->has_src) {
        s->width = (int32_t) s->src_w;
        s->height = (int32_t) s->src_h;
    } else {
        s->width = s->buffer_width / scale;
        s->height = s->buffer_height / scale;
    }
}

static void surface_commit(struct wl_client *c, struct wl_resource *r) {
    (void) c;
    struct pepe_surface *s = wl_resource_get_user_data(r);

    if (s->pending.buffer_attached) {
        apply_buffer(s, s->pending.buffer);
        s->pending.buffer_attached = false;
        wl_list_remove(&s->pending_buffer_destroy.link);
        wl_list_init(&s->pending_buffer_destroy.link);
        s->pending.buffer = NULL;
    }
    s->scale = s->pending.scale;
    s->has_src = s->pending.has_src;
    s->src_x = s->pending.src_x;
    s->src_y = s->pending.src_y;
    s->src_w = s->pending.src_w;
    s->src_h = s->pending.src_h;
    s->dst_w = s->pending.dst_w;
    s->dst_h = s->pending.dst_h;
    update_size(s);

    wl_list_insert_list(s->frame_callbacks.prev, &s->pending.frame_callbacks);
    wl_list_init(&s->pending.frame_callbacks);

    /* Subsurface stacking and positions are applied on the parent's commit. */
    struct pepe_subsurface *sub;
    wl_list_for_each(sub, &s->subsurfaces, parent_link) {
        sub->x = sub->pending_x;
        sub->y = sub->pending_y;
    }

    if (s->role == ROLE_XDG_TOPLEVEL || s->role == ROLE_XDG_POPUP) pepe_xdg_surface_committed(s);
    else if (s->role == ROLE_XWAYLAND) pepe_xwm_surface_committed(s);

    struct pepe_window *w = pepe_window_for_surface(s);
    if (w) {
        pepe_window_schedule_redraw(w);
    } else if (!wl_list_empty(&s->frame_callbacks) && s->role != ROLE_XDG_TOPLEVEL) {
        /* Not shown anywhere (yet): don't leave the client waiting for a frame. */
        pepe_surface_send_frame_done(s, pepe_now_ms());
    }
}

static void surface_set_buffer_transform(struct wl_client *c, struct wl_resource *r, int32_t transform) {
    (void) c; (void) r; (void) transform; /* phones present unrotated buffers */
}

static void surface_set_buffer_scale(struct wl_client *c, struct wl_resource *r, int32_t scale) {
    (void) c;
    struct pepe_surface *s = wl_resource_get_user_data(r);
    if (scale <= 0) {
        wl_resource_post_error(r, WL_SURFACE_ERROR_INVALID_SCALE, "scale must be positive");
        return;
    }
    s->pending.scale = scale;
}

static void surface_damage_buffer(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y, int32_t w, int32_t h) {
    surface_damage(c, r, x, y, w, h);
}

static void surface_offset(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y) {
    (void) c;
    struct pepe_surface *s = wl_resource_get_user_data(r);
    s->pending.dx = x;
    s->pending.dy = y;
}

static const struct wl_surface_interface surface_impl = {
    .destroy = surface_destroy,
    .attach = surface_attach,
    .damage = surface_damage,
    .frame = surface_frame,
    .set_opaque_region = surface_set_opaque_region,
    .set_input_region = surface_set_input_region,
    .commit = surface_commit,
    .set_buffer_transform = surface_set_buffer_transform,
    .set_buffer_scale = surface_set_buffer_scale,
    .damage_buffer = surface_damage_buffer,
    .offset = surface_offset,
};

static void surface_resource_destroyed(struct wl_resource *r) {
    struct pepe_surface *s = wl_resource_get_user_data(r);
    wl_signal_emit(&s->destroy_signal, s);
    pepe_seat_surface_destroyed(s->server, s);
    pepe_xwm_surface_destroyed(s->server, s);

    struct wl_resource *cb, *tmp;
    wl_resource_for_each_safe(cb, tmp, &s->frame_callbacks) wl_resource_destroy(cb);
    wl_resource_for_each_safe(cb, tmp, &s->pending.frame_callbacks) wl_resource_destroy(cb);
    wl_list_remove(&s->pending_buffer_destroy.link);

    struct pepe_subsurface *sub, *stmp;
    wl_list_for_each_safe(sub, stmp, &s->subsurfaces, parent_link) {
        wl_list_remove(&sub->parent_link);
        wl_list_init(&sub->parent_link);
        sub->parent = NULL;
    }
    if (s->viewport) wl_resource_set_user_data(s->viewport, NULL);
    if (s->fractional_scale) wl_resource_set_user_data(s->fractional_scale, NULL);
    free(s->pixels_copy);
    free(s);
}

static void compositor_create_surface(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct pepe_server *server = wl_resource_get_user_data(r);
    struct pepe_surface *s = calloc(1, sizeof *s);
    if (!s) {
        wl_client_post_no_memory(c);
        return;
    }
    s->server = server;
    s->resource = wl_resource_create(c, &wl_surface_interface, wl_resource_get_version(r), id);
    if (!s->resource) {
        free(s);
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(s->resource, &surface_impl, s, surface_resource_destroyed);
    s->pending.scale = s->scale = 1;
    s->pending.dst_w = s->pending.dst_h = s->dst_w = s->dst_h = -1;
    wl_list_init(&s->pending.frame_callbacks);
    wl_list_init(&s->frame_callbacks);
    wl_list_init(&s->subsurfaces);
    wl_list_init(&s->subsurfaces_pending);
    wl_list_init(&s->pending_buffer_destroy.link);
    s->pending_buffer_destroy.notify = pending_buffer_destroyed;
    wl_signal_init(&s->destroy_signal);

    if (wl_resource_get_version(s->resource) >= WL_SURFACE_PREFERRED_BUFFER_SCALE_SINCE_VERSION) {
        wl_surface_send_preferred_buffer_scale(s->resource, server->output_scale);
    }
}

/* ---- wl_region: accepted and ignored ---- */

static void region_destroy(struct wl_client *c, struct wl_resource *r) { (void) c; wl_resource_destroy(r); }
static void region_add(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y, int32_t w, int32_t h) {
    (void) c; (void) r; (void) x; (void) y; (void) w; (void) h;
}
static const struct wl_region_interface region_impl = {
    .destroy = region_destroy, .add = region_add, .subtract = region_add,
};

static void compositor_create_region(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct wl_resource *region = wl_resource_create(c, &wl_region_interface, wl_resource_get_version(r), id);
    if (!region) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(region, &region_impl, NULL, NULL);
}

static const struct wl_compositor_interface compositor_impl = {
    .create_surface = compositor_create_surface,
    .create_region = compositor_create_region,
};

static void bind_compositor(struct wl_client *c, void *data, uint32_t version, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wl_compositor_interface, version, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(r, &compositor_impl, data, NULL);
}

/* ---- wl_subcompositor ---- */

static void subsurface_destroy_req(struct wl_client *c, struct wl_resource *r) { (void) c; wl_resource_destroy(r); }

static void subsurface_set_position(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y) {
    (void) c;
    struct pepe_subsurface *sub = wl_resource_get_user_data(r);
    if (!sub) return;
    sub->pending_x = x;
    sub->pending_y = y;
}

static void restack(struct wl_resource *r, struct wl_resource *sibling_res, bool above) {
    struct pepe_subsurface *sub = wl_resource_get_user_data(r);
    struct pepe_surface *sibling = pepe_surface_from_resource(sibling_res);
    if (!sub || !sub->parent || !sibling) return;
    wl_list_remove(&sub->parent_link);
    if (sibling == sub->parent) {
        /* above the parent = bottom of the "above" list; below the parent: we keep subsurfaces
         * above the parent only (placing below is rare and drawn above). */
        wl_list_insert(&sub->parent->subsurfaces, &sub->parent_link);
        return;
    }
    struct pepe_subsurface *other;
    wl_list_for_each(other, &sub->parent->subsurfaces, parent_link) {
        if (other->surface == sibling) {
            if (above) wl_list_insert(&other->parent_link, &sub->parent_link);
            else wl_list_insert(other->parent_link.prev, &sub->parent_link);
            return;
        }
    }
    wl_list_insert(sub->parent->subsurfaces.prev, &sub->parent_link);
}

static void subsurface_place_above(struct wl_client *c, struct wl_resource *r, struct wl_resource *sibling) {
    (void) c;
    restack(r, sibling, true);
}

static void subsurface_place_below(struct wl_client *c, struct wl_resource *r, struct wl_resource *sibling) {
    (void) c;
    restack(r, sibling, false);
}

static void subsurface_set_sync(struct wl_client *c, struct wl_resource *r) {
    (void) c;
    struct pepe_subsurface *sub = wl_resource_get_user_data(r);
    if (sub) sub->sync = true;
}

static void subsurface_set_desync(struct wl_client *c, struct wl_resource *r) {
    (void) c;
    struct pepe_subsurface *sub = wl_resource_get_user_data(r);
    if (sub) sub->sync = false;
}

static const struct wl_subsurface_interface subsurface_impl = {
    .destroy = subsurface_destroy_req,
    .set_position = subsurface_set_position,
    .place_above = subsurface_place_above,
    .place_below = subsurface_place_below,
    .set_sync = subsurface_set_sync,
    .set_desync = subsurface_set_desync,
};

static void subsurface_resource_destroyed(struct wl_resource *r) {
    struct pepe_subsurface *sub = wl_resource_get_user_data(r);
    if (!sub) return;
    wl_list_remove(&sub->parent_link);
    if (sub->surface) {
        sub->surface->role_data = NULL;
        struct pepe_window *w = sub->parent ? pepe_window_for_surface(sub->parent) : NULL;
        if (w) pepe_window_schedule_redraw(w);
    }
    wl_list_remove(&sub->surface_destroy.link);
    free(sub);
}

static void subsurface_surface_destroyed(struct wl_listener *l, void *data) {
    (void) data;
    struct pepe_subsurface *sub = wl_container_of(l, sub, surface_destroy);
    wl_list_remove(&sub->parent_link);
    wl_list_init(&sub->parent_link);
    wl_list_remove(&l->link);
    wl_list_init(&l->link);
    sub->surface = NULL;
    sub->parent = NULL;
}

static void subcompositor_destroy(struct wl_client *c, struct wl_resource *r) { (void) c; wl_resource_destroy(r); }

static void subcompositor_get_subsurface(struct wl_client *c, struct wl_resource *r, uint32_t id,
                                         struct wl_resource *surface_res, struct wl_resource *parent_res) {
    struct pepe_surface *surface = pepe_surface_from_resource(surface_res);
    struct pepe_surface *parent = pepe_surface_from_resource(parent_res);
    if (!surface || !parent || surface == parent) {
        wl_resource_post_error(r, WL_SUBCOMPOSITOR_ERROR_BAD_SURFACE, "invalid subsurface");
        return;
    }
    if (surface->role != ROLE_NONE && surface->role != ROLE_SUBSURFACE) {
        wl_resource_post_error(r, WL_SUBCOMPOSITOR_ERROR_BAD_SURFACE, "surface already has a role");
        return;
    }
    struct pepe_subsurface *sub = calloc(1, sizeof *sub);
    if (!sub) {
        wl_client_post_no_memory(c);
        return;
    }
    sub->resource = wl_resource_create(c, &wl_subsurface_interface, wl_resource_get_version(r), id);
    if (!sub->resource) {
        free(sub);
        wl_client_post_no_memory(c);
        return;
    }
    sub->surface = surface;
    sub->parent = parent;
    sub->sync = true;
    surface->role = ROLE_SUBSURFACE;
    surface->role_data = sub;
    wl_list_insert(parent->subsurfaces.prev, &sub->parent_link);
    sub->surface_destroy.notify = subsurface_surface_destroyed;
    wl_signal_add(&surface->destroy_signal, &sub->surface_destroy);
    wl_resource_set_implementation(sub->resource, &subsurface_impl, sub, subsurface_resource_destroyed);
}

static const struct wl_subcompositor_interface subcompositor_impl = {
    .destroy = subcompositor_destroy,
    .get_subsurface = subcompositor_get_subsurface,
};

static void bind_subcompositor(struct wl_client *c, void *data, uint32_t version, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wl_subcompositor_interface, version, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(r, &subcompositor_impl, data, NULL);
}

/* ---- wp_viewporter ---- */

static void viewport_destroy(struct wl_client *c, struct wl_resource *r) { (void) c; wl_resource_destroy(r); }

static void viewport_set_source(struct wl_client *c, struct wl_resource *r, wl_fixed_t x, wl_fixed_t y,
                                wl_fixed_t w, wl_fixed_t h) {
    (void) c;
    struct pepe_surface *s = wl_resource_get_user_data(r);
    if (!s) {
        wl_resource_post_error(r, WP_VIEWPORT_ERROR_NO_SURFACE, "surface destroyed");
        return;
    }
    if (x == wl_fixed_from_int(-1) && w == wl_fixed_from_int(-1)) {
        s->pending.has_src = false;
        return;
    }
    s->pending.has_src = true;
    s->pending.src_x = wl_fixed_to_double(x);
    s->pending.src_y = wl_fixed_to_double(y);
    s->pending.src_w = wl_fixed_to_double(w);
    s->pending.src_h = wl_fixed_to_double(h);
}

static void viewport_set_destination(struct wl_client *c, struct wl_resource *r, int32_t w, int32_t h) {
    (void) c;
    struct pepe_surface *s = wl_resource_get_user_data(r);
    if (!s) {
        wl_resource_post_error(r, WP_VIEWPORT_ERROR_NO_SURFACE, "surface destroyed");
        return;
    }
    s->pending.dst_w = w;
    s->pending.dst_h = h;
}

static const struct wp_viewport_interface viewport_impl = {
    .destroy = viewport_destroy,
    .set_source = viewport_set_source,
    .set_destination = viewport_set_destination,
};

static void viewport_resource_destroyed(struct wl_resource *r) {
    struct pepe_surface *s = wl_resource_get_user_data(r);
    if (!s) return;
    s->viewport = NULL;
    s->pending.has_src = false;
    s->pending.dst_w = s->pending.dst_h = -1;
}

static void viewporter_destroy(struct wl_client *c, struct wl_resource *r) { (void) c; wl_resource_destroy(r); }

static void viewporter_get_viewport(struct wl_client *c, struct wl_resource *r, uint32_t id, struct wl_resource *surface_res) {
    struct pepe_surface *s = pepe_surface_from_resource(surface_res);
    if (s && s->viewport) {
        wl_resource_post_error(r, WP_VIEWPORTER_ERROR_VIEWPORT_EXISTS, "viewport exists");
        return;
    }
    struct wl_resource *vp = wl_resource_create(c, &wp_viewport_interface, wl_resource_get_version(r), id);
    if (!vp) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(vp, &viewport_impl, s, viewport_resource_destroyed);
    if (s) s->viewport = vp;
}

static const struct wp_viewporter_interface viewporter_impl = {
    .destroy = viewporter_destroy,
    .get_viewport = viewporter_get_viewport,
};

static void bind_viewporter(struct wl_client *c, void *data, uint32_t version, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wp_viewporter_interface, version, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(r, &viewporter_impl, data, NULL);
}

/* ---- wp_fractional_scale_manager_v1 ---- */

static void fractional_destroy(struct wl_client *c, struct wl_resource *r) { (void) c; wl_resource_destroy(r); }
static const struct wp_fractional_scale_v1_interface fractional_impl = { .destroy = fractional_destroy };

static void fractional_resource_destroyed(struct wl_resource *r) {
    struct pepe_surface *s = wl_resource_get_user_data(r);
    if (s) s->fractional_scale = NULL;
}

void pepe_surface_set_preferred_scale(struct pepe_surface *s, double scale) {
    if (s->fractional_scale) wp_fractional_scale_v1_send_preferred_scale(s->fractional_scale, (uint32_t) lround(scale * 120.0));
    if (wl_resource_get_version(s->resource) >= WL_SURFACE_PREFERRED_BUFFER_SCALE_SINCE_VERSION) {
        int32_t integer = (int32_t) ceil(scale - 0.01);
        wl_surface_send_preferred_buffer_scale(s->resource, integer < 1 ? 1 : integer);
    }
}

static void fractional_manager_destroy(struct wl_client *c, struct wl_resource *r) { (void) c; wl_resource_destroy(r); }

static void fractional_get(struct wl_client *c, struct wl_resource *r, uint32_t id, struct wl_resource *surface_res) {
    struct pepe_surface *s = pepe_surface_from_resource(surface_res);
    if (s && s->fractional_scale) {
        wl_resource_post_error(r, WP_FRACTIONAL_SCALE_MANAGER_V1_ERROR_FRACTIONAL_SCALE_EXISTS, "exists");
        return;
    }
    struct wl_resource *fs = wl_resource_create(c, &wp_fractional_scale_v1_interface, wl_resource_get_version(r), id);
    if (!fs) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(fs, &fractional_impl, s, fractional_resource_destroyed);
    if (!s) return;
    s->fractional_scale = fs;
    struct pepe_window *w = pepe_window_for_surface(s);
    wp_fractional_scale_v1_send_preferred_scale(fs, (uint32_t) lround((w ? w->scale : s->server->default_scale) * 120.0));
}

static const struct wp_fractional_scale_manager_v1_interface fractional_manager_impl = {
    .destroy = fractional_manager_destroy,
    .get_fractional_scale = fractional_get,
};

static void bind_fractional(struct wl_client *c, void *data, uint32_t version, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wp_fractional_scale_manager_v1_interface, version, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(r, &fractional_manager_impl, data, NULL);
}

bool pepe_surface_accepts_input(struct pepe_surface *s, double x, double y) {
    return s->mapped && x >= 0 && y >= 0 && x < s->width && y < s->height;
}

void pepe_surface_globals_init(struct pepe_server *s) {
    wl_global_create(s->display, &wl_compositor_interface, 6, s, bind_compositor);
    wl_global_create(s->display, &wl_subcompositor_interface, 1, s, bind_subcompositor);
    wl_global_create(s->display, &wp_viewporter_interface, 1, s, bind_viewporter);
    wl_global_create(s->display, &wp_fractional_scale_manager_v1_interface, 1, s, bind_fractional);
}
