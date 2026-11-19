package io.pepe.launcher.linux.display

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import io.pepe.launcher.compositor.Compositor
import io.pepe.launcher.linux.LinuxPaths
import io.pepe.launcher.linux.LinuxSettings
import io.pepe.launcher.linux.R
import io.pepe.launcher.linux.apps.GuestFs
import io.pepe.launcher.linux.runtime.ProotRunner
import java.io.File
import java.lang.ref.WeakReference
import com.rama.bohio.R as BohioR

/**
 * The Linux display in the `:linux` process: runs the Wayland compositor, keeps the window
 * registry, and gives every toplevel its own [LinuxWindowActivity] (and thereby its own task and
 * Recents card).
 */
object LinuxDisplay {

    data class WindowInfo(val id: Int, val parentId: Int, val title: String, val appId: String)

    private const val TAG = "pepe-display"
    private const val CHANNEL_ID = "linux-windows"
    private const val KILL_TIMEOUT_MS = 5000L

    private val main = Handler(Looper.getMainLooper())
    private val windows = linkedMapOf<Int, WindowInfo>()
    private val activities = mutableMapOf<Int, WeakReference<LinuxWindowActivity>>()
    private var appContext: Context? = null
    private var lastClipboardFromLinux: String? = null
    private var lastClipboardToLinux: String? = null

    var compositor: Compositor? = null
        private set

    /** Called by LinuxService whenever the number of windows changes. */
    var onWindowsChanged: (() -> Unit)? = null

    val windowCount: Int get() = windows.size

    fun window(id: Int): WindowInfo? = windows[id]

    /** Starts the compositor (and Xwayland if installed). Safe to call repeatedly. */
    fun start(context: Context) {
        if (compositor != null) return
        val app = context.applicationContext
        appContext = app
        val paths = LinuxPaths(app)
        if (!paths.isReady) return
        paths.xdgRuntimeDir.mkdirs()
        val (scale, w, h) = displayParameters(app)
        compositor = runCatching {
            Compositor.start(File(paths.xdgRuntimeDir, ProotRunner.WAYLAND_DISPLAY).absolutePath, scale, w, h, listener)
        }.onFailure { Log.e(TAG, "compositor failed to start", it) }.getOrNull()
        compositor?.settings(scale, w, h, LinuxSettings(app).dialogsOwnTask)
        if (compositor != null) startXwayland(app)
    }

    fun stop() {
        compositor?.stop()
        compositor = null
        windows.clear()
        activities.values.mapNotNull { it.get() }.forEach { it.finishAndRemoveTask() }
        activities.clear()
    }

    /** Re-reads scale and dialog settings (after the settings screen changed them). */
    fun refreshSettings(context: Context) {
        val (scale, w, h) = displayParameters(context.applicationContext)
        compositor?.settings(scale, w, h, LinuxSettings(context).dialogsOwnTask)
    }

    fun scale(context: Context): Double = displayParameters(context.applicationContext).first

    private fun displayParameters(context: Context): Triple<Double, Int, Int> {
        val metrics = context.resources.displayMetrics
        val custom = LinuxSettings(context).displayScale
        val scale = if (custom > 0f) custom.toDouble() else metrics.density.toDouble()
        val wm = context.getSystemService(WindowManager::class.java)
        val (w, h) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            metrics.widthPixels to metrics.heightPixels
        }
        return Triple(scale, w, h)
    }

    private fun startXwayland(context: Context) {
        val paths = LinuxPaths(context)
        if (!GuestFs(paths.rootfs).exists("/usr/bin/Xwayland")) return
        // A previous run may have left a lock behind; Xwayland refuses to start on :0 then.
        File(paths.guestTmp, ".X0-lock").delete()
        File(paths.guestTmp, ".X11-unix/X0").delete()
        val env = ProotRunner.defaultGuestEnv().apply {
            put("WAYLAND_SOCKET", "{WAYLAND_SOCKET}")
            remove("WAYLAND_DISPLAY")
        }
        val invocation = ProotRunner.build(
            paths.prootConfig(LinuxSettings(context)),
            listOf("/usr/bin/Xwayland", ProotRunner.X_DISPLAY, "-rootless", "-noreset", "-wm", "{WM_FD}", "-displayfd", "{DISPLAY_FD}"),
            env,
        )
        val hostEnv = invocation.hostEnvArray() + listOf("ANDROID_ROOT", "ANDROID_DATA")
            .mapNotNull { key -> System.getenv(key)?.let { "$key=$it" } }
        val pid = compositor?.startXwayland(invocation.argv.toTypedArray(), hostEnv) ?: -1
        Log.i(TAG, "Xwayland started (pid $pid)")
    }

    // ---- activities ----

    fun register(id: Int, activity: LinuxWindowActivity) {
        activities[id] = WeakReference(activity)
    }

    fun unregister(id: Int, activity: LinuxWindowActivity) {
        if (activities[id]?.get() == activity) activities.remove(id)
    }

    /** The user swiped the task away (or closed it): ask politely, then kill after a timeout. */
    fun userClosed(id: Int) {
        val c = compositor ?: return
        c.close(id)
        main.postDelayed({ if (windows.containsKey(id)) compositor?.kill(id) }, KILL_TIMEOUT_MS)
    }

    fun windowIntent(context: Context, id: Int): Intent =
        Intent(context, LinuxWindowActivity::class.java)
            .setData(Uri.parse("pepe-window://$id"))
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            )

    private fun show(info: WindowInfo) {
        val context = appContext ?: return
        val intent = windowIntent(context, info.id)
        if (isAppVisible(context)) {
            runCatching { context.startActivity(intent) }.onSuccess { return }
        }
        // Android blocks activity starts from the background: offer the window instead.
        notifyWindow(context, info, intent)
    }

    private fun isAppVisible(context: Context): Boolean {
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        val uid = Process.myUid()
        return am.runningAppProcesses.orEmpty().any {
            it.uid == uid && it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        }
    }

    private fun notifyWindow(context: Context, info: WindowInfo, intent: Intent) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.pepe_window_channel), NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val pending = PendingIntent.getActivity(
            context, info.id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(BohioR.drawable.px_android)
            .setContentTitle(info.title.ifBlank { info.appId.ifBlank { context.getString(R.string.pepe_window_untitled) } })
            .setContentText(context.getString(R.string.pepe_window_opened))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_BASE + info.id, notification)
    }

    private const val NOTIFICATION_BASE = 0x7e00

    // ---- clipboard ----

    /** A Linux window gained focus: offer Android's clipboard (only readable while focused). */
    fun pushAndroidClipboard(context: Context) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
            ?: return
        if (text == lastClipboardFromLinux || text == lastClipboardToLinux) return
        lastClipboardToLinux = text
        compositor?.clipboard(text)
    }

    // ---- compositor upcalls (compositor thread → main thread) ----

    private val listener = object : Compositor.Listener {
        override fun onWindowAdded(id: Int, parentId: Int, title: String, appId: String) {
            main.post {
                val info = WindowInfo(id, parentId, title, appId)
                windows[id] = info
                onWindowsChanged?.invoke()
                show(info)
            }
        }

        override fun onWindowChanged(id: Int, title: String, appId: String) {
            main.post {
                val info = windows[id]?.copy(title = title, appId = appId) ?: return@post
                windows[id] = info
                activities[id]?.get()?.updateTaskDescription(info)
            }
        }

        override fun onWindowRemoved(id: Int) {
            main.post {
                windows.remove(id)
                appContext?.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_BASE + id)
                activities.remove(id)?.get()?.onClientClosed()
                onWindowsChanged?.invoke()
            }
        }

        override fun onClipboard(text: String) {
            main.post {
                val context = appContext ?: return@post
                lastClipboardFromLinux = text
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Linux", text))
            }
        }

        override fun onTextInput(id: Int, enabled: Boolean) {
            main.post {
                val context = appContext ?: return@post
                if (LinuxSettings(context).autoShowKeyboard) activities[id]?.get()?.setKeyboardVisible(enabled)
            }
        }
    }
}
