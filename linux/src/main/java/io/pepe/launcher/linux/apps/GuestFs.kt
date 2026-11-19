package io.pepe.launcher.linux.apps

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Reads the guest filesystem from the Android side. Symlinks in the rootfs are often absolute
 * (`/usr/share/…`), which must be resolved against the rootfs, never against Android's `/`.
 */
class GuestFs(val rootfs: File) {

    /** Host file for [guestPath], following symlinks inside the rootfs; null if it escapes or loops. */
    fun resolve(guestPath: String): File? {
        val pending = ArrayDeque(components(guestPath))
        val resolved = ArrayDeque<String>()
        var hops = 0
        while (pending.isNotEmpty()) {
            val part = pending.removeFirst()
            when (part) {
                "", "." -> continue
                ".." -> {
                    resolved.removeLastOrNull()
                    continue
                }
            }
            val candidate = host(resolved + part)
            if (Files.isSymbolicLink(candidate.toPath())) {
                if (++hops > MAX_HOPS) return null
                val target = runCatching { Files.readSymbolicLink(candidate.toPath()).toString() }
                    .getOrNull() ?: return null
                if (target.startsWith("/")) resolved.clear()
                components(target).asReversed().forEach { pending.addFirst(it) }
            } else {
                resolved.addLast(part)
            }
        }
        return host(resolved)
    }

    fun exists(guestPath: String): Boolean = resolve(guestPath)?.exists() == true

    fun isFile(guestPath: String): Boolean =
        resolve(guestPath)?.let { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) } == true

    fun readText(guestPath: String): String? = resolve(guestPath)?.takeIf { it.isFile }?.let {
        runCatching { it.readText() }.getOrNull()
    }

    /** Lists the guest directory (resolved) as host files. */
    fun list(guestPath: String): List<File> =
        resolve(guestPath)?.listFiles()?.toList().orEmpty()

    private fun host(parts: Collection<String>): File =
        if (parts.isEmpty()) rootfs else File(rootfs, parts.joinToString("/"))

    private fun components(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }

    companion object {
        private const val MAX_HOPS = 40
    }
}
