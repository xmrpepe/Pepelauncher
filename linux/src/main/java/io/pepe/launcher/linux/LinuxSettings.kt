package io.pepe.launcher.linux

import android.content.Context
import android.os.Build
import java.io.File
import java.util.Properties

/**
 * Linux settings. Stored as a small properties file rather than SharedPreferences because the
 * launcher process and the `:linux` process both read them, and SharedPreferences caches values
 * per process. Every read goes to disk; writes are atomic (write + rename).
 */
class LinuxSettings(context: Context) {

    private val file = File(context.applicationContext.filesDir, "linux-settings.properties")

    private fun load(): Properties = Properties().apply {
        runCatching { file.inputStream().use { load(it) } }
    }

    private fun edit(block: Properties.() -> Unit) = synchronized(LOCK) {
        val props = load().apply(block)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { props.store(it, "pepelauncher Linux settings") }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun string(key: String, default: String) = load().getProperty(key) ?: default
    private fun bool(key: String, default: Boolean) =
        load().getProperty(key)?.toBooleanStrictOrNull() ?: default

    private fun float(key: String, default: Float) =
        load().getProperty(key)?.toFloatOrNull() ?: default

    /** Base URL of an Arch Linux ARM mirror (the `os/` directory is appended). */
    var mirror: String
        get() = string(KEY_MIRROR, DEFAULT_MIRROR)
        set(value) = edit { setProperty(KEY_MIRROR, value.trim().trimEnd('/')) }

    /** Full tarball URL that overrides [mirror] (for testing or other distributions). */
    var customRootfsUrl: String
        get() = string(KEY_CUSTOM_URL, "")
        set(value) = edit { setProperty(KEY_CUSTOM_URL, value.trim()) }

    /** Optional expected SHA-256 of the tarball, checked in addition to the mirror's `.md5`. */
    var expectedSha256: String
        get() = string(KEY_SHA256, "")
        set(value) = edit { setProperty(KEY_SHA256, value.trim().lowercase()) }

    var bindSdcard: Boolean
        get() = bool(KEY_BIND_SDCARD, true)
        set(value) = edit { setProperty(KEY_BIND_SDCARD, value.toString()) }

    var shell: String
        get() = string(KEY_SHELL, "/bin/bash")
        set(value) = edit { setProperty(KEY_SHELL, value.trim()) }

    var swipeOpensTerminal: Boolean
        get() = bool(KEY_SWIPE_TERMINAL, true)
        set(value) = edit { setProperty(KEY_SWIPE_TERMINAL, value.toString()) }

    var terminalFontSize: Float
        get() = float(KEY_TERMINAL_FONT, 0f)
        set(value) = edit { setProperty(KEY_TERMINAL_FONT, value.toString()) }

    var displayScale: Float
        get() = float(KEY_DISPLAY_SCALE, 0f)
        set(value) = edit { setProperty(KEY_DISPLAY_SCALE, value.toString()) }

    /** true: touch acts as a mouse pointer; false: touch is forwarded as touch. */
    var touchAsMouse: Boolean
        get() = bool(KEY_TOUCH_AS_MOUSE, true)
        set(value) = edit { setProperty(KEY_TOUCH_AS_MOUSE, value.toString()) }

    var autoShowKeyboard: Boolean
        get() = bool(KEY_AUTO_KEYBOARD, false)
        set(value) = edit { setProperty(KEY_AUTO_KEYBOARD, value.toString()) }

    /** true: transient dialogs get their own Recents card; false: they attach to the parent. */
    var dialogsOwnTask: Boolean
        get() = bool(KEY_DIALOGS_OWN_TASK, true)
        set(value) = edit { setProperty(KEY_DIALOGS_OWN_TASK, value.toString()) }

    /** The tarball URL to download for this device. */
    fun rootfsUrl(): String {
        customRootfsUrl.takeIf { it.isNotEmpty() }?.let { return it }
        return "$mirror/os/${tarballName(Build.SUPPORTED_ABIS.toList())}"
    }

    companion object {
        private val LOCK = Any()

        const val DEFAULT_MIRROR = "https://fl.us.mirror.archlinuxarm.org"

        private const val KEY_MIRROR = "rootfs.mirror"
        private const val KEY_CUSTOM_URL = "rootfs.custom_url"
        private const val KEY_SHA256 = "rootfs.sha256"
        private const val KEY_BIND_SDCARD = "proot.bind_sdcard"
        private const val KEY_SHELL = "terminal.shell"
        private const val KEY_SWIPE_TERMINAL = "terminal.swipe_right"
        private const val KEY_TERMINAL_FONT = "terminal.font_size"
        private const val KEY_DISPLAY_SCALE = "display.scale"
        private const val KEY_TOUCH_AS_MOUSE = "display.touch_as_mouse"
        private const val KEY_AUTO_KEYBOARD = "display.auto_keyboard"
        private const val KEY_DIALOGS_OWN_TASK = "recents.dialogs_own_task"

        /** Arch Linux ARM publishes aarch64 and armv7 root filesystems. */
        fun tarballName(abis: List<String>): String =
            if (abis.firstOrNull() == "armeabi-v7a") "ArchLinuxARM-armv7-latest.tar.gz"
            else "ArchLinuxARM-aarch64-latest.tar.gz"
    }
}
