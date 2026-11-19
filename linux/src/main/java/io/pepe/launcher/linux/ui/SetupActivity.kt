package io.pepe.launcher.linux.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.rama.bohio.activity.BohioActivity
import io.pepe.launcher.linux.Linux
import io.pepe.launcher.linux.LinuxPaths
import io.pepe.launcher.linux.LinuxSettings
import io.pepe.launcher.linux.R
import io.pepe.launcher.linux.service.LinuxService
import io.pepe.launcher.linux.service.SetupState
import io.pepe.launcher.linux.setup.LinuxSetup

/** First-run setup screen: shows the progress of the setup job that [LinuxService] runs. */
class SetupActivity : BohioActivity() {

    private lateinit var stage: TextView
    private lateinit var progress: ProgressBar
    private lateinit var detail: TextView
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var error: TextView
    private lateinit var primary: Button
    private lateinit var cancel: Button

    private val observer = SetupState.Observer { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.pepe_setup)
        val root = findViewById<View>(R.id.setup_root)
        applyEdgeToEdgePadding(root)
        applyCurrentTheme(root)

        stage = findViewById(R.id.stage)
        progress = findViewById(R.id.progress)
        detail = findViewById(R.id.detail)
        log = findViewById(R.id.log)
        logScroll = findViewById(R.id.log_scroll)
        error = findViewById(R.id.error)
        primary = findViewById(R.id.primary)
        cancel = findViewById(R.id.cancel)

        val mirror = LinuxSettings(this).rootfsUrl().substringBefore("/os/")
        findViewById<TextView>(R.id.intro).text = getString(R.string.pepe_setup_intro, mirror)
        findViewById<Button>(R.id.phantom).setOnClickListener { PhantomProcessGuide.show(this) }
        cancel.setOnClickListener {
            startService(
                android.content.Intent(this, LinuxService::class.java)
                    .setAction(LinuxService.ACTION_STOP)
            )
        }

        if (!Linux.isSupported()) {
            stage.setText(R.string.pepe_setup_unsupported)
            primary.visibility = View.GONE
            return
        }
        requestNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        SetupState.observe(observer)
    }

    override fun onStop() {
        SetupState.remove(observer)
        super.onStop()
    }

    private fun render(s: SetupState.Snapshot) {
        if (!Linux.isSupported()) return
        val ready = !s.running && LinuxPaths(this).isReady

        stage.text = when {
            ready -> getString(R.string.pepe_setup_ready)
            else -> s.stage?.let(::stageLabel).orEmpty()
        }
        progress.visibility = if (s.running) View.VISIBLE else View.GONE
        progress.isIndeterminate = s.fraction == null
        s.fraction?.let { progress.progress = (it * 1000).toInt() }
        detail.text = if (s.running) listOf(s.step, s.detail).filter { it.isNotEmpty() }.joinToString(" · ") else ""

        val atBottom = !logScroll.canScrollVertically(1)
        log.text = s.log.joinToString("\n")
        if (atBottom) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }

        error.visibility = if (s.error != null && !s.running) View.VISIBLE else View.GONE
        error.text = s.error?.let { getString(R.string.pepe_setup_failed, it) }

        cancel.visibility = if (s.running) View.VISIBLE else View.GONE
        primary.visibility = if (s.running) View.GONE else View.VISIBLE
        when {
            ready -> {
                primary.setText(R.string.pepe_setup_open_terminal)
                primary.setOnClickListener {
                    Linux.openTerminal(this)
                    finish()
                }
            }

            else -> {
                primary.setText(if (s.error != null) R.string.pepe_setup_retry else R.string.pepe_setup_install)
                primary.setOnClickListener { LinuxService.start(this, LinuxService.ACTION_SETUP) }
            }
        }
    }

    private fun stageLabel(stage: LinuxSetup.Stage): String = getString(
        when (stage) {
            LinuxSetup.Stage.DOWNLOAD -> R.string.pepe_setup_stage_download
            LinuxSetup.Stage.EXTRACT -> R.string.pepe_setup_stage_extract
            LinuxSetup.Stage.CONFIGURE -> R.string.pepe_setup_stage_configure
            LinuxSetup.Stage.UPDATE -> R.string.pepe_setup_stage_update
            LinuxSetup.Stage.DONE -> R.string.pepe_setup_stage_done
        }
    )

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }
}
