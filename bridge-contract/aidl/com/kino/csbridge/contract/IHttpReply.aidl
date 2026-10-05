// Apache-2.0. Shared verbatim by Kino and the CloudStream bridge. Change => bump CsWire.PROTOCOL_VERSION.
package com.kino.csbridge.contract;

interface IHttpReply {
    /** Exactly once: {"status","message","headers":[[n,v]],"finalUrl"} or {"error":{"code","message"}}.
        On success the body is then written to the responseSink pipe and the pipe closed (EOF). */
    oneway void response(String metaJson);
}
