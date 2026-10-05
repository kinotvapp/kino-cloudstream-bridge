// Apache-2.0. Shared verbatim by Kino and the CloudStream bridge. Change => bump CsWire.PROTOCOL_VERSION.
package com.kino.csbridge.contract;

import com.kino.csbridge.contract.IHttpReply;
import com.kino.csbridge.contract.IReply;

interface IHost {
    /** [requestBody] is the read end of a pipe holding the body, or null. [responseSink] is the write end Kino fills. */
    oneway void request(String metaJson, in ParcelFileDescriptor requestBody, in ParcelFileDescriptor responseSink, IHttpReply reply);
    /** Hidden-WebView capture for WebViewResolver / CloudflareKiller (PROTOCOL.md "IHost.webView"). Reply via done() only. */
    oneway void webView(String requestJson, IReply reply);
    /** Sync, small, local: {"op":"get|set|remove|keys",...} → {"value"} | {"ok":true} | {"keys":[]} | {"error"}. */
    String storage(String opJson);
}
