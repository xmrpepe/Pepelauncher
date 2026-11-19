package io.pepe.launcher.linux

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.pepe.launcher.linux.runtime.ProotRunner
import io.pepe.launcher.linux.setup.LinuxSetup
import io.pepe.launcher.linux.setup.RootfsDownloader
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread

/**
 * On-device smoke test for the no-root runtime:
 * 1. proot, shipped as a native library, can be exec'd from nativeLibraryDir (W^X on Android 10+);
 * 2. setup downloads (from a local server), verifies and extracts a rootfs and writes host files.
 * Needs no network and no real Arch rootfs.
 */
@RunWith(AndroidJUnit4::class)
class SetupSmokeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val work = File(context.cacheDir, "smoke").apply { deleteRecursively(); mkdirs() }

    @After
    fun tearDown() {
        work.deleteRecursively()
    }

    @Test
    fun prootExecutesFromNativeLibraryDir() {
        val paths = LinuxPaths(context)
        assertThat(paths.proot.exists()).isTrue()
        assertThat(paths.loader.exists()).isTrue()

        val process = ProcessBuilder(paths.proot.absolutePath, "--version")
            .redirectErrorStream(true)
            .apply { environment().putAll(ProotRunner.hostEnv(paths.prootConfig())) }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertThat(process.waitFor()).isEqualTo(0)
        assertThat(output.lowercase()).contains("proot")
    }

    @Test
    fun setupDownloadsVerifiesAndExtracts() {
        val tarball = fixtureTarball()
        val md5 = File(work, "fixture.tar.gz").apply { writeBytes(tarball) }
            .let { RootfsDownloader.digest(it, "MD5") }
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { s ->
                    val request = s.getInputStream().bufferedReader().readLine().orEmpty()
                    val body = if (request.contains(".md5")) "$md5  rootfs.tar.gz\n".toByteArray() else tarball
                    s.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
        }

        val rootfs = File(work, "arch")
        val setup = LinuxSetup(
            rootfs = rootfs,
            rootfsPartial = File(work, "arch.partial"),
            downloadDir = File(work, "download"),
            tarballUrl = "http://127.0.0.1:${server.localPort}/os/rootfs.tar.gz",
            expectedSha256 = null,
            prootConfig = { LinuxPaths(context).prootConfig() },
            nameservers = { listOf("9.9.9.9") },
        )
        try {
            setup.install(object : LinuxSetup.Listener {
                override fun onStage(stage: LinuxSetup.Stage, step: String) = Unit
                override fun onProgress(fraction: Float?, detail: String) = Unit
                override fun onLog(line: String) = Unit
            })
            setup.writeHostSideFiles()
        } finally {
            server.close()
        }

        assertThat(setup.isInstalled).isTrue()
        assertThat(File(rootfs, "usr/bin/hello").canExecute()).isTrue()
        assertThat(java.nio.file.Files.isSymbolicLink(File(rootfs, "bin").toPath())).isTrue()
        assertThat(File(rootfs, "etc/resolv.conf").readText()).isEqualTo("nameserver 9.9.9.9\n")
        assertThat(File(rootfs, "etc/pacman.conf").readText()).contains("DisableSandbox")
        assertThat(File(rootfs, "etc/pacman.d/hooks/zz-pepelauncher.hook").isFile).isTrue()
        assertThat(File(work, "download/rootfs.tar.gz").exists()).isFalse()
    }

    private fun fixtureTarball(): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(GZIPOutputStream(bytes)).use { tar ->
            fun dir(name: String) {
                tar.putArchiveEntry(TarArchiveEntry(name).apply { mode = 0x41ED })
                tar.closeArchiveEntry()
            }
            fun file(name: String, text: String, mode: Int = 0x81A4) {
                val data = text.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong(); this.mode = mode })
                tar.write(data)
                tar.closeArchiveEntry()
            }
            dir("./")
            dir("./usr/")
            dir("./usr/bin/")
            file("./usr/bin/hello", "#!/bin/sh\necho hello\n", mode = 0x81ED)
            tar.putArchiveEntry(TarArchiveEntry("./bin", TarConstants.LF_SYMLINK).apply { linkName = "usr/bin" })
            tar.closeArchiveEntry()
            dir("./etc/")
            file("./etc/pacman.conf", "[options]\nHoldPkg = pacman\n#DisableSandbox\nDownloadUser = alpm\n")
            tar.putArchiveEntry(TarArchiveEntry("./etc/resolv.conf", TarConstants.LF_SYMLINK).apply {
                linkName = "/run/systemd/resolve/stub-resolv.conf"
            })
            tar.closeArchiveEntry()
        }
        return bytes.toByteArray()
    }
}
