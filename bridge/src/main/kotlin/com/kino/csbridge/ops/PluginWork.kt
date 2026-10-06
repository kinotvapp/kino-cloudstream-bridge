package com.kino.csbridge.ops

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where plugin code runs: a dedicated pool ([dispatcher], a view of IO with its own limit, so plugin threads never take
 * the service's), detached from the op's job (see [run]), with a per-plugin count of leaked tasks.
 *
 * A task leaks when the op stopped waiting on it (deadline, cancel) while the plugin was still inside it: the JVM cannot
 * interrupt blocking code, so it keeps its thread until the plugin returns. A plugin with [MAX_LEAKED] such tasks is
 * [busy]: its provider ops are answered at once without starting plugin code, so one stuck plugin cannot fill the pool.
 * Remove and install are never refused for it: they run on [registryDispatcher] (registry mutations, apart from the
 * plugin pool, which stuck plugins may have filled) and skip the stuck plugin's `beforeUnload()`.
 */
internal object PluginWork {
    const val MAX_LEAKED = 3
    const val POOL_SIZE = 32

    @OptIn(ExperimentalCoroutinesApi::class)
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(POOL_SIZE)

    /** Tests swap it for a smaller pool. */
    @Volatile internal var dispatcher: CoroutineDispatcher = defaultDispatcher

    /**
     * Threads of [registryDispatcher]. Only plugin code that never returns (a hung `load()` or `beforeUnload()` past its
     * wait) keeps one: waits for a plugin's lock give up with their op, so it takes [REGISTRY_THREADS] plugins hung
     * inside an install at once to delay other installs (they then run as soon as a thread frees).
     */
    const val REGISTRY_THREADS = 4

    /** Installs, removes and late-result rollbacks: daemon threads, created once per process. */
    val registryDispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(REGISTRY_THREADS) { r ->
        Thread(r, "plugin-registry").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val leaked = ConcurrentHashMap<String, AtomicInteger>()

    fun leakedCount(pluginId: String): Int = leaked[pluginId]?.get() ?: 0

    fun busy(pluginId: String): Boolean = leakedCount(pluginId) >= MAX_LEAKED

    private const val RUNNING = 0
    private const val FINISHED = 1
    private const val ABANDONED = 2

    /**
     * Runs [block] on [on] (the plugin pool by default), off the op's job but with its context (CallContext, thread
     * local), and waits for it cancellably: when Kino cancels or the deadline fires the op ends at once, even if the
     * plugin is stuck in blocking code. The abandoned work is cancelled (it stops at its next suspension point, if it
     * reaches one) and counted as leaked for [pluginId] until it completes.
     *
     * [onLateResult] gets a result Kino was never told about: one that finished after the op stopped waiting, or in the
     * instant before the hand-over. It runs on [registryDispatcher] (install uses it to roll back).
     *
     * [onDispose] runs once the work has completed, whichever way: returned, threw, or cancelled before it started (then
     * [block] never runs, so resources it would have closed are closed here).
     */
    suspend fun <T> run(
        pluginId: String,
        on: CoroutineDispatcher = dispatcher,
        onLateResult: (suspend (T) -> Unit)? = null,
        onDispose: (() -> Unit)? = null,
        block: suspend () -> T,
    ): T {
        val ctx = currentCoroutineContext().minusKey(Job) + on
        val state = AtomicInteger(RUNNING)
        val work = CoroutineScope(ctx).async {
            val r = block()
            if (!state.compareAndSet(RUNNING, FINISHED)) late(onLateResult, r)
            r
        }
        if (onDispose != null) work.invokeOnCompletion { runCatching { onDispose() } }
        var delivered = false
        try {
            val r = work.await()
            delivered = true
            return r
        } finally {
            if (!delivered) {
                if (state.compareAndSet(RUNNING, ABANDONED)) {
                    if (!work.isCompleted) {
                        val n = leaked.computeIfAbsent(pluginId) { AtomicInteger() }
                        n.incrementAndGet()
                        work.invokeOnCompletion { n.decrementAndGet() } // runs at once if it already completed
                    }
                    work.cancel()
                } else if (state.get() == FINISHED) {
                    // The block finished, but the wait was cancelled before it could hand the result over.
                    work.invokeOnCompletion { if (it == null) late(onLateResult, work.getCompleted()) }
                }
            }
        }
    }

    private fun <T> late(onLateResult: (suspend (T) -> Unit)?, r: T) {
        if (onLateResult == null) return
        CoroutineScope(registryDispatcher).launch { runCatching { onLateResult(r) } }
    }

    /** Tests only. */
    internal fun resetForTest() { leaked.clear(); dispatcher = defaultDispatcher }
}
