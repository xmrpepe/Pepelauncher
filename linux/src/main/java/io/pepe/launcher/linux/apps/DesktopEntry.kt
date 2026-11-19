package io.pepe.launcher.linux.apps

/** One application from a freedesktop `.desktop` file. */
data class DesktopEntry(
    /** Desktop file ID: path below `applications/` without `.desktop`, `/` replaced by `-`. */
    val id: String,
    val name: String,
    /** Raw `Exec` value (after string unescaping). */
    val exec: String,
    /** `Exec` split into arguments with field codes expanded or removed. */
    val command: List<String>,
    val icon: String?,
    val terminal: Boolean,
    val categories: List<String>,
    val noDisplay: Boolean,
    val hidden: Boolean,
    val onlyShowIn: List<String>,
    val notShowIn: List<String>,
    val workingDirectory: String?,
    val tryExec: String?,
    val startupWmClass: String?,
    val comment: String?,
    /** Guest path of the file this entry came from. */
    val path: String,
) {
    /** Whether the launcher should list this entry. */
    fun isVisible(desktopNames: Collection<String> = DesktopEntryParser.DESKTOP_NAMES): Boolean {
        if (noDisplay || hidden || command.isEmpty()) return false
        if (onlyShowIn.isNotEmpty() && onlyShowIn.none { it in desktopNames }) return false
        if (notShowIn.any { it in desktopNames }) return false
        return true
    }
}
