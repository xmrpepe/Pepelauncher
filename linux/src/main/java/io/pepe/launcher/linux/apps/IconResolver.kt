package io.pepe.launcher.linux.apps

import java.io.File

/**
 * Finds the file for an `Icon=` value in the guest: an absolute path, or a name looked up in
 * the hicolor and Adwaita themes (then `/usr/share/pixmaps`).
 *
 * The largest PNG wins. A scalable SVG is preferred only over small PNGs (below
 * [MIN_GOOD_PNG] pixels), because SVGs need a render step (see [io.pepe.launcher.linux.apps.LinuxApps]).
 */
class IconResolver(private val fs: GuestFs) {

    sealed class Icon(val guestPath: String) {
        class Png(guestPath: String, val size: Int) : Icon(guestPath)
        class Svg(guestPath: String) : Icon(guestPath)
    }

    fun resolve(icon: String?): Icon? {
        if (icon.isNullOrBlank()) return null
        if (icon.startsWith("/")) return byExtension(icon)?.takeIf { fs.isFile(icon) }

        val name = icon.removeSuffix(".png").removeSuffix(".svg").removeSuffix(".xpm")
        THEMES.forEach { theme -> fromTheme(theme, name)?.let { return it } }
        listOf("png", "svg").forEach { ext ->
            val path = "/usr/share/pixmaps/$name.$ext"
            if (fs.isFile(path)) return byExtension(path)
        }
        return null
    }

    private fun fromTheme(theme: String, name: String): Icon? {
        val themeDir = "/usr/share/icons/$theme"
        var bestPng: Icon.Png? = null
        var svg: Icon.Svg? = null
        fs.list(themeDir).filter { it.isDirectory }.forEach { sizeDir ->
            val size = sizeOf(sizeDir.name)
            fs.list("$themeDir/${sizeDir.name}").filter { it.isDirectory }.forEach { context ->
                val base = "$themeDir/${sizeDir.name}/${context.name}/$name"
                if (size != null && size > (bestPng?.size ?: 0) && fs.isFile("$base.png")) {
                    bestPng = Icon.Png("$base.png", size)
                }
                if (svg == null && sizeDir.name == "scalable" && fs.isFile("$base.svg")) {
                    svg = Icon.Svg("$base.svg")
                }
            }
        }
        val png = bestPng
        return when {
            png != null && (png.size >= MIN_GOOD_PNG || svg == null) -> png
            else -> svg
        }
    }

    private fun byExtension(path: String): Icon? = when {
        path.endsWith(".png", ignoreCase = true) -> Icon.Png(path, 0)
        path.endsWith(".svg", ignoreCase = true) || path.endsWith(".svgz", ignoreCase = true) -> Icon.Svg(path)
        else -> null
    }

    companion object {
        val THEMES = listOf("hicolor", "Adwaita")
        const val MIN_GOOD_PNG = 96

        /** `48x48` → 48, `256x256@2` → 512, `scalable`/`symbolic` → null. */
        fun sizeOf(dirName: String): Int? {
            val match = Regex("""(\d+)x\d+(?:@(\d+)x?)?""").matchEntire(dirName) ?: return null
            val size = match.groupValues[1].toInt()
            val scale = match.groupValues[2].toIntOrNull() ?: 1
            return size * scale
        }
    }
}
