// GPL-3.0 (this fork).
package com.kino.csbridge.desktop

import android.content.Context
import android.os.ParcelFileDescriptor
import com.kino.csbridge.BuildConfig
import com.kino.csbridge.CallContext
import com.kino.csbridge.HostLink
import com.kino.csbridge.Ops
import com.kino.csbridge.contract.CsWire
import com.kino.csbridge.contract.StdioFrame
import com.kino.csbridge.contract.StdioFrames
import com.kino.csbridge.contract.StdioFrames.Kind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * The stdio twin of Android's BridgeService: the same call lifecycle (one `done` per call, a deadline timer that cancels
 * the call's own job, a duplicate `callId` refused), with frames in place of Binder. Plugin code never runs on this
 * service's threads (it runs on PluginWork's pool and the registry's threads), so a stuck plugin cannot stop the link.
 */
class StdioService(private val app: Context, private val input: InputStream, private val output: OutputStream) {
    private val writeLock = Any()
    private val host = StdioHost(::send)
    private val executor = Executors.newFixedThreadPool(SERVICE_THREADS) { r -> Thread(r, "bridge-ops").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val timers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = ConcurrentHashMap<String, Job>()

    private fun send(f: StdioFrame) {
        synchronized(writeLock) { StdioFrames.write(output, f) }
    }

    /** Reads Kino's frames until the link ends (Kino closed our stdin or died), then shuts everything down. */
    fun serve() {
        try {
            while (true) {
                val f = StdioFrames.read(input) ?: break
                when (f.kind) {
                    Kind.HELLO -> hello(f)
                    Kind.CALL -> call(f)
                    Kind.CANCEL -> cancel(f)
                    else -> if (!host.onFrame(f)) System.err.println("bridge: unknown frame kind ${f.kind}")
                }
            }
        } catch (e: IOException) {
            System.err.println("bridge: link failed: ${e.javaClass.simpleName}")
        } finally {
            HostLink.host = null
            host.close()
            jobs.values.forEach { it.cancel() }
            scope.cancel()
            timers.cancel()
            executor.shutdown()
        }
    }

    private fun hello(f: StdioFrame) {
        val v = runCatching { JSONObject(f.json).optInt("v", -1) }.getOrDefault(-1)
        val answer = if (v != CsWire.PROTOCOL_VERSION) {
            JSONObject(CsWire.error(CsWire.Err.PROTOCOL, "bridge speaks ${CsWire.PROTOCOL_VERSION}"))
                .put("protocolVersion", CsWire.PROTOCOL_VERSION).toString()
        } else {
            val plugins = try { Ops.helloPlugins(app) } catch (_: Exception) {
                send(StdioFrame(Kind.HELLO_REPLY, f.id, CsWire.error(CsWire.Err.UNAVAILABLE, "plugin storage")))
                return
            }
            HostLink.host = host
            JSONObject().put("protocolVersion", CsWire.PROTOCOL_VERSION)
                .put("bridgeVersionCode", BuildConfig.VERSION_CODE.toLong())
                .put("upstreamCommit", BuildConfig.UPSTREAM_COMMIT).put("kotlinVersion", KotlinVersion.CURRENT.toString())
                .put("plugins", CsWire.helloPluginsToJson(plugins)).toString()
        }
        send(StdioFrame(Kind.HELLO_REPLY, f.id, answer))
    }

    private fun call(f: StdioFrame) {
        val id = f.id
        val payload = if (f.body.isNotEmpty()) ParcelFileDescriptor(f.body) else null
        fun reply(json: String) = runCatching { send(StdioFrame(Kind.DONE, id, json)) }
        val req = CsWire.parseOpRequest(f.json)
        if (req == null || (req.op != CsWire.Op.LIST && !req.pluginId.startsWith(ID_PREFIX))) {
            payload?.close()
            reply(CsWire.error(CsWire.Err.BAD_REQUEST, "request"))
            return
        }
        val ctx = CallContext(req.callId, req.pluginId, CallContext.deadlineFromNow(req.deadlineMs))
        synchronized(jobs) {
            if (jobs.containsKey(req.callId)) {
                payload?.close()
                reply(CsWire.error(CsWire.Err.BAD_REQUEST, "duplicate callId"))
                return
            }
            // ATOMIC: the body always starts, so a cancel that races the launch still ends in one `done`.
            val job = scope.launch(ctx + CallContext.threadLocal.asContextElement(ctx), start = CoroutineStart.ATOMIC) {
                // The deadline is a timer that cancels OUR job, so a plugin's own withTimeout or stray
                // CancellationException can never be mistaken for it (or for a cancel from Kino).
                val self = coroutineContext.job
                ctx.job = self // the HTTP shim stops waiting on Kino once this job ends
                val fired = AtomicBoolean(false)
                val timer = timers.launch { delay(req.deadlineMs); fired.set(true); self.cancel() }
                val answer = try {
                    Ops.run(app, req, payload) { ev -> runCatching { send(StdioFrame(Kind.EVENT, id, ev)) }.isSuccess }
                } catch (e: CancellationException) {
                    when {
                        fired.get() -> CsWire.error(CsWire.Err.TIMEOUT, "deadline")
                        self.isCancelled -> CsWire.error(CsWire.Err.CANCELLED, "cancelled")
                        else -> CsWire.error(CsWire.Err.PLUGIN, describe(e))
                    }
                } catch (e: NoSuchMethodError) { incompatible(e)
                } catch (e: NoSuchFieldError) { incompatible(e)
                } catch (e: NoClassDefFoundError) { incompatible(e)
                } catch (e: AbstractMethodError) { incompatible(e)
                } catch (e: IncompatibleClassChangeError) { incompatible(e)
                } catch (e: Throwable) {
                    CsWire.error(CsWire.Err.PLUGIN, describe(e))
                } finally {
                    timer.cancel()
                    runCatching { payload?.close() }
                }
                reply(sized(answer))
            }
            jobs[req.callId] = job
            job.invokeOnCompletion { jobs.remove(req.callId, job) }
        }
    }

    private fun cancel(f: StdioFrame) {
        val callId = runCatching { JSONObject(f.json).optString("callId") }.getOrDefault("")
        jobs[callId]?.cancel()
    }

    /** A result over the streamed-result cap is `too_large`, as on Android (there is no pipe to stream through here). */
    private fun sized(json: String): String =
        if (json.length > CsWire.MAX_STREAM_RESULT_BYTES || json.toByteArray(Charsets.UTF_8).size > CsWire.MAX_STREAM_RESULT_BYTES) TOO_LARGE
        else json

    private fun incompatible(e: Throwable) = CsWire.error(CsWire.Err.INCOMPATIBLE, describe(e))

    companion object {
        private const val ID_PREFIX = "cloudstream-"
        private const val SERVICE_THREADS = 4
        private val TOO_LARGE = CsWire.error(CsWire.Err.TOO_LARGE, "result too large")
        private val QUERY_OR_FRAGMENT = Regex("[?#]\\S*")

        /** `Class: message` with every URL query string and fragment removed (they can carry tokens). */
        internal fun describe(e: Throwable): String =
            "${e.javaClass.simpleName}: ${(e.message ?: "").replace(QUERY_OR_FRAGMENT, "").take(200)}"
    }
}
