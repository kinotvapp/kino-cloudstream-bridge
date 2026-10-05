package com.kino.csbridge.ops

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where plugin code runs: a dedicated pool ([dispatcher], a view of IO with its own limit, so plugin threads never take
 * the service's), detached from the op's job (see [run]), with a per-plugin count of leaked tasks.
 *
 * A task leaks when the op stopped waiting on it (deadline, cancel) while the plugin was still inside it: the JVM cannot
 * interrupt blocking code, so it keeps its thread until the plugin returns. A plugin with [MAX_LEAKED] such tasks is
 * [busy]: its new ops are answered at once without starting plugin code, so one stuck plugin cannot fill the pool.
 */
internal object PluginWork {
    const val MAX_LEAKED = 3
    const val POOL_SIZE = 32

    @OptIn(ExperimentalCoroutinesApi::class)
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(POOL_SIZE)

    /** Tests swap it for a smaller pool. */
    @Volatile internal var dispatcher: CoroutineDispatcher = defaultDispatcher

    private val leaked = ConcurrentHashMap<String, AtomicInteger>()

    fun leakedCount(pluginId: String): Int = leaked[pluginId]?.get() ?: 0

    fun busy(pluginId: String): Boolean = leakedCount(pluginId) >= MAX_LEAKED

    /**
     * Runs [block] on [dispatcher], off the op's job but with its context (CallContext, thread local), and waits for it
     * cancellably: when Kino cancels or the deadline fires the op ends at once, even if the plugin is stuck in blocking
     * code. The abandoned work is cancelled (it stops at its next suspension point, if it reaches one) and counted as
     * leaked for [pluginId] until it completes.
     */
    suspend fun <T> run(pluginId: String, block: suspend () -> T): T {
        val ctx = currentCoroutineContext().minusKey(Job) + dispatcher
        val work = CoroutineScope(ctx).async { block() }
        try {
            return work.await()
        } finally {
            if (!work.isCompleted) {
                val n = leaked.computeIfAbsent(pluginId) { AtomicInteger() }
                n.incrementAndGet()
                work.cancel()
                work.invokeOnCompletion { n.decrementAndGet() } // runs at once if it already completed
            }
        }
    }

    /** Tests only. */
    internal fun resetForTest() { leaked.clear(); dispatcher = defaultDispatcher }
}
