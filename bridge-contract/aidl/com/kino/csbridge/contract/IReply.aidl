// Apache-2.0. Shared verbatim by Kino and the CloudStream bridge. Change => bump CsWire.PROTOCOL_VERSION.
package com.kino.csbridge.contract;

interface IReply {
    /** Streamed partial result, e.g. {"type":"link",...} or {"type":"subtitle",...}. Ordered before done(). */
    oneway void event(String eventJson);
    /** Exactly once per call: the result JSON, or {"error":{"code","message"}}. */
    oneway void done(String resultJson);
}
