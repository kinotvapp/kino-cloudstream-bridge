package com.kino.csbridge.net

import android.os.ParcelFileDescriptor
import android.util.Log
import com.kino.csbridge.CallContext
import com.kino.csbridge.HostLink
import com.kino.csbridge.contract.CsWire
import com.kino.csbridge.contract.IHost
import com.kino.csbridge.contract.IHttpReply
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.insecureApp
import com.lagradost.cloudstream3.UnsafeSSL
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSink
import okio.ForwardingSource
import okio.buffer
import okio.source
import org.json.JSONObject
import java.io.IOException
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Application interceptor that never calls `chain.proceed()`: every request leaves the bridge (which has no INTERNET
 * permission) as one oneway [IHost.request]. Kino applies its own policy (https on every hop, no LAN, valid TLS,
 * redirects followed hop by hop, 1 MB request / 5 MB response caps) and answers through [IHttpReply], then streams
 * the body into the sink pipe and closes it.
 *
 * A request is only served inside a live Kino call: its [CallContext] comes from the thread that created the OkHttp
 * call ([CallCapture], because NiceHttp enqueues on OkHttp's dispatcher threads) or from the current thread.
 */
class IpcInterceptor(private val host: () -> IHost? = { HostLink.host }) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val call = chain.call()
        val ctx = CallCapture.of(call) ?: CallContext.current() ?: throw IOException("bridge: request outside a Kino call")
        if (ctx.isOver()) throw IOException("bridge: the Kino call is over (cancelled or past its deadline)")
        val h = host() ?: throw IOException("bridge: Kino is not connected")

        val body = req.body?.let { readCapped(it) }
        val headers = (0 until req.headers.size).map { req.headers.name(it) to req.headers.value(it) }.toMutableList()
        req.body?.contentType()?.let { ct -> if (req.header("Content-Type") == null) headers += "Content-Type" to ct.toString() }
        val meta = CsWire.httpMeta(
            ctx.callId, ctx.pluginId, req.method, req.url.toString(), headers,
            followRedirects = RedirectFlag.of(chain), timeoutMs = timeoutMs(ctx, call), hasBody = body != null,
        )

        val sink = ParcelFileDescriptor.createReliablePipe() // [0] we read, [1] Kino writes
        val bodyPipe = body?.let { ParcelFileDescriptor.createReliablePipe() } // [0] Kino reads, [1] we write
        val waiter = ReplyWaiter()
        try {
            h.request(meta, bodyPipe?.get(0), sink[1], waiter)
        } catch (e: Exception) { // DeadObjectException / RemoteException, or a Binder-side runtime failure
            closeQuietly(sink[0]); closeQuietly(bodyPipe?.get(1))
            fail(req.url.host, "Kino unreachable (${e.javaClass.simpleName})", e)
        } finally {
            // Binder duplicated these for Kino; our copies must go or the reader never sees EOF.
            closeQuietly(sink[1]); closeQuietly(bodyPipe?.get(0))
        }
        if (body != null) startBodyWriter(bodyPipe!![1], body)

        val replyJson = try {
            awaitReply(waiter, ctx, call, h)
        } catch (e: IOException) {
            closeQuietly(sink[0]); closeQuietly(bodyPipe?.get(1))
            fail(req.url.host, e.message ?: "no reply", null)
        }
        return buildResponse(req, replyJson, sink[0])
    }

    /** Waits for Kino's reply, bounded by the call's deadline; gives up at once on cancellation or Kino's death. */
    private fun awaitReply(waiter: ReplyWaiter, ctx: CallContext, call: Call, h: IHost): String {
        val wake = ctx.job?.invokeOnCompletion { waiter.wake() }
        try {
            while (true) {
                waiter.reply?.let { return it }
                if (ctx.job?.isActive == false || call.isCanceled()) throw IOException("cancelled")
                val left = ctx.remainingMs()
                if (left == 0L) throw IOException("deadline reached waiting for Kino")
                if (!h.asBinder().isBinderAlive) throw IOException("Kino died")
                waiter.await(minOf(left, POLL_MS))
            }
        } finally {
            wake?.dispose()
        }
    }

    private fun buildResponse(req: okhttp3.Request, replyJson: String, read: ParcelFileDescriptor): Response {
        val r = runCatching { JSONObject(replyJson) }.getOrNull()
        CsWire.errorOf(replyJson)?.let { (code, msg) ->
            closeQuietly(read)
            fail(req.url.host, "Kino refused the request: $code ${msg.replace(QUERY_OR_FRAGMENT, "").take(120)}", null)
        }
        val status = r?.optInt("status", -1) ?: -1
        if (r == null || status !in 100..999) { closeQuietly(read); fail(req.url.host, "malformed reply", null) }
        val hb = Headers.Builder()
        CsWire.headersOf(r.optJSONArray("headers") ?: org.json.JSONArray()).forEach { (n, v) -> runCatching { hb.addUnsafeNonAscii(n, v) } }
        val respHeaders = hb.build()
        val finalUrl = r.optString("finalUrl").toHttpUrlOrNull()?.takeIf { it.isHttps } ?: req.url
        val source = CappedSource(ParcelFileDescriptor.AutoCloseInputStream(read).source()).buffer()
        val now = System.currentTimeMillis()
        return Response.Builder()
            .request(req.newBuilder().url(finalUrl).build())
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message(r.optString("message", ""))
            .headers(respHeaders)
            .body(source.asResponseBody(respHeaders["Content-Type"]?.toMediaTypeOrNull(), respHeaders["Content-Length"]?.toLongOrNull() ?: -1L))
            .sentRequestAtMillis(now)
            .receivedResponseAtMillis(now)
            .build()
    }

    /** Fails locally past [MAX_REQUEST_BODY], before anything reaches Kino. */
    private fun readCapped(body: RequestBody): ByteArray {
        val declared = runCatching { body.contentLength() }.getOrDefault(-1L)
        if (declared > MAX_REQUEST_BODY) throw IOException("bridge: request body over ${MAX_REQUEST_BODY / 1024} KB")
        val buf = Buffer()
        val capped = object : ForwardingSink(buf) {
            override fun write(source: Buffer, byteCount: Long) {
                if (buf.size + byteCount > MAX_REQUEST_BODY) throw IOException("bridge: request body over ${MAX_REQUEST_BODY / 1024} KB")
                super.write(source, byteCount)
            }
        }.buffer()
        body.writeTo(capped)
        capped.flush()
        return buf.readByteArray()
    }

    private fun startBodyWriter(write: ParcelFileDescriptor, bytes: ByteArray) {
        Thread({
            // Kino closing its read end (refusal, cancel) ends this write with an IOException; nothing to report.
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(bytes) } }
        }, "bridge-http-body").apply { isDaemon = true }.start()
    }

    private fun timeoutMs(ctx: CallContext, call: Call): Int {
        val callTimeoutMs = call.timeout().timeoutNanos() / 1_000_000
        val left = ctx.remainingMs()
        val ms = if (callTimeoutMs > 0) minOf(callTimeoutMs, left) else left
        return if (ms == Long.MAX_VALUE) 0 else ms.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    }

    /** Never the full URL: queries can carry tokens. Host only. */
    private fun fail(host: String, why: String, cause: Throwable?): Nothing {
        runCatching { Log.w(TAG, "http $host failed: $why") }
        throw IOException("bridge: $why", cause)
    }

    private class ReplyWaiter : IHttpReply.Stub() {
        private val latch = CountDownLatch(1)
        @Volatile var reply: String? = null
            private set

        override fun response(metaJson: String?) {
            if (reply == null && metaJson != null) { reply = metaJson; latch.countDown() }
        }

        fun wake() = latch.countDown()
        fun await(ms: Long) { latch.await(ms, TimeUnit.MILLISECONDS) }
    }

    /** Local guard on top of Kino's own cap: a body past [MAX_RESPONSE_BODY] ends in an IOException. */
    private class CappedSource(delegate: okio.Source) : ForwardingSource(delegate) {
        private var total = 0L
        override fun read(sink: Buffer, byteCount: Long): Long {
            val n = super.read(sink, byteCount)
            if (n > 0) {
                total += n
                if (total > MAX_RESPONSE_BODY) throw IOException("bridge: response body over ${MAX_RESPONSE_BODY / (1024 * 1024)} MB")
            }
            return n
        }
    }

    companion object {
        const val MAX_REQUEST_BODY = 1024 * 1024
        const val MAX_RESPONSE_BODY = 5 * 1024 * 1024
        private const val POLL_MS = 100L
        private const val TAG = "CsBridgeHttp"
        private val QUERY_OR_FRAGMENT = Regex("[?#]\\S*")

        private fun closeQuietly(pfd: ParcelFileDescriptor?) { runCatching { pfd?.close() } }
    }
}

/**
 * Remembers which Kino call created each OkHttp call. OkHttp builds the event listener on the thread that calls
 * `newCall()` (the plugin's, where [CallContext]'s thread local is set), while NiceHttp's `enqueue` runs the
 * interceptor on an OkHttp dispatcher thread that has no context. Weak keys: nothing outlives the call.
 */
internal object CallCapture : EventListener.Factory {
    private val calls = Collections.synchronizedMap(WeakHashMap<Call, CallContext>())

    override fun create(call: Call): EventListener {
        CallContext.current()?.let { calls[call] = it }
        return EventListener.NONE
    }

    fun of(call: Call): CallContext? = calls[call]
}

/** Puts [IpcInterceptor] under CloudStream's `app` and `insecureApp`: every client plugins derive keeps it. */
object NetworkShim {
    fun client(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(IpcInterceptor())
        .eventListenerFactory(CallCapture)
        .build()

    /**
     * `insecureApp` gets the same client: TLS is validated by Kino, so a plugin relying on a broken certificate
     * fails with a plain IOException.
     */
    @OptIn(UnsafeSSL::class)
    fun install() {
        val c = client()
        app.baseClient = c
        insecureApp.baseClient = c
    }
}
