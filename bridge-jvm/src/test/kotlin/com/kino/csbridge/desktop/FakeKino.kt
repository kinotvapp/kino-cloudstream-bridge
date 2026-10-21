// GPL-3.0 (this fork). A stand-in for Kino's desktop client: starts the bridge as a child process, speaks the stdio protocol
// and plays the host (HTTP through the JDK client, storage in memory). Used by the integration test and, as a main, to try a
// real .cs3 by hand:  FakeKino <bridge-lib-dir> <data-dir> <plugin.cs3> <query>
package com.kino.csbridge.desktop

import com.kino.csbridge.contract.StdioFrame
import com.kino.csbridge.contract.StdioFrames
import com.kino.csbridge.contract.StdioFrames.Kind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FakeKino(libDir: File, dataDir: File, java: String = File(System.getProperty("java.home"), "bin/java").path) : AutoCloseable {
    private val process: Process = ProcessBuilder(
        java, "-cp", File(libDir, "*").path, "com.kino.csbridge.desktop.MainKt", "--data-dir=${dataDir.path}",
    ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    private val out: OutputStream = process.outputStream
    private val ids = AtomicInteger(1)
    private val replies = ConcurrentHashMap<Int, LinkedBlockingQueue<StdioFrame>>()
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build()
    private val storage = ConcurrentHashMap<String, String>()
    val requested = CopyOnWriteArrayList<String>()

    init {
        Thread({ readLoop() }, "fake-kino-reader").apply { isDaemon = true }.start()
    }

    @Synchronized private fun send(f: StdioFrame) { StdioFrames.write(out, f) }

    private fun readLoop() {
        while (true) {
            val f = StdioFrames.read(process.inputStream) ?: return
            when (f.kind) {
                Kind.HELLO_REPLY, Kind.EVENT, Kind.DONE -> replies.computeIfAbsent(f.id) { LinkedBlockingQueue() }.add(f)
                Kind.HTTP_REQUEST -> Thread({ http(f) }, "fake-kino-http-${f.id}").apply { isDaemon = true }.start()
                Kind.STORAGE -> send(StdioFrame(Kind.STORAGE_REPLY, f.id, storage(JSONObject(f.json))))
                Kind.WEBVIEW -> send(StdioFrame(Kind.WEBVIEW_DONE, f.id, """{"error":{"code":"browser_unavailable","message":"no hidden page on the desktop yet"}}"""))
            }
        }
    }

    private fun storage(o: JSONObject): String {
        val k = o.optString("pluginId") + "\u0000" + o.optString("key")
        return when (o.getString("op")) {
            "get" -> JSONObject().put("value", storage[k] ?: JSONObject.NULL).toString()
            "set" -> { storage[k] = o.getString("value"); """{"ok":true}""" }
            "remove" -> { storage.remove(k); """{"ok":true}""" }
            "keys" -> JSONObject().put("keys", JSONArray(storage.keys.filter { it.startsWith(o.optString("pluginId") + "\u0000") }.map { it.substringAfter('\u0000') })).toString()
            else -> """{"error":{"code":"bad_request","message":"op"}}"""
        }
    }

    private fun http(f: StdioFrame) {
        val meta = JSONObject(f.json)
        val url = meta.getString("url")
        requested += url
        try {
            require(url.startsWith("https://")) { "https only" }
            val b = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMillis(meta.optLong("timeoutMs", 30_000).coerceAtLeast(1_000)))
            val headers = meta.optJSONArray("headers") ?: JSONArray()
            for (i in 0 until headers.length()) {
                val h = headers.getJSONArray(i)
                if (h.getString(0).lowercase() !in RESTRICTED) b.header(h.getString(0), h.getString(1))
            }
            b.method(meta.getString("method"), if (f.body.isNotEmpty()) HttpRequest.BodyPublishers.ofByteArray(f.body) else HttpRequest.BodyPublishers.noBody())
            val r = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray())
            val hs = JSONArray()
            r.headers().map().forEach { (n, vs) -> vs.forEach { v -> hs.put(JSONArray().put(n).put(v)) } }
            send(StdioFrame(Kind.HTTP_META, f.id, JSONObject().put("status", r.statusCode()).put("message", "").put("headers", hs).put("finalUrl", r.uri().toString()).toString()))
            r.body().asList().chunked(64 * 1024).forEach { send(StdioFrame(Kind.HTTP_BODY, f.id, "", it.toByteArray())) }
            send(StdioFrame(Kind.HTTP_END, f.id))
        } catch (e: Exception) {
            send(StdioFrame(Kind.HTTP_META, f.id, """{"error":{"code":"network","message":"${e.javaClass.simpleName}"}}"""))
            send(StdioFrame(Kind.HTTP_END, f.id))
        }
    }

    private fun waitFor(id: Int, kind: Int, ms: Long): StdioFrame {
        val q = replies.computeIfAbsent(id) { LinkedBlockingQueue() }
        val end = System.nanoTime() + ms * 1_000_000
        while (true) {
            val f = q.poll(((end - System.nanoTime()) / 1_000_000).coerceAtLeast(1), TimeUnit.MILLISECONDS) ?: error("no reply (id $id)")
            if (f.kind == kind) return f
        }
    }

    fun hello(): JSONObject {
        val id = ids.getAndIncrement()
        send(StdioFrame(Kind.HELLO, id, """{"v":2}"""))
        return JSONObject(waitFor(id, Kind.HELLO_REPLY, 60_000).json)
    }

    /** One op; returns its `done` JSON and the events it sent. */
    fun call(op: String, pluginId: String, args: JSONObject = JSONObject(), payload: ByteArray = ByteArray(0), deadlineMs: Long = 120_000): Pair<JSONObject, List<JSONObject>> {
        val id = ids.getAndIncrement()
        val req = JSONObject().put("v", 2).put("op", op).put("callId", "call-$id").put("pluginId", pluginId).put("deadlineMs", deadlineMs).put("args", args)
        send(StdioFrame(Kind.CALL, id, req.toString(), payload))
        val done = waitFor(id, Kind.DONE, deadlineMs + 10_000)
        val events = generateSequence { replies[id]?.poll() }.filter { it.kind == Kind.EVENT }.map { JSONObject(it.json) }.toList()
        return JSONObject(done.json) to events
    }

    override fun close() {
        runCatching { out.close() }
        process.waitFor(10, TimeUnit.SECONDS)
        process.destroyForcibly()
    }

    companion object {
        private val RESTRICTED = setOf("host", "content-length", "connection", "expect", "upgrade")

        fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        @JvmStatic fun main(a: Array<String>) {
            val cs3 = File(a[2]).readBytes()
            val pluginId = "cloudstream-" + File(a[2]).nameWithoutExtension.lowercase()
            FakeKino(File(a[0]), File(a[1])).use { k ->
                println("hello: " + k.hello().toString().take(200))
                val inst = k.call("installPlugin", pluginId, JSONObject().put("sha256", sha256(cs3)).put("fileName", File(a[2]).name), cs3).first
                println("install: " + inst.toString().take(400))
                val providers = inst.optJSONArray("providers") ?: return
                println("list: " + k.call("listPlugins", pluginId).first)
                val s = k.call("search", pluginId, JSONObject().put("provider", 0).put("query", a[3])).first
                println("search: " + s.toString().take(300))
                val first = s.optJSONArray("items")?.optJSONObject(0) ?: return
                val l = k.call("load", pluginId, JSONObject().put("provider", 0).put("url", first.getString("url"))).first
                println("load: " + l.toString().take(300))
                val ep = l.optJSONArray("episodes")?.optJSONObject(0) ?: return
                val ll = k.call("loadLinks", pluginId, JSONObject().put("provider", 0).put("data", ep.getString("data")))
                println("loadLinks: " + ll.first + " events=" + ll.second.size)
                println("host requests: " + k.requested.size)
                println("provider count: " + providers.length())
            }
        }
    }
}
