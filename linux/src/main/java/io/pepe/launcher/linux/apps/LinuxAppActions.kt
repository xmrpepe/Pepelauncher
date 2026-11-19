package io.pepe.launcher.linux.apps

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.pepe.launcher.linux.Linux
import io.pepe.launcher.linux.LinuxPaths
import io.pepe.launcher.linux.R
import io.pepe.launcher.linux.runtime.ProotRunner
import io.pepe.launcher.linux.service.LinuxService
import io.pepe.launcher.linux.terminal.TerminalActivity
import kotlin.concurrent.thread

/** Launching Linux applications and the long-press actions on them. */
object LinuxAppActions {

    /** The built-in "Terminal" entry of the Linux section; it exists before setup has run. */
    const val TERMINAL_ID = "pepelauncher.terminal"

    fun launch(context: Context, id: String): Boolean {
        if (id == TERMINAL_ID || !LinuxPaths(context).isReady) {
            if (context is Activity) Linux.openTerminal(context) else Linux.openSetup(context)
            return true
        }
        val entry = LinuxApps.find(context, id) ?: return false
        if (entry.terminal) {
            context.startActivity(
                TerminalActivity.commandIntent(context, entry.command).addFlags(newTaskIfNeeded(context))
            )
        } else {
            LinuxService.start(context, LinuxService.ACTION_LAUNCH) {
                putExtra(LinuxService.EXTRA_APP_ID, id)
            }
        }
        return true
    }

    /** An extra item the launcher contributes (e.g. mako's "Add to Favorites"). */
    class ExtraAction(val label: String, val run: () -> Unit)

    fun showActions(activity: Activity, id: String, extras: List<ExtraAction>, onChanged: () -> Unit) {
        if (id == TERMINAL_ID) {
            val items = extras.map { it.label } + activity.getString(R.string.pepe_setup_title)
            AlertDialog.Builder(activity)
                .setTitle(R.string.pepe_terminal_label)
                .setItems(items.toTypedArray()) { _, which ->
                    if (which < extras.size) extras[which].run() else Linux.openSetup(activity)
                }
                .show()
            return
        }
        val entry = LinuxApps.find(activity, id) ?: return
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        extras.forEach { actions += it.label to it.run }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            actions += activity.getString(R.string.pepe_app_add_to_home) to { pinShortcut(activity, entry) }
        }
        actions += activity.getString(R.string.pepe_app_info) to { showPackageInfo(activity, entry) }
        actions += activity.getString(R.string.pepe_app_uninstall) to { confirmUninstall(activity, entry, onChanged) }

        AlertDialog.Builder(activity)
            .setTitle(entry.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    // ---- pinned shortcut ----

    private fun pinShortcut(activity: Activity, entry: DesktopEntry) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = activity.getSystemService(ShortcutManager::class.java) ?: return
        if (!manager.isRequestPinShortcutSupported) {
            Toast.makeText(activity, R.string.pepe_app_pin_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        val bitmap = LinuxApps.iconBitmap(activity, entry.id) ?: LinuxApps.letterTile(entry.name)
        val info = ShortcutInfo.Builder(activity, "linux:${entry.id}")
            .setShortLabel(entry.name)
            .setLongLabel(entry.name)
            .setIcon(Icon.createWithBitmap(bitmap))
            .setIntent(LinuxLaunchActivity.intent(activity, entry.id))
            .build()
        manager.requestPinShortcut(info, null)
    }

    // ---- package info / uninstall ----

    private fun showPackageInfo(activity: Activity, entry: DesktopEntry) {
        runInGuest(activity, "pkg=\$(pacman -Qoq ${shellQuote(entry.path)}) && pacman -Qi \"\$pkg\"") { output ->
            AlertDialog.Builder(activity)
                .setTitle(entry.name)
                .setMessage(output.ifBlank { activity.getString(R.string.pepe_app_no_package) })
                .setPositiveButton(R.string.pepe_ok, null)
                .show()
        }
    }

    private fun confirmUninstall(activity: Activity, entry: DesktopEntry, onChanged: () -> Unit) {
        runInGuest(activity, "pacman -Qoq ${shellQuote(entry.path)}") { output ->
            val pkg = output.lineSequence().firstOrNull()?.trim().orEmpty()
            if (!PACKAGE_NAME.matches(pkg)) {
                Toast.makeText(activity, R.string.pepe_app_no_package, Toast.LENGTH_SHORT).show()
                return@runInGuest
            }
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.pepe_app_uninstall_title, pkg))
                .setMessage(R.string.pepe_app_uninstall_text)
                .setPositiveButton(R.string.pepe_app_uninstall) { _, _ ->
                    // In the terminal, so pacman's output and questions are visible.
                    activity.startActivity(
                        TerminalActivity.commandIntent(
                            activity,
                            listOf("/bin/bash", "-c", "pacman -Rns $pkg; echo; read -rp 'Press Enter to close '")
                        )
                    )
                    onChanged()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** Runs a short read-only command in the guest off the main thread. */
    private fun runInGuest(activity: Activity, script: String, onResult: (String) -> Unit) {
        val app = activity.applicationContext
        thread(name = "pepelauncher-pacman") {
            val output = runCatching {
                val process = ProotRunner.start(ProotRunner.script(LinuxPaths(app).prootConfig(), script))
                process.inputStream.bufferedReader().readText().also { process.waitFor() }
            }.getOrElse { it.message.orEmpty() }
            Handler(Looper.getMainLooper()).post {
                if (!activity.isFinishing && !activity.isDestroyed) onResult(output.trim())
            }
        }
    }

    /** pacman package names: letters, digits and @._+- only. */
    private val PACKAGE_NAME = Regex("[A-Za-z0-9@._+-]+")

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun newTaskIfNeeded(context: Context) =
        if (context is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK
}
