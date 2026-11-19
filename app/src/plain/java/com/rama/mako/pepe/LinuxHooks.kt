package com.rama.mako.pepe

import android.app.Activity
import android.view.MotionEvent

/**
 * "plain" flavor: every hook is a no-op so the launcher behaves exactly like mako.
 * Must keep the same public signatures as app/src/linux/.../LinuxHooks.kt.
 */
object LinuxHooks {
    const val ENABLED = false

    fun onHomeTouch(activity: Activity, event: MotionEvent) = Unit
}
