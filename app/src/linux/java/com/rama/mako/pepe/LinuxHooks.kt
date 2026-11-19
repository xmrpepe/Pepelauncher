package com.rama.mako.pepe

/**
 * The only place where upstream mako code reaches into pepelauncher's Linux features.
 * The "plain" flavor ships a no-op copy with the same signatures.
 */
object LinuxHooks {
    const val ENABLED = true
}
