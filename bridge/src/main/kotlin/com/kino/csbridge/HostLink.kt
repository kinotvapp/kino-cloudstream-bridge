package com.kino.csbridge

import android.os.RemoteException
import com.kino.csbridge.contract.IHost

/** The IHost Kino handed over in `hello`; every network and storage need goes through it. */
object HostLink {
    @Volatile var host: IHost? = null

    /** Runs [block] against the host; null when there is no host or the other process died. */
    fun <T> call(block: (IHost) -> T): T? {
        val h = host ?: return null
        return try { block(h) } catch (_: RemoteException) { null }
    }
}
