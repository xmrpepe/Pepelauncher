package io.pepe.launcher.linux.apps

import java.util.Locale

/**
 * Parser for freedesktop.org Desktop Entry files (spec 1.5): the `[Desktop Entry]` group,
 * localised keys, string escapes, list values and `Exec` quoting with field codes.
 * Pure Kotlin so it is unit tested on the JVM.
 */
object DesktopEntryParser {

    /** Values matched against OnlyShowIn/NotShowIn; also exported as XDG_CURRENT_DESKTOP. */
    val DESKTOP_NAMES = listOf("pepelauncher")

    /**
     * Returns null for anything that is not a launchable application: other entry types,
     * missing `Name`/`Exec`, or `DBusActivatable`-only entries without `Exec`.
     */
    fun parse(
        id: String,
        text: String,
        locale: Locale = Locale.getDefault(),
        path: String = "",
    ): DesktopEntry? {
        val keys = parseGroup(text, "Desktop Entry") ?: return null
        if (keys["Type"]?.trim() != "Application") return null
        val name = localized(keys, "Name", locale) ?: return null
        val rawExec = keys["Exec"]?.let(::unescape)?.takeIf { it.isNotBlank() } ?: return null
        val icon = keys["Icon"]?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() }

        return DesktopEntry(
            id = id,
            name = name,
            exec = rawExec,
            command = parseExec(rawExec, name = name, icon = icon, desktopFile = path),
            icon = icon,
            terminal = bool(keys["Terminal"]),
            categories = list(keys["Categories"]),
            noDisplay = bool(keys["NoDisplay"]),
            hidden = bool(keys["Hidden"]),
            onlyShowIn = list(keys["OnlyShowIn"]),
            notShowIn = list(keys["NotShowIn"]),
            workingDirectory = keys["Path"]?.let(::unescape)?.takeIf { it.isNotBlank() },
            tryExec = keys["TryExec"]?.let(::unescape)?.takeIf { it.isNotBlank() },
            startupWmClass = keys["StartupWMClass"]?.trim()?.takeIf { it.isNotEmpty() },
            comment = localized(keys, "Comment", locale),
            path = path,
        )
    }

    /** `foo/bar.desktop` below an applications directory → `foo-bar`. */
    fun idFor(relativePath: String): String =
        relativePath.removeSuffix(".desktop").replace('/', '-')

    // ---- groups and keys ----

    /** Key/value pairs of one group; later duplicates win. Null if the group is absent. */
    fun parseGroup(text: String, group: String): Map<String, String>? {
        var inGroup = false
        var found = false
        val result = LinkedHashMap<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            if (line.startsWith("[") && line.endsWith("]")) {
                inGroup = line.substring(1, line.length - 1) == group
                if (inGroup) found = true
                return@forEach
            }
            if (!inGroup) return@forEach
            val eq = line.indexOf('=')
            if (eq <= 0) return@forEach
            result[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
        }
        return if (found) result else null
    }

    /**
     * Picks `Key[lang_COUNTRY@MODIFIER]`, `Key[lang_COUNTRY]`, `Key[lang@MODIFIER]`,
     * `Key[lang]`, then `Key`, as the spec describes.
     */
    fun localized(keys: Map<String, String>, key: String, locale: Locale): String? {
        val lang = locale.language.takeIf { it.isNotEmpty() }
        val country = locale.country.takeIf { it.isNotEmpty() }
        val modifier = locale.variant.takeIf { it.isNotEmpty() }
        val candidates = buildList {
            if (lang != null) {
                if (country != null && modifier != null) add("${lang}_$country@$modifier")
                if (country != null) add("${lang}_$country")
                if (modifier != null) add("$lang@$modifier")
                add(lang)
            }
        }
        candidates.forEach { suffix ->
            keys["$key[$suffix]"]?.let(::unescape)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return keys[key]?.let(::unescape)?.takeIf { it.isNotBlank() }
    }

    fun bool(value: String?): Boolean = value?.trim()?.lowercase() == "true"

    /** `a;b\;c;` → [a, b;c]. */
    fun list(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        val items = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '\\' && i + 1 < value.length && value[i + 1] == ';' -> {
                    current.append(';'); i++
                }

                c == ';' -> {
                    items += current.toString(); current.clear()
                }

                else -> current.append(c)
            }
            i++
        }
        items += current.toString()
        return items.map { unescape(it).trim() }.filter { it.isNotEmpty() }
    }

    /** String-level escapes: `\s \n \t \r \\`. */
    fun unescape(value: String): String {
        if ('\\' !in value) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val n = value[i + 1]) {
                    's' -> out.append(' ')
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    '\\' -> out.append('\\')
                    else -> out.append('\\').append(n)
                }
                i += 2
            } else {
                out.append(c); i++
            }
        }
        return out.toString()
    }

    // ---- Exec ----

    /**
     * Splits an `Exec` value into arguments. Double-quoted arguments may escape `" ` $ \` with a
     * backslash. Field codes: `%f %F %u %U` (files/URLs: pepelauncher passes none) and the
     * deprecated `%d %D %n %N %v %m` are removed; `%i` becomes `--icon <Icon>`, `%c` the name,
     * `%k` the desktop file, `%%` a literal `%`.
     */
    fun parseExec(
        exec: String,
        name: String = "",
        icon: String? = null,
        desktopFile: String = "",
    ): List<String> {
        val args = mutableListOf<String>()
        tokenize(exec).forEach { token ->
            when (token.text) {
                "%f", "%F", "%u", "%U", "%d", "%D", "%n", "%N", "%v", "%m" ->
                    if (!token.quoted) return@forEach

                "%i" -> if (!token.quoted) {
                    if (icon != null) args += listOf("--icon", icon)
                    return@forEach
                }
            }
            val expanded = expandInline(token.text, name, desktopFile)
            if (expanded.isNotEmpty() || token.quoted) args += expanded
        }
        return args
    }

    private data class Token(val text: String, val quoted: Boolean)

    private fun tokenize(exec: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val current = StringBuilder()
        var inQuotes = false
        var quoted = false
        var started = false
        var i = 0
        fun flush() {
            if (started) tokens += Token(current.toString(), quoted)
            current.clear(); started = false; quoted = false
        }
        while (i < exec.length) {
            val c = exec[i]
            when {
                inQuotes && c == '\\' && i + 1 < exec.length && exec[i + 1] in "\"`$\\" -> {
                    current.append(exec[i + 1]); i++
                }

                c == '"' -> {
                    inQuotes = !inQuotes; quoted = true; started = true
                }

                !inQuotes && (c == ' ' || c == '\t') -> flush()
                else -> {
                    current.append(c); started = true
                }
            }
            i++
        }
        flush()
        return tokens
    }

    /** Field codes inside a larger argument (e.g. `--name=%c`); unknown ones are dropped. */
    private fun expandInline(arg: String, name: String, desktopFile: String): String {
        if ('%' !in arg) return arg
        val out = StringBuilder()
        var i = 0
        while (i < arg.length) {
            val c = arg[i]
            if (c == '%' && i + 1 < arg.length) {
                when (arg[i + 1]) {
                    '%' -> out.append('%')
                    'c' -> out.append(name)
                    'k' -> out.append(desktopFile)
                    else -> Unit
                }
                i += 2
            } else {
                out.append(c); i++
            }
        }
        return out.toString()
    }
}
