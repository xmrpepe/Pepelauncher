package io.pepe.launcher.linux.setup

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import io.pepe.launcher.linux.runtime.ProotConfig
import io.pepe.launcher.linux.runtime.ProotRunner
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress

/**
 * End-to-end check of [LinuxSetup] on a Linux desktop: a host (glibc) build of the same proot
 * sources, and an Arch Linux bootstrap tarball converted to .tar.gz. Skipped unless
 * `PEPE_HOST_PROOT_DIR` (containing `proot` and `loader`) and `PEPE_HOST_ROOTFS_TGZ` are set;
 * see docs/BUILDING.md. It downloads packages for `pacman -Syu`, so it needs network access.
 */
class HostSetupIntegrationTest {

    @Test
    fun `setup installs, configures and updates a rootfs through proot`() {
        val prootDir = System.getenv("PEPE_HOST_PROOT_DIR")
        val tarball = System.getenv("PEPE_HOST_ROOTFS_TGZ")?.let(::File)
        assumeTrue(prootDir != null && tarball != null && tarball.isFile)
        tarball!!

        val work = File(System.getenv("PEPE_HOST_WORK_DIR") ?: "build/host-setup").absoluteFile
        work.mkdirs()
        val md5 = RootfsDownloader.digest(tarball, "MD5")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/os/rootfs.tar.gz") { ex ->
                ex.sendResponseHeaders(200, tarball.length())
                ex.responseBody.use { out -> tarball.inputStream().use { it.copyTo(out) } }
            }
            createContext("/os/rootfs.tar.gz.md5") { ex ->
                val body = "$md5  rootfs.tar.gz\n".toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }

        val rootfs = File(work, "arch")
        val config = {
            ProotConfig(
                prootBinary = File(prootDir, "proot"),
                loader = File(prootDir, "loader"),
                loader32 = null,
                rootfs = rootfs,
                guestTmp = File(work, "tmp").apply { mkdirs() },
                prootTmp = File(work, "proot-tmp").apply { mkdirs() },
                bindSdcard = false,
            )
        }
        val log = File(work, "setup.log").printWriter()
        try {
            val setup = LinuxSetup(
                rootfs = rootfs,
                rootfsPartial = File(work, "arch.partial"),
                downloadDir = File(work, "download"),
                tarballUrl = "http://127.0.0.1:${server.address.port}/os/rootfs.tar.gz",
                expectedSha256 = null,
                prootConfig = config,
                nameservers = { emptyList() },
            )
            setup.run(object : LinuxSetup.Listener {
                override fun onStage(stage: LinuxSetup.Stage, step: String) {
                    log.println("== $stage $step")
                    // Networks with a TLS-intercepting proxy (like CI sandboxes) need their CA
                    // in the guest before pacman can sync. Test-only; set PEPE_HOST_EXTRA_CA.
                    val ca = System.getenv("PEPE_HOST_EXTRA_CA")?.let(::File)
                    if (stage == LinuxSetup.Stage.UPDATE && ca != null && ca.isFile) {
                        val anchors = File(rootfs, "etc/ca-certificates/trust-source/anchors")
                        anchors.mkdirs()
                        ca.copyTo(File(anchors, "pepe-test-proxy.crt"), overwrite = true)
                        ProotRunner.start(ProotRunner.script(config(), "update-ca-trust")).waitFor()
                    }
                }
                override fun onProgress(fraction: Float?, detail: String) = Unit
                override fun onLog(line: String) = log.println(line).also { log.flush() }
            })

            assertThat(setup.isConfigured).isTrue()
            assertThat(File(rootfs, "etc/pacman.d/hooks/zz-pepelauncher.hook").isFile).isTrue()

            val check = ProotRunner.start(
                ProotRunner.script(config(), "pacman -Q pacman && grep -c . /etc/resolv.conf && id -u")
            )
            val out = check.inputStream.bufferedReader().readText()
            assertThat(check.waitFor()).isEqualTo(0)
            assertThat(out).contains("pacman ")
            assertThat(out.trim().lines().last()).isEqualTo("0")
        } finally {
            log.close()
            server.stop(0)
        }
    }
}
