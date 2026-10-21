// Apache-2.0, like the AIDL it mirrors (bridge-contract/aidl). On Android these are generated from the .aidl files; the JVM
// bridge runs in a child process of Kino, so the same four interfaces are plain Kotlin and cross the process boundary
// as frames (see StdioTransport).
package com.kino.csbridge.contract

import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor

interface IReply {
    fun event(eventJson: String?)
    fun done(resultJson: String?)
    fun doneStream(result: ParcelFileDescriptor?)
    fun asBinder(): IBinder

    abstract class Stub : Binder(), IReply {
        override fun asBinder(): IBinder = this
    }
}

interface IHttpReply {
    fun response(metaJson: String?)
    fun asBinder(): IBinder

    abstract class Stub : Binder(), IHttpReply {
        override fun asBinder(): IBinder = this
    }
}

interface IHost {
    fun request(metaJson: String, requestBody: ParcelFileDescriptor?, responseSink: ParcelFileDescriptor?, reply: IHttpReply)
    fun webView(requestJson: String, reply: IReply)
    fun storage(opJson: String): String?
    fun asBinder(): IBinder
}

interface IBridge {
    fun hello(protocolVersion: Int, host: IHost): String
    fun call(requestJson: String, payload: ParcelFileDescriptor?, reply: IReply)
    fun cancel(callId: String)
}
