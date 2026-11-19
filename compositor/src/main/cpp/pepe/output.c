/* pepelauncher compositor: a single wl_output describing the phone screen. */
#include <math.h>

#include "pepe.h"
#include "wayland-server-protocol.h"

static void send_output(struct pepe_server *s, struct wl_resource *r) {
    int32_t w_px = (int32_t) lround(s->screen_w * s->default_scale);
    int32_t h_px = (int32_t) lround(s->screen_h * s->default_scale);
    wl_output_send_geometry(r, 0, 0, 0, 0, WL_OUTPUT_SUBPIXEL_UNKNOWN, "Android", "pepelauncher",
                            WL_OUTPUT_TRANSFORM_NORMAL);
    wl_output_send_mode(r, WL_OUTPUT_MODE_CURRENT | WL_OUTPUT_MODE_PREFERRED, w_px, h_px, 60000);
    if (wl_resource_get_version(r) >= WL_OUTPUT_SCALE_SINCE_VERSION) wl_output_send_scale(r, s->output_scale);
    if (wl_resource_get_version(r) >= WL_OUTPUT_NAME_SINCE_VERSION) wl_output_send_name(r, "ANDROID-1");
    if (wl_resource_get_version(r) >= WL_OUTPUT_DESCRIPTION_SINCE_VERSION) wl_output_send_description(r, "Android screen");
    if (wl_resource_get_version(r) >= WL_OUTPUT_DONE_SINCE_VERSION) wl_output_send_done(r);
}

static void output_release(struct wl_client *c, struct wl_resource *r) { (void) c; wl_resource_destroy(r); }
static const struct wl_output_interface output_impl = { .release = output_release };

static void output_unlink(struct wl_resource *r) { wl_list_remove(wl_resource_get_link(r)); }

static void bind_output(struct wl_client *c, void *data, uint32_t version, uint32_t id) {
    struct pepe_server *s = data;
    struct wl_resource *r = wl_resource_create(c, &wl_output_interface, version, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(r, &output_impl, s, output_unlink);
    wl_list_insert(&s->outputs, wl_resource_get_link(r));
    send_output(s, r);
}

/* Integer scale for clients without fractional-scale support (GTK 3, Xwayland): the scale at
 * which UI is not smaller than on Android; fractional clients get the exact density. */
static int32_t integer_scale(double scale) {
    int32_t s = (int32_t) floor(scale + 0.25);
    return s < 1 ? 1 : s;
}

void pepe_output_init(struct pepe_server *s) {
    s->output_scale = integer_scale(s->default_scale);
    wl_global_create(s->display, &wl_output_interface, 4, s, bind_output);
}

void pepe_output_update(struct pepe_server *s) {
    s->output_scale = integer_scale(s->default_scale);
    struct wl_resource *r;
    wl_resource_for_each(r, &s->outputs) send_output(s, r);
}
