package io.pepe.launcher.linux.terminal

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import io.pepe.launcher.linux.LinuxSettings
import io.pepe.launcher.linux.R
import io.pepe.launcher.linux.service.LinuxService
import io.pepe.launcher.linux.ui.PhantomProcessGuide
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Full-screen Linux terminal, opened by swiping right on the home screen. Sessions belong to
 * [LinuxService], so they keep running when this screen is closed (swipe left or back).
 */
class TerminalActivity : ComponentActivity() {

    private lateinit var terminalView: TerminalView
    private lateinit var extraKeys: ExtraKeysView
    private lateinit var tabs: LinearLayout
    private lateinit var settings: LinuxSettings

    private var service: LinuxService? = null
    private var current: TerminalSession? = null
    private var fontSizePx = 0
    private var minFontPx = 0
    private var maxFontPx = 0
    private var scaleAccumulator = 1f

    private val sessionClient = object : BaseSessionClient() {
        override fun onTextChanged(changedSession: TerminalSession) {
            if (changedSession == current) terminalView.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) = renderTabs()

        override fun onSessionFinished(finishedSession: TerminalSession) {
            service?.onSessionFinished(finishedSession)
            val remaining = service?.sessions.orEmpty()
            if (remaining.isEmpty()) {
                finishToHome()
            } else if (finishedSession == current) {
                attach(remaining.last())
            } else {
                renderTabs()
            }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            if (text == null) return
            clipboard().setPrimaryClip(ClipData.newPlainText("terminal", text))
            Toast.makeText(this@TerminalActivity, R.string.pepe_terminal_copied, Toast.LENGTH_SHORT).show()
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) = paste()

        override fun onColorsChanged(session: TerminalSession) {
            if (session == current) terminalView.invalidate()
        }
    }

    private val viewClient = object : BaseViewClient() {
        override fun onScale(scale: Float): Float {
            scaleAccumulator *= scale
            if (scaleAccumulator !in 0.9f..1.1f) {
                val step = if (scaleAccumulator > 1f) 2 else -2
                setFontSize(fontSizePx + step, persist = true)
                scaleAccumulator = 1f
            }
            return 1f
        }

        override fun onSingleTapUp(e: MotionEvent) = showKeyboard()

        override fun readControlKey() = extraKeys.readModifier(ExtraKeysView.ModifierType.CTRL)

        override fun readAltKey() = extraKeys.readModifier(ExtraKeysView.ModifierType.ALT)

        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
            if (keyCode == KeyEvent.KEYCODE_ENTER && !session.isRunning) {
                service?.removeSession(session)
                return true
            }
            return false
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as LinuxService.LocalBinder).service
            service = svc
            svc.sessions.forEach { it.updateTerminalSessionClient(sessionClient) }
            val session = svc.sessions.lastOrNull() ?: newSession() ?: return
            attach(session)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = LinuxSettings(this)
        setContentView(R.layout.pepe_terminal)
        terminalView = findViewById(R.id.terminal_view)
        extraKeys = findViewById(R.id.extra_keys)
        tabs = findViewById(R.id.tabs)

        setupFullscreen()
        setupFont()
        terminalView.setTerminalViewClient(viewClient)
        terminalView.setTypeface(Typeface.MONOSPACE)
        terminalView.keepScreenOn = false

        extraKeys.listener = object : ExtraKeysView.Listener {
            override fun onKeyCode(keyCode: Int, ctrl: Boolean, alt: Boolean) {
                var mod = 0
                if (ctrl) mod = mod or KeyHandler.KEYMOD_CTRL
                if (alt) mod = mod or KeyHandler.KEYMOD_ALT
                terminalView.handleKeyCode(keyCode, mod)
            }

            override fun onText(text: String, ctrl: Boolean, alt: Boolean) {
                text.codePoints().forEach {
                    terminalView.inputCodePoint(TerminalView.KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD, it, ctrl, alt)
                }
            }
        }

        findViewById<View>(R.id.new_tab).setOnClickListener { newSession()?.let(::attach) }
        findViewById<View>(R.id.more).setOnClickListener(::showMenu)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (terminalView.isSelectingText) terminalView.stopTextSelectionMode() else finishToHome()
            }
        })

        LinuxService.start(this, LinuxService.ACTION_KEEP_ALIVE)
        bindService(Intent(this, LinuxService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        terminalView.requestFocus()
        if (settings.autoShowKeyboard) terminalView.post { showKeyboard() }
    }

    override fun onDestroy() {
        service?.detachClients()
        runCatching { unbindService(connection) }
        service = null
        super.onDestroy()
    }

    // ---- sessions & tabs ----

    private fun newSession(): TerminalSession? = runCatching {
        service?.createSession(sessionClient)
    }.onFailure {
        Toast.makeText(this, getString(R.string.pepe_terminal_session_failed, it.message), Toast.LENGTH_LONG).show()
    }.getOrNull()

    private fun attach(session: TerminalSession) {
        current = session
        terminalView.attachSession(session)
        terminalView.onScreenUpdated()
        renderTabs()
    }

    private fun renderTabs() {
        tabs.removeAllViews()
        service?.sessions.orEmpty().forEachIndexed { index, session ->
            val title = session.title?.takeIf { it.isNotBlank() } ?: "bash"
            val tab = TextView(this).apply {
                text = "${index + 1}: ${title.take(18)}"
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                setTextColor(if (session == current) Color.WHITE else Color.GRAY)
                setBackgroundColor(if (session == current) 0xFF2B2B2B.toInt() else Color.TRANSPARENT)
                typeface = Typeface.MONOSPACE
                setOnClickListener { attach(session) }
                setOnLongClickListener {
                    confirmClose(session)
                    true
                }
            }
            tabs.addView(tab, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
    }

    private fun confirmClose(session: TerminalSession) {
        AlertDialog.Builder(this)
            .setMessage(R.string.pepe_terminal_close_session)
            .setPositiveButton(R.string.pepe_terminal_close_session) { _, _ -> service?.removeSession(session) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- menu ----

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, R.string.pepe_terminal_paste)
        menu.menu.add(0, 2, 1, R.string.pepe_terminal_copy_screen)
        menu.menu.add(0, 3, 2, R.string.pepe_terminal_open_url)
        menu.menu.add(0, 4, 3, R.string.pepe_terminal_keyboard)
        menu.menu.add(0, 5, 4, R.string.pepe_terminal_new_session)
        menu.menu.add(0, 6, 5, R.string.pepe_terminal_close_session)
        if (PhantomProcessGuide.isRelevant()) menu.menu.add(0, 7, 6, R.string.pepe_phantom_button)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> paste()
                2 -> current?.emulator?.screen?.transcriptTextWithoutJoinedLines?.let {
                    sessionClient.onCopyTextToClipboard(current!!, it.trim())
                }

                3 -> openUrl()
                4 -> showKeyboard()
                5 -> newSession()?.let(::attach)
                6 -> current?.let(::confirmClose)
                7 -> PhantomProcessGuide.show(this)
            }
            true
        }
        menu.show()
    }

    private fun paste() {
        val text = clipboard().primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        current?.emulator?.paste(text)
    }

    private fun openUrl() {
        val transcript = current?.emulator?.screen?.transcriptTextWithFullLinesJoined.orEmpty()
        val urls = UrlFinder.find(transcript).reversed()
        if (urls.isEmpty()) {
            Toast.makeText(this, R.string.pepe_terminal_no_urls, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pepe_terminal_open_url)
            .setItems(urls.toTypedArray()) { _, which ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(urls[which]))) }
            }
            .show()
    }

    // ---- display ----

    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val root = findViewById<View>(R.id.terminal_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            // Keep text clear of cutouts and above the keyboard; bars are hidden (immersive).
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(cutout.left, cutout.top, cutout.right, maxOf(cutout.bottom, ime.bottom))
            insets
        }
    }

    private fun setupFont() {
        val dm = resources.displayMetrics
        minFontPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 6f, dm).roundToInt()
        maxFontPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 32f, dm).roundToInt()
        val saved = settings.terminalFontSize
        val default = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, dm).roundToInt()
        setFontSize(if (saved > 0) saved.roundToInt() else default, persist = false)
    }

    private fun setFontSize(px: Int, persist: Boolean) {
        fontSizePx = px.coerceIn(minFontPx, maxFontPx)
        terminalView.setTextSize(fontSizePx)
        if (persist) settings.terminalFontSize = fontSizePx.toFloat()
    }

    private fun showKeyboard() {
        terminalView.requestFocus()
        getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun clipboard() = getSystemService(ClipboardManager::class.java)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    // ---- swipe left returns home ----

    private val swipeDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                val width = window.decorView.width.coerceAtLeast(1)
                if (dx < -width / 3f && abs(dx) > 2 * abs(dy) && abs(vx) > dp(600)) {
                    finishToHome()
                    return true
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!terminalView.isSelectingText) swipeDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun finishToHome() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.pepe_slide_in_right, R.anim.pepe_slide_out_left)
    }
}
