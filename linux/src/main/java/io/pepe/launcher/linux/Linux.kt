package io.pepe.launcher.linux

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import io.pepe.launcher.linux.setup.LinuxSetup
import io.pepe.launcher.linux.terminal.TerminalActivity
import io.pepe.launcher.linux.ui.SetupActivity

/**
 * Public entry points of the Linux module. The launcher (via `com.rama.mako.pepe.LinuxHooks`)
 * only ever calls into this object.
 */
object Linux {

    fun isSupported(): Boolean = LinuxPaths.isSupportedDevice

    fun isReady(context: Context): Boolean = LinuxPaths(context).isReady

    fun setup(context: Context): LinuxSetup {
        val app = context.applicationContext
        val paths = LinuxPaths(app)
        val settings = LinuxSettings(app)
        return LinuxSetup(
            rootfs = paths.rootfs,
            rootfsPartial = paths.rootfsPartial,
            downloadDir = paths.downloadDir,
            tarballUrl = settings.rootfsUrl(),
            expectedSha256 = settings.expectedSha256.ifEmpty { null },
            prootConfig = { paths.prootConfig(settings) },
            nameservers = { nameservers(app) },
        )
    }

    /** Opens the terminal, or the setup screen if the Linux system is not installed yet. */
    fun openTerminal(activity: Activity, fromSwipe: Boolean = false) {
        val target = if (isReady(activity)) TerminalActivity::class.java else SetupActivity::class.java
        val intent = Intent(activity, target)
        val options = if (fromSwipe) {
            ActivityOptions.makeCustomAnimation(
                activity, R.anim.pepe_slide_in_left, R.anim.pepe_slide_out_right
            ).toBundle()
        } else null
        activity.startActivity(intent, options)
    }

    fun openSetup(context: Context) {
        context.startActivity(
            Intent(context, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** DNS servers of the active network, for the guest's /etc/resolv.conf. */
    fun nameservers(context: Context): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyList()
        val props = runCatching { cm.getLinkProperties(cm.activeNetwork) }.getOrNull()
            ?: return emptyList()
        return props.dnsServers.mapNotNull { it.hostAddress?.substringBefore('%') }
    }
}
