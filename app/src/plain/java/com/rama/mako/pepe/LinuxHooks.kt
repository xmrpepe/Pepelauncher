package com.rama.mako.pepe

/**
 * "plain" flavor: every hook is a no-op so the launcher behaves exactly like mako.
 * Must keep the same public signatures as app/src/linux/.../LinuxHooks.kt.
 */
object LinuxHooks {
    const val ENABLED = false
}
