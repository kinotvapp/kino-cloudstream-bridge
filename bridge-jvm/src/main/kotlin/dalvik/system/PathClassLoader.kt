// GPL-3.0 (this fork). The desktop's stand-in for Android's PathClassLoader: a .cs3 holds Dalvik bytecode (classes.dex),
// which a JVM cannot load, so the dex is translated to a jar once (dex2jar, Apache-2.0) and the jar is loaded.
package dalvik.system

import android.content.Context
import com.googlecode.d2j.dex.Dex2jar
import java.io.File
import java.io.IOException
import java.net.URL
import java.net.URLClassLoader
import java.security.MessageDigest
import java.util.UUID

class PathClassLoader(dexPath: String, parent: ClassLoader) : URLClassLoader(arrayOf(translated(File(dexPath))), parent) {
    companion object {
        /** The translated jar of [cs3], cached by the file's sha256 so a plugin is translated once. */
        private fun translated(cs3: File): URL {
            val dir = File(Context().cacheDir, "dexjars").apply { mkdirs() }
            val jar = File(dir, sha256(cs3) + ".jar")
            if (!jar.isFile) {
                val tmp = File(dir, "${jar.name}.${UUID.randomUUID()}.tmp")
                try {
                    Dex2jar.from(cs3).skipDebug().to(tmp.toPath())
                    if (!tmp.renameTo(jar) && !jar.isFile) throw IOException("cannot store the translated plugin")
                } finally {
                    tmp.delete()
                }
            }
            return jar.toURI().toURL()
        }

        private fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
