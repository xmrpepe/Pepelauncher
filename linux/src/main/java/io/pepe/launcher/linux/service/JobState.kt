package io.pepe.launcher.linux.service

import android.os.Handler
import android.os.Looper

/** State of the current backup/restore/reset job, observed by the settings screen. */
object JobState {

    data class Snapshot(
        val running: Boolean = false,
        val title: String = "",
        val message: String = "",
        val error: String? = null,
    )

    fun interface Observer {
        fun onChanged(snapshot: Snapshot)
    }

    private val main = Handler(Looper.getMainLooper())
    private val observers = mutableSetOf<Observer>()

    @Volatile
    var snapshot = Snapshot()
        private set

    fun observe(observer: Observer) = main.post {
        observers += observer
        observer.onChanged(snapshot)
    }

    fun remove(observer: Observer) = main.post { observers -= observer }

    internal fun update(transform: (Snapshot) -> Snapshot) = main.post {
        snapshot = transform(snapshot)
        observers.toList().forEach { it.onChanged(snapshot) }
    }
}
