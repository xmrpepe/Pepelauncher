package com.rama.mako.managers

import android.os.UserHandle

/**
 * pepelauncher: an application inside the Linux system (one `.desktop` file) or the built-in
 * terminal. Kept in its own file so AppsProvider.kt only needs one-line hooks.
 *
 * `packageName` is `linux.<desktop id>`, so mako's per-app preferences (group, custom label)
 * work unchanged and never collide with Android packages.
 */
class LinuxEntry(
    val desktopId: String,
    label: String,
    userHandle: UserHandle,
) : AppsProvider.AppEntry("linux.$desktopId", label, userHandle, null)
