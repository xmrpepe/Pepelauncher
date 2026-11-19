package io.pepe.launcher.linux

import android.app.Activity
import android.view.GestureDetector
import android.view.MotionEvent
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Swipe right on the home screen opens the Linux terminal. Fed from the home activity's
 * dispatchTouchEvent without consuming anything, so mako's vertical list scrolling, taps and
 * long-presses keep working exactly as before.
 */
object HomeGestures {

    private val detectors = WeakHashMap<Activity, GestureDetector>()

    fun onTouch(activity: Activity, event: MotionEvent) {
        if (!Linux.isSupported()) return
        detectors.getOrPut(activity) { detector(activity) }.onTouchEvent(event)
    }

    private fun detector(activity: Activity) = GestureDetector(
        activity,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val start = e1 ?: return false
                val density = activity.resources.displayMetrics.density
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                val isSwipeRight = dx > MIN_DISTANCE_DP * density &&
                    abs(dx) > 2 * abs(dy) &&
                    velocityX > MIN_VELOCITY_DP * density
                if (!isSwipeRight || !LinuxSettings(activity).swipeOpensTerminal) return false
                Linux.openTerminal(activity, fromSwipe = true)
                return true
            }
        }
    )

    private const val MIN_DISTANCE_DP = 96
    private const val MIN_VELOCITY_DP = 500
}
