package io.pepe.launcher.linux.runtime

import java.io.File

/**
 * Android (8+) hides several `/proc` files from apps, which breaks `top`, `free`, `uptime`,
 * `ps` and some package scripts in the guest. As proot-distro does, write plausible static
 * replacements and bind them over the originals — but only for files that really are unreadable.
 */
object FakeProc {

    val FILES: Map<String, String> = linkedMapOf(
        "/proc/loadavg" to "0.12 0.07 0.02 2/165 765\n",
        "/proc/stat" to """
            cpu  1957 0 2877 93280 262 342 254 87 0 0
            cpu0 31 0 226 12027 82 10 4 9 0 0
            cpu1 45 0 664 11144 21 263 233 12 0 0
            cpu2 494 0 537 11283 27 10 3 8 0 0
            cpu3 359 0 234 11723 24 26 5 7 0 0
            cpu4 295 0 268 11772 10 12 2 12 0 0
            cpu5 270 0 251 11833 15 3 1 10 0 0
            cpu6 430 0 520 11386 30 8 1 12 0 0
            cpu7 30 0 172 12108 50 8 1 13 0 0
            intr 127541 38 290 0 0 0 0 4 0 1 0 0 25329 258 0 5777 277 0 0 0 0 0 0 0 0 0 0 0 0
            ctxt 140223
            btime 1680020856
            processes 772
            procs_running 2
            procs_blocked 0
            softirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677
        """.trimIndent() + "\n",
        "/proc/uptime" to "124.08 932.80\n",
        "/proc/version" to "Linux version 6.2.1-pepelauncher (pepelauncher@android) " +
            "(gcc (GCC) 12.2.1, GNU ld (GNU Binutils) 2.40) #1 SMP PREEMPT_DYNAMIC\n",
        "/proc/vmstat" to """
            nr_free_pages 1743136
            nr_zone_inactive_anon 179281
            nr_zone_active_anon 7183
            nr_zone_inactive_file 22858
            nr_zone_active_file 51328
            nr_zone_unevictable 642
            nr_zone_write_pending 0
            nr_mlock 0
            nr_bounce 0
            nr_zspages 0
            nr_free_cma 0
            numa_hit 1259626
            numa_miss 0
            numa_foreign 0
            numa_interleave 720
            numa_local 1259626
            numa_other 0
            nr_inactive_anon 179281
            nr_active_anon 7183
            nr_inactive_file 22858
            nr_active_file 51328
            nr_unevictable 642
            nr_slab_reclaimable 8091
            nr_slab_unreclaimable 7804
            nr_isolated_anon 0
            nr_isolated_file 0
            pgpgin 198292
            pgpgout 6
            pswpin 0
            pswpout 0
            pgalloc_normal 1347736
            pgfree 3125463
            pgactivate 50740
            pgfault 919232
            pgmajfault 1094
        """.trimIndent() + "\n",
        "/proc/sys/kernel/cap_last_cap" to "40\n",
        "/proc/sys/fs/inotify/max_user_watches" to "4096\n",
    )

    /**
     * Writes replacements into [dir] for every file in [FILES] that [isReadable] rejects and
     * returns the bindings for [ProotConfig.fakeProc].
     */
    fun prepare(
        dir: File,
        isReadable: (String) -> Boolean = ::canRead,
    ): Map<String, File> {
        dir.mkdirs()
        val result = linkedMapOf<String, File>()
        FILES.forEach { (guestPath, content) ->
            if (isReadable(guestPath)) return@forEach
            val file = File(dir, guestPath.removePrefix("/").replace('/', '_'))
            if (!file.exists() || file.readText() != content) file.writeText(content)
            result[guestPath] = file
        }
        return result
    }

    /** `File.canRead()` is not enough: SELinux denials only show up when reading. */
    fun canRead(path: String): Boolean = runCatching {
        File(path).inputStream().use { it.read() }
        true
    }.getOrDefault(false)
}
