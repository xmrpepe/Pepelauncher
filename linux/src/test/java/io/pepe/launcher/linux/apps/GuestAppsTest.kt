package io.pepe.launcher.linux.apps

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Locale

/** GuestFs, DesktopEntryRepository and IconResolver against a small fake rootfs. */
class GuestAppsTest {

    private val rootfs: File = Files.createTempDirectory("rootfs").toFile()
    private val stamp = File(rootfs, "var/lib/pepelauncher/apps.stamp")

    @After
    fun tearDown() {
        rootfs.deleteRecursively()
    }

    private fun write(path: String, text: String = "x"): File =
        File(rootfs, path.removePrefix("/")).apply { parentFile.mkdirs(); writeText(text) }

    private fun symlink(path: String, target: String) {
        val link = File(rootfs, path.removePrefix("/"))
        link.parentFile.mkdirs()
        Files.createSymbolicLink(link.toPath(), Paths.get(target))
    }

    private fun desktop(name: String, extra: String = "") =
        "[Desktop Entry]\nType=Application\nName=$name\nExec=${name.lowercase()} %U\n$extra\n"

    // ---- GuestFs ----

    @Test
    fun `absolute symlinks resolve inside the rootfs`() {
        write("/usr/share/real/file.txt", "inside")
        symlink("/usr/share/link", "/usr/share/real")
        symlink("/etc/rel", "../usr/share/real/file.txt")
        val fs = GuestFs(rootfs)

        assertThat(fs.readText("/usr/share/link/file.txt")).isEqualTo("inside")
        assertThat(fs.readText("/etc/rel")).isEqualTo("inside")
        assertThat(fs.resolve("/usr/share/link/file.txt")!!.canonicalPath)
            .startsWith(rootfs.canonicalPath)
    }

    @Test
    fun `dot dot cannot escape and loops terminate`() {
        write("/etc/hosts", "h")
        val fs = GuestFs(rootfs)
        assertThat(fs.readText("/../../../etc/hosts")).isEqualTo("h")
        symlink("/loop/a", "/loop/b")
        symlink("/loop/b", "/loop/a")
        assertThat(fs.resolve("/loop/a")).isNull()
    }

    // ---- DesktopEntryRepository ----

    @Test
    fun `scans data dirs, user entries override system ones, hidden entries are filtered`() {
        write("/usr/share/applications/org.xfce.mousepad.desktop", desktop("Mousepad", "Icon=org.xfce.mousepad"))
        write("/usr/share/applications/zeta.desktop", desktop("Zeta"))
        write("/usr/share/applications/hidden.desktop", desktop("Hidden", "NoDisplay=true"))
        write("/usr/share/applications/kde4/kate.desktop", desktop("Kate"))
        write("/usr/share/applications/gone.desktop", desktop("Gone", "TryExec=gone-binary"))
        write("/root/.local/share/applications/zeta.desktop", desktop("Zeta (mine)"))
        write("/usr/share/applications/not-an-app.desktop", "[Desktop Entry]\nType=Link\nName=L\nURL=x\n")

        val repo = DesktopEntryRepository(rootfs, stamp) { Locale.ENGLISH }

        assertThat(repo.visible().map { it.id }).containsExactly("kde4-kate", "org.xfce.mousepad", "zeta").inOrder()
        assertThat(repo.find("zeta")!!.name).isEqualTo("Zeta (mine)")
        assertThat(repo.find("hidden")).isNotNull()
        assertThat(repo.find("org.xfce.mousepad")!!.command).containsExactly("mousepad")
    }

    @Test
    fun `try exec shows the entry once the binary exists`() {
        write("/usr/share/applications/gone.desktop", desktop("Gone", "TryExec=gone-binary"))
        val repo = DesktopEntryRepository(rootfs, stamp) { Locale.ENGLISH }
        assertThat(repo.visible()).isEmpty()
        write("/usr/bin/gone-binary")
        assertThat(repo.visible().map { it.id }).containsExactly("gone")
    }

    @Test
    fun `cache refreshes when the hook stamp changes`() {
        val repo = DesktopEntryRepository(rootfs, stamp) { Locale.ENGLISH }
        write("/usr/share/applications/a.desktop", desktop("A"))
        assertThat(repo.visible().map { it.id }).containsExactly("a")

        // pacman removes a file: the directory mtime and the stamp both change.
        File(rootfs, "usr/share/applications/a.desktop").delete()
        write("/var/lib/pepelauncher/apps.stamp")
        stamp.setLastModified(stamp.lastModified() + 5000)
        assertThat(repo.visible()).isEmpty()
    }

    @Test
    fun `desktop files that are absolute symlinks are read from the rootfs`() {
        write("/opt/app/app.desktop", desktop("Opt App"))
        symlink("/usr/share/applications/opt-app.desktop", "/opt/app/app.desktop")
        val repo = DesktopEntryRepository(rootfs, stamp) { Locale.ENGLISH }
        assertThat(repo.visible().map { it.name }).containsExactly("Opt App")
    }

    // ---- IconResolver ----

    @Test
    fun `largest png wins, svg only beats small pngs`() {
        write("/usr/share/icons/hicolor/48x48/apps/big.png")
        write("/usr/share/icons/hicolor/256x256/apps/big.png")
        write("/usr/share/icons/hicolor/scalable/apps/big.svg")
        write("/usr/share/icons/hicolor/16x16/apps/small.png")
        write("/usr/share/icons/hicolor/scalable/apps/small.svg")
        write("/usr/share/icons/Adwaita/32x32/legacy/adw.png")
        write("/usr/share/pixmaps/pix.png")
        val resolver = IconResolver(GuestFs(rootfs))

        val big = resolver.resolve("big")
        assertThat(big).isInstanceOf(IconResolver.Icon.Png::class.java)
        assertThat(big!!.guestPath).isEqualTo("/usr/share/icons/hicolor/256x256/apps/big.png")
        assertThat(resolver.resolve("small")).isInstanceOf(IconResolver.Icon.Svg::class.java)
        assertThat(resolver.resolve("adw")!!.guestPath).isEqualTo("/usr/share/icons/Adwaita/32x32/legacy/adw.png")
        assertThat(resolver.resolve("pix")!!.guestPath).isEqualTo("/usr/share/pixmaps/pix.png")
        assertThat(resolver.resolve("missing")).isNull()
        assertThat(resolver.resolve(null)).isNull()
    }

    @Test
    fun `absolute icon paths and hidpi directories`() {
        write("/opt/app/icon.svg")
        write("/usr/share/icons/hicolor/128x128@2/apps/hidpi.png")
        write("/usr/share/icons/hicolor/192x192/apps/hidpi.png")
        val resolver = IconResolver(GuestFs(rootfs))

        assertThat(resolver.resolve("/opt/app/icon.svg")).isInstanceOf(IconResolver.Icon.Svg::class.java)
        assertThat(resolver.resolve("/opt/app/missing.png")).isNull()
        assertThat(resolver.resolve("hidpi")!!.guestPath).isEqualTo("/usr/share/icons/hicolor/128x128@2/apps/hidpi.png")
        assertThat(IconResolver.sizeOf("256x256@2")).isEqualTo(512)
        assertThat(IconResolver.sizeOf("scalable")).isNull()
    }
}
