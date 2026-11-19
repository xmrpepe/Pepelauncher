package io.pepe.launcher.linux.runtime

import java.io.File

/** A `-b host:guest` bind mount. */
data class Binding(val host: String, val guest: String) {
    fun toArg(): String = if (host == guest) host else "$host:$guest"
}

/**
 * Everything [ProotRunner] needs to know about the host side. Kept free of Android types so the
 * command building can be unit tested on the JVM.
 */
data class ProotConfig(
    /** `nativeLibraryDir/libproot.so`: the only place an app may execve() from (W^X). */
    val prootBinary: File,
    /** `nativeLibraryDir/libproot-loader.so`, exported as `PROOT_LOADER`. */
    val loader: File,
    /** `nativeLibraryDir/libproot-loader32.so` on arm64, exported as `PROOT_LOADER_32`. */
    val loader32: File?,
    /** Extracted Arch Linux ARM root filesystem. */
    val rootfs: File,
    /** Host directory that the guest sees as `/tmp` (also `/dev/shm`). */
    val guestTmp: File,
    /** Private scratch directory for proot itself (`PROOT_TMP_DIR`). Must not be [guestTmp]. */
    val prootTmp: File,
    /** Bind Android shared storage to `/sdcard` in the guest. */
    val bindSdcard: Boolean = true,
    val sdcardPath: String = "/storage/emulated/0",
    /**
     * Replacements for `/proc` files Android hides from apps, guest path → host file
     * (see [FakeProc]).
     */
    val fakeProc: Map<String, File> = emptyMap(),
    val extraBindings: List<Binding> = emptyList(),
    /** Emulate SysV IPC (shmget & co.), which Android kernels lack. Xwayland needs it. */
    val sysvipc: Boolean = true,
    /** Value reported by uname -r in the guest; null keeps the real kernel release. */
    val kernelRelease: String? = null,
)

/** argv and host environment for one proot process. */
data class ProotInvocation(val argv: List<String>, val hostEnv: Map<String, String>) {
    val executable: String get() = argv.first()
    fun hostEnvArray(): Array<String> = hostEnv.map { (k, v) -> "$k=$v" }.toTypedArray()
}

/**
 * Builds proot command lines. This is the single place that knows how pepelauncher enters the
 * Linux system; the terminal, setup, app launches and Xwayland all go through it.
 *
 * The result has the shape
 * `proot --kill-on-exit --link2symlink -0 -r <rootfs> -b /dev -b /proc -b /sys -b <tmp>:/tmp
 *  [-b /storage/emulated/0:/sdcard] -w /root /usr/bin/env -i HOME=/root … <command>`.
 */
object ProotRunner {

    const val GUEST_HOME = "/root"
    const val GUEST_TMP = "/tmp"
    const val GUEST_XDG_RUNTIME_DIR = "/tmp/xdg"
    const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/bin:/usr/sbin:/bin:/sbin"
    const val WAYLAND_DISPLAY = "wayland-0"
    const val X_DISPLAY = ":0"

    /** Environment every guest process starts with (`env -i` drops Android's). */
    fun defaultGuestEnv(): LinkedHashMap<String, String> = linkedMapOf(
        "HOME" to GUEST_HOME,
        "USER" to "root",
        "LOGNAME" to "root",
        "SHELL" to "/bin/bash",
        "TERM" to "xterm-256color",
        "COLORTERM" to "truecolor",
        "LANG" to "C.UTF-8",
        "PATH" to GUEST_PATH,
        "TMPDIR" to GUEST_TMP,
        "XDG_RUNTIME_DIR" to GUEST_XDG_RUNTIME_DIR,
        "WAYLAND_DISPLAY" to WAYLAND_DISPLAY,
        "DISPLAY" to X_DISPLAY,
        "GDK_BACKEND" to "wayland,x11",
        "QT_QPA_PLATFORM" to "wayland;xcb",
        "SDL_VIDEODRIVER" to "wayland",
        "MOZ_ENABLE_WAYLAND" to "1",
    )

    fun build(
        config: ProotConfig,
        command: List<String>,
        guestEnv: Map<String, String> = defaultGuestEnv(),
        workDir: String = GUEST_HOME,
    ): ProotInvocation {
        require(command.isNotEmpty()) { "command must not be empty" }

        val argv = mutableListOf(
            config.prootBinary.absolutePath,
            "--kill-on-exit",
            "--link2symlink",
            "-0",
        )
        if (config.sysvipc) argv += "--sysvipc"
        config.kernelRelease?.let { argv += "--kernel-release=$it" }
        argv += listOf("-r", config.rootfs.absolutePath)

        bindings(config).forEach { argv += listOf("-b", it.toArg()) }

        argv += listOf("-w", workDir)
        argv += listOf("/usr/bin/env", "-i")
        guestEnv.forEach { (key, value) ->
            require('=' !in key && key.isNotEmpty()) { "invalid environment name: $key" }
            argv += "$key=$value"
        }
        argv += command

        return ProotInvocation(argv, hostEnv(config))
    }

    /** A login shell, as used by the terminal. */
    fun loginShell(config: ProotConfig, shell: String = "/bin/bash"): ProotInvocation =
        build(config, listOf(shell, "-l"), defaultGuestEnv() + ("SHELL" to shell))

    /** Runs a shell snippet (`bash -c`) as root, as used by setup and package actions. */
    fun script(config: ProotConfig, script: String): ProotInvocation =
        build(config, listOf("/bin/bash", "-c", script))

    fun bindings(config: ProotConfig): List<Binding> {
        val tmp = config.guestTmp.absolutePath
        val list = mutableListOf(
            Binding("/dev", "/dev"),
            Binding("/proc", "/proc"),
            Binding("/sys", "/sys"),
            // As proot-distro does: stdio and fd links that Android's /dev lacks or hides.
            Binding("/proc/self/fd", "/dev/fd"),
            Binding("/proc/self/fd/0", "/dev/stdin"),
            Binding("/proc/self/fd/1", "/dev/stdout"),
            Binding("/proc/self/fd/2", "/dev/stderr"),
            Binding("/dev/urandom", "/dev/random"),
            Binding(tmp, GUEST_TMP),
            Binding(tmp, "/dev/shm"),
        )
        config.fakeProc.toSortedMap().forEach { (guest, host) ->
            list += Binding(host.absolutePath, guest)
        }
        if (config.bindSdcard) list += Binding(config.sdcardPath, "/sdcard")
        list += config.extraBindings
        return list
    }

    fun hostEnv(config: ProotConfig): Map<String, String> {
        val env = linkedMapOf(
            "PROOT_TMP_DIR" to config.prootTmp.absolutePath,
            "PROOT_LOADER" to config.loader.absolutePath,
        )
        config.loader32?.let { env["PROOT_LOADER_32"] = it.absolutePath }
        return env
    }

    /** Starts a non-interactive proot process (setup, pacman, app launches). */
    fun start(invocation: ProotInvocation, redirectErrorStream: Boolean = true): Process {
        val builder = ProcessBuilder(invocation.argv).redirectErrorStream(redirectErrorStream)
        val env = builder.environment()
        // proot runs on the host; keep Android's environment, but never leak a preload library.
        env.remove("LD_PRELOAD")
        env.putAll(invocation.hostEnv)
        return builder.start()
    }
}
