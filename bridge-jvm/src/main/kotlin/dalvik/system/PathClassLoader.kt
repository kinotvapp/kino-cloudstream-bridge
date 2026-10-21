// GPL-3.0 (this fork). The desktop's stand-in for Android's PathClassLoader: a .cs3 holds Dalvik bytecode (classes.dex),
// which a JVM cannot load, so the dex is translated to a jar once (dex2jar, Apache-2.0) and the jar is loaded.
//
// Two adjustments make the translated code link on a JVM:
//  * D8 renames Kotlin's inline-class members (`constructor-impl` becomes `constructor_impl`, dex has no '-'); the call
//    sites in the translated jar are renamed back for the stdlib classes that have them.
//  * A plugin's settings screens extend Android UI classes (Fragment, Dialog, ...). Those never run here, but the JVM
//    needs a class to exist for each supertype before it defines the plugin class, so a missing platform supertype is
//    defined empty on the spot (as a class or as an interface, whichever the plugin's class declares).
package dalvik.system

import android.content.Context
import com.googlecode.d2j.dex.Dex2jar
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import java.io.File
import java.io.IOException
import java.net.URL
import java.net.URLClassLoader
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class PathClassLoader(dexPath: String, parent: ClassLoader) : URLClassLoader(arrayOf(translated(File(dexPath))), parent) {

    override fun findClass(name: String): Class<*> {
        val path = name.replace('.', '/') + ".class"
        val bytes = findResource(path)?.openStream()?.use { it.readBytes() } ?: throw ClassNotFoundException(name)
        val reader = ClassReader(bytes)
        reader.superName?.let { ensurePlatformType(it, isInterface = false) }
        reader.interfaces.forEach { ensurePlatformType(it, isInterface = true) }
        return defineClass(name, bytes, 0, bytes.size)
    }

    /** Defines [internalName] empty in this loader when it is a platform class nobody provides. */
    private fun ensurePlatformType(internalName: String, isInterface: Boolean) {
        if (PLATFORM_PREFIXES.none { internalName.startsWith(it) }) return
        val binary = internalName.replace('/', '.')
        synchronized(getClassLoadingLock(binary)) {
            if (findLoadedClass(binary) != null) return
            try {
                parent.loadClass(binary)
                return
            } catch (_: ClassNotFoundException) {
            }
            val w = ClassWriter(0)
            val access = Opcodes.ACC_PUBLIC or if (isInterface) Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT else Opcodes.ACC_SUPER
            w.visit(Opcodes.V1_8, access, internalName, null, "java/lang/Object", null)
            if (!isInterface) {
                w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                    visitCode()
                    visitVarInsn(Opcodes.ALOAD, 0)
                    visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                    visitInsn(Opcodes.RETURN)
                    visitMaxs(1, 1)
                    visitEnd()
                }
            }
            w.visitEnd()
            val shell = w.toByteArray()
            defineClass(binary, shell, 0, shell.size)
        }
    }

    companion object {
        private val PLATFORM_PREFIXES = listOf("android/", "androidx/", "dalvik/", "com/google/android/", "org/xmlpull/")

        /** Kotlin inline classes whose D8-renamed members the translated code calls (`name_impl`, really `name-impl`). */
        private val INLINE_OWNERS = setOf(
            "kotlin/Result", "kotlin/UInt", "kotlin/ULong", "kotlin/UByte", "kotlin/UShort", "kotlin/time/Duration",
        )

        /** The translated jar of [cs3], cached by the file's sha256 so a plugin is translated once. */
        private fun translated(cs3: File): URL {
            val dir = File(Context().cacheDir, "dexjars").apply { mkdirs() }
            val jar = File(dir, sha256(cs3) + "-$FORMAT.jar")
            if (!jar.isFile) {
                val raw = File(dir, "${jar.name}.${UUID.randomUUID()}.raw")
                val tmp = File(dir, "${jar.name}.${UUID.randomUUID()}.tmp")
                try {
                    Dex2jar.from(cs3).skipDebug().to(raw.toPath())
                    restoreInlineNames(raw, tmp)
                    if (!tmp.renameTo(jar) && !jar.isFile) throw IOException("cannot store the translated plugin")
                } finally {
                    raw.delete()
                    tmp.delete()
                }
            }
            return jar.toURI().toURL()
        }

        private val REMAPPER = object : Remapper(Opcodes.ASM9) {
            override fun mapMethodName(owner: String, name: String, descriptor: String): String =
                if (owner in INLINE_OWNERS && name.contains("_impl")) name.replace("_impl", "-impl") else name
        }

        private fun restoreInlineNames(from: File, to: File) {
            ZipFile(from).use { zip ->
                ZipOutputStream(to.outputStream().buffered()).use { out ->
                    for (e in zip.entries().asSequence()) {
                        val bytes = zip.getInputStream(e).use { it.readBytes() }
                        out.putNextEntry(ZipEntry(e.name))
                        if (e.name.endsWith(".class")) {
                            val reader = ClassReader(bytes)
                            val writer = ClassWriter(0)
                            reader.accept(ClassRemapper(writer, REMAPPER), 0)
                            out.write(writer.toByteArray())
                        } else {
                            out.write(bytes)
                        }
                        out.closeEntry()
                    }
                }
            }
        }

        /** Bumped when the translation changes, so cached jars of an older build are not reused. */
        private const val FORMAT = 2

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
