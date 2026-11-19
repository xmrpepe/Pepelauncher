package com.rama.mako.pepe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.rama.mako.activities.MainActivity
import com.rama.mako.managers.PrefsManager
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * Renders the home screen (mako's MainActivity) with the Linux section for the README, using
 * the real Android framework (Robolectric native graphics). Opt-in: PEPE_SCREENSHOT_DIR, and
 * PEPE_SCREENSHOT_ROOTFS pointing at an installed rootfs whose .desktop files and icons are shown.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h914dp-port-420dpi")
class HomeScreenshotTest {

    @Test
    fun homeWithLinuxSection() {
        val out = System.getenv("PEPE_SCREENSHOT_DIR")?.let(::File)
        val source = System.getenv("PEPE_SCREENSHOT_ROOTFS")?.let(::File)
        assumeTrue(out != null && source != null && source.isDirectory)

        val context = ApplicationProvider.getApplicationContext<Context>()
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a"))
        context.applicationInfo.nativeLibraryDir = context.filesDir.absolutePath
        val rootfs = File(context.filesDir, "arch")
        copyTree(File(source, "usr/share/applications"), File(rootfs, "usr/share/applications"))
        copyTree(File(source, "usr/share/icons/hicolor"), File(rootfs, "usr/share/icons/hicolor"))
        File(rootfs, "var/lib/pepelauncher").mkdirs()
        listOf("installed", "configured", "apps.stamp").forEach { File(rootfs, "var/lib/pepelauncher/$it").writeText("1\n") }

        val prefs = PrefsManager.getInstance(context)
        prefs.initPrefs(true)
        prefs.setIconSource(PrefsManager.IconSource.SYSTEM)

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val view = activity.window.decorView
        repeat(2) {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, 1080, 2400)
            shadowOf(Looper.getMainLooper()).idle()
        }
        val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        out!!.mkdirs()
        File(out, "home.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Copies without following symlinks (absolute links point into the rootfs). */
    private fun copyTree(from: File, to: File) {
        if (!from.exists()) return
        from.walkTopDown().onEnter { it == from || !Files.isSymbolicLink(it.toPath()) }.forEach { f ->
            val target = File(to, f.relativeTo(from).path)
            when {
                Files.isSymbolicLink(f.toPath()) -> {
                    target.parentFile?.mkdirs()
                    Files.deleteIfExists(target.toPath())
                    Files.createSymbolicLink(target.toPath(), Files.readSymbolicLink(f.toPath()))
                }
                f.isDirectory -> target.mkdirs()
                else -> Files.copy(f.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS)
            }
        }
    }
}
