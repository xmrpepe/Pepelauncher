package io.pepe.launcher.linux.service

import io.pepe.launcher.linux.runtime.Binding
import io.pepe.launcher.linux.runtime.ProotConfig
import io.pepe.launcher.linux.runtime.ProotRunner
import io.pepe.launcher.linux.setup.LinuxSetup
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * Backup, restore and removal of the root filesystem. Archiving runs `bsdtar` (libarchive, part
 * of every Arch install) inside the guest, so ownership as seen by the guest and zstd
 * compression come for free and the app needs no native zstd library.
 */
object RootfsJobs {

    /** Paths that are bind mounts, caches or runtime state — never part of a backup. */
    val EXCLUDES = listOf(
        "./proc", "./sys", "./dev", "./tmp", "./run", "./sdcard", "./mnt/pepe-import",
        "./var/cache/pacman/pkg", "./var/tmp",
    )

    fun exportCommand(): String =
        "cd / && exec bsdtar --zstd -cpf - " +
            EXCLUDES.joinToString(" ") { "--exclude '$it'" } + " ."

    fun importCommand(): String = "exec bsdtar --zstd -xpf - -C /mnt/pepe-import"

    /** Streams a `.tar.zst` of the rootfs into [out]. */
    fun export(config: ProotConfig, out: OutputStream, log: (String) -> Unit) {
        val process = ProotRunner.start(ProotRunner.script(config, exportCommand()), redirectErrorStream = false)
        val stderr = drain(process.errorStream, log)
        process.inputStream.use { it.copyTo(out, 256 * 1024) }
        out.flush()
        stderr.join()
        val code = process.waitFor()
        if (code != 0) throw IOException("bsdtar exited with $code")
    }

    /**
     * Unpacks a backup into a fresh directory with the *current* rootfs's bsdtar, then swaps it in.
     * The current system must be installed (a fresh device runs setup first).
     */
    fun import(
        config: ProotConfig,
        rootfs: File,
        input: InputStream,
        log: (String) -> Unit,
    ) {
        val staging = File(rootfs.parentFile, "arch.import")
        deleteTree(staging)
        staging.mkdirs()
        val importConfig = config.copy(extraBindings = config.extraBindings + Binding(staging.absolutePath, "/mnt/pepe-import"))
        val process = ProotRunner.start(ProotRunner.script(importConfig, importCommand()), redirectErrorStream = false)
        val stderr = drain(process.errorStream, log)
        val stdout = drain(process.inputStream, log)
        process.outputStream.use { input.copyTo(it, 256 * 1024) }
        stderr.join()
        stdout.join()
        val code = process.waitFor()
        if (code != 0) {
            deleteTree(staging)
            throw IOException("bsdtar exited with $code")
        }
        if (!File(staging, "etc/os-release").exists() && !File(staging, "usr/lib/os-release").exists()) {
            deleteTree(staging)
            throw IOException("The archive does not contain a Linux root filesystem")
        }
        File(staging, LinuxSetup.STATE_DIR).mkdirs()
        File(staging, "${LinuxSetup.STATE_DIR}/${LinuxSetup.MARKER_INSTALLED}").writeText("import\n")

        val old = File(rootfs.parentFile, "arch.old")
        deleteTree(old)
        if (rootfs.exists() && !rootfs.renameTo(old)) throw IOException("Could not move the old rootfs aside")
        if (!staging.renameTo(rootfs)) {
            old.renameTo(rootfs)
            throw IOException("Could not move the imported rootfs into place")
        }
        deleteTree(old)
    }

    /**
     * Deletes a tree even where the guest made directories read-only (pacman installs some
     * 0555 directories): grant the owner write access first, then delete. Symlinks are never
     * followed.
     */
    fun deleteTree(root: File) {
        val path = root.toPath()
        when {
            java.nio.file.Files.isSymbolicLink(path) -> java.nio.file.Files.deleteIfExists(path)
            root.isDirectory -> {
                root.setWritable(true, true)
                root.setExecutable(true, true)
                root.setReadable(true, true)
                root.listFiles()?.forEach(::deleteTree)
                java.nio.file.Files.deleteIfExists(path)
            }

            else -> java.nio.file.Files.deleteIfExists(path)
        }
    }

    private fun drain(stream: InputStream, log: (String) -> Unit) = thread(name = "pepelauncher-job-log") {
        stream.bufferedReader().useLines { lines -> lines.forEach(log) }
    }
}
