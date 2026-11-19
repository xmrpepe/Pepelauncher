package io.pepe.launcher.linux

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * Binding /storage/emulated/0 to /sdcard only helps when the app may read it: on Android 11+
 * that takes "All files access" (MANAGE_EXTERNAL_STORAGE), granted by the user in Settings;
 * on Android 10 the legacy WRITE_EXTERNAL_STORAGE runtime permission.
 */
object Storage {
    fun hasAllFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    fun requestAllFilesAccess(activity: Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            activity.requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                requestCode
            )
            return
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", activity.packageName, null)
        )
        runCatching { activity.startActivity(intent) }.onFailure {
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }
}
