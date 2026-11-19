package io.pepe.launcher.linux

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.view.TerminalRenderer
import io.pepe.launcher.linux.service.SetupState
import io.pepe.launcher.linux.setup.LinuxSetup
import io.pepe.launcher.linux.terminal.BaseSessionClient
import io.pepe.launcher.linux.ui.LinuxSettingsActivity
import io.pepe.launcher.linux.ui.SetupActivity
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders pepelauncher's Linux screens with the real Android framework (Robolectric native
 * graphics) into PNGs for the README. Opt-in: set PEPE_SCREENSHOT_DIR. Optional inputs:
 * PEPE_SCREENSHOT_SETUP_LOG (a setup log to show) and PEPE_SCREENSHOT_TRANSCRIPT (raw terminal
 * output to replay through the real terminal emulator).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h914dp-port-420dpi")
class ScreenshotsTest {

    private val outDir: File? = System.getenv("PEPE_SCREENSHOT_DIR")?.let(::File)

    private fun save(view: View, name: String) {
        val width = 1080
        val height = 2400
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        save(bitmap, name)
    }

    private fun save(bitmap: Bitmap, name: String) {
        outDir!!.mkdirs()
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun setupScreen() {
        assumeTrue(outDir != null)
        val log = System.getenv("PEPE_SCREENSHOT_SETUP_LOG")?.let(::File)?.takeIf { it.isFile }
            ?.readLines()?.filter { it.isNotBlank() && !it.startsWith("==") }?.takeLast(40).orEmpty()
        SetupState.reset()
        SetupState.update {
            it.copy(
                running = true,
                stage = LinuxSetup.Stage.UPDATE,
                step = "Updating packages (pacman -Syu)",
                fraction = 0.62f,
                detail = "",
            )
        }
        log.forEach(SetupState::appendLog)
        val activity = Robolectric.buildActivity(SetupActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        save(activity.window.decorView, "setup")
    }

    @Test
    fun linuxSettings() {
        assumeTrue(outDir != null)
        // Robolectric has no native library directory; a device always does.
        androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .applicationInfo.nativeLibraryDir = outDir!!.absolutePath
        val activity = Robolectric.buildActivity(LinuxSettingsActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        save(activity.window.decorView, "linux-settings")
    }

    /**
     * The terminal screen: the real layout (tabs, extra-keys row) and the real terminal
     * emulator + renderer, replaying recorded output (no shell process under Robolectric).
     */
    @Test
    fun terminal() {
        assumeTrue(outDir != null)
        val transcript = System.getenv("PEPE_SCREENSHOT_TRANSCRIPT")?.let(::File)?.takeIf { it.isFile }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(android.R.style.Theme_DeviceDefault_NoActionBar)
        activity.setContentView(R.layout.pepe_terminal)
        val tabs = activity.findViewById<LinearLayout>(R.id.tabs)
        listOf("1: bash" to true, "2: htop" to false).forEach { (label, current) ->
            tabs.addView(TextView(activity).apply {
                text = label
                gravity = Gravity.CENTER
                setPadding(32, 0, 32, 0)
                typeface = Typeface.MONOSPACE
                setTextColor(if (current) Color.WHITE else Color.GRAY)
                setBackgroundColor(if (current) 0xFF2B2B2B.toInt() else Color.TRANSPARENT)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
        val root = activity.findViewById<View>(R.id.terminal_root)
        val width = 1080
        val height = 2400
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        root.draw(canvas)

        val termView = activity.findViewById<View>(R.id.terminal_view)
        val renderer = TerminalRenderer(32, Typeface.MONOSPACE) // 12sp at 420 dpi
        val columns = (termView.width / renderer.fontWidth).toInt()
        val rows = termView.height / renderer.fontLineSpacing
        val output = object : TerminalOutput() {
            override fun write(data: ByteArray, offset: Int, count: Int) = Unit
            override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
            override fun onCopyTextToClipboard(text: String?) = Unit
            override fun onPasteTextFromClipboard() = Unit
            override fun onBell() = Unit
            override fun onColorsChanged() = Unit
        }
        val emulator = TerminalEmulator(output, columns, rows, renderer.fontWidth.toInt(), renderer.fontLineSpacing,
            2000, object : BaseSessionClient() {})
        fun feed(text: String) = text.toByteArray().let { emulator.append(it, it.size) }
        fun feed(bytes: ByteArray) = emulator.append(bytes, bytes.size)
        feed("[root@localhost ~]# pacman -S mousepad\r\n")
        transcript?.readBytes()?.let(::feed)
        feed("\r\n[root@localhost ~]# ")

        canvas.save()
        canvas.translate(termView.left.toFloat(), termView.top.toFloat())
        canvas.clipRect(0, 0, termView.width, termView.height)
        renderer.render(emulator, canvas, 0, -1, -1, -1, -1)
        canvas.restore()
        save(bitmap, "terminal")
    }
}
