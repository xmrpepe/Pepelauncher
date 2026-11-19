package io.pepe.launcher.linux.setup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GuestFilesTest {

    private val archPacmanConf = """
        #
        # /etc/pacman.conf
        #
        [options]
        #RootDir     = /
        HoldPkg     = pacman glibc
        Architecture = auto
        CheckSpace
        #DisableSandbox
        DownloadUser = alpm
        ParallelDownloads = 5

        [core]
        Include = /etc/pacman.d/mirrorlist
        # DownloadUser = alpm   (a comment in another section must not change)
    """.trimIndent()

    @Test
    fun `pacman conf disables sandbox, download user and space check`() {
        val patched = GuestFiles.patchPacmanConf(archPacmanConf).lines()

        assertThat(patched).contains("DisableSandbox")
        assertThat(patched).doesNotContain("#DisableSandbox")
        assertThat(patched).contains("#DownloadUser = alpm")
        assertThat(patched).doesNotContain("DownloadUser = alpm")
        assertThat(patched).contains("#CheckSpace")
        assertThat(patched).contains("# DownloadUser = alpm   (a comment in another section must not change)")
        assertThat(patched).contains("ParallelDownloads = 5")
    }

    @Test
    fun `pacman conf gets DisableSandbox when the option is missing`() {
        val patched = GuestFiles.patchPacmanConf("[options]\nHoldPkg = pacman\n[core]\n").lines()
        assertThat(patched.subList(0, 2)).containsExactly("[options]", "DisableSandbox").inOrder()
    }

    @Test
    fun `pacman conf patch is idempotent`() {
        val once = GuestFiles.patchPacmanConf(archPacmanConf)
        assertThat(GuestFiles.patchPacmanConf(once)).isEqualTo(once)
    }

    @Test
    fun `locale gen enables en_US without duplicating it`() {
        val gen = "#en_GB.UTF-8 UTF-8\n#en_US.UTF-8 UTF-8\n#en_US ISO-8859-1\n"
        val enabled = GuestFiles.enableLocale(gen)
        assertThat(enabled.lines()).contains("en_US.UTF-8 UTF-8")
        assertThat(enabled.lines()).contains("#en_US ISO-8859-1")
        assertThat(GuestFiles.enableLocale(enabled)).isEqualTo(enabled)
        assertThat(GuestFiles.enableLocale("").lines()).contains("en_US.UTF-8 UTF-8")
    }

    @Test
    fun `enables the first mirror only when none is active`() {
        val bootstrap = "## Worldwide\n#Server = https://geo.example/\$repo/os/\$arch\n#Server = https://b.example/\n"
        assertThat(GuestFiles.ensureActiveMirror(bootstrap).lines())
            .containsAtLeast("Server = https://geo.example/\$repo/os/\$arch", "#Server = https://b.example/")
        val active = "Server = https://a.example/\n#Server = https://b.example/\n"
        assertThat(GuestFiles.ensureActiveMirror(active)).isEqualTo(active)
    }

    @Test
    fun `machine id is 32 lowercase hex digits`() {
        val id = GuestFiles.machineId()
        assertThat(GuestFiles.isValidMachineId(id)).isTrue()
        assertThat(GuestFiles.isValidMachineId("")).isFalse()
        assertThat(GuestFiles.isValidMachineId("uninitialized\n")).isFalse()
    }

    @Test
    fun `resolv conf falls back to public resolvers`() {
        assertThat(GuestFiles.resolvConf(listOf("10.0.0.1", "")))
            .isEqualTo("nameserver 10.0.0.1\n")
        assertThat(GuestFiles.resolvConf(emptyList()))
            .isEqualTo("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
    }

    @Test
    fun `pacman hook watches desktop files on install, upgrade and remove`() {
        val hook = GuestFiles.pacmanHook("/var/lib/pepelauncher/apps.stamp")
        assertThat(hook).contains("Type = Path")
        assertThat(hook).contains("Operation = Install")
        assertThat(hook).contains("Operation = Upgrade")
        assertThat(hook).contains("Operation = Remove")
        assertThat(hook).contains("Target = usr/share/applications/*.desktop")
        assertThat(hook).contains("When = PostTransaction")
        assertThat(hook).contains("Exec = /usr/bin/touch /var/lib/pepelauncher/apps.stamp")
    }
}
