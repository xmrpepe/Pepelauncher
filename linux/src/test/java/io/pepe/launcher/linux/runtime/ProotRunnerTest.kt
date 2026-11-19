package io.pepe.launcher.linux.runtime

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class ProotRunnerTest {

    private val config = ProotConfig(
        prootBinary = File("/data/app/lib/arm64/libproot.so"),
        loader = File("/data/app/lib/arm64/libproot-loader.so"),
        loader32 = File("/data/app/lib/arm64/libproot-loader32.so"),
        rootfs = File("/data/data/io.pepe.launcher/files/arch"),
        guestTmp = File("/data/data/io.pepe.launcher/files/tmp"),
        prootTmp = File("/data/data/io.pepe.launcher/cache/proot"),
    )

    @Test
    fun `command has the documented shape`() {
        val inv = ProotRunner.build(config, listOf("/bin/bash", "-l"), linkedMapOf("HOME" to "/root"))

        assertThat(inv.argv).containsExactly(
            "/data/app/lib/arm64/libproot.so",
            "--kill-on-exit", "--link2symlink", "-0", "--sysvipc",
            "-r", "/data/data/io.pepe.launcher/files/arch",
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "/proc/self/fd:/dev/fd",
            "-b", "/proc/self/fd/0:/dev/stdin",
            "-b", "/proc/self/fd/1:/dev/stdout",
            "-b", "/proc/self/fd/2:/dev/stderr",
            "-b", "/dev/urandom:/dev/random",
            "-b", "/data/data/io.pepe.launcher/files/tmp:/tmp",
            "-b", "/data/data/io.pepe.launcher/files/tmp:/dev/shm",
            "-b", "/storage/emulated/0:/sdcard",
            "-w", "/root",
            "/usr/bin/env", "-i", "HOME=/root",
            "/bin/bash", "-l",
        ).inOrder()
    }

    @Test
    fun `host environment points proot at its loaders and tmp dir`() {
        val inv = ProotRunner.build(config, listOf("true"))

        assertThat(inv.hostEnv).containsExactly(
            "PROOT_TMP_DIR", "/data/data/io.pepe.launcher/cache/proot",
            "PROOT_LOADER", "/data/app/lib/arm64/libproot-loader.so",
            "PROOT_LOADER_32", "/data/app/lib/arm64/libproot-loader32.so",
        )
        assertThat(inv.hostEnvArray()).asList()
            .contains("PROOT_LOADER=/data/app/lib/arm64/libproot-loader.so")
    }

    @Test
    fun `no loader32 on 32-bit devices`() {
        val inv = ProotRunner.build(config.copy(loader32 = null), listOf("true"))
        assertThat(inv.hostEnv).doesNotContainKey("PROOT_LOADER_32")
    }

    @Test
    fun `sdcard binding can be turned off`() {
        val argv = ProotRunner.build(config.copy(bindSdcard = false), listOf("true")).argv
        assertThat(argv.joinToString(" ")).doesNotContain("/sdcard")
    }

    @Test
    fun `fake proc files are bound over the originals`() {
        val fake = config.copy(
            fakeProc = mapOf(
                "/proc/stat" to File("/f/proc_stat"),
                "/proc/loadavg" to File("/f/proc_loadavg"),
            )
        )
        val argv = ProotRunner.build(fake, listOf("true")).argv
        assertThat(argv).containsAtLeast("-b", "/f/proc_loadavg:/proc/loadavg").inOrder()
        assertThat(argv).containsAtLeast("-b", "/f/proc_stat:/proc/stat").inOrder()
        // After the generic /proc binding, so they are visible.
        assertThat(argv.indexOf("/f/proc_stat:/proc/stat")).isGreaterThan(argv.indexOf("/proc"))
    }

    @Test
    fun `sysvipc and kernel release are optional`() {
        val argv = ProotRunner.build(
            config.copy(sysvipc = false, kernelRelease = "6.2.1-pepe"),
            listOf("true")
        ).argv
        assertThat(argv).doesNotContain("--sysvipc")
        assertThat(argv).contains("--kernel-release=6.2.1-pepe")
    }

    @Test
    fun `default guest environment is clean and wayland first`() {
        val env = ProotRunner.defaultGuestEnv()
        assertThat(env).containsEntry("HOME", "/root")
        assertThat(env).containsEntry("TERM", "xterm-256color")
        assertThat(env).containsEntry("LANG", "C.UTF-8")
        assertThat(env).containsEntry("WAYLAND_DISPLAY", "wayland-0")
        assertThat(env).containsEntry("XDG_RUNTIME_DIR", "/tmp/xdg")
        assertThat(env).containsEntry("GDK_BACKEND", "wayland,x11")
        assertThat(env).containsEntry("QT_QPA_PLATFORM", "wayland;xcb")
        assertThat(env).containsEntry("SDL_VIDEODRIVER", "wayland")
        assertThat(env).containsEntry("MOZ_ENABLE_WAYLAND", "1")
        assertThat(env).containsEntry("GLYCIN_DISABLE_SANDBOX", "i-know-the-risks")
        assertThat(env["PATH"]).contains("/usr/bin")
    }

    @Test
    fun `values with spaces and shell characters stay single arguments`() {
        val argv = ProotRunner.script(config, "echo 'a b' | cat; exit 3").argv
        assertThat(argv.takeLast(3)).containsExactly("/bin/bash", "-c", "echo 'a b' | cat; exit 3")
            .inOrder()
    }

    @Test
    fun `login shell honours the configured shell`() {
        val argv = ProotRunner.loginShell(config, "/usr/bin/zsh").argv
        assertThat(argv.takeLast(2)).containsExactly("/usr/bin/zsh", "-l").inOrder()
        assertThat(argv).contains("SHELL=/usr/bin/zsh")
    }

    @Test
    fun `custom working directory`() {
        val argv = ProotRunner.build(config, listOf("pwd"), workDir = "/srv").argv
        assertThat(argv).containsAtLeast("-w", "/srv").inOrder()
    }

    @Test
    fun `rejects empty commands and malformed env names`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProotRunner.build(config, emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProotRunner.build(config, listOf("true"), mapOf("A=B" to "c"))
        }
    }

    @Test
    fun `fake proc only covers unreadable files`() {
        val dir = createTempDir()
        try {
            val bindings = FakeProc.prepare(dir) { it == "/proc/uptime" }
            assertThat(bindings.keys).doesNotContain("/proc/uptime")
            assertThat(bindings.keys).containsAtLeast("/proc/stat", "/proc/loadavg", "/proc/version")
            assertThat(bindings.getValue("/proc/loadavg").readText())
                .isEqualTo(FakeProc.FILES.getValue("/proc/loadavg"))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun createTempDir(): File = java.nio.file.Files.createTempDirectory("fakeproc").toFile()
}
