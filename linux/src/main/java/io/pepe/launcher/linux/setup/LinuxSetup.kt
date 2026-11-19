package io.pepe.launcher.linux.setup

import io.pepe.launcher.linux.runtime.ProotConfig
import io.pepe.launcher.linux.runtime.ProotRunner
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * First-run setup: download → extract → configure. Every step is idempotent and leaves a
 * marker, so a failed or interrupted setup is simply run again and continues where it stopped.
 *
 * Free of Android types (paths and callbacks come in from [io.pepe.launcher.linux.service.LinuxService])
 * so it can be exercised on a desktop JVM against a host build of proot.
 */
class LinuxSetup(
    private val rootfs: File,
    private val rootfsPartial: File,
    private val downloadDir: File,
    private val tarballUrl: String,
    private val expectedSha256: String?,
    private val prootConfig: () -> ProotConfig,
    private val nameservers: () -> List<String>,
    private val downloader: RootfsDownloader = RootfsDownloader(),
    private val extractor: TarExtractor = TarExtractor(),
) {

    enum class Stage { DOWNLOAD, EXTRACT, CONFIGURE, UPDATE, DONE }

    interface Listener {
        fun onStage(stage: Stage, step: String)

        /** [fraction] is null when the step's length is unknown. */
        fun onProgress(fraction: Float?, detail: String)
        fun onLog(line: String)
    }

    class StepFailed(message: String) : IOException(message)

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    private val stateDir get() = File(rootfs, STATE_DIR)
    private fun marker(name: String) = File(stateDir, name)

    val isInstalled: Boolean get() = marker(MARKER_INSTALLED).exists()
    val isConfigured: Boolean get() = marker(MARKER_CONFIGURED).exists()

    fun run(listener: Listener) {
        cancelled = false
        if (!isInstalled) install(listener)
        configure(listener)
        listener.onStage(Stage.DONE, "Ready")
    }

    // ---- download + extract ----

    /** Download + extract only (no guest commands); used directly by the instrumented smoke test. */
    fun install(listener: Listener) {
        downloadDir.mkdirs()
        val usable = downloadDir.usableSpace
        if (usable in 1 until MIN_FREE_BYTES) {
            throw StepFailed(
                "Not enough free storage: ${usable / MB} MB free, about ${MIN_FREE_BYTES / MB} MB needed"
            )
        }

        listener.onStage(Stage.DOWNLOAD, "Downloading $tarballUrl")
        val tarball = File(downloadDir, tarballUrl.substringAfterLast('/').ifEmpty { "rootfs.tar.gz" })
        downloader.download(
            url = tarballUrl,
            target = tarball,
            expectedSha256 = expectedSha256,
            progress = { done, total ->
                val fraction = if (total > 0) done.toFloat() / total else null
                listener.onProgress(fraction, "${done / MB} / ${if (total > 0) total / MB else "?"} MB")
            },
            isCancelled = { cancelled },
        )
        listener.onLog("Checksum verified: ${tarball.name}")

        listener.onStage(Stage.EXTRACT, "Extracting")
        rootfsPartial.deleteRecursively()
        val total = tarball.length().coerceAtLeast(1)
        CountingInputStream(BufferedInputStream(tarball.inputStream(), 256 * 1024)).use { counting ->
            GZIPInputStream(counting, 64 * 1024).use { gz ->
                extractor.extract(
                    tar = gz,
                    destination = rootfsPartial,
                    progress = { entries, _ ->
                        listener.onProgress(counting.count.toFloat() / total, "$entries files")
                    },
                    isCancelled = { cancelled },
                )
            }
        }
        File(rootfsPartial, STATE_DIR).mkdirs()
        File(rootfsPartial, "$STATE_DIR/$MARKER_INSTALLED").writeText(tarballUrl + "\n")

        if (rootfs.exists()) rootfs.deleteRecursively()
        if (!rootfsPartial.renameTo(rootfs)) throw StepFailed("Could not move the extracted rootfs into place")
        tarball.delete()
        listener.onLog("Root filesystem extracted")
    }

    // ---- first-boot configuration ----

    private fun configure(listener: Listener) {
        listener.onStage(Stage.CONFIGURE, "Configuring")
        writeHostSideFiles()

        step(listener, "locale", "Generating the en_US.UTF-8 locale", "locale-gen")
        step(
            listener, "keyring", "Initialising the pacman keyring",
            """
            pacman-key --init
            if [ -f /usr/share/pacman/keyrings/archlinuxarm.gpg ]; then
                pacman-key --populate archlinuxarm
            else
                pacman-key --populate
            fi
            """.trimIndent()
        )
        step(
            listener, "nokernel", "Removing the kernel and firmware packages",
            """
            pkgs=${'$'}(pacman -Qq | grep -E '^(linux-aarch64|linux-armv7|linux-firmware.*)${'$'}' || true)
            if [ -n "${'$'}pkgs" ]; then pacman -Rdd --noconfirm ${'$'}pkgs; fi
            """.trimIndent()
        )
        listener.onStage(Stage.UPDATE, "Updating packages (pacman -Syu)")
        // ldconfig again: its pacman hook can fail while glibc itself is being replaced.
        step(listener, "update", "Updating packages", "pacman -Syu --noconfirm\nldconfig")

        marker(MARKER_CONFIGURED).writeText("1\n")
    }

    /**
     * Files written from the Android side. Called on every setup run and before sessions start,
     * since DNS servers change with the network.
     */
    fun writeHostSideFiles() {
        val etc = File(rootfs, "etc")
        writeReplacing(File(etc, "resolv.conf"), GuestFiles.resolvConf(nameservers()))
        writeReplacing(File(etc, "hosts"), GuestFiles.hosts())

        val pacmanConf = File(etc, "pacman.conf")
        if (pacmanConf.isFile) {
            val patched = GuestFiles.patchPacmanConf(pacmanConf.readText())
            if (patched != pacmanConf.readText()) pacmanConf.writeText(patched)
        }
        val mirrorlist = File(etc, "pacman.d/mirrorlist")
        if (mirrorlist.isFile) {
            val text = mirrorlist.readText()
            val fixed = GuestFiles.ensureActiveMirror(text)
            if (fixed != text) mirrorlist.writeText(fixed)
        }
        val localeGen = File(etc, "locale.gen")
        if (localeGen.isFile) localeGen.writeText(GuestFiles.enableLocale(localeGen.readText()))
        writeReplacing(File(etc, "locale.conf"), "LANG=en_US.UTF-8\n")
        val machineId = File(etc, "machine-id")
        if (!GuestFiles.isValidMachineId(runCatching { machineId.readText() }.getOrNull())) {
            writeReplacing(machineId, GuestFiles.machineId())
        }

        stateDir.mkdirs()
        File(etc, "pacman.d/hooks").mkdirs()
        writeReplacing(
            File(etc, "pacman.d/hooks/$HOOK_NAME"),
            GuestFiles.pacmanHook("/$STATE_DIR/$APPS_STAMP")
        )
    }

    private fun step(listener: Listener, id: String, title: String, script: String) {
        val done = marker("step-$id")
        if (done.exists()) return
        listener.onProgress(null, title)
        listener.onLog("$ $title")
        val process = ProotRunner.start(ProotRunner.script(prootConfig(), "set -e\n$script"))
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (cancelled) {
                    process.destroy()
                    throw StepFailed("cancelled")
                }
                listener.onLog(line)
            }
        }
        val code = process.waitFor()
        if (code != 0) throw StepFailed("$title failed (exit code $code)")
        done.writeText("1\n")
    }

    private fun writeReplacing(file: File, content: String) {
        file.parentFile?.mkdirs()
        // resolv.conf in Arch is a symlink into /run; replace the link itself.
        java.nio.file.Files.deleteIfExists(file.toPath())
        file.writeText(content)
    }

    private class CountingInputStream(input: java.io.InputStream) :
        java.io.FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count += it }

        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }

    companion object {
        const val STATE_DIR = "var/lib/pepelauncher"
        const val APPS_STAMP = "apps.stamp"
        const val HOOK_NAME = "zz-pepelauncher.hook"
        const val MARKER_INSTALLED = "installed"
        const val MARKER_CONFIGURED = "configured"
        private const val MB = 1024L * 1024
        private const val MIN_FREE_BYTES = 3L * 1024 * MB
    }
}
