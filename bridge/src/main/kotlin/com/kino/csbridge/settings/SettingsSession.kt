// GPL-3.0 (this fork).
package com.kino.csbridge.settings

/**
 * The plugin whose settings screen is open, if any (at most one: a second screen is refused with `busy` while one is
 * open). [com.kino.csbridge.prefs.HostStore] gives a storage access with no live op and no plugin frame on the stack to
 * it, on the main thread only; [SettingsGuard] absorbs that plugin's throwables while it is open and reports them to
 * [Token.onPluginFailure].
 */
object SettingsSession {
    class Token internal constructor(val pluginId: String, internal val onPluginFailure: (Throwable) -> Unit) {
        /**
         * The plugin's classes as loaded for this screen, kept for the screen's whole life: an update or removal while it
         * closes unregisters the live ones, and the old instance's dialog code still runs until the screen is destroyed.
         */
        @Volatile var claim: com.kino.csbridge.plugins.PluginFrames.Claim? = null
            internal set
    }

    @Volatile private var current: Token? = null

    /** The new session, or null when one is already open. */
    @Synchronized fun begin(pluginId: String, onPluginFailure: (Throwable) -> Unit): Token? =
        if (current != null) null else Token(pluginId, onPluginFailure).also { current = it }

    /** Ends [token]'s session if it is still the current one. */
    @Synchronized fun end(token: Token) { if (current === token) current = null }

    fun pluginId(): String? = current?.pluginId

    internal fun current(): Token? = current

    /** Tests only: a test that stopped half-way never leaves an owner behind for the next one. */
    internal fun clearForTest() { current = null }
}
