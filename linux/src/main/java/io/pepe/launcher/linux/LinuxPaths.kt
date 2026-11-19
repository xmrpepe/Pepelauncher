package io.pepe.launcher.linux

import android.content.Context
import android.os.Build
import io.pepe.launcher.linux.runtime.FakeProc
import io.pepe.launcher.linux.runtime.ProotConfig
import java.io.File

/** Where everything lives on the Android side. */
class LinuxPaths(context: Context) {
    private val app = context.applicationContext

    val nativeLibDir = File(app.applicationInfo.nativeLibraryDir)
    val proot = File(nativeLibDir, "libproot.so")
    val loader = File(nativeLibDir, "libproot-loader.so")
    val loader32 = File(nativeLibDir, "libproot-loader32.so").takeIf { it.exists() }

    /** The Arch Linux ARM root filesystem. */
    val rootfs = File(app.filesDir, "arch")

    /** Extraction happens here and is renamed to [rootfs] once complete. */
    val rootfsPartial = File(app.filesDir, "arch.partial")

    /** Seen as /tmp (and /dev/shm) in the guest; the Wayland socket lives in tmp/xdg. */
    val guestTmp = File(app.filesDir, "tmp")
    val xdgRuntimeDir = File(guestTmp, "xdg")
    val prootTmp = File(app.cacheDir, "proot")
    val fakeProcDir = File(app.filesDir, "fakeproc")
    val downloadDir = File(app.cacheDir, "rootfs")

    /** pepelauncher's own state inside the rootfs (markers, pacman hook stamp). */
    val stateDir = File(rootfs, "var/lib/pepelauncher")
    val installedMarker = File(stateDir, "installed")
    val configuredMarker = File(stateDir, "configured")
    val appsStamp = File(stateDir, "apps.stamp")

    val isInstalled: Boolean get() = installedMarker.exists()
    val isReady: Boolean get() = configuredMarker.exists()

    fun prootConfig(settings: LinuxSettings = LinuxSettings(app)): ProotConfig {
        guestTmp.mkdirs()
        xdgRuntimeDir.mkdirs()
        xdgRuntimeDir.setReadable(false, false)
        xdgRuntimeDir.setReadable(true, true)
        xdgRuntimeDir.setWritable(true, true)
        xdgRuntimeDir.setExecutable(true, true)
        prootTmp.mkdirs()
        return ProotConfig(
            prootBinary = proot,
            loader = loader,
            loader32 = loader32,
            rootfs = rootfs,
            guestTmp = guestTmp,
            prootTmp = prootTmp,
            bindSdcard = settings.bindSdcard && Storage.hasAllFilesAccess(app),
            fakeProc = FakeProc.prepare(fakeProcDir),
        )
    }

    companion object {
        /** proot works from Android 7, but pepelauncher only supports and tests Android 10+. */
        val isSupportedDevice: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "armeabi-v7a" || it == "x86_64" }
    }
}
