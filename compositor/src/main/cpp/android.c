/*
 * pepelauncher compositor: Android backend (JNI) for io.pepe.launcher.compositor.Compositor.
 *
 * The compositor runs on its own thread. Window targets are ANativeWindows obtained from each
 * LinuxWindowActivity's SurfaceView; upcalls go to a Kotlin Compositor.Listener.
 */
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

#include "pepe_api.h"
#include "xwayland_launch.h"

#define TAG "pepe-compositor"

struct android_target {
    ANativeWindow *window;
    atomic_int width, height;
    int configured_w, configured_h;
};

struct instance {
    struct pepe_server *server;
    struct pepe_platform platform;
    pthread_t thread;
    JavaVM *vm;
    JNIEnv *env;             /* compositor thread's env */
    jobject listener;        /* global ref */
    jmethodID on_added, on_changed, on_removed, on_clipboard, on_text_input;
};

/* ---- upcalls (compositor thread) ---- */

static jstring jstr(JNIEnv *env, const char *s) {
    return (*env)->NewStringUTF(env, s ? s : "");
}

static void call_void(struct instance *in, jmethodID m, ...) {
    va_list ap;
    va_start(ap, m);
    (*in->env)->CallVoidMethodV(in->env, in->listener, m, ap);
    va_end(ap);
    if ((*in->env)->ExceptionCheck(in->env)) {
        (*in->env)->ExceptionDescribe(in->env);
        (*in->env)->ExceptionClear(in->env);
    }
}

static void up_added(void *d, uint32_t id, uint32_t parent, const char *title, const char *app_id) {
    struct instance *in = d;
    jstring t = jstr(in->env, title), a = jstr(in->env, app_id);
    call_void(in, in->on_added, (jint) id, (jint) parent, t, a);
    (*in->env)->DeleteLocalRef(in->env, t);
    (*in->env)->DeleteLocalRef(in->env, a);
}

static void up_changed(void *d, uint32_t id, const char *title, const char *app_id) {
    struct instance *in = d;
    jstring t = jstr(in->env, title), a = jstr(in->env, app_id);
    call_void(in, in->on_changed, (jint) id, t, a);
    (*in->env)->DeleteLocalRef(in->env, t);
    (*in->env)->DeleteLocalRef(in->env, a);
}

static void up_removed(void *d, uint32_t id) {
    struct instance *in = d;
    call_void(in, in->on_removed, (jint) id);
}

static void up_clipboard(void *d, const char *text) {
    struct instance *in = d;
    jstring t = jstr(in->env, text);
    call_void(in, in->on_clipboard, t);
    (*in->env)->DeleteLocalRef(in->env, t);
}

static void up_text_input(void *d, uint32_t id, bool enabled) {
    struct instance *in = d;
    call_void(in, in->on_text_input, (jint) id, (jboolean) enabled);
}

static void up_log(void *d, const char *msg) {
    (void) d;
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", msg);
}

/* ---- targets ---- */

static bool target_lock(void *d, void *target, struct pepe_target_buffer *b) {
    (void) d;
    struct android_target *t = target;
    int w = atomic_load(&t->width), h = atomic_load(&t->height);
    if (w <= 0 || h <= 0) return false;
    if (w != t->configured_w || h != t->configured_h) {
        ANativeWindow_setBuffersGeometry(t->window, w, h, WINDOW_FORMAT_RGBA_8888);
        t->configured_w = w;
        t->configured_h = h;
    }
    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(t->window, &buf, NULL) != 0) return false;
    b->pixels = buf.bits;
    b->width = buf.width;
    b->height = buf.height;
    b->stride = buf.stride;
    return true;
}

static void target_post(void *d, void *target) {
    (void) d;
    ANativeWindow_unlockAndPost(((struct android_target *) target)->window);
}

static void target_release(void *d, void *target) {
    (void) d;
    struct android_target *t = target;
    ANativeWindow_release(t->window);
    free(t);
}

/* ---- thread ---- */

static void *run(void *arg) {
    struct instance *in = arg;
    JavaVMAttachArgs args = { JNI_VERSION_1_6, "pepe-compositor", NULL };
    (*in->vm)->AttachCurrentThread(in->vm, &in->env, &args);
    pepe_server_run(in->server);
    pepe_server_destroy(in->server);
    in->server = NULL;
    (*in->env)->DeleteGlobalRef(in->env, in->listener);
    (*in->vm)->DetachCurrentThread(in->vm);
    return NULL;
}

/* ---- JNI ---- */

#define JNI(name) Java_io_pepe_launcher_compositor_Compositor_##name

JNIEXPORT jlong JNICALL JNI(nativeStart)(JNIEnv *env, jclass cls, jstring socket, jdouble scale,
                                         jint w, jint h, jobject listener) {
    (void) cls;
    struct instance *in = calloc(1, sizeof *in);
    (*env)->GetJavaVM(env, &in->vm);
    in->listener = (*env)->NewGlobalRef(env, listener);
    jclass lc = (*env)->GetObjectClass(env, listener);
    in->on_added = (*env)->GetMethodID(env, lc, "onWindowAdded", "(IILjava/lang/String;Ljava/lang/String;)V");
    in->on_changed = (*env)->GetMethodID(env, lc, "onWindowChanged", "(ILjava/lang/String;Ljava/lang/String;)V");
    in->on_removed = (*env)->GetMethodID(env, lc, "onWindowRemoved", "(I)V");
    in->on_clipboard = (*env)->GetMethodID(env, lc, "onClipboard", "(Ljava/lang/String;)V");
    in->on_text_input = (*env)->GetMethodID(env, lc, "onTextInput", "(IZ)V");
    in->platform = (struct pepe_platform) {
        .data = in,
        .window_added = up_added,
        .window_changed = up_changed,
        .window_removed = up_removed,
        .clipboard_changed = up_clipboard,
        .text_input_changed = up_text_input,
        .log = up_log,
        .target_lock = target_lock,
        .target_post = target_post,
        .target_release = target_release,
    };
    const char *path = (*env)->GetStringUTFChars(env, socket, NULL);
    in->server = pepe_server_create(path, &in->platform, scale, w, h);
    (*env)->ReleaseStringUTFChars(env, socket, path);
    if (!in->server) {
        (*env)->DeleteGlobalRef(env, in->listener);
        free(in);
        return 0;
    }
    pthread_create(&in->thread, NULL, run, in);
    return (jlong) (intptr_t) in;
}

static struct pepe_server *server_of(jlong handle) {
    struct instance *in = (struct instance *) (intptr_t) handle;
    return in ? in->server : NULL;
}

static void post(jlong handle, struct pepe_cmd *c) {
    struct pepe_server *s = server_of(handle);
    if (s) pepe_post(s, c);
    else { free(c->text); free(c); }
}

JNIEXPORT void JNICALL JNI(nativeStop)(JNIEnv *env, jclass cls, jlong handle) {
    (void) env; (void) cls;
    struct instance *in = (struct instance *) (intptr_t) handle;
    if (!in || !in->server) return;
    pepe_post(in->server, pepe_cmd_new(PEPE_CMD_STOP, 0));
    pthread_join(in->thread, NULL);
    free(in);
}

JNIEXPORT jlong JNICALL JNI(nativeAttach)(JNIEnv *env, jclass cls, jlong handle, jint id, jobject surface,
                                          jint w, jint h, jdouble scale) {
    (void) cls;
    struct android_target *t = calloc(1, sizeof *t);
    t->window = ANativeWindow_fromSurface(env, surface);
    if (!t->window) { free(t); return 0; }
    atomic_store(&t->width, w);
    atomic_store(&t->height, h);
    struct pepe_cmd *c = pepe_cmd_new(PEPE_CMD_ATTACH, (uint32_t) id);
    c->ptr = t;
    c->i0 = w;
    c->i1 = h;
    c->d0 = scale;
    post(handle, c);
    return (jlong) (intptr_t) t;
}

JNIEXPORT void JNICALL JNI(nativeResize)(JNIEnv *env, jclass cls, jlong handle, jint id, jlong target,
                                         jint w, jint h, jdouble scale) {
    (void) env; (void) cls;
    struct android_target *t = (struct android_target *) (intptr_t) target;
    if (t) { atomic_store(&t->width, w); atomic_store(&t->height, h); }
    struct pepe_cmd *c = pepe_cmd_new(PEPE_CMD_RESIZE, (uint32_t) id);
    c->i0 = w;
    c->i1 = h;
    c->d0 = scale;
    post(handle, c);
}

/* Synchronous: SurfaceHolder.Callback.surfaceDestroyed must not return while we still draw. */
JNIEXPORT void JNICALL JNI(nativeDetach)(JNIEnv *env, jclass cls, jlong handle, jint id) {
    (void) env; (void) cls;
    struct pepe_server *s = server_of(handle);
    if (s) pepe_post_sync(s, pepe_cmd_new(PEPE_CMD_DETACH, (uint32_t) id));
}

static void simple(jlong handle, enum pepe_cmd_type type, jint id, jint i0, jint i1, jdouble d0, jdouble d1) {
    struct pepe_cmd *c = pepe_cmd_new(type, (uint32_t) id);
    c->i0 = i0;
    c->i1 = i1;
    c->d0 = d0;
    c->d1 = d1;
    post(handle, c);
}

JNIEXPORT void JNICALL JNI(nativeFocus)(JNIEnv *e, jclass c, jlong h, jint id, jboolean f) {
    (void) e; (void) c; simple(h, PEPE_CMD_FOCUS, id, f, 0, 0, 0);
}
JNIEXPORT void JNICALL JNI(nativeClose)(JNIEnv *e, jclass c, jlong h, jint id) {
    (void) e; (void) c; simple(h, PEPE_CMD_CLOSE, id, 0, 0, 0, 0);
}
JNIEXPORT void JNICALL JNI(nativeKill)(JNIEnv *e, jclass c, jlong h, jint id) {
    (void) e; (void) c; simple(h, PEPE_CMD_KILL, id, 0, 0, 0, 0);
}
JNIEXPORT void JNICALL JNI(nativePointerMotion)(JNIEnv *e, jclass c, jlong h, jint id, jfloat x, jfloat y) {
    (void) e; (void) c; simple(h, PEPE_CMD_POINTER_MOTION, id, 0, 0, x, y);
}
JNIEXPORT void JNICALL JNI(nativePointerButton)(JNIEnv *e, jclass c, jlong h, jint id, jint button, jboolean pressed) {
    (void) e; (void) c; simple(h, PEPE_CMD_POINTER_BUTTON, id, button, pressed, 0, 0);
}
JNIEXPORT void JNICALL JNI(nativePointerAxis)(JNIEnv *e, jclass c, jlong h, jint id, jfloat dx, jfloat dy) {
    (void) e; (void) c; simple(h, PEPE_CMD_POINTER_AXIS, id, 0, 0, dx, dy);
}
JNIEXPORT void JNICALL JNI(nativePointerLeave)(JNIEnv *e, jclass c, jlong h) {
    (void) e; (void) c; simple(h, PEPE_CMD_POINTER_LEAVE, 0, 0, 0, 0, 0);
}
JNIEXPORT void JNICALL JNI(nativeTouch)(JNIEnv *e, jclass c, jlong h, jint id, jint type, jint slot, jfloat x, jfloat y) {
    (void) e; (void) c; simple(h, PEPE_CMD_TOUCH, id, type, slot, x, y);
}
JNIEXPORT void JNICALL JNI(nativeKey)(JNIEnv *e, jclass c, jlong h, jint code, jboolean pressed) {
    (void) e; (void) c; simple(h, PEPE_CMD_KEY, 0, code, pressed, 0, 0);
}
JNIEXPORT void JNICALL JNI(nativeDeleteSurrounding)(JNIEnv *e, jclass c, jlong h, jint before, jint after) {
    (void) e; (void) c; simple(h, PEPE_CMD_DELETE_SURROUNDING, 0, before, after, 0, 0);
}

static void text_cmd(JNIEnv *env, jlong handle, enum pepe_cmd_type type, jstring text) {
    struct pepe_cmd *c = pepe_cmd_new(type, 0);
    const char *s = (*env)->GetStringUTFChars(env, text, NULL);
    c->text = strdup(s);
    (*env)->ReleaseStringUTFChars(env, text, s);
    post(handle, c);
}

JNIEXPORT void JNICALL JNI(nativeText)(JNIEnv *e, jclass c, jlong h, jstring t) { (void) c; text_cmd(e, h, PEPE_CMD_TEXT, t); }
JNIEXPORT void JNICALL JNI(nativePreedit)(JNIEnv *e, jclass c, jlong h, jstring t) { (void) c; text_cmd(e, h, PEPE_CMD_PREEDIT, t); }
JNIEXPORT void JNICALL JNI(nativeClipboard)(JNIEnv *e, jclass c, jlong h, jstring t) { (void) c; text_cmd(e, h, PEPE_CMD_CLIPBOARD, t); }

JNIEXPORT void JNICALL JNI(nativeSettings)(JNIEnv *e, jclass c, jlong h, jdouble scale, jint w, jint hh, jboolean dialogs) {
    (void) e; (void) c;
    struct pepe_cmd *cmd = pepe_cmd_new(PEPE_CMD_SETTINGS, 0);
    cmd->d0 = scale;
    cmd->i0 = w;
    cmd->i1 = hh;
    cmd->i2 = dialogs;
    post(h, cmd);
}

static const char **strings(JNIEnv *env, jobjectArray arr) {
    if (!arr) return NULL;
    jsize n = (*env)->GetArrayLength(env, arr);
    const char **out = calloc((size_t) n + 1, sizeof *out);
    for (jsize i = 0; i < n; i++) {
        jstring s = (*env)->GetObjectArrayElement(env, arr, i);
        const char *utf = (*env)->GetStringUTFChars(env, s, NULL);
        out[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, s, utf);
        (*env)->DeleteLocalRef(env, s);
    }
    return out;
}

static void free_strings(const char **v) {
    if (!v) return;
    for (const char **p = v; *p; p++) free((void *) *p);
    free(v);
}

/* Starts Xwayland (argv = the proot command) and hands its connections to the compositor. */
JNIEXPORT jint JNICALL JNI(nativeStartXwayland)(JNIEnv *env, jclass cls, jlong handle, jobjectArray argv, jobjectArray envp) {
    (void) cls;
    struct pepe_server *s = server_of(handle);
    if (!s) return -1;
    const char **a = strings(env, argv), **e = strings(env, envp);
    int wm, wl, ready;
    pid_t pid = pepe_xwayland_spawn(a, e, &wm, &wl, &ready);
    free_strings(a);
    free_strings(e);
    if (pid <= 0) return -1;
    struct pepe_cmd *c = pepe_cmd_new(PEPE_CMD_XWAYLAND, 0);
    c->i0 = wm;
    c->i1 = wl;
    c->i2 = ready;
    pepe_post(s, c);
    return pid;
}
