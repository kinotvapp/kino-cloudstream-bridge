package com.kino.csbridge

import kotlinx.coroutines.Job
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Which call (and plugin) the running code serves; HostLink and the HTTP layer read it to tag requests.
 * [deadlineAtNanos] is on the [System.nanoTime] clock; [job] is the op's coroutine, set by the service as the op
 * starts, so blocking code (the HTTP shim) can stop waiting when the op is cancelled or its deadline fires.
 */
data class CallContext(
    val callId: String,
    val pluginId: String,
    val deadlineAtNanos: Long = Long.MAX_VALUE,
) : AbstractCoroutineContextElement(Key) {
    @Volatile var job: Job? = null

    /** Milliseconds left before the call's deadline, never negative; [Long.MAX_VALUE] when there is none. */
    fun remainingMs(): Long = if (deadlineAtNanos == Long.MAX_VALUE) Long.MAX_VALUE
        else ((deadlineAtNanos - System.nanoTime()) / 1_000_000).coerceAtLeast(0)

    /** True once the op was cancelled, timed out or finished: nothing may keep waiting on its behalf. */
    fun isOver(): Boolean = job?.isActive == false || remainingMs() == 0L

    companion object Key : CoroutineContext.Key<CallContext> {
        val threadLocal = ThreadLocal<CallContext?>()

        /** Coroutine element first (suspend callers), then the thread local (plugin code on a blocking thread). */
        suspend fun currentInCoroutine(): CallContext? = coroutineContext[Key] ?: threadLocal.get()

        fun current(): CallContext? = threadLocal.get()

        /** `now + deadlineMs` on the nanoTime clock; capped at 30 days so it can never overflow. */
        fun deadlineFromNow(deadlineMs: Long): Long =
            System.nanoTime() + deadlineMs.coerceIn(0, MAX_DEADLINE_MS) * 1_000_000

        private const val MAX_DEADLINE_MS = 30L * 24 * 3600 * 1000
    }
}
