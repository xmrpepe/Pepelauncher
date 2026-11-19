package io.pepe.launcher.linux.display

import android.content.Context
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import io.pepe.launcher.compositor.Compositor
import kotlin.math.hypot

/**
 * Shows one Linux window and forwards input to it. Pixel coordinates are sent as-is; the
 * compositor converts them with the scale it renders at.
 */
class LinuxWindowView(context: Context, private val windowId: Int) : SurfaceView(context), SurfaceHolder.Callback {

    var touchAsMouse = true
    private var target: Compositor.Target? = null
    private val compositor get() = LinuxDisplay.compositor

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // ---- surface ----

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val c = compositor ?: return
        val scale = LinuxDisplay.scale(context)
        val t = target
        if (t == null) target = c.attach(windowId, holder.surface, width, height, scale)
        else c.resize(windowId, t, width, height, scale)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (target != null) compositor?.detach(windowId)
        target = null
    }

    // ---- touch: as a mouse (tap = click, long-press = right click, two fingers = scroll) or as touch ----

    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var leftPressed = false
    private var moved = false
    private var scrolling = false
    private var lastScrollY = 0f
    private var lastScrollX = 0f
    private val longPress = Runnable {
        if (!moved && !leftPressed && !scrolling) {
            compositor?.pointerButton(windowId, Compositor.BTN_RIGHT, true)
            compositor?.pointerButton(windowId, Compositor.BTN_RIGHT, false)
            longPressFired = true
        }
    }
    private var longPressFired = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val c = compositor ?: return false
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return mouseEvent(c, event)
        if (!touchAsMouse) return touchEvent(c, event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                downTime = SystemClock.uptimeMillis()
                downX = event.x
                downY = event.y
                moved = false
                scrolling = false
                longPressFired = false
                c.pointerMotion(windowId, event.x, event.y)
                postDelayed(longPress, LONG_PRESS_MS)
            }

            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2) {
                removeCallbacks(longPress)
                if (leftPressed) {
                    c.pointerButton(windowId, Compositor.BTN_LEFT, false)
                    leftPressed = false
                }
                scrolling = true
                lastScrollX = (event.getX(0) + event.getX(1)) / 2
                lastScrollY = (event.getY(0) + event.getY(1)) / 2
            }

            MotionEvent.ACTION_MOVE -> {
                if (scrolling && event.pointerCount >= 2) {
                    val x = (event.getX(0) + event.getX(1)) / 2
                    val y = (event.getY(0) + event.getY(1)) / 2
                    c.pointerAxis(windowId, lastScrollX - x, lastScrollY - y)
                    lastScrollX = x
                    lastScrollY = y
                    return true
                }
                if (!moved && hypot(event.x - downX, event.y - downY) > touchSlop) {
                    moved = true
                    removeCallbacks(longPress)
                    if (!longPressFired && !scrolling) {
                        c.pointerButton(windowId, Compositor.BTN_LEFT, true) // drag (select, move sliders…)
                        leftPressed = true
                    }
                }
                c.pointerMotion(windowId, event.x, event.y)
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                c.pointerMotion(windowId, event.x, event.y)
                when {
                    leftPressed -> c.pointerButton(windowId, Compositor.BTN_LEFT, false)
                    !moved && !longPressFired && !scrolling -> {
                        c.pointerButton(windowId, Compositor.BTN_LEFT, true)
                        c.pointerButton(windowId, Compositor.BTN_LEFT, false)
                    }
                }
                leftPressed = false
                scrolling = false
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (leftPressed) c.pointerButton(windowId, Compositor.BTN_LEFT, false)
                leftPressed = false
                scrolling = false
            }
        }
        return true
    }

    private fun touchEvent(c: Compositor, event: MotionEvent): Boolean {
        val index = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                requestFocus()
                c.touch(windowId, Compositor.TOUCH_DOWN, event.getPointerId(index), event.getX(index), event.getY(index))
            }

            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) {
                c.touch(windowId, Compositor.TOUCH_MOTION, event.getPointerId(i), event.getX(i), event.getY(i))
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                c.touch(windowId, Compositor.TOUCH_UP, event.getPointerId(index), event.getX(index), event.getY(index))

            MotionEvent.ACTION_CANCEL -> for (i in 0 until event.pointerCount) {
                c.touch(windowId, Compositor.TOUCH_CANCEL, event.getPointerId(i), 0f, 0f)
            }
        }
        return true
    }

    // ---- mouse ----

    private var mouseButtons = 0

    private fun mouseEvent(c: Compositor, event: MotionEvent): Boolean {
        c.pointerMotion(windowId, event.x, event.y)
        val buttons = event.buttonState
        listOf(
            MotionEvent.BUTTON_PRIMARY to Compositor.BTN_LEFT,
            MotionEvent.BUTTON_SECONDARY to Compositor.BTN_RIGHT,
            MotionEvent.BUTTON_TERTIARY to Compositor.BTN_MIDDLE,
        ).forEach { (mask, button) ->
            val now = buttons and mask != 0
            val before = mouseButtons and mask != 0
            if (now != before) c.pointerButton(windowId, button, now)
        }
        mouseButtons = buttons
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val c = compositor ?: return false
        if (!event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) return super.onGenericMotionEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> c.pointerMotion(windowId, event.x, event.y)
            MotionEvent.ACTION_HOVER_EXIT -> c.pointerLeave()
            MotionEvent.ACTION_SCROLL -> {
                val step = SCROLL_STEP_DP * resources.displayMetrics.density
                c.pointerAxis(
                    windowId,
                    -event.getAxisValue(MotionEvent.AXIS_HSCROLL) * step,
                    -event.getAxisValue(MotionEvent.AXIS_VSCROLL) * step,
                )
            }

            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> return mouseEvent(c, event)
            else -> return super.onGenericMotionEvent(event)
        }
        return true
    }

    // ---- keyboard and input method ----

    fun handleKey(event: KeyEvent): Boolean {
        val c = compositor ?: return false
        // Back from the navigation bar stays Android's; Back on a hardware keyboard is Escape.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && !event.isFromSource(InputDevice.SOURCE_KEYBOARD)) return false
        val code = KeyMapping.toEvdev(event.keyCode)
        if (code < 0) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) c.key(code, true)
            KeyEvent.ACTION_UP -> c.key(code, false)
        }
        return true // clients do their own key repeat (wl_keyboard.repeat_info)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                compositor?.text(text.toString())
                return true
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                compositor?.preedit(text.toString())
                composing = text.toString()
                return true
            }

            override fun finishComposingText(): Boolean {
                if (composing.isNotEmpty()) compositor?.text(composing)
                composing = ""
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                compositor?.deleteSurrounding(beforeLength, afterLength)
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean = handleKey(event)

            private var composing = ""
        }
    }

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    companion object {
        private const val LONG_PRESS_MS = 500L
        private const val SCROLL_STEP_DP = 48f
    }
}
