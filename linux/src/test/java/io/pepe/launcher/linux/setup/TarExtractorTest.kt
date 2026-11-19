package io.pepe.launcher.linux.setup

import com.google.common.truth.Truth.assertThat
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

class TarExtractorTest {

    private val dest: File = Files.createTempDirectory("tarx").toFile()

    @After
    fun tearDown() {
        dest.deleteRecursively()
    }

    private fun tar(build: TarArchiveOutputStream.() -> Unit): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(bytes).use {
            it.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            it.build()
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    private fun TarArchiveOutputStream.dir(name: String, mode: Int = 0x1ED) {
        putArchiveEntry(TarArchiveEntry(name).apply { this.mode = mode or 0x4000 })
        closeArchiveEntry()
    }

    private fun TarArchiveOutputStream.file(name: String, content: String, mode: Int = 0x1A4) {
        val data = content.toByteArray()
        putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong(); this.mode = mode or 0x8000 })
        write(data)
        closeArchiveEntry()
    }

    private fun TarArchiveOutputStream.link(name: String, target: String, type: Byte) {
        putArchiveEntry(TarArchiveEntry(name, type).apply { linkName = target })
        closeArchiveEntry()
    }

    @Test
    fun `extracts files, symlinks and converts hardlinks to relative symlinks`() {
        val input = tar {
            dir("./")
            dir("./usr/")
            dir("./usr/bin/")
            file("./usr/bin/perl5.40", "#!perl", mode = 0x1ED) // 0755
            link("./usr/bin/perl", "perl5.40", TarConstants.LF_SYMLINK)
            link("./usr/bin/perl-hard", "./usr/bin/perl5.40", TarConstants.LF_LINK)
            dir("./etc/")
            link("./etc/resolv.conf", "/run/systemd/resolve/stub-resolv.conf", TarConstants.LF_SYMLINK)
            file("./etc/shadow", "root:*:", mode = 0x180) // 0600
        }

        TarExtractor().extract(input, dest)

        val perl = File(dest, "usr/bin/perl5.40")
        assertThat(perl.readText()).isEqualTo("#!perl")
        assertThat(perl.canExecute()).isTrue()
        assertThat(Files.readSymbolicLink(File(dest, "usr/bin/perl").toPath()).toString())
            .isEqualTo("perl5.40")
        assertThat(Files.isSymbolicLink(File(dest, "usr/bin/perl-hard").toPath())).isTrue()
        assertThat(Files.readSymbolicLink(File(dest, "usr/bin/perl-hard").toPath()).toString())
            .isEqualTo("perl5.40")
        assertThat(File(dest, "usr/bin/perl-hard").readText()).isEqualTo("#!perl")
        // Absolute links are left for proot to resolve inside the guest.
        assertThat(Files.readSymbolicLink(File(dest, "etc/resolv.conf").toPath()).toString())
            .isEqualTo("/run/systemd/resolve/stub-resolv.conf")
    }

    @Test
    fun `keeps permission bits but always grants the owner access`() {
        val input = tar {
            dir("ro/", mode = 0x16D) // 0555
            file("ro/readonly", "x", mode = 0x124) // 0444
            file("ro/setuid", "x", mode = 0x9ED) // 04755
        }

        TarExtractor().extract(input, dest)

        val readonly = Files.getPosixFilePermissions(File(dest, "ro/readonly").toPath())
        assertThat(readonly).containsAtLeast(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ
        )
        assertThat(readonly).doesNotContain(PosixFilePermission.OTHERS_WRITE)
        val dir = Files.getPosixFilePermissions(File(dest, "ro").toPath())
        assertThat(dir).contains(PosixFilePermission.OWNER_WRITE)
        assertThat(dir).contains(PosixFilePermission.OTHERS_EXECUTE)
        assertThat(TarExtractor.permissionsFor(0x9ED, isDirectory = false)).hasSize(7)
    }

    @Test
    fun `strips a single root dot arch directory`() {
        val input = tar {
            dir("root.x86_64/")
            dir("root.x86_64/etc/")
            file("root.x86_64/etc/os-release", "NAME=Arch")
        }

        TarExtractor().extract(input, dest)

        assertThat(File(dest, "etc/os-release").readText()).isEqualTo("NAME=Arch")
        assertThat(File(dest, "root.x86_64").exists()).isFalse()
    }

    @Test
    fun `rejects path traversal`() {
        val input = tar { file("../evil", "x") }
        assertThrows(IOException::class.java) { TarExtractor().extract(input, dest) }
        assertThat(File(dest.parentFile, "evil").exists()).isFalse()
    }

    @Test
    fun `re-extracting over an existing tree replaces entries`() {
        TarExtractor().extract(tar { file("a", "one") }, dest)
        TarExtractor().extract(tar { link("a", "b", TarConstants.LF_SYMLINK); file("b", "two") }, dest)
        assertThat(File(dest, "a").readText()).isEqualTo("two")
    }

    @Test
    fun `can be cancelled`() {
        val input = tar { file("a", "x") }
        assertThrows(TarExtractor.Cancelled::class.java) {
            TarExtractor().extract(input, dest, isCancelled = { true })
        }
    }
}
