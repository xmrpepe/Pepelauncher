package io.pepe.launcher.linux.apps

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/** Invisible trampoline behind pinned shortcuts: launches a Linux app and finishes. */
class LinuxLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.schemeSpecificPart?.let { id -> LinuxAppActions.launch(this, id) }
        finish()
    }

    companion object {
        fun intent(context: Context, id: String): Intent =
            Intent(context, LinuxLaunchActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.fromParts("pepe-linux-app", id, null))
    }
}
