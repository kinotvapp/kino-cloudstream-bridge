package com.kino.csbridge

import android.app.Application
import com.kino.csbridge.net.NetworkShim

class BridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before any plugin loads: every request CloudStream code makes goes through Kino's IHost.
        NetworkShim.install()
    }
}
