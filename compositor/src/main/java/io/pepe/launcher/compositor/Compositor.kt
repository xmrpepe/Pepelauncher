package io.pepe.launcher.compositor

import android.view.Surface

/**
 * JNI wrapper around the native compositor (src/main/cpp). One instance per process; all calls
 * are thread-safe (they post to the compositor thread). Coordinates are in pixels of the
 * Activity's SurfaceView; the compositor converts them with the scale it renders at.
 */
class Compositor private constructor(private val handle: Long) {

    /** Called on the compositor thread. */
    interface Listener {
        fun onWindowAdded(id: Int, parentId: Int, title: String, appId: String)
        fun onWindowChanged(id: Int, title: String, appId: String)
        fun onWindowRemoved(id: Int)
        fun onClipboard(text: String)
        fun onTextInput(id: Int, enabled: Boolean)
    }

    /** A window buffer handed to the compositor; pass it back to [resize]. */
    @JvmInline
    value class Target(val pointer: Long)

    fun attach(id: Int, surface: Surface, width: Int, height: Int, scale: Double): Target? =
        nativeAttach(handle, id, surface, width, height, scale).takeIf { it != 0L }?.let(::Target)

    fun resize(id: Int, target: Target, width: Int, height: Int, scale: Double) =
        nativeResize(handle, id, target.pointer, width, height, scale)

    /** Blocks until the compositor stopped drawing into the surface. */
    fun detach(id: Int) = nativeDetach(handle, id)

    fun focus(id: Int, focused: Boolean) = nativeFocus(handle, id, focused)
    fun close(id: Int) = nativeClose(handle, id)
    fun kill(id: Int) = nativeKill(handle, id)
    fun pointerMotion(id: Int, x: Float, y: Float) = nativePointerMotion(handle, id, x, y)
    fun pointerButton(id: Int, button: Int, pressed: Boolean) = nativePointerButton(handle, id, button, pressed)
    fun pointerAxis(id: Int, dx: Float, dy: Float) = nativePointerAxis(handle, id, dx, dy)
    fun pointerLeave() = nativePointerLeave(handle)
    fun touch(id: Int, type: Int, slot: Int, x: Float, y: Float) = nativeTouch(handle, id, type, slot, x, y)
    fun key(evdevCode: Int, pressed: Boolean) = nativeKey(handle, evdevCode, pressed)
    fun text(text: String) = nativeText(handle, text)
    fun preedit(text: String) = nativePreedit(handle, text)
    fun deleteSurrounding(before: Int, after: Int) = nativeDeleteSurrounding(handle, before, after)
    fun clipboard(text: String) = nativeClipboard(handle, text)
    fun settings(scale: Double, screenWidth: Int, screenHeight: Int, dialogsOwnTask: Boolean) =
        nativeSettings(handle, scale, screenWidth, screenHeight, dialogsOwnTask)

    /**
     * Spawns Xwayland with [argv] (a proot command line; "{WAYLAND_SOCKET}", "{WM_FD}" and
     * "{DISPLAY_FD}" are replaced by inherited descriptors) and connects the X window manager.
     */
    fun startXwayland(argv: Array<String>, env: Array<String>): Int = nativeStartXwayland(handle, argv, env)

    fun stop() = nativeStop(handle)

    companion object {
        const val TOUCH_DOWN = 0
        const val TOUCH_MOTION = 1
        const val TOUCH_UP = 2
        const val TOUCH_CANCEL = 3
        const val BTN_LEFT = 0x110
        const val BTN_RIGHT = 0x111
        const val BTN_MIDDLE = 0x112

        init {
            System.loadLibrary("pepecompositor")
        }

        fun start(socketPath: String, scale: Double, screenWidth: Int, screenHeight: Int, listener: Listener): Compositor? =
            nativeStart(socketPath, scale, screenWidth, screenHeight, listener).takeIf { it != 0L }?.let(::Compositor)

        @JvmStatic private external fun nativeStart(socket: String, scale: Double, w: Int, h: Int, listener: Listener): Long
        @JvmStatic private external fun nativeStop(handle: Long)
        @JvmStatic private external fun nativeAttach(handle: Long, id: Int, surface: Surface, w: Int, h: Int, scale: Double): Long
        @JvmStatic private external fun nativeResize(handle: Long, id: Int, target: Long, w: Int, h: Int, scale: Double)
        @JvmStatic private external fun nativeDetach(handle: Long, id: Int)
        @JvmStatic private external fun nativeFocus(handle: Long, id: Int, focused: Boolean)
        @JvmStatic private external fun nativeClose(handle: Long, id: Int)
        @JvmStatic private external fun nativeKill(handle: Long, id: Int)
        @JvmStatic private external fun nativePointerMotion(handle: Long, id: Int, x: Float, y: Float)
        @JvmStatic private external fun nativePointerButton(handle: Long, id: Int, button: Int, pressed: Boolean)
        @JvmStatic private external fun nativePointerAxis(handle: Long, id: Int, dx: Float, dy: Float)
        @JvmStatic private external fun nativePointerLeave(handle: Long)
        @JvmStatic private external fun nativeTouch(handle: Long, id: Int, type: Int, slot: Int, x: Float, y: Float)
        @JvmStatic private external fun nativeKey(handle: Long, code: Int, pressed: Boolean)
        @JvmStatic private external fun nativeText(handle: Long, text: String)
        @JvmStatic private external fun nativePreedit(handle: Long, text: String)
        @JvmStatic private external fun nativeDeleteSurrounding(handle: Long, before: Int, after: Int)
        @JvmStatic private external fun nativeClipboard(handle: Long, text: String)
        @JvmStatic private external fun nativeSettings(handle: Long, scale: Double, w: Int, h: Int, dialogsOwnTask: Boolean)
        @JvmStatic private external fun nativeStartXwayland(handle: Long, argv: Array<String>, env: Array<String>): Int
    }
}
