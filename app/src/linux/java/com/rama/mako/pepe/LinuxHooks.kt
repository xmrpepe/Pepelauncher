package com.rama.mako.pepe

import android.app.Activity
import android.view.MotionEvent
import io.pepe.launcher.linux.HomeGestures

/**
 * The only place where upstream mako code reaches into pepelauncher's Linux features.
 * The "plain" flavor ships a no-op copy with the same signatures.
 */
object LinuxHooks {
    const val ENABLED = true

    /** MainActivity.dispatchTouchEvent: swipe right opens the Linux terminal. */
    fun onHomeTouch(activity: Activity, event: MotionEvent) = HomeGestures.onTouch(activity, event)
}
