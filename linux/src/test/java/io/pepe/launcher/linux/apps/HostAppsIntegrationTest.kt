package io.pepe.launcher.linux.apps

import com.google.common.truth.Truth.assertThat
import io.pepe.launcher.linux.runtime.ProotConfig
import io.pepe.launcher.linux.runtime.ProotRunner
import io.pepe.launcher.linux.setup.LinuxSetup
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Milestone check for the Linux section on a desktop: in a rootfs prepared by
 * HostSetupIntegrationTest, `pacman -S mousepad` must fire pepelauncher's pacman hook and make
 * the entry (with an icon) appear; `pacman -Rns mousepad` must make it disappear again.
 * Skipped unless PEPE_HOST_PROOT_DIR and PEPE_HOST_WORK_DIR point at a configured setup.
 */
class HostAppsIntegrationTest {

    @Test
    fun `installing and removing a package updates the Linux section`() {
        val prootDir = System.getenv("PEPE_HOST_PROOT_DIR")
        val work = System.getenv("PEPE_HOST_WORK_DIR")?.let(::File)
        assumeTrue(prootDir != null && work != null)
        val rootfs = File(work, "arch")
        assumeTrue(File(rootfs, "${LinuxSetup.STATE_DIR}/${LinuxSetup.MARKER_CONFIGURED}").exists())

        val config = ProotConfig(
            prootBinary = File(prootDir, "proot"),
            loader = File(prootDir, "loader"),
            loader32 = null,
            rootfs = rootfs,
            guestTmp = File(work, "tmp").apply { mkdirs() },
            prootTmp = File(work, "proot-tmp").apply { mkdirs() },
            bindSdcard = false,
        )
        fun run(script: String): String {
            val p = ProotRunner.start(ProotRunner.script(config, script))
            val out = p.inputStream.bufferedReader().readText()
            assertThat(p.waitFor()).isEqualTo(0)
            return out
        }

        val stamp = File(rootfs, "${LinuxSetup.STATE_DIR}/${LinuxSetup.APPS_STAMP}")
        val repo = DesktopEntryRepository(rootfs, stamp) { Locale.ENGLISH }
        run("pacman -Rns --noconfirm mousepad >/dev/null 2>&1 || true")
        val before = stamp.lastModified()
        assertThat(repo.visible().map { it.id }).doesNotContain("org.xfce.mousepad")

        val install = run("pacman -S --noconfirm --needed mousepad")
        assertThat(install).contains("Updating the pepelauncher Linux section")
        assertThat(stamp.lastModified()).isAtLeast(before)

        val mousepad = repo.visible().first { it.id == "org.xfce.mousepad" }
        assertThat(mousepad.name).isEqualTo("Mousepad")
        assertThat(mousepad.command).containsExactly("mousepad")

        val icon = IconResolver(GuestFs(rootfs)).resolve(mousepad.icon)
        assertThat(icon).isNotNull()
        File(work, "mousepad-icon.txt").writeText(icon!!.guestPath)
        if (icon is IconResolver.Icon.Svg) {
            run("rsvg-convert -w 192 -h 192 -o /tmp/mousepad.png '${icon.guestPath}'")
            assertThat(File(work, "tmp/mousepad.png").length()).isGreaterThan(0L)
        }

        val remove = run("pacman -Rns --noconfirm mousepad")
        assertThat(remove).contains("Updating the pepelauncher Linux section")
        assertThat(repo.visible().map { it.id }).doesNotContain("org.xfce.mousepad")
    }
}
