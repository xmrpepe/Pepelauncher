package io.pepe.launcher.linux.service

import android.os.Handler
import android.os.Looper
import io.pepe.launcher.linux.setup.LinuxSetup

/**
 * Observable state of the running (or last) setup, shared between [LinuxService] and the setup
 * screen. Lives in the `:linux` process; callbacks arrive on the main thread.
 */
object SetupState {

    data class Snapshot(
        val running: Boolean = false,
        val stage: LinuxSetup.Stage? = null,
        val step: String = "",
        val fraction: Float? = null,
        val detail: String = "",
        val error: String? = null,
        val log: List<String> = emptyList(),
    )

    fun interface Observer {
        fun onChanged(snapshot: Snapshot)
    }

    private const val MAX_LOG_LINES = 400
    private val main = Handler(Looper.getMainLooper())
    private val observers = mutableSetOf<Observer>()
    private val log = ArrayDeque<String>()

    @Volatile
    var snapshot = Snapshot()
        private set

    fun observe(observer: Observer) {
        main.post {
            observers += observer
            observer.onChanged(snapshot)
        }
    }

    fun remove(observer: Observer) {
        main.post { observers -= observer }
    }

    internal fun update(transform: (Snapshot) -> Snapshot) {
        main.post {
            snapshot = transform(snapshot)
            observers.toList().forEach { it.onChanged(snapshot) }
        }
    }

    internal fun appendLog(line: String) {
        main.post {
            log.addLast(line)
            while (log.size > MAX_LOG_LINES) log.removeFirst()
            snapshot = snapshot.copy(log = log.toList())
            observers.toList().forEach { it.onChanged(snapshot) }
        }
    }

    internal fun reset() {
        main.post { log.clear() }
        update { Snapshot(running = true) }
    }
}
