package io.pepe.launcher.linux.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.rama.bohio.activity.BohioActivity
import com.rama.bohio.widgets.WdCheckbox
import io.pepe.launcher.linux.LinuxPaths
import io.pepe.launcher.linux.LinuxSettings
import io.pepe.launcher.linux.R
import io.pepe.launcher.linux.Storage
import io.pepe.launcher.linux.service.JobState
import io.pepe.launcher.linux.service.LinuxService
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Linux, display, Recents and backup settings (section 5). */
class LinuxSettingsActivity : BohioActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        LinuxUiPrefs.ensure(newBase)
        super.attachBaseContext(newBase)
    }

    private lateinit var settings: LinuxSettings
    private lateinit var paths: LinuxPaths
    private lateinit var jobStatus: TextView

    private val jobObserver = JobState.Observer { s ->
        jobStatus.visibility = if (s.title.isEmpty()) View.GONE else View.VISIBLE
        jobStatus.text = when {
            s.running -> listOf(s.title, s.message).filter { it.isNotBlank() }.joinToString("\n")
            s.error != null -> getString(R.string.pepe_job_failed, s.title, s.error)
            else -> getString(R.string.pepe_job_done, s.title)
        }
        if (!s.running) updateStorage()
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zstd")
    ) { uri -> uri?.let { LinuxService.start(this, LinuxService.ACTION_EXPORT) { data = it } } }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { LinuxService.start(this, LinuxService.ACTION_IMPORT) { data = it } } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.pepe_linux_settings)
        settings = LinuxSettings(this)
        paths = LinuxPaths(this)
        val root = findViewById<View>(R.id.linux_settings_root)
        applyEdgeToEdgePadding(root)
        applyCurrentTheme(root)
        jobStatus = findViewById(R.id.job_status)

        bindText(R.id.mirror, settings.mirror) { settings.mirror = it.ifBlank { LinuxSettings.DEFAULT_MIRROR } }
        bindText(R.id.custom_url, settings.customRootfsUrl) { settings.customRootfsUrl = it }
        bindText(R.id.sha256, settings.expectedSha256) { settings.expectedSha256 = it }
        bindText(R.id.shell, settings.shell) { settings.shell = it.ifBlank { "/bin/bash" } }
        bindText(R.id.scale, settings.displayScale.takeIf { it > 0 }?.toString().orEmpty()) {
            settings.displayScale = it.toFloatOrNull()?.coerceIn(0.25f, 8f) ?: 0f
        }

        bindCheckbox(R.id.bind_sdcard, settings.bindSdcard) { checked ->
            settings.bindSdcard = checked
            if (checked && !Storage.hasAllFilesAccess(this)) Storage.requestAllFilesAccess(this, REQUEST_STORAGE)
        }
        bindCheckbox(R.id.swipe_terminal, settings.swipeOpensTerminal) { settings.swipeOpensTerminal = it }
        bindCheckbox(R.id.touch_as_mouse, settings.touchAsMouse) { settings.touchAsMouse = it }
        bindCheckbox(R.id.auto_keyboard, settings.autoShowKeyboard) { settings.autoShowKeyboard = it }
        bindCheckbox(R.id.dialogs_own_task, settings.dialogsOwnTask) { settings.dialogsOwnTask = it }

        findViewById<Button>(R.id.open_setup).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        findViewById<Button>(R.id.phantom).setOnClickListener { PhantomProcessGuide.show(this) }
        findViewById<Button>(R.id.install_x11).setOnClickListener {
            // In the terminal, so pacman's questions and progress are visible. Xwayland starts with
            // the next Linux session (the display restarts when the service does).
            startActivity(
                io.pepe.launcher.linux.terminal.TerminalActivity.commandIntent(
                    this,
                    listOf("/bin/bash", "-c", "pacman -S --needed xorg-xwayland; echo; read -rp 'Press Enter to close '")
                )
            )
        }
        findViewById<Button>(R.id.reinstall).setOnClickListener {
            confirm(R.string.pepe_settings_reinstall_confirm) {
                LinuxService.start(this, LinuxService.ACTION_REINSTALL)
                startActivity(Intent(this, SetupActivity::class.java))
            }
        }
        findViewById<Button>(R.id.reset).setOnClickListener {
            confirm(R.string.pepe_settings_reset_confirm) { LinuxService.start(this, LinuxService.ACTION_RESET) }
        }
        findViewById<Button>(R.id.export).setOnClickListener {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
            exportLauncher.launch("pepelauncher-linux-$stamp.tar.zst")
        }
        findViewById<Button>(R.id.import_backup).setOnClickListener {
            importLauncher.launch(arrayOf("application/zstd", "application/octet-stream", "*/*"))
        }
    }

    override fun onStart() {
        super.onStart()
        JobState.observe(jobObserver)
        updateStorage()
    }

    override fun onStop() {
        JobState.remove(jobObserver)
        super.onStop()
    }

    private fun bindText(id: Int, value: String, save: (String) -> Unit) {
        val edit = findViewById<EditText>(id)
        edit.setText(value)
        edit.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) save(edit.text.toString().trim()) }
        textSavers += { save(edit.text.toString().trim()) }
    }

    private val textSavers = mutableListOf<() -> Unit>()

    override fun onPause() {
        textSavers.forEach { it() }
        io.pepe.launcher.linux.display.LinuxDisplay.refreshSettings(this)
        super.onPause()
    }

    private fun bindCheckbox(id: Int, value: Boolean, onChange: (Boolean) -> Unit) {
        val box = findViewById<WdCheckbox>(id)
        box.setChecked(value)
        box.setOnCheckedChangeListener { checked -> onChange(checked) }
    }

    private fun confirm(message: Int, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> action() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateStorage() {
        val label = findViewById<TextView>(R.id.storage_used)
        if (!paths.rootfs.exists()) {
            label.setText(R.string.pepe_settings_storage_none)
            return
        }
        label.setText(R.string.pepe_settings_storage_counting)
        val rootfs = paths.rootfs
        thread(name = "pepelauncher-du") {
            val bytes = sizeOf(rootfs)
            runOnUiThread {
                if (!isDestroyed) label.text = getString(R.string.pepe_settings_storage, Formatter.formatShortFileSize(this, bytes))
            }
        }
    }

    /** Disk usage without following symlinks (absolute links would leave the rootfs). */
    private fun sizeOf(root: File): Long = root.walkTopDown()
        .onEnter { !Files.isSymbolicLink(it.toPath()) }
        .filter { !Files.isSymbolicLink(it.toPath()) && it.isFile }
        .sumOf { it.length() }

    companion object {
        private const val REQUEST_STORAGE = 2
    }
}
