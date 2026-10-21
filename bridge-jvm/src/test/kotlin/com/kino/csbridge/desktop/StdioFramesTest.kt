package com.kino.csbridge.desktop

import com.kino.csbridge.contract.StdioFrame
import com.kino.csbridge.contract.StdioFrames
import com.kino.csbridge.contract.StdioFrames.Kind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException

class StdioFramesTest {
    private fun roundTrip(vararg frames: StdioFrame): List<StdioFrame> {
        val out = ByteArrayOutputStream()
        frames.forEach { StdioFrames.write(out, it) }
        val input = ByteArrayInputStream(out.toByteArray())
        return generateSequence { StdioFrames.read(input) }.toList()
    }

    @Test fun framesSurviveTheWireInOrder() {
        val payload = ByteArray(300_000) { (it % 251).toByte() }
        val back = roundTrip(
            StdioFrame(Kind.HELLO, 0, """{"v":2}"""),
            StdioFrame(Kind.CALL, 7, """{"op":"installPlugin"}""", payload),
            StdioFrame(Kind.HTTP_END, 9),
        )
        assertEquals(listOf(Kind.HELLO, Kind.CALL, Kind.HTTP_END), back.map { it.kind })
        assertEquals(listOf(0, 7, 9), back.map { it.id })
        assertEquals("""{"v":2}""", back[0].json)
        assertArrayEquals(payload, back[1].body)
        assertEquals("", back[2].json)
        assertEquals(0, back[2].body.size)
    }

    @Test fun jsonIsUtf8() {
        val text = """{"name":"Película – 映画"}"""
        assertEquals(text, roundTrip(StdioFrame(Kind.DONE, 1, text)).single().json)
    }

    @Test fun cleanEndOfStreamIsNull() {
        assertNull(StdioFrames.read(ByteArrayInputStream(ByteArray(0))))
    }

    @Test fun truncatedFrameFails() {
        val out = ByteArrayOutputStream()
        StdioFrames.write(out, StdioFrame(Kind.DONE, 1, "{}", ByteArray(100)))
        val cut = out.toByteArray().copyOf(50)
        assertThrows(IOException::class.java) { StdioFrames.read(ByteArrayInputStream(cut)) }
    }

    @Test fun oversizedAndMalformedLengthsAreRefused() {
        fun frame(total: Int, jsonLen: Int) = ByteArrayOutputStream().also {
            DataOutputStream(it).apply { writeInt(total); writeByte(Kind.DONE); writeInt(1); writeInt(jsonLen) }
        }.toByteArray()
        assertThrows(IOException::class.java) { StdioFrames.read(ByteArrayInputStream(frame(StdioFrames.MAX_FRAME_BYTES + 1, 0))) }
        assertThrows(IOException::class.java) { StdioFrames.read(ByteArrayInputStream(frame(4, 0))) }
        assertThrows(IOException::class.java) { StdioFrames.read(ByteArrayInputStream(frame(20, 50))) }
        assertThrows(IllegalArgumentException::class.java) {
            StdioFrames.write(ByteArrayOutputStream(), StdioFrame(Kind.CALL, 1, "", ByteArray(StdioFrames.MAX_FRAME_BYTES)))
        }
    }
}
