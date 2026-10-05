// Apache-2.0. Shared verbatim by Kino and the CloudStream bridge. Change => bump CsWire.PROTOCOL_VERSION.
package com.kino.csbridge.contract;

interface IReply {
    /** Streamed partial result, e.g. {"type":"link",...} or {"type":"subtitle",...}. Ordered before done()/doneStream(). */
    oneway void event(String eventJson);
    /** The result JSON, or {"error":{"code","message"}}. Exactly one of done() / doneStream() ends a call. */
    oneway void done(String resultJson);
    /**
     * The same JSON done() would carry, when it is longer than CsWire.MAX_INLINE_RESULT_CHARS: [result] is the read end
     * of a reliable pipe holding it as UTF-8, at most CsWire.MAX_STREAM_RESULT_BYTES. The writer closes its end with an
     * OK status when complete; EOF without it is CsWire.Err.STREAM_BROKEN (CsWire.readStreamedResult).
     * Appended after done(): existing transaction ids stay. Never used to answer IHost.webView.
     */
    oneway void doneStream(in ParcelFileDescriptor result);
}
