package com.rama.mako.pepe

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import android.widget.Button
import com.rama.bohio.managers.ThemeManager
import io.pepe.launcher.linux.ui.LinuxSettingsActivity
import com.rama.bohio.R as BohioR
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import com.rama.mako.R
import com.rama.mako.managers.AppsProvider
import com.rama.mako.managers.LinuxEntry
import com.rama.mako.managers.PrefsManager
import io.pepe.launcher.linux.HomeGestures
import io.pepe.launcher.linux.Linux
import io.pepe.launcher.linux.apps.LinuxAppActions
import io.pepe.launcher.linux.apps.LinuxApps

/**
 * The only place where upstream mako code reaches into pepelauncher's Linux features.
 * The "plain" flavor ships a no-op copy with the same signatures.
 */
object LinuxHooks {
    const val ENABLED = true

    /** Group that new Linux entries land in: the "Linux" section of mako's app list. */
    const val LINUX_GROUP_ID = "pepe-linux"

    /** MainActivity.dispatchTouchEvent: swipe right opens the Linux terminal. */
    fun onHomeTouch(activity: Activity, event: MotionEvent) = HomeGestures.onTouch(activity, event)

    /** MainActivity.onCreate: refresh the list when pacman adds or removes applications. */
    fun onHomeCreated(activity: ComponentActivity, refresh: () -> Unit) {
        if (Linux.isSupported()) LinuxApps.watch(activity, activity, refresh)
    }

    /** SettingsActivity.onCreate: a "Linux settings" button under the title, in mako's style. */
    fun onSettingsCreated(activity: Activity) {
        if (!Linux.isSupported()) return
        val list = (activity.findViewById<ViewGroup>(R.id.settings_root)
            ?.getChildAt(0) as? ViewGroup)?.getChildAt(0) as? ViewGroup ?: return
        val button = Button(activity, null, 0, BohioR.style.BTN2).apply {
            setText(io.pepe.launcher.linux.R.string.pepe_settings_entry)
            setOnClickListener {
                activity.startActivity(Intent(activity, LinuxSettingsActivity::class.java))
            }
        }
        list.addView(
            button,
            1.coerceAtMost(list.childCount),
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        ThemeManager.applyTheme(activity, button)
    }

    /** AppsProvider.getAll: the built-in terminal plus every visible .desktop application. */
    fun entries(context: Context): List<AppsProvider.AppEntry> {
        if (!Linux.isSupported()) return emptyList()
        val user = Process.myUserHandle()
        val entries = listOf(
            LinuxEntry(LinuxAppActions.TERMINAL_ID, context.getString(R.string.pepe_linux_terminal), user)
        ) + LinuxApps.entries(context).map { LinuxEntry(it.id, it.name, user) }
        assignDefaultGroup(context, entries)
        return entries
    }

    fun launch(context: Context, entry: LinuxEntry): Boolean =
        LinuxAppActions.launch(context, entry.desktopId)

    fun icon(context: Context, entry: LinuxEntry): Drawable =
        if (entry.desktopId == LinuxAppActions.TERMINAL_ID) {
            BitmapDrawable(context.resources, LinuxApps.letterTile(">_"))
        } else {
            LinuxApps.icon(context, entry.desktopId, entry.label)
        }

    /** AppListManager.openAppSettings: the long-press actions of a Linux entry. */
    fun showActions(context: Context, entry: LinuxEntry, refresh: () -> Unit) {
        val activity = context as? Activity ?: return
        val prefs = PrefsManager.getInstance(context)
        val isFavorite = prefs.getAppGroupId(entry) == PrefsManager.SystemIds.FAVORITES
        val favorite = LinuxAppActions.ExtraAction(
            context.getString(if (isFavorite) R.string.pepe_remove_from_favorites else R.string.pepe_add_to_favorites)
        ) {
            prefs.setAppGroupId(entry, if (isFavorite) LINUX_GROUP_ID else PrefsManager.SystemIds.FAVORITES)
            if (isFavorite) ensureLinuxGroup(prefs)
            refresh()
        }
        LinuxAppActions.showActions(activity, entry.desktopId, listOf(favorite), refresh)
    }

    private fun assignDefaultGroup(context: Context, entries: List<LinuxEntry>) {
        val prefs = PrefsManager.getInstance(context)
        val unassigned = entries.filter { prefs.getRawAppGroupId(it) == null }
        if (unassigned.isEmpty()) return
        ensureLinuxGroup(prefs)
        unassigned.forEach { prefs.setAppGroupId(it, LINUX_GROUP_ID) }
    }

    private fun ensureLinuxGroup(prefs: PrefsManager) {
        if (LINUX_GROUP_ID in prefs.getGroupIds()) return
        prefs.addGroupId(LINUX_GROUP_ID)
        prefs.setGroupLabel(LINUX_GROUP_ID, "${PrefsManager.UI.SEPARATOR} Linux")
        prefs.setGroupVisible(LINUX_GROUP_ID, true)
        prefs.setGroupExpanded(LINUX_GROUP_ID, true)
    }
}
