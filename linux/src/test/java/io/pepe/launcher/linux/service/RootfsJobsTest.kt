package io.pepe.launcher.linux.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

class RootfsJobsTest {

    @Test
    fun `deleteTree removes read-only directories and never follows symlinks`() {
        val base = Files.createTempDirectory("jobs").toFile()
        val outside = File(base, "outside").apply { mkdirs() }
        val keep = File(outside, "keep.txt").apply { writeText("keep") }
        val tree = File(base, "arch")
        val ro = File(tree, "usr/share/ro").apply { mkdirs() }
        File(ro, "file").writeText("x")
        Files.createSymbolicLink(File(tree, "link-out").toPath(), outside.toPath())
        Files.createSymbolicLink(File(tree, "abs").toPath(), Paths.get("/etc"))
        ro.setWritable(false, false)

        RootfsJobs.deleteTree(tree)

        assertThat(tree.exists()).isFalse()
        assertThat(keep.readText()).isEqualTo("keep")
        base.deleteRecursively()
    }

    @Test
    fun `backups exclude bind mounts and the package cache`() {
        val cmd = RootfsJobs.exportCommand()
        assertThat(cmd).startsWith("cd / && exec bsdtar --zstd -cpf - ")
        listOf("./proc", "./sys", "./dev", "./tmp", "./sdcard", "./var/cache/pacman/pkg").forEach {
            assertThat(cmd).contains("--exclude '$it'")
        }
        assertThat(RootfsJobs.importCommand()).contains("-C /mnt/pepe-import")
    }
}
