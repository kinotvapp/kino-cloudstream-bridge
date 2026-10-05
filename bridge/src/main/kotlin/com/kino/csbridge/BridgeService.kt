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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

class BridgeService : Service() {
    /** Overridable for tests. */
    internal var callerCheck: (Int) -> Boolean = { uid -> CallerCheck.isKino(this, uid) }

    /** The op runner; tests swap it to simulate silent or throwing ops. */
    internal var runOp: suspend (CsWire.OpRequest, ParcelFileDescriptor?, (String) -> Unit) -> String =
        { req, payload, emit -> Ops.run(this, req, payload, emit) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val jobs = ConcurrentHashMap<String, Job>()

    internal val binder = object : IBridge.Stub() {
        override fun hello(protocolVersion: Int, host: IHost): String {
            if (!callerCheck(Binder.getCallingUid())) return CsWire.error(CsWire.Err.REFUSED, "caller")
            if (protocolVersion != CsWire.PROTOCOL_VERSION) {
                return JSONObject(CsWire.error(CsWire.Err.PROTOCOL, "bridge speaks ${CsWire.PROTOCOL_VERSION}"))
                    .put("protocolVersion", CsWire.PROTOCOL_VERSION).toString()
            }
            HostLink.host = host
            return JSONObject().put("protocolVersion", CsWire.PROTOCOL_VERSION)
                .put("bridgeVersionCode", BuildConfig.VERSION_CODE.toLong())
                .put("upstreamCommit", BuildConfig.UPSTREAM_COMMIT).put("kotlinVersion", KotlinVersion.CURRENT.toString())
                .put("plugins", CsWire.helloPluginsToJson(Ops.helloPlugins(this@BridgeService))).toString()
        }

        override fun call(requestJson: String, payload: ParcelFileDescriptor?, reply: IReply) {
            if (!callerCheck(Binder.getCallingUid())) return refuse(reply, payload, CsWire.Err.REFUSED, "caller")
            val req = CsWire.parseOpRequest(requestJson)
            if (req == null || (req.op != CsWire.Op.LIST && !req.pluginId.startsWith(ID_PREFIX))) return refuse(reply, payload, CsWire.Err.BAD_REQUEST, "request")
            val ctx = CallContext(req.callId, req.pluginId)
            synchronized(jobs) {
                if (jobs.containsKey(req.callId)) return refuse(reply, payload, CsWire.Err.BAD_REQUEST, "duplicate callId")
                // ATOMIC: the body always starts, so a cancel that races the launch still ends in one `done`.
                val job = scope.launch(ctx + CallContext.threadLocal.asContextElement(ctx), start = CoroutineStart.ATOMIC) {
                    // Our deadline is a timer that cancels OUR job, so a plugin's own withTimeout or stray
                    // CancellationException can never be mistaken for it (or for a cancel from Kino).
                    val self = coroutineContext.job
                    val fired = AtomicBoolean(false)
                    val timer = scope.launch { delay(req.deadlineMs); fired.set(true); self.cancel() }
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
                    safeDone(reply, answer)
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
        super.onDestroy()
    }

    private fun refuse(r: IReply, payload: ParcelFileDescriptor?, code: String, msg: String) {
        runCatching { payload?.close() }
        safeDone(r, CsWire.error(code, msg))
    }

    private fun incompatible(e: Throwable) = CsWire.error(CsWire.Err.INCOMPATIBLE, describe(e))

    /** Exactly one `done` reaches Kino: an oversized or Binder-rejected result becomes a short too_large error. */
    private fun safeDone(r: IReply, json: String) {
        val out = if (json.length > MAX_RESULT_CHARS) TOO_LARGE else json
        try {
            r.done(out)
        } catch (_: DeadObjectException) {
        } catch (_: RemoteException) {
            if (out !== TOO_LARGE) try { r.done(TOO_LARGE) } catch (_: RemoteException) {}
        }
    }
    private fun safeEvent(r: IReply, json: String) { try { r.event(json) } catch (_: RemoteException) {} }

    companion object {
        private const val ID_PREFIX = "cloudstream-"

        private const val MAX_RESULT_CHARS = 512 * 1024
        private val TOO_LARGE = CsWire.error(CsWire.Err.TOO_LARGE, "result too large")
        private val QUERY_OR_FRAGMENT = Regex("[?#]\\S*")

        /** `Class: message` with every URL query string and fragment removed (they can carry tokens). */
        internal fun describe(e: Throwable): String =
            "${e.javaClass.simpleName}: ${(e.message ?: "").replace(QUERY_OR_FRAGMENT, "").take(200)}"
    }
}
