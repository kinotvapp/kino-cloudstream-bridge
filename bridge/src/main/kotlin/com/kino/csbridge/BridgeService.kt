package com.kino.csbridge

import android.app.Service
import android.content.Intent
import android.os.Binder
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

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
                .put("upstreamCommit", BuildConfig.UPSTREAM_COMMIT).put("kotlinVersion", BuildConfig.KOTLIN_VERSION)
                .put("plugins", CsWire.helloPluginsToJson(Ops.helloPlugins(this@BridgeService))).toString()
        }

        override fun call(requestJson: String, payload: ParcelFileDescriptor?, reply: IReply) {
            if (!callerCheck(Binder.getCallingUid())) return refuse(reply, payload, CsWire.Err.REFUSED, "caller")
            val req = CsWire.parseOpRequest(requestJson)
            if (req == null || !isKinoPluginId(req.pluginId)) return refuse(reply, payload, CsWire.Err.BAD_REQUEST, "request")
            val ctx = CallContext(req.callId, req.pluginId)
            synchronized(jobs) {
                if (jobs.containsKey(req.callId)) return refuse(reply, payload, CsWire.Err.BAD_REQUEST, "duplicate callId")
                // ATOMIC: the body always starts, so a cancel that races the launch still ends in one `done`.
                val job = scope.launch(ctx + CallContext.threadLocal.asContextElement(ctx), start = CoroutineStart.ATOMIC) {
                    val answer = try {
                        withTimeout(req.deadlineMs) { runOp(req, payload) { ev -> safeEvent(reply, ev) } }
                    } catch (e: TimeoutCancellationException) {
                        CsWire.error(CsWire.Err.TIMEOUT, "deadline")
                    } catch (e: CancellationException) {
                        CsWire.error(CsWire.Err.CANCELLED, "cancelled")
                    } catch (e: LinkageError) {
                        CsWire.error(CsWire.Err.INCOMPATIBLE, "${e.javaClass.simpleName}: ${e.message?.take(200)}")
                    } catch (e: Throwable) {
                        CsWire.error(CsWire.Err.PLUGIN, "${e.javaClass.simpleName}: ${e.message?.take(200)}")
                    } finally {
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

    private fun safeDone(r: IReply, json: String) { try { r.done(json) } catch (_: RemoteException) {} }
    private fun safeEvent(r: IReply, json: String) { try { r.event(json) } catch (_: RemoteException) {} }

    companion object {
        private const val ID_PREFIX = "cloudstream-"

        /** Plugin ids come only from Kino: `cloudstream-…`, within the contract's id charset and length. */
        internal fun isKinoPluginId(id: String): Boolean =
            id.startsWith(ID_PREFIX) && id.length <= 64 && id.all { it.isLetterOrDigit() || it in "-_." }
    }
}
