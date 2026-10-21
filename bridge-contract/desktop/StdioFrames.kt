// Apache-2.0 (license: bridge-contract/LICENSE). The desktop transport of the Kino <-> bridge protocol: the same JSON as the
// AIDL one, carried over the bridge process's stdin/stdout instead of Binder (bridge-contract/PROTOCOL.md, "Desktop transport").
// Copied verbatim into Kino's repository, like the rest of this folder.
package com.kino.csbridge.contract

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * One frame on the wire: `u32 length | u8 kind | u32 id | u32 jsonLength | json (UTF-8) | body`, big-endian, where `length`
 * counts everything after itself. [id] ties a frame to its call (`Kind.CALL` and the bridge's `EVENT`/`DONE`) or to one host
 * request (`HTTP_*`, `WEBVIEW_*`, `STORAGE_*`); a sender numbers the exchanges it starts. [body] is bytes with no meaning to the
 * transport: an install payload, an HTTP body chunk.
 */
class StdioFrame(val kind: Int, val id: Int, val json: String = "", val body: ByteArray = EMPTY) {
    companion object { val EMPTY = ByteArray(0) }
}

object StdioFrames {
    /** Kino to bridge. */
    object Kind {
        /** `{"v":2}`: the first frame; answered by [HELLO_REPLY] (the `hello` JSON of PROTOCOL.md). */
        const val HELLO = 1
        /** The op request JSON (PROTOCOL.md "call"); [StdioFrame.body] is the payload of `installPlugin`. */
        const val CALL = 2
        /** `{"callId":"..."}`. */
        const val CANCEL = 3
        /** The `IHttpReply.response` JSON of host request [StdioFrame.id]. */
        const val HTTP_META = 11
        /** A chunk of that request's response body. */
        const val HTTP_BODY = 12
        /** The response body ended: the sink pipe's OK close. */
        const val HTTP_END = 13
        /** The response body failed: `{"message":"..."}`, the sink pipe's `closeWithError`. */
        const val HTTP_FAIL = 14
        /** An `IReply.event` of `IHost.webView` request [StdioFrame.id]. */
        const val WEBVIEW_EVENT = 15
        /** The `IReply.done` of `IHost.webView` request [StdioFrame.id]. */
        const val WEBVIEW_DONE = 16
        /** The answer to `IHost.storage` request [StdioFrame.id]. */
        const val STORAGE_REPLY = 17

        /** Bridge to Kino. */
        const val HELLO_REPLY = 101
        const val EVENT = 102
        const val DONE = 103
        /** An `IHost.request`: the meta JSON, then the request body in [StdioFrame.body]. */
        const val HTTP_REQUEST = 111
        const val WEBVIEW = 112
        const val STORAGE = 113
    }

    /** No frame is larger than this (a streamed result may be up to 8 MiB, an install payload 20 MiB). */
    const val MAX_FRAME_BYTES = 40 shl 20

    fun write(out: OutputStream, f: StdioFrame) {
        val json = f.json.toByteArray(Charsets.UTF_8)
        val total = 1 + 4 + 4 + json.size + f.body.size
        require(total <= MAX_FRAME_BYTES) { "frame too large" }
        val d = DataOutputStream(out)
        d.writeInt(total)
        d.writeByte(f.kind)
        d.writeInt(f.id)
        d.writeInt(json.size)
        d.write(json)
        d.write(f.body)
        d.flush()
    }

    /** The next frame, or null at a clean end of the stream. Throws [IOException] on a truncated or oversized frame. */
    fun read(input: InputStream): StdioFrame? {
        val d = DataInputStream(input)
        val total = try { d.readInt() } catch (_: EOFException) { return null }
        if (total < 9 || total > MAX_FRAME_BYTES) throw IOException("bad frame length")
        val kind = d.readUnsignedByte()
        val id = d.readInt()
        val jsonLen = d.readInt()
        if (jsonLen < 0 || jsonLen > total - 9) throw IOException("bad json length")
        val json = ByteArray(jsonLen).also { d.readFully(it) }
        val body = ByteArray(total - 9 - jsonLen).also { d.readFully(it) }
        return StdioFrame(kind, id, String(json, Charsets.UTF_8), body)
    }
}
