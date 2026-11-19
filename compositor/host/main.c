/*
 * Desktop test backend for the pepelauncher compositor (not shipped in the app).
 *
 * Every toplevel gets an in-memory "Activity" of WIDTHxHEIGHT pixels; each posted frame is
 * written to <outdir>/window-<id>.png. Commands read from a FIFO inject
 * the same events the Android Activity would send, through the same command queue:
 *
 *   motion <id> <x> <y> | button <id> <code> <0|1> | click <id> <x> <y> | key <code> <0|1>
 *   text <utf8...> | touch <id> <down|move|up> <slot> <x> <y> | resize <id> <w> <h>
 *   close <id> | kill <id> | clipboard <text...> | xwayland <wm_fd> <wl_fd> | quit
 *
 * Window events are printed to stdout as "added <id> <title> <app_id>", "changed …", "removed <id>".
 */
#define _GNU_SOURCE
#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include <zlib.h>

#include "pepe_api.h"
#include "xwayland_launch.h"

struct target {
    uint32_t id;
    uint32_t *pixels;
    int32_t w, h;
    struct timespec last_save;
    unsigned frames;
};

static struct pepe_server *server;
static const char *outdir = ".";
static int32_t width = 720, height = 1280;
static double scale = 2.0;

static void put32(unsigned char *p, uint32_t v) { p[0] = v >> 24; p[1] = v >> 16; p[2] = v >> 8; p[3] = v; }

static void write_chunk(FILE *f, const char *type, const unsigned char *data, uint32_t len) {
    unsigned char hdr[8];
    put32(hdr, len);
    memcpy(hdr + 4, type, 4);
    fwrite(hdr, 1, 8, f);
    if (len) fwrite(data, 1, len, f);
    uLong crc = crc32(0, (const Bytef *) type, 4);
    if (len) crc = crc32(crc, data, len);
    unsigned char c[4];
    put32(c, (uint32_t) crc);
    fwrite(c, 1, 4, f);
}

/* Pixels are RGBA bytes, as on Android. */
static void save_png(const struct target *t) {
    char path[512], tmp[520];
    snprintf(path, sizeof path, "%s/window-%u.png", outdir, t->id);
    snprintf(tmp, sizeof tmp, "%s.tmp", path);
    size_t raw_len = (size_t) t->h * (1 + (size_t) t->w * 4);
    unsigned char *raw = malloc(raw_len);
    for (int32_t y = 0; y < t->h; y++) {
        unsigned char *row = raw + (size_t) y * (1 + (size_t) t->w * 4);
        row[0] = 0;
        memcpy(row + 1, t->pixels + (size_t) y * t->w, (size_t) t->w * 4);
    }
    uLongf zlen = compressBound(raw_len);
    unsigned char *z = malloc(zlen);
    compress2(z, &zlen, raw, raw_len, 3);
    FILE *f = fopen(tmp, "wb");
    if (f) {
        static const unsigned char sig[8] = { 137, 80, 78, 71, 13, 10, 26, 10 };
        fwrite(sig, 1, 8, f);
        unsigned char ihdr[13];
        put32(ihdr, (uint32_t) t->w);
        put32(ihdr + 4, (uint32_t) t->h);
        ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
        write_chunk(f, "IHDR", ihdr, 13);
        write_chunk(f, "IDAT", z, (uint32_t) zlen);
        write_chunk(f, "IEND", NULL, 0);
        fclose(f);
        rename(tmp, path);
    }
    free(raw);
    free(z);
}

static void attach(uint32_t id, int32_t w, int32_t h) {
    struct target *t = calloc(1, sizeof *t);
    t->id = id;
    t->w = w;
    t->h = h;
    t->pixels = calloc((size_t) w * h, 4);
    struct pepe_cmd *c = pepe_cmd_new(PEPE_CMD_ATTACH, id);
    c->ptr = t;
    c->i0 = w;
    c->i1 = h;
    c->d0 = scale;
    pepe_post(server, c);
    struct pepe_cmd *f = pepe_cmd_new(PEPE_CMD_FOCUS, id);
    f->i0 = 1;
    pepe_post(server, f);
}

static void on_added(void *d, uint32_t id, uint32_t parent, const char *title, const char *app_id) {
    (void) d; (void) parent;
    printf("added %u '%s' '%s'\n", id, title, app_id);
    fflush(stdout);
    attach(id, width, height); /* like starting LinuxWindowActivity */
}
static void on_changed(void *d, uint32_t id, const char *title, const char *app_id) {
    (void) d;
    printf("changed %u '%s' '%s'\n", id, title, app_id);
    fflush(stdout);
}
static void on_removed(void *d, uint32_t id) {
    (void) d;
    printf("removed %u\n", id);
    fflush(stdout);
}
static void on_clipboard(void *d, const char *text) {
    (void) d;
    printf("clipboard '%s'\n", text);
    fflush(stdout);
}
static void on_text_input(void *d, uint32_t id, bool enabled) {
    (void) d;
    printf("text-input %u %d\n", id, enabled);
    fflush(stdout);
}
static void on_log(void *d, const char *msg) {
    (void) d;
    fprintf(stderr, "pepe: %s\n", msg);
}

static bool target_lock(void *d, void *target, struct pepe_target_buffer *b) {
    (void) d;
    struct target *t = target;
    b->pixels = t->pixels;
    b->width = t->w;
    b->height = t->h;
    b->stride = t->w;
    return true;
}

static void target_post(void *d, void *target) {
    (void) d;
    struct target *t = target;
    t->frames++;
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    save_png(t); /* every frame, so the snapshot always shows the latest state */
    t->last_save = now;
}

static void target_release(void *d, void *target) {
    (void) d;
    struct target *t = target;
    save_png(t);
    free(t->pixels);
    free(t);
}

static const struct pepe_platform platform = {
    .window_added = on_added,
    .window_changed = on_changed,
    .window_removed = on_removed,
    .clipboard_changed = on_clipboard,
    .text_input_changed = on_text_input,
    .log = on_log,
    .target_lock = target_lock,
    .target_post = target_post,
    .target_release = target_release,
};

static void *command_thread(void *arg) {
    FILE *in = fopen(arg, "r");
    char line[4096];
    for (;;) {
        if (!in || !fgets(line, sizeof line, in)) {
            if (in) fclose(in);
            in = fopen(arg, "r"); /* FIFO: reopen after each writer */
            if (!in) return NULL;
            continue;
        }
        line[strcspn(line, "\n")] = 0;
        char cmd[32] = "";
        unsigned id = 0;
        double x = 0, y = 0;
        int a = 0, b = 0;
        sscanf(line, "%31s", cmd);
        struct pepe_cmd *c = NULL;
        if (!strcmp(cmd, "motion") && sscanf(line, "%*s %u %lf %lf", &id, &x, &y) == 3) {
            c = pepe_cmd_new(PEPE_CMD_POINTER_MOTION, id); c->d0 = x; c->d1 = y;
        } else if (!strcmp(cmd, "button") && sscanf(line, "%*s %u %d %d", &id, &a, &b) == 3) {
            c = pepe_cmd_new(PEPE_CMD_POINTER_BUTTON, id); c->i0 = a; c->i1 = b;
        } else if (!strcmp(cmd, "click") && sscanf(line, "%*s %u %lf %lf", &id, &x, &y) == 3) {
            c = pepe_cmd_new(PEPE_CMD_POINTER_MOTION, id); c->d0 = x; c->d1 = y; pepe_post(server, c);
            c = pepe_cmd_new(PEPE_CMD_POINTER_BUTTON, id); c->i0 = 0x110; c->i1 = 1; pepe_post(server, c);
            c = pepe_cmd_new(PEPE_CMD_POINTER_BUTTON, id); c->i0 = 0x110; c->i1 = 0;
        } else if (!strcmp(cmd, "key") && sscanf(line, "%*s %d %d", &a, &b) == 2) {
            c = pepe_cmd_new(PEPE_CMD_KEY, 0); c->i0 = a; c->i1 = b;
        } else if (!strcmp(cmd, "text")) {
            c = pepe_cmd_new(PEPE_CMD_TEXT, 0); c->text = strdup(line + 5);
        } else if (!strcmp(cmd, "clipboard")) {
            c = pepe_cmd_new(PEPE_CMD_CLIPBOARD, 0); c->text = strdup(line + 10);
        } else if (!strcmp(cmd, "touch")) {
            char kind[8];
            if (sscanf(line, "%*s %u %7s %d %lf %lf", &id, kind, &a, &x, &y) == 5) {
                c = pepe_cmd_new(PEPE_CMD_TOUCH, id);
                c->i0 = !strcmp(kind, "down") ? 0 : !strcmp(kind, "move") ? 1 : 2;
                c->i1 = a; c->d0 = x; c->d1 = y;
            }
        } else if (!strcmp(cmd, "resize") && sscanf(line, "%*s %u %d %d", &id, &a, &b) == 3) {
            c = pepe_cmd_new(PEPE_CMD_RESIZE, id); c->i0 = a; c->i1 = b; c->d0 = scale;
        } else if (!strcmp(cmd, "close") && sscanf(line, "%*s %u", &id) == 1) {
            c = pepe_cmd_new(PEPE_CMD_CLOSE, id);
        } else if (!strcmp(cmd, "kill") && sscanf(line, "%*s %u", &id) == 1) {
            c = pepe_cmd_new(PEPE_CMD_KILL, id);
        } else if (!strcmp(cmd, "quit")) {
            c = pepe_cmd_new(PEPE_CMD_STOP, 0);
        }
        if (c) pepe_post(server, c);
        else if (cmd[0]) fprintf(stderr, "host: unknown command: %s\n", line);
    }
}

int main(int argc, char **argv) {
    if (argc < 4) {
        fprintf(stderr, "usage: %s <socket> <outdir> <command-fifo> [scale] [WxH] [-- xwayland launch argv...]\n", argv[0]);
        return 2;
    }
    outdir = argv[2];
    if (argc > 4) scale = atof(argv[4]);
    if (argc > 5) sscanf(argv[5], "%dx%d", &width, &height);
    mkdir(outdir, 0755);
    server = pepe_server_create(argv[1], &platform, scale, width, height);
    if (!server) return 1;

    /* Optional: start Xwayland the way the app does, with every argument after "--". */
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--") != 0) continue;
        int wm_fd, wl_fd, ready_fd;
        pid_t pid = pepe_xwayland_spawn((const char *const *) argv + i + 1, NULL, &wm_fd, &wl_fd, &ready_fd);
        if (pid > 0) {
            struct pepe_cmd *c = pepe_cmd_new(PEPE_CMD_XWAYLAND, 0);
            c->i0 = wm_fd;
            c->i1 = wl_fd;
            c->i2 = ready_fd;
            pepe_post(server, c);
            fprintf(stderr, "host: Xwayland launcher pid %d\n", pid);
        }
        break;
    }

    pthread_t t;
    pthread_create(&t, NULL, command_thread, argv[3]);
    pepe_server_run(server);
    pepe_server_destroy(server);
    return 0;
}
