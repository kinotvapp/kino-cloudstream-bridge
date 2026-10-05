package com.kino.csbridge.plugins

/** The only way a plugin id becomes a path. */
object SafeNames {
    private val ID = Regex("^cloudstream-[a-z0-9-]{1,40}$")

    /** `"$pluginId.cs3"` for an id Kino makes (`cloudstream-` + 1..40 of `[a-z0-9-]`), else null. */
    fun fileName(pluginId: String): String? = if (ID.matches(pluginId)) "$pluginId.cs3" else null
}
