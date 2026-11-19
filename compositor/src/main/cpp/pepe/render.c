/* pepelauncher compositor: CPU compositing of a window's surfaces into its Android buffer. */
#include <math.h>
#include <stdlib.h>
#include <string.h>

#include "pepe.h"
#include "wayland-server-protocol.h"

/* ---- scene ---- */

static void scene_add(struct pepe_scene *scene, struct pepe_surface *surface, int32_t x, int32_t y) {
    if (scene->count == scene->capacity) {
        size_t cap = scene->capacity ? scene->capacity * 2 : 16;
        struct pepe_scene_item *items = realloc(scene->items, cap * sizeof *items);
        if (!items) return;
        scene->items = items;
        scene->capacity = cap;
    }
    scene->items[scene->count++] = (struct pepe_scene_item) { surface, x, y };
}

static void add_tree(struct pepe_scene *scene, struct pepe_surface *surface, int32_t x, int32_t y) {
    if (!surface || !surface->mapped) return;
    scene_add(scene, surface, x, y);
    struct pepe_subsurface *sub;
    wl_list_for_each(sub, &surface->subsurfaces, parent_link) {
        if (sub->surface) add_tree(scene, sub->surface, x + sub->x, y + sub->y);
    }
}

/* Popups: position relative to the parent's geometry origin, minus their own geometry offset. */
static void add_popups(struct pepe_scene *scene, struct pepe_xdg_surface *parent, int32_t gx, int32_t gy) {
    struct pepe_xdg_surface *popup;
    wl_list_for_each(popup, &parent->popups, popup_link) {
        if (!popup->surface) continue;
        int32_t px = gx + popup->popup_x, py = gy + popup->popup_y;
        int32_t ox = popup->has_geometry ? popup->geo_x : 0, oy = popup->has_geometry ? popup->geo_y : 0;
        add_tree(scene, popup->surface, px - ox, py - oy);
        add_popups(scene, popup, px, py);
    }
}

struct overlay_ctx {
    struct pepe_scene *scene;
};

static void add_overlay(struct pepe_surface *surface, int32_t x, int32_t y, void *data) {
    struct overlay_ctx *ctx = data;
    add_tree(ctx->scene, surface, x, y);
}

static void add_window(struct pepe_scene *scene, struct pepe_window *w, int32_t x, int32_t y) {
    int32_t gx, gy, gw, gh;
    pepe_window_get_geometry(w, &gx, &gy, &gw, &gh);
    add_tree(scene, w->surface, x - gx, y - gy);
    if (w->xdg) add_popups(scene, w->xdg, x, y);
    if (w->xwin) {
        struct overlay_ctx ctx = { scene };
        pepe_xwm_for_each_overlay(w, add_overlay, &ctx);
    }
}

void pepe_scene_build(struct pepe_window *w, struct pepe_scene *scene) {
    scene->count = 0;
    add_window(scene, w, 0, 0);
    /* Dialogs attached to this window (setting "dialogs attach to their parent"): centred. */
    struct pepe_window *child;
    wl_list_for_each(child, &w->children, child_link) {
        int32_t gx, gy, cw, ch;
        pepe_window_get_geometry(child, &gx, &gy, &cw, &ch);
        add_window(scene, child, (w->width - cw) / 2, (w->height - ch) / 2);
    }
}

void pepe_scene_finish(struct pepe_scene *scene) {
    free(scene->items);
    scene->items = NULL;
    scene->count = scene->capacity = 0;
}

struct pepe_surface *pepe_surface_at(struct pepe_window *w, double x, double y, double *sx, double *sy) {
    struct pepe_scene scene = { 0 };
    pepe_scene_build(w, &scene);
    struct pepe_surface *hit = NULL;
    for (size_t i = scene.count; i-- > 0;) {
        struct pepe_scene_item *it = &scene.items[i];
        double lx = x - it->x, ly = y - it->y;
        if (pepe_surface_accepts_input(it->surface, lx, ly)) {
            hit = it->surface;
            *sx = lx;
            *sy = ly;
            break;
        }
    }
    pepe_scene_finish(&scene);
    return hit;
}

/*
 * Pixels per logical unit for this frame: the window's scale, reduced when the client insists on
 * a window geometry larger than its task (minimum sizes on narrow phone screens), so the whole
 * window stays visible instead of being cropped.
 */
double pepe_window_fit_scale(struct pepe_window *w, int32_t px_w, int32_t px_h) {
    double scale = w->scale > 0 ? w->scale : 1.0;
    /* Only Wayland clients that applied our size and are still larger (a real minimum size) are
     * scaled down. X11 windows are always resized by the window manager instead; their first
     * frames still show the default size and must not be mistaken for a minimum. */
    if (!w->xdg || !w->xdg->size_settled || w->width <= 0) return w->render_scale > 0 ? w->render_scale : scale;
    int32_t gx, gy, gw, gh;
    pepe_window_get_geometry(w, &gx, &gy, &gw, &gh);
    if (gw > 0 && px_w > 0 && gw * scale > px_w) scale = (double) px_w / gw;
    if (gh > 0 && px_h > 0 && gh * scale > px_h) scale = (double) px_h / gh;
    return scale;
}

/* ---- pixels ---- */

/* wl_shm ARGB8888 (0xAARRGGBB in a uint32) → Android RGBA_8888 (bytes R,G,B,A = 0xAABBGGRR). */
static inline uint32_t to_rgba(uint32_t p, uint32_t a) {
    return (a << 24) | ((p & 0xFF) << 16) | (p & 0xFF00) | ((p >> 16) & 0xFF);
}

static inline uint32_t blend(uint32_t dst, uint32_t src_rgba, uint32_t a) {
    uint32_t inv = 255 - a;
    uint32_t rb = (dst & 0x00FF00FF) * inv;
    uint32_t g = ((dst >> 8) & 0x00FF00FF) * inv;
    rb = ((rb + 0x00800080 + ((rb >> 8) & 0x00FF00FF)) >> 8) & 0x00FF00FF;
    g = ((g + 0x00800080 + ((g >> 8) & 0x00FF00FF)) >> 8) & 0x00FF00FF;
    uint32_t scaled = rb | (g << 8);
    /* premultiplied source-over, saturating per channel */
    uint32_t out = 0;
    for (int shift = 0; shift < 32; shift += 8) {
        uint32_t c = ((src_rgba >> shift) & 0xFF) + ((scaled >> shift) & 0xFF);
        out |= (c > 255 ? 255 : c) << shift;
    }
    return out;
}

static void draw_surface(const struct pepe_target_buffer *buf, const struct pepe_surface *s,
                         int32_t lx, int32_t ly, double scale) {
    if (!s->pixels_copy || s->width <= 0 || s->height <= 0) return;
    int32_t bscale = s->scale > 0 ? s->scale : 1;

    /* Source rectangle in buffer pixels. */
    double sx0 = 0, sy0 = 0, sw = s->buffer_width, sh = s->buffer_height;
    if (s->has_src) {
        sx0 = s->src_x * bscale;
        sy0 = s->src_y * bscale;
        sw = s->src_w * bscale;
        sh = s->src_h * bscale;
    }
    /* Destination rectangle in target pixels. */
    int32_t dx0 = (int32_t) lround(lx * scale), dy0 = (int32_t) lround(ly * scale);
    int32_t dx1 = (int32_t) lround((lx + s->width) * scale), dy1 = (int32_t) lround((ly + s->height) * scale);
    int32_t dw = dx1 - dx0, dh = dy1 - dy0;
    if (dw <= 0 || dh <= 0 || sw <= 0 || sh <= 0) return;

    int32_t x_start = dx0 < 0 ? 0 : dx0, x_end = dx1 > buf->width ? buf->width : dx1;
    int32_t y_start = dy0 < 0 ? 0 : dy0, y_end = dy1 > buf->height ? buf->height : dy1;
    if (x_start >= x_end || y_start >= y_end) return;

    /* Nearest-neighbour column lookup, shared by every row. */
    int32_t *cols = malloc((size_t) (x_end - x_start) * sizeof *cols);
    if (!cols) return;
    for (int32_t x = x_start; x < x_end; x++) {
        int32_t sx = (int32_t) (sx0 + (x - dx0 + 0.5) * sw / dw);
        if (sx < 0) sx = 0;
        if (sx >= s->buffer_width) sx = s->buffer_width - 1;
        cols[x - x_start] = sx;
    }
    for (int32_t y = y_start; y < y_end; y++) {
        int32_t sy = (int32_t) (sy0 + (y - dy0 + 0.5) * sh / dh);
        if (sy < 0) sy = 0;
        if (sy >= s->buffer_height) sy = s->buffer_height - 1;
        const uint32_t *src = s->pixels_copy + (size_t) sy * s->copy_stride;
        uint32_t *dst = buf->pixels + (size_t) y * buf->stride;
        if (!s->copy_has_alpha) {
            for (int32_t x = x_start; x < x_end; x++) dst[x] = to_rgba(src[cols[x - x_start]], 0xFF);
            continue;
        }
        for (int32_t x = x_start; x < x_end; x++) {
            uint32_t p = src[cols[x - x_start]];
            uint32_t a = p >> 24;
            if (a == 0xFF) dst[x] = to_rgba(p, 0xFF);
            else if (a) dst[x] = blend(dst[x], to_rgba(p, a), a);
        }
    }
    free(cols);
}

void pepe_render_window(struct pepe_window *w) {
    struct pepe_server *s = w->server;
    struct pepe_scene scene = { 0 };
    pepe_scene_build(w, &scene);

    struct pepe_target_buffer buf;
    if (w->target && s->platform->target_lock && s->platform->target_lock(s->platform->data, w->target, &buf)) {
        for (int32_t y = 0; y < buf.height; y++) {
            uint32_t *row = buf.pixels + (size_t) y * buf.stride;
            for (int32_t x = 0; x < buf.width; x++) row[x] = 0xFF000000;
        }
        double scale = pepe_window_fit_scale(w, buf.width, buf.height);
        w->render_scale = scale;
        if (scale < w->scale - 0.001) {
            /* The client is wider (or taller) than its task: it is shown scaled down, so offer it
             * the matching larger logical size and it fills the task at that scale. */
            int32_t want_w = (int32_t) (buf.width / scale), want_h = (int32_t) (buf.height / scale);
            if (want_w != w->width || want_h != w->height) {
                w->width = want_w;
                w->height = want_h;
                if (w->xdg) pepe_xdg_configure_toplevel(w);
                if (w->xwin) pepe_xwm_configure(w->xwin, want_w, want_h);
            }
        }
        for (size_t i = 0; i < scene.count; i++) {
            draw_surface(&buf, scene.items[i].surface, scene.items[i].x, scene.items[i].y, scale);
        }
        s->platform->target_post(s->platform->data, w->target);

        uint32_t now = pepe_now_ms();
        for (size_t i = 0; i < scene.count; i++) {
            struct pepe_surface *surface = scene.items[i].surface;
            if (surface->entered) continue;
            surface->entered = true;
            struct wl_resource *out;
            wl_resource_for_each(out, &s->outputs) {
                if (wl_resource_get_client(out) == wl_resource_get_client(surface->resource))
                    wl_surface_send_enter(surface->resource, out);
            }
        }
        for (size_t i = 0; i < scene.count; i++) pepe_surface_send_frame_done(scene.items[i].surface, now);
    }
    pepe_scene_finish(&scene);
}
