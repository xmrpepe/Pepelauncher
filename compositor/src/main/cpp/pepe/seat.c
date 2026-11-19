/* pepelauncher compositor: wl_seat (pointer, keyboard, touch), clipboard and text-input-v3. */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

#include "pepe.h"
#include "text-input-unstable-v3-protocol.h"
#include "wayland-server-protocol.h"
#include "../generated/keymap_us.h"

#define KEY_BACKSPACE 14
#define KEY_TAB 15
#define KEY_ENTER 28
#define KEY_DELETE 111

struct sym_key {
    xkb_keysym_t sym;
    xkb_keycode_t code;
    xkb_mod_mask_t mods;
};
static struct sym_key *sym_table;
static size_t sym_count;

struct data_source {
    struct wl_resource *resource;
    struct wl_array mimes; /* char* */
};

static struct pepe_seat *seat_of(struct pepe_server *s) { return &s->seat; }

static void resource_unlink(struct wl_resource *r) { wl_list_remove(wl_resource_get_link(r)); }

/* ---- keymap ---- */

static int keymap_fd_create(const char *data, size_t size) {
    int fd = -1;
#ifdef __NR_memfd_create
    fd = (int) syscall(__NR_memfd_create, "pepe-keymap", 1 /* MFD_CLOEXEC */);
#endif
    if (fd < 0) {
        char path[] = "/tmp/pepe-keymap-XXXXXX";
        fd = mkostemp(path, O_CLOEXEC);
        if (fd >= 0) unlink(path);
    }
    if (fd < 0) return -1;
    if (ftruncate(fd, (off_t) size) < 0) {
        close(fd);
        return -1;
    }
    void *map = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (map == MAP_FAILED) {
        close(fd);
        return -1;
    }
    memcpy(map, data, size);
    munmap(map, size);
    return fd;
}

/* keysym → key + modifiers, for typing text from the Android input method. */
static void build_sym_table(struct xkb_keymap *km) {
    size_t cap = 512;
    sym_table = calloc(cap, sizeof *sym_table);
    sym_count = 0;
    for (xkb_keycode_t kc = xkb_keymap_min_keycode(km); kc <= xkb_keymap_max_keycode(km); kc++) {
        xkb_level_index_t levels = xkb_keymap_num_levels_for_key(km, kc, 0);
        for (xkb_level_index_t lvl = 0; lvl < levels; lvl++) {
            const xkb_keysym_t *syms;
            int n = xkb_keymap_key_get_syms_by_level(km, kc, 0, lvl, &syms);
            if (n != 1) continue;
            xkb_mod_mask_t masks[4];
            size_t m = xkb_keymap_key_get_mods_for_level(km, kc, 0, lvl, masks, 4);
            bool known = false;
            for (size_t i = 0; i < sym_count; i++) if (sym_table[i].sym == syms[0]) known = true;
            if (known) continue;
            if (sym_count == cap) {
                cap *= 2;
                struct sym_key *t = realloc(sym_table, cap * sizeof *t);
                if (!t) return;
                sym_table = t;
            }
            sym_table[sym_count++] = (struct sym_key) { syms[0], kc, m ? masks[0] : 0 };
        }
    }
}

static const struct sym_key *lookup_sym(xkb_keysym_t sym) {
    for (size_t i = 0; i < sym_count; i++) if (sym_table[i].sym == sym) return &sym_table[i];
    return NULL;
}

/* ---- focus helpers ---- */

static struct wl_client *client_of(struct pepe_surface *s) {
    return s ? wl_resource_get_client(s->resource) : NULL;
}

static void send_modifiers_to(struct pepe_seat *seat, struct wl_resource *kb, uint32_t serial) {
    wl_keyboard_send_modifiers(kb, serial, seat->mods_depressed, seat->mods_latched, seat->mods_locked, seat->group);
}

static void text_input_enter_leave(struct pepe_server *s, struct pepe_surface *old, struct pepe_surface *now) {
    struct pepe_seat *seat = seat_of(s);
    struct wl_resource *ti;
    wl_resource_for_each(ti, &seat->text_inputs) {
        struct wl_client *c = wl_resource_get_client(ti);
        if (old && c == client_of(old)) zwp_text_input_v3_send_leave(ti, old->resource);
        if (now && c == client_of(now)) zwp_text_input_v3_send_enter(ti, now->resource);
    }
    if (old != now) seat->text_input_active = NULL;
}

void pepe_seat_set_keyboard_focus(struct pepe_server *s, struct pepe_surface *surface) {
    struct pepe_seat *seat = seat_of(s);
    if (seat->keyboard_focus == surface) return;
    struct pepe_surface *old = seat->keyboard_focus;
    uint32_t serial = pepe_next_serial(s);
    struct wl_resource *kb;
    if (old) {
        wl_resource_for_each(kb, &seat->keyboards) {
            if (wl_resource_get_client(kb) == client_of(old)) wl_keyboard_send_leave(kb, serial, old->resource);
        }
    }
    seat->keyboard_focus = surface;
    if (surface) {
        struct wl_array keys;
        wl_array_init(&keys);
        wl_resource_for_each(kb, &seat->keyboards) {
            if (wl_resource_get_client(kb) != client_of(surface)) continue;
            wl_keyboard_send_enter(kb, serial, surface->resource, &keys);
            send_modifiers_to(seat, kb, serial);
        }
        wl_array_release(&keys);
    }
    text_input_enter_leave(s, old, surface);

    /* Offer the clipboard to the newly focused client. */
    struct wl_resource *dev;
    wl_resource_for_each(dev, &seat->data_devices) {
        if (surface && wl_resource_get_client(dev) == client_of(surface)) pepe_seat_offer_selection(s, dev);
    }
}

static void pointer_enter(struct pepe_server *s, struct pepe_surface *surface, double sx, double sy) {
    struct pepe_seat *seat = seat_of(s);
    if (seat->pointer_focus == surface) return;
    uint32_t serial = pepe_next_serial(s);
    struct wl_resource *p;
    if (seat->pointer_focus) {
        wl_resource_for_each(p, &seat->pointers) {
            if (wl_resource_get_client(p) != client_of(seat->pointer_focus)) continue;
            wl_pointer_send_leave(p, serial, seat->pointer_focus->resource);
            if (wl_resource_get_version(p) >= WL_POINTER_FRAME_SINCE_VERSION) wl_pointer_send_frame(p);
        }
    }
    seat->pointer_focus = surface;
    if (surface) {
        wl_resource_for_each(p, &seat->pointers) {
            if (wl_resource_get_client(p) != client_of(surface)) continue;
            wl_pointer_send_enter(p, serial, surface->resource, wl_fixed_from_double(sx), wl_fixed_from_double(sy));
            if (wl_resource_get_version(p) >= WL_POINTER_FRAME_SINCE_VERSION) wl_pointer_send_frame(p);
        }
    }
}

/* ---- pointer input from Android ---- */

static double grab_ox, grab_oy; /* window → surface offset of the surface holding the implicit grab */

void pepe_seat_pointer_motion(struct pepe_server *s, struct pepe_window *w, double x, double y) {
    struct pepe_seat *seat = seat_of(s);
    double sx, sy;
    struct pepe_surface *hit;
    if (seat->buttons_down && seat->pointer_focus) {
        hit = seat->pointer_focus; /* implicit grab while a button is held */
        sx = x - grab_ox;
        sy = y - grab_oy;
    } else {
        hit = pepe_surface_at(w, x, y, &sx, &sy);
        pointer_enter(s, hit, sx, sy);
        grab_ox = x - sx;
        grab_oy = y - sy;
    }
    seat->pointer_x = sx;
    seat->pointer_y = sy;
    if (!hit) return;
    struct wl_resource *p;
    wl_resource_for_each(p, &seat->pointers) {
        if (wl_resource_get_client(p) != client_of(hit)) continue;
        wl_pointer_send_motion(p, pepe_now_ms(), wl_fixed_from_double(sx), wl_fixed_from_double(sy));
        if (wl_resource_get_version(p) >= WL_POINTER_FRAME_SINCE_VERSION) wl_pointer_send_frame(p);
    }
}

static bool surface_is_popup_of(struct pepe_surface *surface, struct pepe_window *w) {
    struct pepe_window *sw = pepe_window_for_surface(surface);
    return sw == w && surface && (surface->role == ROLE_XDG_POPUP ||
                                  (surface->role == ROLE_SUBSURFACE && w->xdg && surface != w->surface));
}

static bool has_grabbing_popup(struct pepe_xdg_surface *xdg) {
    struct pepe_xdg_surface *p;
    wl_list_for_each(p, &xdg->popups, popup_link) if (p->grab || has_grabbing_popup(p)) return true;
    return false;
}

void pepe_seat_pointer_button(struct pepe_server *s, struct pepe_window *w, uint32_t button, bool pressed) {
    struct pepe_seat *seat = seat_of(s);
    if (pressed && w->xdg && has_grabbing_popup(w->xdg) && !surface_is_popup_of(seat->pointer_focus, w)) {
        /* A click outside a grabbing popup (menu) dismisses it and is not delivered. */
        pepe_xdg_dismiss_popups(w->xdg);
        return;
    }
    if (pressed) seat->buttons_down++;
    else if (seat->buttons_down) seat->buttons_down--;
    struct pepe_surface *focus = seat->pointer_focus;
    if (!focus) return;
    uint32_t serial = pepe_next_serial(s);
    struct wl_resource *p;
    wl_resource_for_each(p, &seat->pointers) {
        if (wl_resource_get_client(p) != client_of(focus)) continue;
        wl_pointer_send_button(p, serial, pepe_now_ms(), button,
                               pressed ? WL_POINTER_BUTTON_STATE_PRESSED : WL_POINTER_BUTTON_STATE_RELEASED);
        if (wl_resource_get_version(p) >= WL_POINTER_FRAME_SINCE_VERSION) wl_pointer_send_frame(p);
    }
    if (pressed && w->surface && seat->keyboard_focus != w->surface && w->focused) {
        pepe_seat_set_keyboard_focus(s, w->surface);
    }
}

void pepe_seat_pointer_axis(struct pepe_server *s, struct pepe_window *w, double dx, double dy) {
    (void) w;
    struct pepe_seat *seat = seat_of(s);
    if (!seat->pointer_focus) return;
    uint32_t now = pepe_now_ms();
    struct wl_resource *p;
    wl_resource_for_each(p, &seat->pointers) {
        if (wl_resource_get_client(p) != client_of(seat->pointer_focus)) continue;
        if (wl_resource_get_version(p) >= WL_POINTER_AXIS_SOURCE_SINCE_VERSION)
            wl_pointer_send_axis_source(p, WL_POINTER_AXIS_SOURCE_FINGER);
        if (dy != 0) wl_pointer_send_axis(p, now, WL_POINTER_AXIS_VERTICAL_SCROLL, wl_fixed_from_double(dy));
        if (dx != 0) wl_pointer_send_axis(p, now, WL_POINTER_AXIS_HORIZONTAL_SCROLL, wl_fixed_from_double(dx));
        if (wl_resource_get_version(p) >= WL_POINTER_FRAME_SINCE_VERSION) wl_pointer_send_frame(p);
    }
}

void pepe_seat_pointer_leave(struct pepe_server *s) {
    seat_of(s)->buttons_down = 0;
    pointer_enter(s, NULL, 0, 0);
}

/* ---- touch ---- */

void pepe_seat_touch(struct pepe_server *s, struct pepe_window *w, int type, int slot, double x, double y) {
    struct pepe_seat *seat = seat_of(s);
    if (slot < 0 || slot >= 16) return;
    uint32_t now = pepe_now_ms();
    struct wl_resource *t;
    if (type == 0) { /* down */
        double sx, sy;
        struct pepe_surface *hit = pepe_surface_at(w, x, y, &sx, &sy);
        seat->touch_focus[slot] = hit;
        if (!hit) return;
        if (w->xdg && has_grabbing_popup(w->xdg) && !surface_is_popup_of(hit, w)) {
            pepe_xdg_dismiss_popups(w->xdg);
            seat->touch_focus[slot] = NULL;
            return;
        }
        uint32_t serial = pepe_next_serial(s);
        wl_resource_for_each(t, &seat->touches) {
            if (wl_resource_get_client(t) != client_of(hit)) continue;
            wl_touch_send_down(t, serial, now, hit->resource, slot, wl_fixed_from_double(sx), wl_fixed_from_double(sy));
            wl_touch_send_frame(t);
        }
        if (w->surface && seat->keyboard_focus != w->surface && w->focused) pepe_seat_set_keyboard_focus(s, w->surface);
        return;
    }
    struct pepe_surface *focus = seat->touch_focus[slot];
    if (!focus) return;
    if (type == 1) {
        /* Motion coordinates relative to the surface the touch began on. */
        struct pepe_scene scene = { 0 };
        pepe_scene_build(w, &scene);
        double ox = 0, oy = 0;
        for (size_t i = 0; i < scene.count; i++) {
            if (scene.items[i].surface == focus) { ox = scene.items[i].x; oy = scene.items[i].y; }
        }
        pepe_scene_finish(&scene);
        wl_resource_for_each(t, &seat->touches) {
            if (wl_resource_get_client(t) != client_of(focus)) continue;
            wl_touch_send_motion(t, now, slot, wl_fixed_from_double(x - ox), wl_fixed_from_double(y - oy));
            wl_touch_send_frame(t);
        }
    } else {
        uint32_t serial = pepe_next_serial(s);
        wl_resource_for_each(t, &seat->touches) {
            if (wl_resource_get_client(t) != client_of(focus)) continue;
            if (type == 2) wl_touch_send_up(t, serial, now, slot);
            else wl_touch_send_cancel(t);
            wl_touch_send_frame(t);
        }
        seat->touch_focus[slot] = NULL;
    }
}

/* ---- keyboard ---- */

static void update_modifiers(struct pepe_server *s) {
    struct pepe_seat *seat = seat_of(s);
    xkb_mod_mask_t dep = xkb_state_serialize_mods(seat->state, XKB_STATE_MODS_DEPRESSED);
    xkb_mod_mask_t lat = xkb_state_serialize_mods(seat->state, XKB_STATE_MODS_LATCHED);
    xkb_mod_mask_t loc = xkb_state_serialize_mods(seat->state, XKB_STATE_MODS_LOCKED);
    xkb_layout_index_t grp = xkb_state_serialize_layout(seat->state, XKB_STATE_LAYOUT_EFFECTIVE);
    if (dep == seat->mods_depressed && lat == seat->mods_latched && loc == seat->mods_locked && grp == seat->group) return;
    seat->mods_depressed = dep;
    seat->mods_latched = lat;
    seat->mods_locked = loc;
    seat->group = grp;
    if (!seat->keyboard_focus) return;
    uint32_t serial = pepe_next_serial(s);
    struct wl_resource *kb;
    wl_resource_for_each(kb, &seat->keyboards) {
        if (wl_resource_get_client(kb) == client_of(seat->keyboard_focus)) send_modifiers_to(seat, kb, serial);
    }
}

static void send_key(struct pepe_server *s, uint32_t key, bool pressed) {
    struct pepe_seat *seat = seat_of(s);
    if (!seat->keyboard_focus) return;
    uint32_t serial = pepe_next_serial(s);
    struct wl_resource *kb;
    wl_resource_for_each(kb, &seat->keyboards) {
        if (wl_resource_get_client(kb) != client_of(seat->keyboard_focus)) continue;
        wl_keyboard_send_key(kb, serial, pepe_now_ms(), key,
                             pressed ? WL_KEYBOARD_KEY_STATE_PRESSED : WL_KEYBOARD_KEY_STATE_RELEASED);
    }
}

void pepe_seat_key(struct pepe_server *s, uint32_t evdev_key, bool pressed) {
    struct pepe_seat *seat = seat_of(s);
    xkb_state_update_key(seat->state, evdev_key + 8, pressed ? XKB_KEY_DOWN : XKB_KEY_UP);
    send_key(s, evdev_key, pressed);
    update_modifiers(s);
}

/* Types one character with the modifiers its key level needs, restoring them afterwards. */
static void type_codepoint(struct pepe_server *s, uint32_t cp) {
    struct pepe_seat *seat = seat_of(s);
    if (cp == '\n') { send_key(s, KEY_ENTER, true); send_key(s, KEY_ENTER, false); return; }
    if (cp == '\t') { send_key(s, KEY_TAB, true); send_key(s, KEY_TAB, false); return; }
    const struct sym_key *k = lookup_sym(xkb_utf32_to_keysym(cp));
    if (!k || !seat->keyboard_focus) return;
    uint32_t serial = pepe_next_serial(s);
    struct wl_resource *kb;
    bool needs_mods = k->mods != seat->mods_depressed;
    wl_resource_for_each(kb, &seat->keyboards) {
        if (wl_resource_get_client(kb) != client_of(seat->keyboard_focus)) continue;
        if (needs_mods) wl_keyboard_send_modifiers(kb, serial, k->mods, 0, seat->mods_locked & ~k->mods, seat->group);
        wl_keyboard_send_key(kb, serial, pepe_now_ms(), k->code - 8, WL_KEYBOARD_KEY_STATE_PRESSED);
        wl_keyboard_send_key(kb, serial, pepe_now_ms(), k->code - 8, WL_KEYBOARD_KEY_STATE_RELEASED);
        if (needs_mods) send_modifiers_to(seat, kb, serial);
    }
}

static uint32_t decode_utf8(const char **p) {
    const unsigned char *s = (const unsigned char *) *p;
    uint32_t cp;
    int len;
    if (s[0] < 0x80) { cp = s[0]; len = 1; }
    else if ((s[0] & 0xE0) == 0xC0) { cp = s[0] & 0x1F; len = 2; }
    else if ((s[0] & 0xF0) == 0xE0) { cp = s[0] & 0x0F; len = 3; }
    else { cp = s[0] & 0x07; len = 4; }
    for (int i = 1; i < len; i++) {
        if ((s[i] & 0xC0) != 0x80) { *p += i; return 0xFFFD; }
        cp = (cp << 6) | (s[i] & 0x3F);
    }
    *p += len;
    return cp;
}

static struct wl_resource *active_text_input(struct pepe_server *s) {
    struct pepe_seat *seat = seat_of(s);
    if (!seat->text_input_active || !seat->keyboard_focus) return NULL;
    if (wl_resource_get_client(seat->text_input_active) != client_of(seat->keyboard_focus)) return NULL;
    return seat->text_input_active;
}

static uint32_t ti_serial(struct wl_resource *ti);

void pepe_seat_text(struct pepe_server *s, const char *utf8) {
    struct wl_resource *ti = active_text_input(s);
    if (ti) {
        zwp_text_input_v3_send_preedit_string(ti, NULL, 0, 0);
        zwp_text_input_v3_send_commit_string(ti, utf8);
        zwp_text_input_v3_send_done(ti, ti_serial(ti));
        return;
    }
    for (const char *p = utf8; *p;) type_codepoint(s, decode_utf8(&p));
}

void pepe_seat_preedit(struct pepe_server *s, const char *utf8) {
    struct wl_resource *ti = active_text_input(s);
    if (!ti) return;
    int32_t len = (int32_t) strlen(utf8);
    zwp_text_input_v3_send_preedit_string(ti, len ? utf8 : NULL, len, len);
    zwp_text_input_v3_send_done(ti, ti_serial(ti));
}

void pepe_seat_delete_surrounding(struct pepe_server *s, uint32_t before, uint32_t after) {
    struct wl_resource *ti = active_text_input(s);
    if (ti) {
        zwp_text_input_v3_send_delete_surrounding_text(ti, before, after);
        zwp_text_input_v3_send_done(ti, ti_serial(ti));
        return;
    }
    for (uint32_t i = 0; i < before; i++) { send_key(s, KEY_BACKSPACE, true); send_key(s, KEY_BACKSPACE, false); }
    for (uint32_t i = 0; i < after; i++) { send_key(s, KEY_DELETE, true); send_key(s, KEY_DELETE, false); }
}

/* ---- wl_pointer / wl_keyboard / wl_touch resources ---- */

static void pointer_set_cursor(struct wl_client *c, struct wl_resource *r, uint32_t serial,
                               struct wl_resource *surface, int32_t hx, int32_t hy) {
    (void) c; (void) r; (void) serial; (void) hx; (void) hy;
    /* Touch screens show no cursor; the surface just must not be drawn anywhere. */
    struct pepe_surface *cs = pepe_surface_from_resource(surface);
    if (cs && cs->role == ROLE_NONE) cs->role = ROLE_CURSOR;
}
static void release(struct wl_client *c, struct wl_resource *r) { (void) c; wl_resource_destroy(r); }
static const struct wl_pointer_interface pointer_impl = { .set_cursor = pointer_set_cursor, .release = release };
static const struct wl_keyboard_interface keyboard_impl = { .release = release };
static const struct wl_touch_interface touch_impl = { .release = release };

static void seat_get_pointer(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct pepe_server *s = wl_resource_get_user_data(r);
    struct wl_resource *p = wl_resource_create(c, &wl_pointer_interface, wl_resource_get_version(r), id);
    if (!p) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(p, &pointer_impl, s, resource_unlink);
    wl_list_insert(&s->seat.pointers, wl_resource_get_link(p));
}

static void seat_get_keyboard(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct pepe_server *s = wl_resource_get_user_data(r);
    struct pepe_seat *seat = &s->seat;
    struct wl_resource *kb = wl_resource_create(c, &wl_keyboard_interface, wl_resource_get_version(r), id);
    if (!kb) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(kb, &keyboard_impl, s, resource_unlink);
    wl_list_insert(&seat->keyboards, wl_resource_get_link(kb));
    if (seat->keymap_fd >= 0)
        wl_keyboard_send_keymap(kb, WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1, seat->keymap_fd, seat->keymap_size);
    if (wl_resource_get_version(kb) >= WL_KEYBOARD_REPEAT_INFO_SINCE_VERSION) wl_keyboard_send_repeat_info(kb, 25, 500);
    if (seat->keyboard_focus && client_of(seat->keyboard_focus) == c) {
        struct wl_array keys;
        wl_array_init(&keys);
        uint32_t serial = pepe_next_serial(s);
        wl_keyboard_send_enter(kb, serial, seat->keyboard_focus->resource, &keys);
        send_modifiers_to(seat, kb, serial);
        wl_array_release(&keys);
    }
}

static void seat_get_touch(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct pepe_server *s = wl_resource_get_user_data(r);
    struct wl_resource *t = wl_resource_create(c, &wl_touch_interface, wl_resource_get_version(r), id);
    if (!t) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(t, &touch_impl, s, resource_unlink);
    wl_list_insert(&s->seat.touches, wl_resource_get_link(t));
}

static const struct wl_seat_interface seat_impl = {
    .get_pointer = seat_get_pointer,
    .get_keyboard = seat_get_keyboard,
    .get_touch = seat_get_touch,
    .release = release,
};

static void bind_seat(struct wl_client *c, void *data, uint32_t version, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wl_seat_interface, version, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &seat_impl, data, NULL);
    wl_seat_send_capabilities(r, WL_SEAT_CAPABILITY_POINTER | WL_SEAT_CAPABILITY_KEYBOARD | WL_SEAT_CAPABILITY_TOUCH);
    if (version >= WL_SEAT_NAME_SINCE_VERSION) wl_seat_send_name(r, "seat0");
}

/* ---- clipboard (wl_data_device_manager) ---- */

static const char *TEXT_MIMES[] = { "text/plain;charset=utf-8", "text/plain", "UTF8_STRING", "TEXT", "STRING" };

static void source_offer(struct wl_client *c, struct wl_resource *r, const char *mime) {
    (void) c;
    struct data_source *src = wl_resource_get_user_data(r);
    char **slot = wl_array_add(&src->mimes, sizeof *slot);
    if (slot) *slot = strdup(mime);
}
static void source_set_actions(struct wl_client *c, struct wl_resource *r, uint32_t actions) { (void) c; (void) r; (void) actions; }
static const struct wl_data_source_interface source_impl = {
    .offer = source_offer, .destroy = release, .set_actions = source_set_actions,
};

static void source_destroyed(struct wl_resource *r) {
    struct data_source *src = wl_resource_get_user_data(r);
    char **m;
    wl_array_for_each(m, &src->mimes) free(*m);
    wl_array_release(&src->mimes);
    free(src);
}

/* Offers: a proxy for the client selection, or the Android clipboard text. */
struct offer_data {
    struct pepe_server *server;
    struct wl_resource *source; /* NULL: Android clipboard */
};

struct write_job {
    int fd;
    char *text;
};

static void *write_thread(void *arg) {
    struct write_job *job = arg;
    size_t len = strlen(job->text), done = 0;
    while (done < len) {
        ssize_t n = write(job->fd, job->text + done, len - done);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) break;
        done += (size_t) n;
    }
    close(job->fd);
    free(job->text);
    free(job);
    return NULL;
}

static void offer_accept(struct wl_client *c, struct wl_resource *r, uint32_t serial, const char *mime) {
    (void) c; (void) r; (void) serial; (void) mime;
}

static void offer_receive(struct wl_client *c, struct wl_resource *r, const char *mime, int32_t fd) {
    (void) c;
    struct offer_data *od = wl_resource_get_user_data(r);
    if (!od) { close(fd); return; }
    struct pepe_seat *seat = seat_of(od->server);
    if (od->source && od->source == seat->selection_source) {
        wl_data_source_send_send(od->source, mime, fd);
        close(fd);
        return;
    }
    if (!seat->android_clipboard) { close(fd); return; }
    struct write_job *job = calloc(1, sizeof *job);
    job->fd = fd;
    job->text = strdup(seat->android_clipboard);
    pthread_t t;
    if (pthread_create(&t, NULL, write_thread, job) == 0) pthread_detach(t);
    else { close(fd); free(job->text); free(job); }
}

static void offer_finish(struct wl_client *c, struct wl_resource *r) { (void) c; (void) r; }
static void offer_set_actions(struct wl_client *c, struct wl_resource *r, uint32_t a, uint32_t p) { (void) c; (void) r; (void) a; (void) p; }
static const struct wl_data_offer_interface offer_impl = {
    .accept = offer_accept, .receive = offer_receive, .destroy = release,
    .finish = offer_finish, .set_actions = offer_set_actions,
};
static void offer_destroyed(struct wl_resource *r) { free(wl_resource_get_user_data(r)); }

void pepe_seat_offer_selection(struct pepe_server *s, struct wl_resource *device) {
    struct pepe_seat *seat = seat_of(s);
    struct wl_client *c = wl_resource_get_client(device);
    if (!seat->selection_source && !seat->android_clipboard) {
        wl_data_device_send_selection(device, NULL);
        return;
    }
    if (seat->selection_source && wl_resource_get_client(seat->selection_source) == c) return;
    struct wl_resource *offer = wl_resource_create(c, &wl_data_offer_interface, wl_resource_get_version(device), 0);
    if (!offer) return;
    struct offer_data *od = calloc(1, sizeof *od);
    od->server = s;
    od->source = seat->selection_source;
    wl_resource_set_implementation(offer, &offer_impl, od, offer_destroyed);
    wl_data_device_send_data_offer(device, offer);
    if (seat->selection_source) {
        struct data_source *src = wl_resource_get_user_data(seat->selection_source);
        char **m;
        wl_array_for_each(m, &src->mimes) wl_data_offer_send_offer(offer, *m);
    } else {
        for (size_t i = 0; i < sizeof TEXT_MIMES / sizeof *TEXT_MIMES; i++) wl_data_offer_send_offer(offer, TEXT_MIMES[i]);
    }
    wl_data_device_send_selection(device, offer);
}

static void finish_selection_read(struct pepe_seat *seat) {
    if (seat->selection_read) wl_event_source_remove(seat->selection_read);
    seat->selection_read = NULL;
    if (seat->selection_read_fd >= 0) close(seat->selection_read_fd);
    seat->selection_read_fd = -1;
    free(seat->selection_buf);
    seat->selection_buf = NULL;
    seat->selection_len = 0;
}

static int on_selection_readable(int fd, uint32_t mask, void *data) {
    struct pepe_server *s = data;
    struct pepe_seat *seat = seat_of(s);
    char chunk[4096];
    ssize_t n = read(fd, chunk, sizeof chunk);
    if (n > 0 && seat->selection_len < (1u << 20)) {
        char *grown = realloc(seat->selection_buf, seat->selection_len + (size_t) n + 1);
        if (grown) {
            memcpy(grown + seat->selection_len, chunk, (size_t) n);
            seat->selection_len += (size_t) n;
            grown[seat->selection_len] = 0;
            seat->selection_buf = grown;
        }
        return 0;
    }
    if (n < 0 && errno == EAGAIN && !(mask & WL_EVENT_HANGUP)) return 0;
    if (seat->selection_buf && s->platform->clipboard_changed) {
        free(seat->android_clipboard);
        seat->android_clipboard = strdup(seat->selection_buf);
        s->platform->clipboard_changed(s->platform->data, seat->selection_buf);
    }
    finish_selection_read(seat);
    return 0;
}

/* Reads a new client selection as text and hands it to Android. */
static void forward_selection(struct pepe_server *s, struct wl_resource *source) {
    struct pepe_seat *seat = seat_of(s);
    struct data_source *src = wl_resource_get_user_data(source);
    const char *mime = NULL;
    for (size_t i = 0; i < sizeof TEXT_MIMES / sizeof *TEXT_MIMES && !mime; i++) {
        char **m;
        wl_array_for_each(m, &src->mimes) if (strcmp(*m, TEXT_MIMES[i]) == 0) { mime = TEXT_MIMES[i]; break; }
    }
    if (!mime) return;
    int fds[2];
    if (pipe2(fds, O_CLOEXEC | O_NONBLOCK) < 0) return;
    finish_selection_read(seat);
    wl_data_source_send_send(source, mime, fds[1]);
    close(fds[1]);
    seat->selection_read_fd = fds[0];
    seat->selection_read = wl_event_loop_add_fd(s->loop, fds[0], WL_EVENT_READABLE, on_selection_readable, s);
}

static void selection_source_destroyed(struct wl_listener *l, void *data);
static struct wl_listener selection_destroy_listener = { .notify = selection_source_destroyed };

static void set_selection_source(struct pepe_server *s, struct wl_resource *source) {
    struct pepe_seat *seat = seat_of(s);
    if (seat->selection_source) {
        wl_list_remove(&selection_destroy_listener.link);
        if (seat->selection_source != source) wl_data_source_send_cancelled(seat->selection_source);
    }
    seat->selection_source = source;
    if (source) wl_resource_add_destroy_listener(source, &selection_destroy_listener);
    struct wl_resource *dev;
    wl_resource_for_each(dev, &seat->data_devices) {
        if (seat->keyboard_focus && wl_resource_get_client(dev) == client_of(seat->keyboard_focus))
            pepe_seat_offer_selection(s, dev);
    }
}

static struct pepe_server *selection_server;

static void selection_source_destroyed(struct wl_listener *l, void *data) {
    (void) data;
    wl_list_remove(&l->link);
    wl_list_init(&l->link);
    if (selection_server) selection_server->seat.selection_source = NULL;
}

static void device_start_drag(struct wl_client *c, struct wl_resource *r, struct wl_resource *source,
                              struct wl_resource *origin, struct wl_resource *icon, uint32_t serial) {
    (void) c; (void) r; (void) origin; (void) icon; (void) serial;
    if (source) wl_data_source_send_cancelled(source); /* drag and drop is not supported */
}

static void device_set_selection(struct wl_client *c, struct wl_resource *r, struct wl_resource *source, uint32_t serial) {
    (void) c; (void) serial;
    struct pepe_server *s = wl_resource_get_user_data(r);
    set_selection_source(s, source);
    if (source) forward_selection(s, source);
}

static const struct wl_data_device_interface device_impl = {
    .start_drag = device_start_drag, .set_selection = device_set_selection, .release = release,
};

static void manager_create_source(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct data_source *src = calloc(1, sizeof *src);
    struct wl_resource *res = wl_resource_create(c, &wl_data_source_interface, wl_resource_get_version(r), id);
    if (!src || !res) { free(src); wl_client_post_no_memory(c); return; }
    src->resource = res;
    wl_array_init(&src->mimes);
    wl_resource_set_implementation(res, &source_impl, src, source_destroyed);
}

static void manager_get_device(struct wl_client *c, struct wl_resource *r, uint32_t id, struct wl_resource *seat_res) {
    (void) seat_res;
    struct pepe_server *s = wl_resource_get_user_data(r);
    struct wl_resource *dev = wl_resource_create(c, &wl_data_device_interface, wl_resource_get_version(r), id);
    if (!dev) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(dev, &device_impl, s, resource_unlink);
    wl_list_insert(&s->seat.data_devices, wl_resource_get_link(dev));
    if (s->seat.keyboard_focus && client_of(s->seat.keyboard_focus) == c) pepe_seat_offer_selection(s, dev);
}

static const struct wl_data_device_manager_interface manager_impl = {
    .create_data_source = manager_create_source, .get_data_device = manager_get_device,
};

static void bind_data_manager(struct wl_client *c, void *data, uint32_t version, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wl_data_device_manager_interface, version, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &manager_impl, data, NULL);
}

void pepe_seat_set_android_clipboard(struct pepe_server *s, const char *text) {
    struct pepe_seat *seat = seat_of(s);
    if (seat->android_clipboard && text && strcmp(seat->android_clipboard, text) == 0) return;
    free(seat->android_clipboard);
    seat->android_clipboard = text ? strdup(text) : NULL;
    set_selection_source(s, NULL);
}

/* ---- text-input-v3 ---- */

struct text_input {
    struct pepe_server *server;
    uint32_t commits;
    bool enabled, pending_enabled;
};

static uint32_t ti_serial(struct wl_resource *ti) {
    struct text_input *t = wl_resource_get_user_data(ti);
    return t ? t->commits : 0;
}

static void ti_enable(struct wl_client *c, struct wl_resource *r) {
    (void) c;
    ((struct text_input *) wl_resource_get_user_data(r))->pending_enabled = true;
}
static void ti_disable(struct wl_client *c, struct wl_resource *r) {
    (void) c;
    ((struct text_input *) wl_resource_get_user_data(r))->pending_enabled = false;
}
static void ti_set_surrounding(struct wl_client *c, struct wl_resource *r, const char *text, int32_t cursor, int32_t anchor) {
    (void) c; (void) r; (void) text; (void) cursor; (void) anchor;
}
static void ti_set_u32(struct wl_client *c, struct wl_resource *r, uint32_t v) { (void) c; (void) r; (void) v; }
static void ti_set_content_type(struct wl_client *c, struct wl_resource *r, uint32_t hint, uint32_t purpose) {
    (void) c; (void) r; (void) hint; (void) purpose;
}
static void ti_set_cursor_rectangle(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y, int32_t w, int32_t h) {
    (void) c; (void) r; (void) x; (void) y; (void) w; (void) h;
}

static void ti_commit(struct wl_client *c, struct wl_resource *r) {
    (void) c;
    struct text_input *t = wl_resource_get_user_data(r);
    struct pepe_server *s = t->server;
    struct pepe_seat *seat = &s->seat;
    t->commits++;
    if (t->enabled == t->pending_enabled) return;
    t->enabled = t->pending_enabled;
    if (t->enabled) seat->text_input_active = r;
    else if (seat->text_input_active == r) seat->text_input_active = NULL;
    struct pepe_window *w = seat->keyboard_focus ? pepe_window_for_surface(seat->keyboard_focus) : NULL;
    if (w && s->platform->text_input_changed) s->platform->text_input_changed(s->platform->data, w->id, t->enabled);
}

static const struct zwp_text_input_v3_interface ti_impl = {
    .destroy = release,
    .enable = ti_enable,
    .disable = ti_disable,
    .set_surrounding_text = ti_set_surrounding,
    .set_text_change_cause = ti_set_u32,
    .set_content_type = ti_set_content_type,
    .set_cursor_rectangle = ti_set_cursor_rectangle,
    .commit = ti_commit,
};

static void ti_destroyed(struct wl_resource *r) {
    struct text_input *t = wl_resource_get_user_data(r);
    if (t->server->seat.text_input_active == r) t->server->seat.text_input_active = NULL;
    wl_list_remove(wl_resource_get_link(r));
    free(t);
}

static void ti_manager_get(struct wl_client *c, struct wl_resource *r, uint32_t id, struct wl_resource *seat_res) {
    (void) seat_res;
    struct pepe_server *s = wl_resource_get_user_data(r);
    struct text_input *t = calloc(1, sizeof *t);
    struct wl_resource *ti = wl_resource_create(c, &zwp_text_input_v3_interface, wl_resource_get_version(r), id);
    if (!t || !ti) { free(t); wl_client_post_no_memory(c); return; }
    t->server = s;
    wl_resource_set_implementation(ti, &ti_impl, t, ti_destroyed);
    wl_list_insert(&s->seat.text_inputs, wl_resource_get_link(ti));
    if (s->seat.keyboard_focus && client_of(s->seat.keyboard_focus) == c)
        zwp_text_input_v3_send_enter(ti, s->seat.keyboard_focus->resource);
}

static const struct zwp_text_input_manager_v3_interface ti_manager_impl = {
    .destroy = release, .get_text_input = ti_manager_get,
};

static void bind_ti_manager(struct wl_client *c, void *data, uint32_t version, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &zwp_text_input_manager_v3_interface, version, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &ti_manager_impl, data, NULL);
}

/* ---- lifecycle ---- */

void pepe_seat_surface_destroyed(struct pepe_server *s, struct pepe_surface *surface) {
    struct pepe_seat *seat = seat_of(s);
    if (seat->pointer_focus == surface) { seat->pointer_focus = NULL; seat->buttons_down = 0; }
    if (seat->keyboard_focus == surface) { seat->keyboard_focus = NULL; seat->text_input_active = NULL; }
    for (int i = 0; i < 16; i++) if (seat->touch_focus[i] == surface) seat->touch_focus[i] = NULL;
}

void pepe_seat_init(struct pepe_server *s) {
    struct pepe_seat *seat = &s->seat;
    wl_list_init(&seat->pointers);
    wl_list_init(&seat->keyboards);
    wl_list_init(&seat->touches);
    wl_list_init(&seat->data_devices);
    wl_list_init(&seat->text_inputs);
    seat->selection_read_fd = -1;
    selection_server = s;
    wl_list_init(&selection_destroy_listener.link);

    seat->xkb = xkb_context_new(XKB_CONTEXT_NO_DEFAULT_INCLUDES | XKB_CONTEXT_NO_ENVIRONMENT_NAMES);
    seat->keymap = xkb_keymap_new_from_string(seat->xkb, PEPE_KEYMAP_US, XKB_KEYMAP_FORMAT_TEXT_V1, 0);
    seat->keymap_fd = -1;
    if (seat->keymap) {
        seat->state = xkb_state_new(seat->keymap);
        char *text = xkb_keymap_get_as_string(seat->keymap, XKB_KEYMAP_FORMAT_TEXT_V1);
        seat->keymap_size = (uint32_t) strlen(text) + 1;
        seat->keymap_fd = keymap_fd_create(text, seat->keymap_size);
        free(text);
        build_sym_table(seat->keymap);
    } else {
        pepe_log(s, "could not compile the built-in keymap");
    }

    seat->global = wl_global_create(s->display, &wl_seat_interface, 9, s, bind_seat);
    wl_global_create(s->display, &wl_data_device_manager_interface, 3, s, bind_data_manager);
    wl_global_create(s->display, &zwp_text_input_manager_v3_interface, 1, s, bind_ti_manager);
}

void pepe_seat_finish(struct pepe_server *s) {
    struct pepe_seat *seat = &s->seat;
    finish_selection_read(seat);
    if (seat->keymap_fd >= 0) close(seat->keymap_fd);
    if (seat->state) xkb_state_unref(seat->state);
    if (seat->keymap) xkb_keymap_unref(seat->keymap);
    if (seat->xkb) xkb_context_unref(seat->xkb);
    free(seat->android_clipboard);
    free(sym_table);
    sym_table = NULL;
    sym_count = 0;
    selection_server = NULL;
}
