package io.pepe.launcher.linux.setup

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * Extracts a root filesystem tarball without root:
 *
 * - symlinks are recreated as-is (absolute targets are resolved by proot inside the guest);
 * - hardlinks become **relative symlinks** to their target, because Android forbids link(2) in
 *   app storage (the same reason proot runs with `--link2symlink`);
 * - permission bits are kept, minus setuid/setgid/sticky, and the owner always gets read/write
 *   (and execute on directories) so the app can update or delete the files later;
 * - ownership is ignored (proot `-0` fakes root), device nodes and FIFOs are skipped;
 * - a single top-level `root.<arch>/` directory (as in Arch's bootstrap tarballs) is stripped.
 *
 * Entries that would escape [destination] are rejected.
 */
class TarExtractor {

    fun interface Progress {
        fun onEntry(entries: Long, bytes: Long)
    }

    class Cancelled : IOException("cancelled")

    fun extract(
        tar: InputStream,
        destination: File,
        progress: Progress = Progress { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ) {
        val root = destination.toPath().toAbsolutePath().normalize()
        Files.createDirectories(root)
        val directoryModes = mutableListOf<Pair<Path, Int>>()
        var stripPrefix: String? = null
        var entries = 0L
        var bytes = 0L

        TarArchiveInputStream(tar).use { input ->
            while (true) {
                if (isCancelled()) throw Cancelled()
                val entry = input.nextEntry ?: break
                var name = normalizeName(entry.name)
                if (entries == 0L && entry.isDirectory && name.startsWith("root.") && '/' !in name) {
                    stripPrefix = "$name/"
                }
                entries++
                stripPrefix?.let { prefix ->
                    name = if (name == prefix.dropLast(1)) "" else name.removePrefix(prefix)
                }
                if (name.isEmpty()) continue

                val path = resolveInside(root, name)
                when {
                    entry.isDirectory -> {
                        Files.createDirectories(path)
                        directoryModes += path to entry.mode
                    }

                    entry.isSymbolicLink -> {
                        Files.createDirectories(path.parent)
                        replaceWithSymlink(path, entry.linkName)
                    }

                    entry.isLink -> {
                        Files.createDirectories(path.parent)
                        val target = resolveInside(root, stripped(normalizeName(entry.linkName), stripPrefix))
                        replaceWithSymlink(path, path.parent.relativize(target).toString())
                    }

                    entry.isFile -> {
                        Files.createDirectories(path.parent)
                        Files.deleteIfExists(path)
                        Files.newOutputStream(path).use { out -> bytes += input.copyTo(out) }
                        setMode(path, entry.mode, isDirectory = false)
                        runCatching { path.toFile().setLastModified(entry.modTime.time) }
                    }

                    else -> Unit // character/block devices, FIFOs: proot binds the real /dev
                }
                if (entries % 256 == 0L) progress.onEntry(entries, bytes)
            }
        }

        // Directory modes last, so read-only directories do not block their own extraction.
        directoryModes.asReversed().forEach { (path, mode) -> setMode(path, mode, isDirectory = true) }
        progress.onEntry(entries, bytes)
    }

    private fun stripped(name: String, prefix: String?): String =
        if (prefix == null) name else name.removePrefix(prefix)

    private fun replaceWithSymlink(path: Path, target: String) {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                path.toFile().deleteRecursively()
            } else {
                Files.delete(path)
            }
        }
        Files.createSymbolicLink(path, path.fileSystem.getPath(target))
    }

    companion object {
        fun normalizeName(name: String): String {
            var n = name
            while (n.startsWith("./")) n = n.substring(2)
            return n.trimStart('/').trimEnd('/')
        }

        fun resolveInside(root: Path, name: String): Path {
            val path = root.resolve(name).normalize()
            if (!path.startsWith(root)) throw IOException("Archive entry escapes destination: $name")
            return path
        }

        /** Applies [mode] (0..07777) minus special bits, guaranteeing owner access. */
        fun permissionsFor(mode: Int, isDirectory: Boolean): Set<PosixFilePermission> {
            var bits = mode and 0x1FF // 0777
            bits = bits or if (isDirectory) 0x1C0 else 0x180 // owner rwx / rw
            val all = listOf(
                0x100 to PosixFilePermission.OWNER_READ,
                0x080 to PosixFilePermission.OWNER_WRITE,
                0x040 to PosixFilePermission.OWNER_EXECUTE,
                0x020 to PosixFilePermission.GROUP_READ,
                0x010 to PosixFilePermission.GROUP_WRITE,
                0x008 to PosixFilePermission.GROUP_EXECUTE,
                0x004 to PosixFilePermission.OTHERS_READ,
                0x002 to PosixFilePermission.OTHERS_WRITE,
                0x001 to PosixFilePermission.OTHERS_EXECUTE,
            )
            return all.filter { (bit, _) -> bits and bit != 0 }.mapTo(mutableSetOf()) { it.second }
        }

        private fun setMode(path: Path, mode: Int, isDirectory: Boolean) {
            runCatching { Files.setPosixFilePermissions(path, permissionsFor(mode, isDirectory)) }
        }
    }
}
