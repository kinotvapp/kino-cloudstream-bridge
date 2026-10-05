package com.kino.csbridge

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One `IReply.doneStream` pipe as the writer sees it. [readEnd] goes to Kino; the bridge keeps it open as [drain] until
 * the write ends, so that a write stuck on a full pipe (Kino not reading) can be freed by reading it ourselves.
 */
internal class StreamPipe(
    val readEnd: ParcelFileDescriptor,
    val out: OutputStream,
    val drain: InputStream,
    /** Ends the write end: null = complete (OK status), else an error (closeWithError). */
    val closeWrite: (error: String?) -> Unit,
    /** Releases the bridge's own read end. */
    val closeRead: () -> Unit,
)

/**
 * Writes a large result into a [StreamPipe] (PROTOCOL.md "call, event, done, doneStream"), bounded in time.
 *
 * A blocking pipe write cannot be interrupted, and closing the descriptor from another thread does not wake it. So the
 * bridge keeps its own copy of the read end: at the deadline a watchdog marks the write abandoned and reads (discards)
 * from that copy, which frees the stuck write; the writer then sees the mark and ends the pipe with an error. Kino, which
 * has its own read end, sees EOF without the OK status (`bridge_stream_broken`). A state that only one side can claim
 * makes sure the drain never eats the bytes of a write that completed.
 *
 * While the bridge holds its read end, a Kino that closed (or lost) its own does not fail the write early; the deadline
 * still bounds it.
 */
internal object ResultStream {
    private const val RUNNING = 0
    private const val DONE = 1
    private const val ABANDONED = 2
    private const val CHUNK = 16 * 1024
    /** How long the writer waits for the watchdog's drain to see EOF before releasing the read end anyway. */
    private const val DRAIN_WAIT_MS = 2_000L

    const val ERR_DEADLINE = "deadline"
    const val ERR_WRITE = "write failed"

    /** A reliable pipe: its write end's close sends an OK status, closeWithError an error, both readable with checkError. */
    fun pipe(): StreamPipe {
        val (read, write) = ParcelFileDescriptor.createReliablePipe()
        return StreamPipe(
            read, FileOutputStream(write.fileDescriptor), FileInputStream(read.fileDescriptor),
            closeWrite = { err ->
                if (err == null) runCatching { write.close() }
                else try { write.closeWithError(err) } catch (_: IOException) { runCatching { write.close() } }
            },
            closeRead = { runCatching { read.close() } },
        )
    }

    /** How long a stream may take: what the op has left, at least [minMs] and at most [maxMs]. */
    fun windowMs(remainingMs: Long, minMs: Long, maxMs: Long): Long = remainingMs.coerceIn(minMs, maxMs)

    /**
     * Writes [bytes] into [pipe] within [timeoutMs] on the calling thread, then ends the write end (OK when complete,
     * else [ERR_DEADLINE] / [ERR_WRITE]) and releases the bridge's read end. True when every byte was written.
     */
    fun write(pipe: StreamPipe, bytes: ByteArray, timeoutMs: Long): Boolean {
        val state = AtomicInteger(RUNNING)
        val finished = CountDownLatch(1)
        val drained = CountDownLatch(1)
        Thread({
            try {
                if (!finished.await(timeoutMs, TimeUnit.MILLISECONDS) && state.compareAndSet(RUNNING, ABANDONED)) {
                    val buf = ByteArray(64 * 1024)
                    runCatching { while (pipe.drain.read(buf) >= 0) { /* discarded: only to free the stuck write */ } }
                }
            } finally {
                drained.countDown()
            }
        }, "bridge-stream-watch").apply { isDaemon = true; start() }

        var error: String? = ERR_WRITE
        try {
            var off = 0
            while (off < bytes.size && state.get() == RUNNING) {
                val n = minOf(CHUNK, bytes.size - off)
                pipe.out.write(bytes, off, n)
                off += n
            }
            if (off == bytes.size) pipe.out.flush()
            error = if (off == bytes.size && state.compareAndSet(RUNNING, DONE)) null else ERR_DEADLINE
        } catch (_: Exception) {
            error = if (state.get() == ABANDONED) ERR_DEADLINE else ERR_WRITE
            state.compareAndSet(RUNNING, DONE) // a failed write: nothing left for the watchdog to free
        }
        finished.countDown()
        runCatching { pipe.closeWrite(error) }
        // Abandoned: the watchdog drains until the close above gives it EOF; only then is the read end released.
        drained.await(DRAIN_WAIT_MS, TimeUnit.MILLISECONDS)
        runCatching { pipe.closeRead() }
        return error == null
    }
}
