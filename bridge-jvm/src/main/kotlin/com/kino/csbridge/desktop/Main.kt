// GPL-3.0 (this fork). Entry point of the desktop bridge: Kino starts this as a child process and talks to it over stdio.
package com.kino.csbridge.desktop

import android.content.Context
import com.kino.csbridge.Ops
import com.kino.csbridge.initForBridge
import com.kino.csbridge.net.NetworkShim
import com.kino.csbridge.plugins.PluginRegistry
import com.lagradost.cloudstream3.CloudStreamApp
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.system.exitProcess

/** The platform's own classes (the UI a plugin's settings dialog is made of) are never linked on the desktop: not checked. */
internal fun isPlatformOwner(desc: String): Boolean =
    desc.startsWith("Landroid/") || desc.startsWith("Landroidx/") || desc.startsWith("Ldalvik/") ||
        desc.startsWith("Lcom/google/android/") || desc.startsWith("Lorg/xmlpull/")

fun main(args: Array<String>) {
    val dataDir = args.firstOrNull { it.startsWith("--data-dir=") }?.removePrefix("--data-dir=")
        ?: System.getenv("KINO_BRIDGE_DATA")
        ?: run { System.err.println("usage: bridge --data-dir=<folder>"); exitProcess(2) }
    // stdout carries the protocol: anything a plugin prints goes to stderr instead.
    val protocolOut = FileOutputStream(FileDescriptor.out).buffered(64 * 1024)
    System.setOut(System.err)

    Context.setRoot(File(dataDir))
    val app = DesktopApp()
    Ops.instance = PluginRegistry(app, platformOwner = ::isPlatformOwner)
    com.kino.csbridge.ops.Json.initIdPrefixes()
    CloudStreamApp.initForBridge(app)
    NetworkShim.install()

    StdioService(app, FileInputStream(FileDescriptor.`in`).buffered(64 * 1024), protocolOut).serve()
    exitProcess(0)
}
