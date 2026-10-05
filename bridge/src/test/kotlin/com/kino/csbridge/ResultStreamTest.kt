package com.kino.csbridge

import android.os.ParcelFileDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The writer behind `IReply.doneStream`, on a real OS pipe (java.nio's: Robolectric's ParcelFileDescriptor pipes are
 * file-backed and never block, so they cannot show a reader that stops reading).
 */
@RunWith(RobolectricTestRunner::class)
class ResultStreamTest {
    /** A [StreamPipe] over a real pipe; [ended] is the write end's closing status ("" = still open, null = OK). */
    private class RealPipe {
        val nio: Pipe = Pipe.open()
        @Volatile var ended: String? = ""
        @Volatile var readReleased = false
        val stream = StreamPipe(
            readEnd = ParcelFileDescriptor.createPipe()[0],
            out = Channels.newOutputStream(nio.sink()),
            drain = Channels.newInputStream(nio.source()),
            closeWrite = { err -> ended = err; nio.sink().close() },
            // Our own read end. Kino's copy is a separate descriptor; here both are the nio source, so it stays open.
            closeRead = { readReleased = true },
        )
    }

    @Test fun `a reader that reads gets every byte and the stream ends OK`() {
        val p = RealPipe()
        val bytes = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        val got = ByteArrayOutputStream()
        // Kino's side: its own copy of the read end (here the nio source, read before our drain could ever run).
        val reader = thread { Channels.newInputStream(p.nio.source()).copyTo(got) }
        assertTrue(ResultStream.write(p.stream, bytes, timeoutMs = 10_000))
        reader.join(5_000)
        assertNull(p.ended)
        assertTrue(p.readReleased)
        assertTrue(bytes.contentEquals(got.toByteArray()))
    }

    @Test fun `a reader that never reads makes the write give up at its timeout and end with an error`() {
        val p = RealPipe()
        val t0 = System.nanoTime()
        val ok = ResultStream.write(p.stream, ByteArray(2 * 1024 * 1024), timeoutMs = 500)
        val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
        assertFalse(ok)
        assertTrue("gave up after $ms ms", ms in 400..3_000)
        assertEquals("deadline", p.ended)
        assertTrue(p.readReleased)
    }

    @Test fun `a write end that fails ends the stream with an error at once`() {
        val p = RealPipe()
        p.nio.source().close() // the reader went away: the write fails
        val ok = ResultStream.write(p.stream, ByteArray(1024 * 1024), timeoutMs = 10_000)
        assertFalse(ok)
        assertEquals("write failed", p.ended)
    }

    @Test fun `the stream window is what the op has left, within 5 to 60 s`() {
        assertEquals(5_000L, ResultStream.windowMs(0, 5_000, 60_000))
        assertEquals(12_000L, ResultStream.windowMs(12_000, 5_000, 60_000))
        assertEquals(60_000L, ResultStream.windowMs(Long.MAX_VALUE, 5_000, 60_000))
    }
}
