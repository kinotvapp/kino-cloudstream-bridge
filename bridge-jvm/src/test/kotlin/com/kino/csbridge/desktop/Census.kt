// GPL-3.0 (this fork). Installs every .cs3 of a folder through the real bridge process and reports what happened: how many
// install, what the rest are missing. Run:  Census <bridge-lib-dir> <data-dir> <folder-with-cs3>
package com.kino.csbridge.desktop

import org.json.JSONObject
import java.io.File

object Census {
    @JvmStatic fun main(a: Array<String>) {
        val files = File(a[2]).listFiles { f -> f.name.endsWith(".cs3") }!!.sortedBy { it.name }
        var ok = 0
        FakeKino(File(a[0]), File(a[1])).use { k ->
            k.hello()
            for (f in files) {
                val bytes = f.readBytes()
                val id = "cloudstream-" + f.nameWithoutExtension.lowercase()
                val r: JSONObject = try {
                    k.call("installPlugin", id, JSONObject().put("sha256", FakeKino.sha256(bytes)).put("fileName", f.name), bytes, deadlineMs = 120_000).first
                } catch (e: Exception) {
                    JSONObject().put("error", JSONObject().put("code", "harness").put("message", e.message))
                }
                val err = r.optJSONObject("error")
                val missing = r.optJSONArray("missingSymbols")
                val line = when {
                    err != null -> "ERR ${err.optString("code")}: ${err.optString("message").take(160)}"
                    missing != null && missing.length() > 0 -> "MISSING ${missing.length()}: " + (0 until minOf(3, missing.length())).joinToString(" | ") { missing.getString(it).take(90) }
                    else -> { ok++; "OK ${r.optJSONArray("providers")?.length() ?: 0} provider(s)" }
                }
                println("${f.nameWithoutExtension} :: $line")
            }
        }
        println("installed $ok of ${files.size}")
    }
}
