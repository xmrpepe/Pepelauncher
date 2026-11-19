package io.pepe.launcher.linux.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.pepe.launcher.linux.R

/**
 * Explains Android 12+'s phantom process killer, which kills Linux processes ("signal 9") when an
 * app has more than 32 child processes, and how to turn it off without root.
 */
object PhantomProcessGuide {

    fun isRelevant(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun show(activity: Activity) {
        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.pepe_phantom_title)
            .setMessage(R.string.pepe_phantom_text)
            .setPositiveButton(R.string.pepe_ok, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            builder.setNeutralButton(R.string.pepe_phantom_developer_options) { _, _ ->
                runCatching {
                    activity.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                }
            }
        } else {
            builder.setNeutralButton(R.string.pepe_phantom_app_settings) { _, _ ->
                runCatching {
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", activity.packageName, null)
                        )
                    )
                }
            }
        }
        builder.show()
    }
}
