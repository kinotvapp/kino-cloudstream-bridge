package com.kino.csbridge

import android.content.Intent
import android.os.ParcelFileDescriptor
import com.kino.csbridge.contract.CsWire
import com.kino.csbridge.contract.IBridge
import com.kino.csbridge.contract.IHost
import com.kino.csbridge.contract.IHttpReply
import com.kino.csbridge.contract.IReply
import com.kino.csbridge.plugins.PluginRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class HelloStorageTest {
    private val context = RuntimeEnvironment.getApplication()
    private val dir = File(context.filesDir, "plugins")

    private class FakeHost : IHost.Stub() {
        override fun request(m: String?, b: ParcelFileDescriptor?, s: ParcelFileDescriptor?, r: IHttpReply?) {}
        override fun webView(j: String?, r: IReply?) {}
        override fun storage(o: String?): String = "{}"
    }

    @After fun tearDown() { Ops.instance = null; HostLink.host = null; dir.deleteRecursively() }

    @Test fun `hello answers unavailable, not an empty plugin list, when plugin storage cannot be read`() {
        dir.deleteRecursively(); dir.writeText("not a directory") // the index can be neither read nor written
        Ops.instance = PluginRegistry(context, javaClass.classLoader!!, { _, parent -> parent })
        val s = Robolectric.setupService(BridgeService::class.java).apply { callerCheck = { true } }
        val answer = IBridge.Stub.asInterface(s.onBind(Intent())).hello(CsWire.PROTOCOL_VERSION, FakeHost())
        assertEquals(CsWire.Err.UNAVAILABLE, CsWire.errorOf(answer)!!.first)
        assertNull(HostLink.host)
    }
}
