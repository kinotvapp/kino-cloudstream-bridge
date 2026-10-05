package com.kino.csbridge

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.kino.csbridge.contract.CsWire
import com.kino.csbridge.contract.IBridge
import com.kino.csbridge.contract.IHost
import com.kino.csbridge.contract.IReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

class BridgeService : Service() {
    /** Overridable for tests. */
    internal var callerCheck: (Int) -> Boolean = { uid -> CallerCheck.isKino(this, uid) }

    /** The op runner; tests swap it to simulate silent or throwing ops. */
    internal var runOp: suspend (CsWire.OpRequest, ParcelFileDescriptor?, (String) -> Boolean) -> String =
        { req, payload, emit -> Ops.run(this, req, payload, emit) }

    /** The pipe for a large result; tests swap it for one whose reader never reads. */
    internal var streamPipes: () -> StreamPipe = { ResultStream.pipe() }
    /** A streamed result gets what its op has left, within these bounds (PROTOCOL.md). Tests shrink them. */
    @Volatile internal var minStreamMs = 5_000L
    @Volatile internal var maxStreamMs = 60_000L

    /**
     * Stream writes, on threads of their own: a Kino that reads slowly (or not at all) holds one of these for up to
     * [maxStreamMs], never an op thread, so every other op's answer (a deadline's included) still goes out on time.
     * At most [STREAM_THREADS] streams at once; another large result is answered `streams_busy` instead.
     */
    private val streamExecutor = Executors.newFixedThreadPool(STREAM_THREADS) { r -> Thread(r, "bridge-stream").apply { isDaemon = true } }
    private val streamSlots = Semaphore(STREAM_THREADS)

    /** True when no stream write is running (tests wait for it). */
    internal fun streamsIdle(): Boolean = streamSlots.availablePermits() == STREAM_THREADS

    /**
     * Op bodies and replies, on this service's own threads (never Dispatchers.IO, which plugin code may fill). Plugin code
     * never runs here (it runs on PluginWork's pool or the registry's threads), so stuck plugins cannot take them.
     */
    private val executor = Executors.newFixedThreadPool(SERVICE_THREADS) { r -> Thread(r, "bridge-ops").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    /** Deadline timers: never blocked by anything, so a deadline always fires. */
    private val timers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val jobs = ConcurrentHashMap<String, Job>()

    internal val binder = object : IBridge.Stub() {
        override fun hello(protocolVersion: Int, host: IHost): String {
            if (!callerCheck(Binder.getCallingUid())) return CsWire.error(CsWire.Err.REFUSED, "caller")
            if (protocolVersion != CsWire.PROTOCOL_VERSION) {
                return JSONObject(CsWire.error(CsWire.Err.PROTOCOL, "bridge speaks ${CsWire.PROTOCOL_VERSION}"))
                    .put("protocolVersion", CsWire.PROTOCOL_VERSION).toString()
            }
            // Runs on this binder thread; a storage failure must not escape it (a checked exception would kill the process).
            val plugins = try { Ops.helloPlugins(this@BridgeService) } catch (_: Exception) {
                return CsWire.error(CsWire.Err.UNAVAILABLE, "plugin storage")
            }
            HostLink.host = host
            return JSONObject().put("protocolVersion", CsWire.PROTOCOL_VERSION)
                .put("bridgeVersionCode", BuildConfig.VERSION_CODE.toLong())
                .put("upstreamCommit", BuildConfig.UPSTREAM_COMMIT).put("kotlinVersion", KotlinVersion.CURRENT.toString())
                .put("plugins", CsWire.helloPluginsToJson(plugins)).toString()
        }

        override fun call(requestJson: String, payload: ParcelFileDescriptor?, reply: IReply) {
            if (!callerCheck(Binder.getCallingUid())) return refuse(reply, payload, CsWire.Err.REFUSED, "caller")
            val req = CsWire.parseOpRequest(requestJson)
            if (req == null || (req.op != CsWire.Op.LIST && !req.pluginId.startsWith(ID_PREFIX))) return refuse(reply, payload, CsWire.Err.BAD_REQUEST, "request")
            val ctx = CallContext(req.callId, req.pluginId, CallContext.deadlineFromNow(req.deadlineMs))
            synchronized(jobs) {
                if (jobs.containsKey(req.callId)) return refuse(reply, payload, CsWire.Err.BAD_REQUEST, "duplicate callId")
                // ATOMIC: the body always starts, so a cancel that races the launch still ends in one `done`.
                val job = scope.launch(ctx + CallContext.threadLocal.asContextElement(ctx), start = CoroutineStart.ATOMIC) {
                    // Our deadline is a timer that cancels OUR job, so a plugin's own withTimeout or stray
                    // CancellationException can never be mistaken for it (or for a cancel from Kino).
                    val self = coroutineContext.job
                    ctx.job = self // the HTTP shim stops waiting on Kino once this job ends
                    val fired = AtomicBoolean(false)
                    val timer = timers.launch { delay(req.deadlineMs); fired.set(true); self.cancel() }
                    val answer = try {
                        runOp(req, payload) { ev -> safeEvent(reply, ev) }
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
                    safeDone(reply, answer, ctx)
                }
                jobs[req.callId] = job
                job.invokeOnCompletion { jobs.remove(req.callId, job) }
            }
        }

        override fun cancel(callId: String) {
            if (!callerCheck(Binder.getCallingUid())) return
            jobs[callId]?.cancel()
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onUnbind(intent: Intent): Boolean {
        jobs.values.forEach { it.cancel() }
        HostLink.host = null
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        timers.cancel()
        executor.shutdown()
        streamExecutor.shutdownNow() // interrupts nothing stuck in a pipe write, but drops queued work at once
        super.onDestroy()
    }

    private fun refuse(r: IReply, payload: ParcelFileDescriptor?, code: String, msg: String) {
        runCatching { payload?.close() }
        safeInline(r, CsWire.error(code, msg))
    }

    private fun incompatible(e: Throwable) = CsWire.error(CsWire.Err.INCOMPATIBLE, describe(e))

    /**
     * Ends the op with exactly one `done` or `doneStream` (PROTOCOL.md): up to [CsWire.MAX_INLINE_RESULT_CHARS] inline,
     * up to [CsWire.MAX_STREAM_RESULT_BYTES] of UTF-8 through a pipe written on a stream thread within what the op has
     * left ([minStreamMs]..[maxStreamMs]), and anything larger as a short too_large `done`. With every stream thread
     * busy, a large result is answered `streams_busy` (before any doneStream).
     */
    private fun safeDone(r: IReply, json: String, ctx: CallContext) {
        if (json.length <= CsWire.MAX_INLINE_RESULT_CHARS) return safeInline(r, json)
        // UTF-8 never has fewer bytes than chars: a longer string is over the cap without encoding it.
        if (json.length > CsWire.MAX_STREAM_RESULT_BYTES) return safeInline(r, TOO_LARGE)
        val bytes = json.toByteArray(Charsets.UTF_8)
        if (bytes.size > CsWire.MAX_STREAM_RESULT_BYTES) return safeInline(r, TOO_LARGE)
        if (!streamSlots.tryAcquire()) return safeInline(r, CsWire.error(CsWire.Err.STREAMS_BUSY, "both result streams busy"))
        var handedOver = false
        try {
            val pipe = try { streamPipes() } catch (_: Exception) { return safeInline(r, CsWire.error(CsWire.Err.UNAVAILABLE, "result pipe")) }
            try {
                r.doneStream(pipe.readEnd)
            } catch (e: RemoteException) {
                runCatching { pipe.closeWrite(ResultStream.ERR_WRITE) }; runCatching { pipe.closeRead() }
                // Not delivered: the same single too_large fallback as a refused done (a dead peer makes it fail harmlessly).
                if (e !is DeadObjectException || r.asBinder().isBinderAlive) try { r.done(TOO_LARGE) } catch (_: RemoteException) {}
                return
            }
            val window = ResultStream.windowMs(ctx.remainingMs(), minStreamMs, maxStreamMs)
            try {
                streamExecutor.execute {
                    try { ResultStream.write(pipe, bytes, window) } finally { streamSlots.release() }
                }
                handedOver = true
            } catch (_: RejectedExecutionException) { // the service is shutting down: the reader sees a broken stream
                runCatching { pipe.closeWrite(ResultStream.ERR_WRITE) }; runCatching { pipe.closeRead() }
            }
        } finally {
            if (!handedOver) streamSlots.release()
        }
    }

    /**
     * One inline `done`: a Binder-rejected one becomes a short too_large error. A DeadObjectException on a live binder is
     * not a dead peer: libbinder reports an exhausted oneway buffer that way, so it gets the same single too_large retry
     * (on a truly dead binder the retry fails harmlessly).
     */
    private fun safeInline(r: IReply, json: String) {
        val out = if (json.length > CsWire.MAX_INLINE_RESULT_CHARS) TOO_LARGE else json
        try {
            r.done(out)
        } catch (_: DeadObjectException) {
            if (out !== TOO_LARGE && r.asBinder().isBinderAlive) try { r.done(TOO_LARGE) } catch (_: RemoteException) {}
        } catch (_: RemoteException) {
            if (out !== TOO_LARGE) try { r.done(TOO_LARGE) } catch (_: RemoteException) {}
        }
    }

    /** True when the event left this process; a failed one is not counted as sent. */
    private fun safeEvent(r: IReply, json: String): Boolean = try { r.event(json); true } catch (_: RemoteException) { false }

    companion object {
        private const val ID_PREFIX = "cloudstream-"

        private const val SERVICE_THREADS = 4
        private const val STREAM_THREADS = 2
        private val TOO_LARGE = CsWire.error(CsWire.Err.TOO_LARGE, "result too large")
        private val QUERY_OR_FRAGMENT = Regex("[?#]\\S*")

        /** `Class: message` with every URL query string and fragment removed (they can carry tokens). */
        internal fun describe(e: Throwable): String =
            "${e.javaClass.simpleName}: ${(e.message ?: "").replace(QUERY_OR_FRAGMENT, "").take(200)}"
    }
}
