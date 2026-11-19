/* pepelauncher compositor: windows. One window = one toplevel = one Android task. */
#include <math.h>
#include <stdlib.h>
#include <string.h>

#include "pepe.h"
#include "xdg-shell-protocol.h"

struct pepe_window *pepe_window_create(struct pepe_server *s, struct pepe_surface *surface) {
    struct pepe_window *w = calloc(1, sizeof *w);
    if (!w) return NULL;
    w->server = s;
    w->id = s->next_window_id++;
    w->surface = surface;
    w->scale = s->default_scale;
    wl_list_init(&w->children);
    wl_list_init(&w->child_link);
    wl_list_insert(s->windows.prev, &w->link);
    return w;
}

void pepe_window_destroy(struct pepe_window *w) {
    struct pepe_server *s = w->server;
    if (w->redraw_idle) wl_event_source_remove(w->redraw_idle);
    if (w->kill_timer) wl_event_source_remove(w->kill_timer);
    if (w->target && s->platform->target_release) s->platform->target_release(s->platform->data, w->target);
    if (w->announced && s->platform->window_removed) s->platform->window_removed(s->platform->data, w->id);

    struct pepe_window *child, *tmp;
    wl_list_for_each_safe(child, tmp, &w->children, child_link) {
        wl_list_remove(&child->child_link);
        wl_list_init(&child->child_link);
        child->parent = NULL;
        pepe_window_announce(child); /* an attached dialog outlives its parent: give it a task */
    }
    if (w->parent) {
        wl_list_remove(&w->child_link);
        pepe_window_schedule_redraw(w->parent);
    }
    wl_list_remove(&w->link);
    free(w->title);
    free(w->app_id);
    free(w);
}

struct pepe_window *pepe_window_find(struct pepe_server *s, uint32_t id) {
    struct pepe_window *w;
    wl_list_for_each(w, &s->windows, link) if (w->id == id) return w;
    return NULL;
}

struct pepe_window *pepe_window_for_surface(struct pepe_surface *surface) {
    for (int depth = 0; surface && depth < 32; depth++) {
        switch (surface->role) {
        case ROLE_XDG_TOPLEVEL:
        case ROLE_XDG_POPUP: {
            struct pepe_xdg_surface *xdg = surface->role_data;
            return xdg ? xdg->window : NULL;
        }
        case ROLE_SUBSURFACE: {
            struct pepe_subsurface *sub = surface->role_data;
            surface = sub ? sub->parent : NULL;
            break;
        }
        case ROLE_XWAYLAND:
            return pepe_xwm_window_of(surface);
        default:
            return NULL;
        }
    }
    return NULL;
}

/* The window that actually has the Android target: attached dialogs draw in their parent. */
static struct pepe_window *presenting_window(struct pepe_window *w) {
    while (w && w->parent) w = w->parent;
    return w;
}

static void redraw(void *data) {
    struct pepe_window *w = data;
    w->redraw_idle = NULL;
    w->redraw_scheduled = false;
    pepe_render_window(w);
}

void pepe_window_schedule_redraw(struct pepe_window *w) {
    w = presenting_window(w);
    if (!w || w->redraw_scheduled) return;
    w->redraw_scheduled = true;
    w->redraw_idle = wl_event_loop_add_idle(w->server->loop, redraw, w);
}

static void notify_changed(struct pepe_window *w) {
    struct pepe_server *s = w->server;
    if (w->announced && s->platform->window_changed)
        s->platform->window_changed(s->platform->data, w->id, w->title ? w->title : "", w->app_id ? w->app_id : "");
}

void pepe_window_set_title(struct pepe_window *w, const char *title) {
    if (w->title && title && strcmp(w->title, title) == 0) return;
    free(w->title);
    w->title = title ? strdup(title) : NULL;
    notify_changed(w);
}

void pepe_window_set_app_id(struct pepe_window *w, const char *app_id) {
    if (w->app_id && app_id && strcmp(w->app_id, app_id) == 0) return;
    free(w->app_id);
    w->app_id = app_id ? strdup(app_id) : NULL;
    notify_changed(w);
}

/* A mapped toplevel gets its own task, unless it is a dialog attached to its parent. */
void pepe_window_announce(struct pepe_window *w) {
    struct pepe_server *s = w->server;
    if (w->announced || w->parent) return;
    w->announced = true;
    if (s->platform->window_added) {
        s->platform->window_added(s->platform->data, w->id, 0,
                                  w->title ? w->title : "", w->app_id ? w->app_id : "");
    }
}

/* Size from the Android target (or the screen before the Activity exists), then tell the client. */
void pepe_window_configure(struct pepe_window *w) {
    struct pepe_window *host = presenting_window(w);
    if (w->target && w->target_w > 0 && w->scale > 0) {
        w->width = (int32_t) floor(w->target_w / w->scale);
        w->height = (int32_t) floor(w->target_h / w->scale);
    } else {
        /* No Activity yet, or a dialog attached to its parent: the client picks its size. */
        w->width = 0;
        w->height = 0;
    }
    if (w->xdg) pepe_xdg_configure_toplevel(w);
    if (w->xwin) pepe_xwm_configure(w->xwin, w->width, w->height);
    if (w->surface) pepe_surface_set_preferred_scale(w->surface, host ? host->scale : w->scale);
}

void pepe_window_get_geometry(struct pepe_window *w, int32_t *x, int32_t *y, int32_t *width, int32_t *height) {
    if (w->xdg && w->xdg->has_geometry) {
        *x = w->xdg->geo_x;
        *y = w->xdg->geo_y;
        *width = w->xdg->geo_w;
        *height = w->xdg->geo_h;
        return;
    }
    *x = 0;
    *y = 0;
    *width = w->surface ? w->surface->width : 0;
    *height = w->surface ? w->surface->height : 0;
}
