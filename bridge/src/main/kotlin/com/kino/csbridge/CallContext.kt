package com.kino.csbridge

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Which call (and plugin) the running code serves; HostLink and the HTTP layer read it to tag requests. */
data class CallContext(val callId: String, val pluginId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CallContext> {
        val threadLocal = ThreadLocal<CallContext?>()

        /** Coroutine element first (suspend callers), then the thread local (plugin code on a blocking thread). */
        suspend fun currentInCoroutine(): CallContext? = coroutineContext[Key] ?: threadLocal.get()

        fun current(): CallContext? = threadLocal.get()
    }
}
