package io.pepe.launcher.linux.apps

import java.io.File
import java.util.Locale

/**
 * The applications installed in the guest, from `.desktop` files in the XDG data directories.
 * Results are cached and only re-read when the pacman hook's stamp or a directory changes, so
 * calling [entries] on every launcher refresh is cheap.
 */
class DesktopEntryRepository(
    rootfs: File,
    private val stamp: File,
    private val locale: () -> Locale = { Locale.getDefault() },
) {
    private val fs = GuestFs(rootfs)

    @Volatile
    private var cache: Pair<String, List<DesktopEntry>>? = null

    /** All parsed applications, visible or not, keyed by desktop file ID (higher priority wins). */
    fun all(): List<DesktopEntry> {
        val signature = signature()
        cache?.let { (sig, entries) -> if (sig == signature) return entries }
        val entries = scan()
        cache = signature to entries
        return entries
    }

    /** What the launcher shows, sorted by name. */
    fun visible(): List<DesktopEntry> =
        all().filter { it.isVisible() && tryExecOk(it) }
            .sortedBy { it.name.lowercase(locale()) }

    fun find(id: String): DesktopEntry? = all().firstOrNull { it.id == id }

    fun invalidate() {
        cache = null
    }

    /** Changes when packages are installed/removed (hook stamp) or files appear/disappear. */
    private fun signature(): String = buildString {
        append(stamp.lastModified()).append(':').append(locale().toLanguageTag())
        DATA_DIRS.forEach { dir ->
            val host = fs.resolve(dir)
            append(':').append(host?.lastModified() ?: 0)
            host?.listFiles()?.filter { it.isDirectory }?.forEach { append(',').append(it.lastModified()) }
        }
    }

    private fun scan(): List<DesktopEntry> {
        val byId = LinkedHashMap<String, DesktopEntry>()
        DATA_DIRS.forEach { dir ->
            val base = fs.resolve(dir) ?: return@forEach
            if (!base.isDirectory) return@forEach
            base.walkTopDown()
                .maxDepth(4)
                // Symlinked directories may point anywhere (absolute = Android's /); skip them.
                .onEnter { it == base || !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                .filter { it.name.endsWith(".desktop") }
                .forEach { file ->
                    val relative = file.relativeTo(base).invariantSeparatorsPath
                    val guestPath = "$dir/$relative"
                    val text = fs.readText(guestPath) ?: return@forEach
                    val id = DesktopEntryParser.idFor(relative)
                    val entry = DesktopEntryParser.parse(id, text, locale(), guestPath)
                    if (entry != null) byId[id] = entry else byId.remove(id)
                }
        }
        return byId.values.toList()
    }

    /** `TryExec`: hide entries whose program is missing. */
    private fun tryExecOk(entry: DesktopEntry): Boolean {
        val tryExec = entry.tryExec ?: return true
        if (tryExec.startsWith("/")) return fs.exists(tryExec)
        return BIN_DIRS.any { fs.exists("$it/$tryExec") }
    }

    companion object {
        /** Lowest priority first; later directories override earlier ones for the same ID. */
        val DATA_DIRS = listOf(
            "/usr/share/applications",
            "/usr/local/share/applications",
            "/root/.local/share/applications",
        )
        private val BIN_DIRS = listOf("/usr/local/bin", "/usr/bin", "/bin")
    }
}
