// GPL-3.0 (this fork).
package com.kino.csbridge.desktop

import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.kino.csbridge.contract.IHost
import com.kino.csbridge.contract.IHttpReply
import com.kino.csbridge.contract.IReply
import com.kino.csbridge.contract.StdioFrame
import com.kino.csbridge.contract.StdioFrames.Kind
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Kino as the bridge sees it: [IHost] over the stdio link. Every network, storage and hidden-page need becomes a frame to
 * Kino; Kino's frames come back through [onFrame] (called on the reader thread, which never blocks here).
 */
class StdioHost(private val send: (StdioFrame) -> Unit) : IHost {
    private val ids = AtomicInteger(1)
    private val binder = object : Binder() {}

    private class Http(val reply: IHttpReply, val sink: ParcelFileDescriptor?)
    private class Storage { val latch = CountDownLatch(1); @Volatile var json: String? = null }

    private val http = ConcurrentHashMap<Int, Http>()
    private val webViews = ConcurrentHashMap<Int, IReply>()
    private val storages = ConcurrentHashMap<Int, Storage>()

    override fun asBinder(): IBinder = binder

    /** The link is down: every waiter gives up, and `isBinderAlive` turns false. */
    fun close() {
        binder.kill()
        http.values.forEach { runCatching { it.sink?.closeWithError("Kino died") } }
        http.clear()
        webViews.clear()
        storages.values.forEach { it.latch.countDown() }
        storages.clear()
    }

    override fun request(metaJson: String, requestBody: ParcelFileDescriptor?, responseSink: ParcelFileDescriptor?, reply: IHttpReply) {
        val id = ids.getAndIncrement()
        val body = requestBody?.let { readAll(it) } ?: ByteArray(0)
        http[id] = Http(reply, responseSink)
        try {
            send(StdioFrame(Kind.HTTP_REQUEST, id, metaJson, body))
        } catch (e: IOException) {
            http.remove(id)
            throw android.os.RemoteException(e.javaClass.simpleName)
        }
    }

    override fun webView(requestJson: String, reply: IReply) {
        val id = ids.getAndIncrement()
        webViews[id] = reply
        try {
            send(StdioFrame(Kind.WEBVIEW, id, requestJson))
        } catch (e: IOException) {
            webViews.remove(id)
            throw android.os.RemoteException(e.javaClass.simpleName)
        }
    }

    override fun storage(opJson: String): String? {
        val id = ids.getAndIncrement()
        val s = Storage()
        storages[id] = s
        try {
            send(StdioFrame(Kind.STORAGE, id, opJson))
        } catch (e: IOException) {
            storages.remove(id)
            throw android.os.RemoteException(e.javaClass.simpleName)
        }
        if (!s.latch.await(STORAGE_WAIT_MS, TimeUnit.MILLISECONDS)) storages.remove(id)
        return s.json
    }

    /** Handles one of Kino's frames for a host exchange; true when it was one. */
    fun onFrame(f: StdioFrame): Boolean {
        when (f.kind) {
            Kind.HTTP_META -> http[f.id]?.let { runCatching { it.reply.response(f.json) } }
            Kind.HTTP_BODY -> http[f.id]?.sink?.let { sink ->
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(sink).write(f.body)
                } catch (_: IOException) {
                    // the bridge stopped reading (the call ended): nothing to deliver
                }
            }
            Kind.HTTP_END -> http.remove(f.id)?.sink?.close()
            Kind.HTTP_FAIL -> http.remove(f.id)?.sink?.closeWithError(f.json.ifEmpty { "failed" })
            Kind.WEBVIEW_EVENT -> webViews[f.id]?.let { runCatching { it.event(f.json) } }
            Kind.WEBVIEW_DONE -> webViews.remove(f.id)?.let { runCatching { it.done(f.json) } }
            Kind.STORAGE_REPLY -> storages.remove(f.id)?.let { it.json = f.json; it.latch.countDown() }
            else -> return false
        }
        return true
    }

    private fun readAll(pfd: ParcelFileDescriptor): ByteArray =
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }

    private companion object {
        /** `IHost.storage` is synchronous, like the Binder call it replaces; Kino answers from its own store. */
        const val STORAGE_WAIT_MS = 30_000L
    }
}
