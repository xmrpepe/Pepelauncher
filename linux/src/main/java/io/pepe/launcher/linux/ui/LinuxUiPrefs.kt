package io.pepe.launcher.linux.ui

import android.content.Context
import com.rama.bohio.managers.PrefsManager

/**
 * bohio's widgets and BohioActivity need a registered PrefsManager in every process. The launcher
 * process registers mako's; the `:linux` process has none, so the Linux screens register this one.
 * It reads the same "settings" file, so they follow the launcher's theme, font and UI scale.
 */
internal class LinuxUiPrefs private constructor(context: Context) : PrefsManager(context) {
    companion object {
        fun ensure(context: Context) = synchronized(this) {
            val registered = runCatching { PrefsManager.getInstance(context) }.isSuccess
            if (!registered) PrefsManager.register(LinuxUiPrefs(context.applicationContext))
        }
    }
}
