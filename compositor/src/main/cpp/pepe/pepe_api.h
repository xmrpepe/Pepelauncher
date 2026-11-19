/*
 * pepelauncher compositor: the interface between the platform-independent compositor and a
 * backend (android.c for the app, host/main.c for desktop tests).
 */
#pragma once

#include <stdbool.h>
#include <stdint.h>

struct pepe_server;

/* A locked window buffer: RGBA bytes in memory (Android's WINDOW_FORMAT_RGBA_8888). */
struct pepe_target_buffer {
    uint32_t *pixels;
    int32_t width, height;
    int32_t stride; /* in pixels */
};

/* Upcalls from the compositor thread to the backend. */
struct pepe_platform {
    void *data;
    /* A toplevel was mapped: show it (Android: start an Activity in its own task). */
    void (*window_added)(void *data, uint32_t id, uint32_t parent_id, const char *title, const char *app_id);
    void (*window_changed)(void *data, uint32_t id, const char *title, const char *app_id);
    /* The client destroyed the toplevel: close its task. */
    void (*window_removed)(void *data, uint32_t id);
    /* A client set the clipboard selection to text. */
    void (*clipboard_changed)(void *data, const char *text);
    /* The focused client enabled or disabled text input (show/hide the soft keyboard). */
    void (*text_input_changed)(void *data, uint32_t id, bool enabled);
    void (*log)(void *data, const char *message);

    bool (*target_lock)(void *data, void *target, struct pepe_target_buffer *buffer);
    void (*target_post)(void *data, void *target);
    void (*target_release)(void *data, void *target);
};

enum pepe_cmd_type {
    PEPE_CMD_ATTACH,        /* window, ptr = target, i0/i1 = size px, d0 = scale */
    PEPE_CMD_DETACH,        /* window (synchronous: the target is released when it returns) */
    PEPE_CMD_RESIZE,        /* window, i0/i1 = size px, d0 = scale */
    PEPE_CMD_FOCUS,         /* window, i0 = focused */
    PEPE_CMD_CLOSE,         /* window: polite close request (xdg_toplevel.close / WM_DELETE_WINDOW) */
    PEPE_CMD_KILL,          /* window: kill the client (or the X client) */
    PEPE_CMD_POINTER_MOTION,/* window, d0/d1 = position in pixels of the Android view */
    PEPE_CMD_POINTER_BUTTON,/* window, i0 = linux button code, i1 = pressed */
    PEPE_CMD_POINTER_AXIS,  /* window, d0/d1 = scroll delta in pixels */
    PEPE_CMD_POINTER_LEAVE,
    PEPE_CMD_TOUCH,         /* window, i0 = 0 down / 1 motion / 2 up / 3 cancel, i1 = slot, d0/d1 = position px */
    PEPE_CMD_KEY,           /* i0 = evdev key code, i1 = pressed */
    PEPE_CMD_TEXT,          /* text = UTF-8 committed by the input method */
    PEPE_CMD_PREEDIT,       /* text = UTF-8 composing text ("" clears) */
    PEPE_CMD_DELETE_SURROUNDING, /* i0 = before, i1 = after (bytes) */
    PEPE_CMD_CLIPBOARD,     /* text = Android clipboard text offered to clients */
    PEPE_CMD_SETTINGS,      /* d0 = default scale, i0 = screen width px, i1 = screen height px, i2 = dialogs own task */
    PEPE_CMD_XWAYLAND,      /* i0 = wm fd, i1 = wayland fd, i2 = readiness fd of a started Xwayland */
    PEPE_CMD_STOP,
};

struct pepe_cmd {
    enum pepe_cmd_type type;
    uint32_t window;
    int32_t i0, i1, i2;
    double d0, d1;
    char *text;        /* owned, freed after processing */
    void *ptr;
    struct pepe_cmd *next;
};

/* Creates the server and its listening socket. scale = pixels per logical unit. */
struct pepe_server *pepe_server_create(const char *socket_path, const struct pepe_platform *platform,
                                       double scale, int32_t screen_w_px, int32_t screen_h_px);
/* Runs the event loop on the calling thread until PEPE_CMD_STOP. */
void pepe_server_run(struct pepe_server *server);
void pepe_server_destroy(struct pepe_server *server);
/* Thread-safe. Takes ownership of a heap-allocated cmd (malloc) and its text. */
void pepe_post(struct pepe_server *server, struct pepe_cmd *cmd);
/* Thread-safe. Posts and waits until the compositor thread processed the command. */
void pepe_post_sync(struct pepe_server *server, struct pepe_cmd *cmd);
/* Convenience: allocate a command. */
struct pepe_cmd *pepe_cmd_new(enum pepe_cmd_type type, uint32_t window);
