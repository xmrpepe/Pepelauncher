package io.pepe.launcher.linux.setup

/**
 * Text transformations for files pepelauncher writes into the guest during first boot.
 * Pure functions, so they are unit tested on the JVM.
 */
object GuestFiles {

    /**
     * pacman under proot: no Landlock sandbox and no privilege drop for downloads (neither works
     * without real root), and no disk space check (statfs results under proot are unreliable).
     */
    fun patchPacmanConf(original: String): String {
        val lines = original.lines().toMutableList()
        var inOptions = false
        var optionsIndex = -1
        var hasDisableSandbox = false

        for (i in lines.indices) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inOptions = trimmed == "[options]"
                if (inOptions) optionsIndex = i
                continue
            }
            if (!inOptions) continue
            val key = trimmed.removePrefix("#").trim().substringBefore("=").trim()
            when (key) {
                "DisableSandbox" -> {
                    lines[i] = "DisableSandbox"
                    hasDisableSandbox = true
                }

                "DownloadUser", "CheckSpace" ->
                    if (!trimmed.startsWith("#")) lines[i] = "#$trimmed"
            }
        }

        if (!hasDisableSandbox) {
            if (optionsIndex >= 0) {
                lines.add(optionsIndex + 1, "DisableSandbox")
            } else {
                lines.add(0, "[options]")
                lines.add(1, "DisableSandbox")
            }
        }
        return lines.joinToString("\n")
    }

    /** Enables a locale in /etc/locale.gen. */
    fun enableLocale(localeGen: String, locale: String = "en_US.UTF-8 UTF-8"): String {
        var found = false
        val lines = localeGen.lines().map { line ->
            val uncommented = line.removePrefix("#").trim()
            if (uncommented == locale) {
                found = true
                locale
            } else line
        }
        return if (found) lines.joinToString("\n") else (lines + locale).joinToString("\n")
    }

    /**
     * Arch's x86_64 bootstrap ships a mirrorlist with every server commented out (Arch Linux ARM's
     * has active ones). If no server is active, enable the first listed one so pacman can sync.
     */
    fun ensureActiveMirror(mirrorlist: String): String {
        val lines = mirrorlist.lines()
        if (lines.any { it.trim().startsWith("Server") }) return mirrorlist
        val first = lines.indexOfFirst { it.trim().removePrefix("#").trim().startsWith("Server") }
        if (first < 0) return mirrorlist
        return lines.toMutableList().apply {
            this[first] = this[first].trim().removePrefix("#").trim()
        }.joinToString("\n")
    }

    fun resolvConf(nameservers: List<String>): String {
        val servers = nameservers.filter { it.isNotBlank() }.ifEmpty { DEFAULT_NAMESERVERS }
        return servers.joinToString("") { "nameserver $it\n" }
    }

    fun hosts(hostname: String = "localhost"): String = buildString {
        append("127.0.0.1   localhost.localdomain localhost\n")
        append("::1         localhost.localdomain localhost ip6-localhost ip6-loopback\n")
        if (hostname != "localhost") append("127.0.1.1   $hostname\n")
    }

    /** Fires on any .desktop change so the launcher can rescan its Linux section. */
    fun pacmanHook(stampPath: String): String = """
        [Trigger]
        Type = Path
        Operation = Install
        Operation = Upgrade
        Operation = Remove
        Target = usr/share/applications/*.desktop

        [Action]
        Description = Updating the pepelauncher Linux section...
        When = PostTransaction
        Exec = /usr/bin/touch $stampPath
    """.trimIndent() + "\n"

    val DEFAULT_NAMESERVERS = listOf("1.1.1.1", "8.8.8.8")
}
