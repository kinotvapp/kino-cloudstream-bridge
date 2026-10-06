// GPL-3.0 (this fork).
package com.kino.csbridge.settings

/**
 * The plugin whose settings screen is open, if any. [com.kino.csbridge.prefs.HostStore] attributes a storage access
 * with no live op ([com.kino.csbridge.CallContext]) to it, ahead of the single-installed-plugin fallback: a settings
 * screen runs on the main thread and on whatever threads the plugin starts from it, none of which carries an op, and
 * with two plugins installed its writes would otherwise stay in this process's memory. A live op always wins (its
 * plugin is known for certain). One screen at a time: a newer one replaces the owner, and ending an older one never
 * clears the newer one's.
 */
object SettingsSession {
    class Token internal constructor(val pluginId: String)

    @Volatile private var current: Token? = null

    fun begin(pluginId: String): Token = Token(pluginId).also { current = it }

    /** Ends [token]'s session if it is still the current one. */
    @Synchronized fun end(token: Token) { if (current === token) current = null }

    fun pluginId(): String? = current?.pluginId

    /** Tests only: a test that stopped half-way never leaves an owner behind for the next one. */
    internal fun clearForTest() { current = null }
}
