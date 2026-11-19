package com.rama.mako.pepe

import android.app.Activity
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import com.rama.mako.managers.AppsProvider
import com.rama.mako.managers.LinuxEntry

/**
 * "plain" flavor: every hook is a no-op so the launcher behaves exactly like mako.
 * Must keep the same public signatures as app/src/linux/.../LinuxHooks.kt.
 */
object LinuxHooks {
    const val ENABLED = false

    fun onHomeTouch(activity: Activity, event: MotionEvent) = Unit

    fun onHomeCreated(activity: ComponentActivity, refresh: () -> Unit) = Unit

    fun onSettingsCreated(activity: Activity) = Unit

    fun entries(context: Context): List<AppsProvider.AppEntry> = emptyList()

    fun launch(context: Context, entry: LinuxEntry): Boolean = false

    fun icon(context: Context, entry: LinuxEntry): Drawable = context.packageManager.defaultActivityIcon

    fun showActions(context: Context, entry: LinuxEntry, refresh: () -> Unit) = Unit
}
