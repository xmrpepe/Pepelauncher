package io.pepe.launcher.linux.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import android.util.Log
import io.pepe.launcher.linux.apps.DesktopEntryParser
import io.pepe.launcher.linux.apps.LinuxApps
import io.pepe.launcher.linux.display.LinuxDisplay
import java.io.File
import io.pepe.launcher.linux.Linux
import io.pepe.launcher.linux.LinuxPaths
import io.pepe.launcher.linux.LinuxSettings
import io.pepe.launcher.linux.R
import io.pepe.launcher.linux.runtime.ProotRunner
import io.pepe.launcher.linux.setup.LinuxSetup
import io.pepe.launcher.linux.terminal.TerminalActivity
import io.pepe.launcher.linux.ui.SetupActivity
import kotlin.concurrent.thread
import com.rama.bohio.R as BohioR

/**
 * Foreground service that owns everything running in the Linux system: the setup job and the
 * terminal sessions (and, later, the compositor and app processes). It runs in the `:linux`
 * process, shows a persistent notification while anything is alive, and stops itself when the
 * last session ends.
 */
class LinuxService : Service() {

    inner class LocalBinder : Binder() {
        val service: LinuxService get() = this@LinuxService
    }

    private val binder = LocalBinder()
    private val paths by lazy { LinuxPaths(this) }

    private val _sessions = mutableListOf<TerminalSession>()
    val sessions: List<TerminalSession> get() = _sessions

    /** Receives session callbacks while no terminal screen is attached. */
    private val detachedClient = DetachedSessionClient { finished -> onSessionFinished(finished) }

    @Volatile
    private var setup: LinuxSetup? = null
    private var isForeground = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // The Wayland compositor (and Xwayland) live as long as the service.
        LinuxDisplay.onWindowsChanged = {
            updateNotification()
            stopIfIdle()
        }
        if (paths.isReady) runCatching { LinuxDisplay.start(this) }
            .onFailure { Log.e(TAG, "display failed to start", it) }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_SETUP -> startSetup()
            ACTION_LAUNCH -> intent.getStringExtra(EXTRA_APP_ID)?.let(::launchApp)
            ACTION_EXPORT -> intent.data?.let { uri -> runJob(getString(R.string.pepe_job_export)) { exportTo(uri) } }
            ACTION_IMPORT -> intent.data?.let { uri -> runJob(getString(R.string.pepe_job_import)) { importFrom(uri) } }
            ACTION_RESET -> runJob(getString(R.string.pepe_job_reset)) { resetRootfs() }
            ACTION_REINSTALL -> runJob(getString(R.string.pepe_job_reset)) {
                resetRootfs()
                ContextCompat.getMainExecutor(this).execute { startSetup() }
            }
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
        }
        stopIfIdle()
        return START_NOT_STICKY
    }

    // ---- setup ----

    val isSetupRunning: Boolean get() = setup != null

    private fun startSetup() {
        if (setup != null) return
        val job = Linux.setup(this)
        setup = job
        SetupState.reset()
        updateNotification()
        thread(name = "pepelauncher-setup") {
            val error = runCatching {
                job.run(object : LinuxSetup.Listener {
                    override fun onStage(stage: LinuxSetup.Stage, step: String) =
                        SetupState.update { it.copy(stage = stage, step = step, fraction = null, detail = "") }

                    override fun onProgress(fraction: Float?, detail: String) =
                        SetupState.update { it.copy(fraction = fraction, detail = detail) }

                    override fun onLog(line: String) = SetupState.appendLog(line)
                })
            }.exceptionOrNull()
            SetupState.update {
                it.copy(running = false, error = error?.let { e -> e.message ?: e.toString() })
            }
            setup = null
            ContextCompat.getMainExecutor(this).execute {
                updateNotification()
                stopIfIdle()
            }
        }
    }

    fun cancelSetup() {
        setup?.cancel()
    }

    // ---- jobs: backup, restore, reset ----

    @Volatile
    private var job: Thread? = null

    private fun runJob(title: String, block: (log: (String) -> Unit) -> Unit) {
        if (job != null || setup != null) return
        JobState.update { JobState.Snapshot(running = true, title = title) }
        job = thread(name = "pepelauncher-job") {
            val error = runCatching {
                block { line -> JobState.update { it.copy(message = line) } }
            }.exceptionOrNull()
            if (error != null) Log.e(TAG, "job failed: $title", error)
            JobState.update {
                it.copy(running = false, error = error?.let { e -> e.message ?: e.toString() })
            }
            job = null
            ContextCompat.getMainExecutor(this).execute {
                updateNotification()
                stopIfIdle()
            }
        }
        updateNotification()
    }

    /** Called on a job thread: ends every session and app before the rootfs is touched. */
    private fun stopLinuxProcesses() {
        val done = java.util.concurrent.CountDownLatch(1)
        ContextCompat.getMainExecutor(this).execute {
            _sessions.toList().forEach { it.finishIfRunning() }
            _sessions.clear()
            done.countDown()
        }
        done.await(5, java.util.concurrent.TimeUnit.SECONDS)
        synchronized(apps) { apps.toList() }.forEach {
            it.destroy()
            it.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    private fun exportTo(uri: android.net.Uri) {
        val out = contentResolver.openOutputStream(uri, "wt") ?: error("Cannot write $uri")
        out.use { RootfsJobs.export(paths.prootConfig(), it) { line -> Log.i(TAG, line) } }
    }

    private fun importFrom(uri: android.net.Uri) {
        check(paths.isInstalled) { getString(R.string.pepe_job_import_needs_system) }
        stopLinuxProcesses()
        val input = contentResolver.openInputStream(uri) ?: error("Cannot read $uri")
        input.use { RootfsJobs.import(paths.prootConfig(), paths.rootfs, it) { line -> Log.i(TAG, line) } }
        LinuxApps.repository(this).invalidate()
    }

    private fun resetRootfs() {
        stopLinuxProcesses()
        RootfsJobs.deleteTree(paths.rootfs)
        RootfsJobs.deleteTree(paths.rootfsPartial)
        RootfsJobs.deleteTree(paths.downloadDir)
        RootfsJobs.deleteTree(paths.guestTmp)
        LinuxApps.repository(this).invalidate()
    }

    // ---- Linux applications ----

    private val apps = mutableListOf<Process>()

    /** Starts a desktop entry's command; the service owns the process and keeps it alive. */
    private fun launchApp(id: String) {
        val entry = LinuxApps.find(this, id) ?: return
        if (LinuxDisplay.compositor == null) runCatching { LinuxDisplay.start(this) }
        runCatching { Linux.setup(this).writeHostSideFiles() }
        val env = ProotRunner.defaultGuestEnv().apply {
            put("XDG_CURRENT_DESKTOP", DesktopEntryParser.DESKTOP_NAMES.first())
        }
        val invocation = ProotRunner.build(
            paths.prootConfig(LinuxSettings(this)),
            entry.command,
            env,
            workDir = entry.workingDirectory ?: ProotRunner.GUEST_HOME,
        )
        val process = runCatching { ProotRunner.start(invocation) }.getOrElse {
            Log.e(TAG, "launch failed: $id", it)
            return
        }
        synchronized(apps) { apps += process }
        updateNotification()
        val log = File(filesDir, "logs/$id.log").apply { parentFile?.mkdirs() }
        thread(name = "pepelauncher-app-$id") {
            runCatching {
                log.outputStream().use { out -> process.inputStream.copyTo(out) }
            }
            process.waitFor()
            synchronized(apps) { apps -= process }
            ContextCompat.getMainExecutor(this).execute {
                updateNotification()
                stopIfIdle()
            }
        }
    }

    // ---- terminal sessions ----

    /** A login shell, or [command] (argv inside the guest) when given. */
    fun createSession(client: TerminalSessionClient?, command: List<String>? = null): TerminalSession {
        val settings = LinuxSettings(this)
        runCatching { Linux.setup(this).writeHostSideFiles() }
        val config = paths.prootConfig(settings)
        val invocation = if (command.isNullOrEmpty()) {
            ProotRunner.loginShell(config, settings.shell)
        } else {
            ProotRunner.build(config, command)
        }
        val env = invocation.hostEnvArray() + androidEnv()
        val session = TerminalSession(
            invocation.executable,
            filesDir.absolutePath,
            invocation.argv.toTypedArray(),
            env,
            TRANSCRIPT_ROWS,
            client ?: detachedClient,
        )
        _sessions += session
        updateNotification()
        return session
    }

    fun removeSession(session: TerminalSession) {
        session.finishIfRunning()
        onSessionFinished(session)
    }

    /** Called by the attached screen's client too, so the service always forgets dead sessions. */
    fun onSessionFinished(session: TerminalSession) {
        if (_sessions.remove(session)) {
            updateNotification()
            stopIfIdle()
        }
    }

    /** Hands sessions back to the service when the terminal screen goes away. */
    fun detachClients() {
        _sessions.forEach { it.updateTerminalSessionClient(detachedClient) }
    }

    // ---- lifecycle ----

    private fun stopIfIdle() {
        if (_sessions.isEmpty() && setup == null && job == null && LinuxDisplay.windowCount == 0 &&
            synchronized(apps) { apps.isEmpty() }
        ) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForeground = false
            stopSelf()
        }
    }

    private fun stopEverything() {
        setup?.cancel()
        _sessions.toList().forEach { it.finishIfRunning() }
        _sessions.clear()
        synchronized(apps) { apps.toList() }.forEach { it.destroy() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isForeground = false
        stopSelf()
    }

    override fun onDestroy() {
        LinuxDisplay.onWindowsChanged = null
        LinuxDisplay.stop()
        synchronized(apps) { apps.toList() }.forEach { it.destroy() }
        _sessions.toList().forEach { it.finishIfRunning() }
        _sessions.clear()
        setup?.cancel()
        super.onDestroy()
    }

    // ---- notification ----

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        isForeground = true
    }

    private fun updateNotification() {
        if (!isForeground) return
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val text = when {
            setup != null -> getString(R.string.pepe_notification_setup)
            job != null -> JobState.snapshot.title
            else -> {
                val appCount = synchronized(apps) { apps.size }
                listOfNotNull(
                    resources.getQuantityString(R.plurals.pepe_notification_sessions, _sessions.size, _sessions.size),
                    if (appCount > 0) resources.getQuantityString(R.plurals.pepe_notification_apps, appCount, appCount) else null,
                    LinuxDisplay.windowCount.takeIf { it > 0 }?.let {
                        resources.getQuantityString(R.plurals.pepe_notification_windows, it, it)
                    },
                ).joinToString(" · ")
            }
        }
        val target = if (setup != null) SetupActivity::class.java else TerminalActivity::class.java
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LinuxService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(BohioR.drawable.px_android)
            .setContentTitle(getString(R.string.pepe_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, getString(R.string.pepe_notification_exit), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.pepe_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun androidEnv(): Array<String> =
        listOf("ANDROID_ROOT", "ANDROID_DATA", "ANDROID_ART_ROOT", "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT")
            .mapNotNull { key -> System.getenv(key)?.let { "$key=$it" } }
            .toTypedArray()

    companion object {
        const val ACTION_SETUP = "io.pepe.launcher.linux.action.SETUP"
        const val ACTION_KEEP_ALIVE = "io.pepe.launcher.linux.action.KEEP_ALIVE"
        const val ACTION_STOP = "io.pepe.launcher.linux.action.STOP"
        const val ACTION_LAUNCH = "io.pepe.launcher.linux.action.LAUNCH"
        const val ACTION_EXPORT = "io.pepe.launcher.linux.action.EXPORT"
        const val ACTION_IMPORT = "io.pepe.launcher.linux.action.IMPORT"
        const val ACTION_RESET = "io.pepe.launcher.linux.action.RESET"
        const val ACTION_REINSTALL = "io.pepe.launcher.linux.action.REINSTALL"
        const val EXTRA_APP_ID = "app_id"
        private const val TAG = "pepe-linux"
        private const val CHANNEL_ID = "linux"
        private const val NOTIFICATION_ID = 0x7e7e
        private const val TRANSCRIPT_ROWS = 5000

        fun start(context: Context, action: String, extras: Intent.() -> Unit = {}) {
            ContextCompat.startForegroundService(
                context, Intent(context, LinuxService::class.java).setAction(action).apply(extras)
            )
        }
    }
}
