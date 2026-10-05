package com.kino.csbridge.net

import android.os.ParcelFileDescriptor
import android.util.Log
import com.kino.csbridge.CallContext
import com.kino.csbridge.HostLink
import com.kino.csbridge.contract.CsWire
import com.kino.csbridge.contract.IHost
import com.kino.csbridge.contract.IHttpReply
import com.lagradost.cloudstream3.UnsafeSSL
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.insecureApp
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSink
import okio.ForwardingSource
import okio.buffer
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Application interceptor that never lets OkHttp touch the network: every request leaves the bridge (which has no
 * INTERNET permission) as one oneway [IHost.request]. Kino applies its own policy (https on every hop, no LAN, valid
 * TLS, redirects followed hop by hop, 1 MB request / 5 MB response caps), answers through [IHttpReply], then streams
 * the body into the sink pipe and closes it (with an error status when the body failed).
 *
 * A request is only served inside a live Kino call. Its [CallContext] comes from the thread that created the OkHttp
 * call ([CallCapture], because NiceHttp enqueues on OkHttp's dispatcher threads) or from the current thread.
 *
 * Plugin interceptors (decision record, Task 4 review B4). NiceHttp adds a request's `interceptor =` (CloudflareKiller,
 * header and cookie interceptors) after this one, and plugins add their own to derived clients. Since OkHttp's chain
 * would reach them only through `proceed()`, which would go to the network, this interceptor runs them itself:
 * every application interceptor after it in the calling client's list, then the client's network interceptors,
 * through a [BridgeChain] whose last `proceed(request)` is one IPC with that request. Each `proceed` is its own IPC,
 * so a retrying interceptor works. Network interceptors run too, because their usual job (headers, response
 * rewriting) still makes sense on Kino's response. They see `connection() == null`, and the redirects Kino followed
 * happen inside the one exchange. While they run, the [CallContext] thread local is set on this thread (and restored
 * after), so calls they start themselves (CloudflareKiller's `app.baseClient.newCall()`) belong to the same Kino
 * call. The client is read through [RedirectFlag.clientOf]. If that read fails, only the IPC runs.
 */
class IpcInterceptor(private val host: () -> IHost? = { HostLink.host }) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val call = chain.call()
        val ctx = CallCapture.of(call) ?: CallContext.current() ?: throw IOException("bridge: request outside a Kino call")
        val client = RedirectFlag.clientOf(call)
        val follow = client?.followRedirects ?: true
        val downstream = client?.let { downstreamOf(it) }.orEmpty()
        val previous = CallContext.threadLocal.get()
        CallContext.threadLocal.set(ctx)
        try {
            return BridgeChain(
                downstream, 0, chain.request(), call, ctx, follow,
                chain.connectTimeoutMillis(), chain.readTimeoutMillis(), chain.writeTimeoutMillis(), explicitMs = 0,
            ).proceed(chain.request())
        } finally {
            CallContext.threadLocal.set(previous)
        }
    }

    /** Application interceptors after this one (found by identity), then the network interceptors. */
    private fun downstreamOf(client: OkHttpClient): List<Interceptor> {
        val appInterceptors = client.interceptors
        val i = appInterceptors.indexOfFirst { it === this }
        if (i < 0) return emptyList()
        return appInterceptors.subList(i + 1, appInterceptors.size) + client.networkInterceptors
    }

    /**
     * The chain plugin interceptors see. `with*Timeout` values set by an interceptor bound the request's timeout
     * ([explicitMs], 0 = none set); the client's defaults are only reported, never sent as a total timeout.
     */
    private inner class BridgeChain(
        private val interceptors: List<Interceptor>, private val index: Int, private val request: Request,
        private val call: Call, private val ctx: CallContext, private val follow: Boolean,
        private val connectMs: Int, private val readMs: Int, private val writeMs: Int, private val explicitMs: Int,
    ) : Interceptor.Chain {
        override fun request(): Request = request
        override fun call(): Call = call
        override fun connection(): Connection? = null
        override fun connectTimeoutMillis(): Int = connectMs
        override fun readTimeoutMillis(): Int = readMs
        override fun writeTimeoutMillis(): Int = writeMs

        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain =
            ms(timeout, unit).let { copy(connect = it, explicit = tighter(it)) }
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain =
            ms(timeout, unit).let { copy(read = it, explicit = tighter(it)) }
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain =
            ms(timeout, unit).let { copy(write = it, explicit = tighter(it)) }

        override fun proceed(request: Request): Response =
            if (index < interceptors.size) {
                val next = BridgeChain(interceptors, index + 1, request, call, ctx, follow, connectMs, readMs, writeMs, explicitMs)
                interceptors[index].intercept(next)
            } else {
                exchange(request, call, ctx, follow, explicitMs)
            }

        private fun ms(timeout: Int, unit: TimeUnit): Int = unit.toMillis(timeout.toLong()).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

        /** The smallest positive timeout an interceptor set so far (0 = none). */
        private fun tighter(ms: Int): Int = listOf(explicitMs, ms).filter { it > 0 }.minOrNull() ?: 0

        private fun copy(connect: Int = connectMs, read: Int = readMs, write: Int = writeMs, explicit: Int): BridgeChain =
            BridgeChain(interceptors, index, request, call, ctx, follow, connect, read, write, explicit)
    }

    /** One IPC: [req] to Kino, its answer back as an OkHttp response streaming from the sink pipe. */
    private fun exchange(req: Request, call: Call, ctx: CallContext, follow: Boolean, explicitMs: Int): Response {
        if (ctx.isOver()) throw IOException("bridge: the Kino call is over (cancelled or past its deadline)")
        val h = host() ?: throw IOException("bridge: Kino is not connected")

        val body = req.body?.let { readCapped(it) }
        val headers = (0 until req.headers.size).map { req.headers.name(it) to req.headers.value(it) }.toMutableList()
        req.body?.contentType()?.let { ct -> if (req.header("Content-Type") == null) headers += "Content-Type" to ct.toString() }
        val meta = CsWire.httpMeta(
            ctx.callId, ctx.pluginId, req.method, req.url.toString(), headers,
            followRedirects = follow, timeoutMs = timeoutMs(ctx, call, explicitMs), hasBody = body != null,
        )

        val sink = ParcelFileDescriptor.createReliablePipe() // [0] we read, [1] Kino writes
        val bodyPipe = body?.let { ParcelFileDescriptor.createReliablePipe() } // [0] Kino reads, [1] the writer thread
        // The writer thread owns bodyPipe[1] alone: it ends when the bytes are written or every read end is gone.
        if (body != null) startBodyWriter(bodyPipe!![1], body)
        val waiter = ReplyWaiter()
        try {
            h.request(meta, bodyPipe?.get(0), sink[1], waiter)
        } catch (e: Exception) { // DeadObjectException / RemoteException, or a Binder-side runtime failure
            closeQuietly(sink[0])
            fail(req.url, "Kino unreachable (${e.javaClass.simpleName})", e)
        } finally {
            // Binder duplicated these for Kino; our copies must go or the reader never sees EOF.
            closeQuietly(sink[1]); closeQuietly(bodyPipe?.get(0))
        }

        val replyJson = try {
            awaitReply(waiter, ctx, call, h)
        } catch (e: IOException) {
            closeQuietly(sink[0])
            fail(req.url, e.message ?: "no reply", null)
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

    private fun buildResponse(req: Request, replyJson: String, read: ParcelFileDescriptor): Response {
        val r = runCatching { JSONObject(replyJson) }.getOrNull()
        CsWire.errorOf(replyJson)?.let { (code, msg) ->
            closeQuietly(read)
            fail(req.url, "Kino refused the request: $code ${scrubUrls(msg).take(160)}", null)
        }
        val status = r?.optInt("status", -1) ?: -1
        if (r == null || status !in 100..999) { closeQuietly(read); fail(req.url, "malformed reply", null) }
        val hb = Headers.Builder()
        var dropped = 0
        CsWire.headersOf(r.optJSONArray("headers") ?: JSONArray()).forEach { (n, v) ->
            if (runCatching { hb.addUnsafeNonAscii(n, v) }.isFailure) dropped++
        }
        if (dropped > 0) log("http ${req.url.host}: dropped $dropped invalid response header(s)")
        val respHeaders = hb.build()
        val finalUrl = r.optString("finalUrl").toHttpUrlOrNull()?.takeIf { it.isHttps } ?: req.url
        val source = CheckedSource(read).buffer()
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

    private fun timeoutMs(ctx: CallContext, call: Call, explicitMs: Int): Int {
        var ms = ctx.remainingMs()
        val callTimeoutMs = call.timeout().timeoutNanos() / 1_000_000
        if (callTimeoutMs > 0) ms = minOf(ms, callTimeoutMs)
        if (explicitMs > 0) ms = minOf(ms, explicitMs.toLong())
        return if (ms == Long.MAX_VALUE) 0 else ms.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    }

    /** Logs host + first path segment only (queries can carry tokens), then throws. */
    private fun fail(url: okhttp3.HttpUrl, why: String, cause: Throwable?): Nothing {
        val where = url.host + (url.pathSegments.firstOrNull()?.takeIf { it.isNotEmpty() }?.let { "/$it" } ?: "")
        log("http $where failed: $why")
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

    /**
     * The response body from our read end of the sink pipe. At EOF it asks the pipe how Kino closed it: a
     * `closeWithError` (Kino's 5 MB cap, a timeout, a reset) or Kino's death is an IOException, never a silently
     * truncated success. A local guard on top of Kino's cap stops past [MAX_RESPONSE_BODY].
     */
    internal class CheckedSource(private val pfd: ParcelFileDescriptor) :
        ForwardingSource(ParcelFileDescriptor.AutoCloseInputStream(pfd).source()) {
        private var total = 0L
        override fun read(sink: Buffer, byteCount: Long): Long {
            val n = super.read(sink, byteCount)
            if (n == -1L) pfd.checkError()
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
        private val URL = Regex("(https?://[^/\\s?#]+)(/[^/\\s?#]*)?\\S*")

        /** Every URL in [s] cut to scheme + host + first path segment. */
        internal fun scrubUrls(s: String): String = s.replace(URL) { m -> m.groupValues[1] + m.groupValues[2] }

        private fun log(msg: String) { runCatching { Log.w(TAG, msg) } }
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
