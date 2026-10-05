package com.kino.csbridge

import android.content.Context
import android.os.ParcelFileDescriptor
import com.kino.csbridge.contract.CsWire

/**
 * Dispatch of the wire ops. Tasks 5-6 replace the bodies (install/remove/list and the provider ops backed by the
 * plugin registry); the service only depends on [run] and [helloPlugins].
 */
object Ops {
    /** Plugins the bridge holds, for `hello`. Only entries that are fully filled (id + sha256 of the stored .cs3). */
    fun helloPlugins(context: Context): List<CsWire.HelloPlugin> = emptyList()

    /** Runs one op and returns its `done` JSON. [emit] sends `event` JSON. May throw; the service maps throwables to errors. */
    suspend fun run(context: Context, req: CsWire.OpRequest, payload: ParcelFileDescriptor?, emit: (String) -> Unit): String =
        when (req.op) {
            CsWire.Op.LIST -> org.json.JSONObject().put("plugins", CsWire.helloPluginsToJson(helloPlugins(context))).toString()
            CsWire.Op.INSTALL -> CsWire.error(CsWire.Err.UNAVAILABLE, "install")
            CsWire.Op.REMOVE -> org.json.JSONObject().put("ok", true).toString()
            else -> CsWire.error(CsWire.Err.NOT_INSTALLED, "plugin")
        }
}
