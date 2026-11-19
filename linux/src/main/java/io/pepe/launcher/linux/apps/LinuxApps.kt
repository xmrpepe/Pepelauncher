package io.pepe.launcher.linux.apps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.pepe.launcher.linux.LinuxPaths
import io.pepe.launcher.linux.runtime.ProotRunner
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * The Linux applications as the launcher sees them: entries, icons, change notifications.
 * Used from the launcher process; heavy work (SVG rendering) happens on a background thread.
 */
object LinuxApps {

    private const val ICON_PX = 192
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "pepelauncher-icons") }
    private val bitmaps = LruCache<String, Bitmap>(64)
    private val attempted = mutableSetOf<String>()
    private var repository: DesktopEntryRepository? = null
    private val listeners = mutableSetOf<() -> Unit>()

    fun repository(context: Context): DesktopEntryRepository = synchronized(this) {
        repository ?: LinuxPaths(context).let { paths ->
            DesktopEntryRepository(paths.rootfs, paths.appsStamp)
        }.also { repository = it }
    }

    /** Visible applications, or nothing until setup has finished. */
    fun entries(context: Context): List<DesktopEntry> {
        if (!LinuxPaths(context).isReady) return emptyList()
        return runCatching { repository(context).visible() }.getOrDefault(emptyList())
    }

    fun find(context: Context, id: String): DesktopEntry? =
        runCatching { repository(context).find(id) }.getOrNull()

    /**
     * The desktop entry for a window's app_id (Wayland) or WM_CLASS (X11): by desktop file ID,
     * StartupWMClass, or the program name, case-insensitively.
     */
    fun findForAppId(context: Context, appId: String): DesktopEntry? {
        if (appId.isBlank()) return null
        val all = runCatching { repository(context).all() }.getOrDefault(emptyList())
        val key = appId.lowercase()
        return all.firstOrNull { it.id.lowercase() == key }
            ?: all.firstOrNull { it.startupWmClass?.lowercase() == key }
            ?: all.firstOrNull { it.id.lowercase().endsWith(".$key") }
            ?: all.firstOrNull { it.command.firstOrNull()?.substringAfterLast('/')?.lowercase() == key }
    }

    // ---- icons ----

    fun icon(context: Context, id: String, label: String): Drawable {
        val bitmap = iconBitmap(context, id) ?: letterTile(label)
        return BitmapDrawable(context.resources, bitmap)
    }

    /** Cached or directly decodable icon; schedules SVG rendering and returns null meanwhile. */
    fun iconBitmap(context: Context, id: String): Bitmap? {
        val entry = find(context, id) ?: return null
        val paths = LinuxPaths(context)
        val icon = IconResolver(GuestFs(paths.rootfs)).resolve(entry.icon) ?: return null
        val key = cacheKey(paths, icon.guestPath)
        bitmaps.get(key)?.let { return it }

        val decoded = when (icon) {
            is IconResolver.Icon.Png -> GuestFs(paths.rootfs).resolve(icon.guestPath)?.let(::decode)
            is IconResolver.Icon.Svg -> renderedFile(context, key).takeIf { it.isFile }?.let(::decode)
                ?: run {
                    scheduleSvgRender(context, key, icon.guestPath)
                    null
                }
        }
        decoded?.let { bitmaps.put(key, it) }
        return decoded
    }

    private fun scheduleSvgRender(context: Context, key: String, guestSvg: String) {
        synchronized(attempted) { if (!attempted.add(key)) return }
        val app = context.applicationContext
        worker.execute {
            if (renderSvg(app, key, guestSvg)) notifyChanged()
        }
    }

    /** Renders with `rsvg-convert` inside the guest (librsvg comes with every GTK app). */
    private fun renderSvg(context: Context, key: String, guestSvg: String): Boolean {
        val paths = LinuxPaths(context)
        if (!GuestFs(paths.rootfs).exists("/usr/bin/rsvg-convert")) return false
        val tmpName = ".pepe-icons/$key.png"
        File(paths.guestTmp, ".pepe-icons").mkdirs()
        val process = ProotRunner.start(
            ProotRunner.build(
                paths.prootConfig(),
                listOf("/usr/bin/rsvg-convert", "-w", "$ICON_PX", "-h", "$ICON_PX", "-o", "/tmp/$tmpName", guestSvg)
            )
        )
        process.inputStream.readBytes()
        if (process.waitFor() != 0) return false
        val out = File(paths.guestTmp, tmpName)
        val target = renderedFile(context, key)
        target.parentFile?.mkdirs()
        return out.renameTo(target) || runCatching { out.copyTo(target, overwrite = true); out.delete() }.isSuccess
    }

    private fun renderedFile(context: Context, key: String) =
        File(context.applicationContext.filesDir, "linux-icons/$key.png")

    private fun cacheKey(paths: LinuxPaths, guestPath: String): String {
        val mtime = GuestFs(paths.rootfs).resolve(guestPath)?.lastModified() ?: 0
        val digest = MessageDigest.getInstance("SHA-1").digest("$guestPath@$mtime".toByteArray())
        return digest.take(10).joinToString("") { "%02x".format(it) }
    }

    private fun decode(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= ICON_PX && bounds.outHeight / (sample * 2) >= ICON_PX) sample *= 2
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** Fallback icon: the first letter on a colour derived from the name (or a ">…" symbol). */
    fun letterTile(label: String): Bitmap {
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val hue = (abs(label.hashCode()) % 360).toFloat()
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.55f)) }
        canvas.drawRoundRect(RectF(0f, 0f, ICON_PX.toFloat(), ICON_PX.toFloat()), 36f, 36f, bg)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = ICON_PX * 0.55f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val letter = when {
            label.startsWith(">") -> label // a symbol tile such as ">_"
            else -> label.trim().firstOrNull()?.uppercase() ?: "?"
        }
        canvas.drawText(letter, ICON_PX / 2f, ICON_PX / 2f - (text.descent() + text.ascent()) / 2, text)
        return bitmap
    }

    // ---- change notifications ----

    /**
     * Calls [onChanged] on the main thread whenever the pacman hook touches its stamp (a package
     * with .desktop files was installed, upgraded or removed) or a rendered icon becomes ready.
     * Stops automatically when [owner] is destroyed.
     */
    fun watch(context: Context, owner: LifecycleOwner, onChanged: () -> Unit) {
        val paths = LinuxPaths(context)
        paths.stateDir.mkdirs()
        @Suppress("DEPRECATION")
        val observer = object : FileObserver(
            paths.stateDir.absolutePath,
            CLOSE_WRITE or ATTRIB or CREATE or MOVED_TO
        ) {
            override fun onEvent(event: Int, path: String?) {
                if (path == paths.appsStamp.name) {
                    repository?.invalidate()
                    synchronized(attempted) { attempted.clear() }
                    notifyChanged()
                }
            }
        }
        observer.startWatching()
        synchronized(listeners) { listeners += onChanged }
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                observer.stopWatching()
                synchronized(listeners) { listeners -= onChanged }
            }
        })
    }

    private fun notifyChanged() {
        val snapshot = synchronized(listeners) { listeners.toList() }
        main.post { snapshot.forEach { it() } }
    }
}
