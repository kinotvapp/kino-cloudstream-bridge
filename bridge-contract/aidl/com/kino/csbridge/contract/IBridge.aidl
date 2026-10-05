// Apache-2.0. Shared verbatim by Kino and the CloudStream bridge. Change => bump CsWire.PROTOCOL_VERSION.
package com.kino.csbridge.contract;

import com.kino.csbridge.contract.IHost;
import com.kino.csbridge.contract.IReply;

interface IBridge {
    /** Sync and fast. Returns CsWire hello JSON. The bridge keeps [host] for this binding's life. */
    String hello(int protocolVersion, IHost host);
    /** One operation (CsWire.Op). [payload] carries .cs3 bytes for installPlugin, otherwise null. */
    oneway void call(String requestJson, in ParcelFileDescriptor payload, IReply reply);
    /** Best effort: the op ends with Err.CANCELLED if it was still running. */
    oneway void cancel(String callId);
}
