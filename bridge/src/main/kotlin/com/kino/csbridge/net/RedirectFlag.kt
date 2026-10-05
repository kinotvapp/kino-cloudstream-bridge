package com.kino.csbridge.net

import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.lang.reflect.Method

/**
 * Whether the client that made a call follows redirects, so `app.get(url, allowRedirects = false)` (NiceHttp builds a
 * `followRedirects(false)` client per request) reaches Kino as `followRedirects=false`; Kino follows redirects itself.
 *
 * Why reflection (decision record, Task 4):
 * - OkHttp 5.3.2's public `Interceptor.Chain` has no redirect setting (request, proceed, connection, call and the
 *   three timeouts only), and `Call` exposes no client. An application interceptor never sees the client otherwise.
 * - The brief's alternative, vendoring NiceHttp 0.4.18's source to tag requests, was refused by the permission system
 *   as untrusted code integration, so it was not done.
 * - What remains is one guarded read of `RealCall.getClient()` (a public method of OkHttp's internal call class). It is
 *   isolated here, defaults to `true` when it fails (the pre-shim behaviour), and `IpcInterceptorTest` asserts it
 *   still works with the bundled OkHttp, so a version bump that breaks it fails the build instead of silently
 *   following redirects for plugins that asked not to.
 */
internal object RedirectFlag {
    @Volatile private var cached: Pair<Class<*>, Method?>? = null

    fun of(chain: Interceptor.Chain): Boolean = read(chain.call()) ?: true

    /** The call's client `followRedirects`, or null when it cannot be read. */
    fun read(call: Call): Boolean? = clientOf(call)?.followRedirects

    /**
     * The client that made [call], or null when the read fails. [IpcInterceptor] also uses it for the client's public
     * `interceptors` / `networkInterceptors` lists (the plugin interceptors it runs itself): no further reflection.
     * R8 keeps `RealCall.getClient()` through bridge/proguard-rules.pro.
     */
    fun clientOf(call: Call): OkHttpClient? = runCatching {
        val c = call.javaClass
        val m = cached?.takeIf { it.first == c }?.second
            ?: runCatching { c.getMethod("getClient") }.getOrNull().also { cached = c to it }
        m?.invoke(call) as? OkHttpClient
    }.getOrNull()
}
