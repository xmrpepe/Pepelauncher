package io.pepe.launcher.linux.display

import android.app.Activity
import android.app.ActivityManager
import android.app.PictureInPictureParams
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import io.pepe.launcher.linux.LinuxSettings
import io.pepe.launcher.linux.R
import io.pepe.launcher.linux.apps.LinuxApps

/**
 * One Linux window = one Activity in its own task (documentLaunchMode="always"), so every window
 * has its own Recents card. Resizing (split screen, freeform, PiP) resizes the toplevel.
 *
 * Closing: when the client closes the window, [onClientClosed] finishes and removes the task;
 * when the user swipes the card away (the Activity finishes without the client asking), the
 * client gets xdg_toplevel.close / WM_DELETE_WINDOW and is killed after a timeout.
 */
class LinuxWindowActivity : Activity() {

    private var windowId = 0
    private lateinit var view: LinuxWindowView
    private var closedByClient = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        windowId = intent?.data?.host?.toIntOrNull() ?: 0
        val info = LinuxDisplay.window(windowId)
        if (info == null || LinuxDisplay.compositor == null) {
            // The window is gone (e.g. the Linux process was restarted): drop the stale task.
            finishAndRemoveTask()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val settings = LinuxSettings(this)
        view = LinuxWindowView(this, windowId).apply { touchAsMouse = settings.touchAsMouse }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(view, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(menuButton(), FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP or Gravity.END))
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(root)

        LinuxDisplay.register(windowId, this)
        updateTaskDescription(info)
        view.requestFocus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!::view.isInitialized) return
        LinuxDisplay.compositor?.focus(windowId, hasFocus)
        if (hasFocus) LinuxDisplay.pushAndroidClipboard(this)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::view.isInitialized && view.handleKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        if (::view.isInitialized) {
            LinuxDisplay.unregister(windowId, this)
            // Finishing without the client having closed the window: the user swiped the task away.
            if (isFinishing && !closedByClient && !isChangingConfigurations) LinuxDisplay.userClosed(windowId)
        }
        super.onDestroy()
    }

    /** The client destroyed the toplevel. */
    fun onClientClosed() {
        closedByClient = true
        finishAndRemoveTask()
    }

    fun updateTaskDescription(info: LinuxDisplay.WindowInfo) {
        val label = info.title.ifBlank { info.appId.ifBlank { getString(R.string.pepe_window_untitled) } }
        title = label
        val entry = LinuxApps.findForAppId(this, info.appId)
        val icon = entry?.let { LinuxApps.iconBitmap(this, it.id) } ?: LinuxApps.letterTile(entry?.name ?: label)
        @Suppress("DEPRECATION")
        setTaskDescription(ActivityManager.TaskDescription(label, icon))
    }

    fun setKeyboardVisible(visible: Boolean) {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        if (visible) {
            view.requestFocus()
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        } else {
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    // ---- the small menu in the corner: keyboard, touch mode, PiP, close ----

    private fun menuButton(): View = TextView(this).apply {
        text = "⋮"
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        alpha = 0.45f
        setBackgroundColor(0x66000000)
        contentDescription = getString(R.string.pepe_terminal_more)
        setOnClickListener { showMenu(it) }
    }

    private fun showMenu(anchor: View) {
        val settings = LinuxSettings(this)
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, R.string.pepe_terminal_keyboard)
        menu.menu.add(0, 2, 1, if (view.touchAsMouse) R.string.pepe_window_touch_mode else R.string.pepe_window_mouse_mode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) menu.menu.add(0, 3, 2, R.string.pepe_window_pip)
        menu.menu.add(0, 4, 3, R.string.pepe_window_close)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> setKeyboardVisible(true)
                2 -> {
                    view.touchAsMouse = !view.touchAsMouse
                    settings.touchAsMouse = view.touchAsMouse
                }

                3 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    enterPictureInPictureMode(PictureInPictureParams.Builder().build())
                }

                4 -> LinuxDisplay.compositor?.close(windowId)
            }
            true
        }
        menu.show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
