package com.kino.csbridge.plugins

import com.android.tools.smali.dexlib2.ReferenceType
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.DualReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.Modifier
import java.util.zip.ZipFile

/**
 * Member-level ABI check of a `.cs3` against the bridge's classes, before any of its code runs.
 *
 * Every method and field reference in every `classes*.dex` is resolved the way the runtime links it: through the
 * plugin's own classes first, then [loader] (`Class.forName(name, false, loader)` and the declared members of the class,
 * its superclasses and its interfaces, matched on name + JVM descriptor; private members never link from plugin code).
 * Every type the code uses (owners, descriptors, supertypes, `new-instance`/`const-class`/casts, catch types) must
 * load too. Types that only appear in annotations are not checked: a missing annotation class never breaks linking.
 *
 * [missing] returns what does not resolve, sorted: `Lowner;->name(params)ret`, `Lowner;->name:type` or `Ltype;`.
 * A member whose owner class is itself missing is reported only as the class.
 */
class SymbolChecker(
    private val loader: ClassLoader,
    /** Descriptors (`Lowner;`) the host platform provides and the checker takes as linked: the JVM bridge's Android UI classes. */
    private val platformOwner: (String) -> Boolean = { false },
) {
    private val classes = HashMap<String, Class<*>?>()
    private val members = HashMap<Class<*>, Set<String>>()

    /** @throws IllegalArgumentException when [cs3] is not a zip with at least one readable `classes*.dex`. */
    fun missing(cs3: File): List<String> {
        val dexes = readDexes(cs3)
        val defs = HashMap<String, ClassDef>()
        val types = HashSet<String>()
        val methods = HashSet<MethodKey>()
        val fields = HashSet<FieldKey>()
        val own: Map<String, InDex> = try {
            for (dex in dexes) {
                for (def in dex.classes) defs.putIfAbsent(def.type, def)
                for (r in dex.getReferences(ReferenceType.METHOD)) {
                    val m = r as MethodReference
                    val key = MethodKey(m.definingClass, m.name, m.parameterTypes.joinToString(""), m.returnType)
                    if (methods.add(key)) { types += key.owner; types += key.ret; m.parameterTypes.forEach { types += it.toString() } }
                }
                for (r in dex.getReferences(ReferenceType.FIELD)) {
                    val f = r as FieldReference
                    if (fields.add(FieldKey(f.definingClass, f.name, f.type))) { types += f.definingClass; types += f.type }
                }
                for (def in dex.classes) {
                    def.superclass?.let { types += it }
                    types += def.interfaces
                    for (m in def.methods) {
                        val impl = m.implementation ?: continue
                        for (insn in impl.instructions) {
                            ((insn as? ReferenceInstruction)?.reference as? TypeReference)?.let { types += it.type }
                            ((insn as? DualReferenceInstruction)?.reference2 as? TypeReference)?.let { types += it.type }
                        }
                        for (tb in impl.tryBlocks) for (h in tb.exceptionHandlers) h.exceptionType?.let { types += it }
                    }
                }
            }
            defs.mapValues { (_, def) -> InDex(def) }
        } catch (e: RuntimeException) {
            throw IllegalArgumentException("malformed dex", e) // dexlib2 parses lazily: a corrupt table throws while iterating
        }

        val out = sortedSetOf<String>()
        for (t in types) if (!typeOk(t, own)) out += t
        for (m in methods) {
            if (!typeOk(m.owner, own) || platformOwner(m.owner)) continue
            if (!resolves(m.owner, "${m.name}(${m.params})${m.ret}", "${m.name}(*)", own)) out += "${m.owner}->${m.name}(${m.params})${m.ret}"
        }
        for (f in fields) {
            if (!typeOk(f.owner, own) || platformOwner(f.owner)) continue
            if (!resolves(f.owner, "${f.name}:${f.type}", null, own)) out += "${f.owner}->${f.name}:${f.type}"
        }
        return out.toList()
    }

    private data class MethodKey(val owner: String, val name: String, val params: String, val ret: String)
    private data class FieldKey(val owner: String, val name: String, val type: String)

    /** A class the plugin defines: its supertypes and every member it declares, whatever the access. */
    private class InDex(def: ClassDef) {
        val supertypes: List<String> = listOfNotNull(def.superclass) + def.interfaces
        val members: Set<String> = def.methods.map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }.toSet() +
            def.fields.map { "${it.name}:${it.type}" }
    }

    private fun typeOk(desc: String, defs: Map<String, InDex>): Boolean {
        val e = desc.trimStart('[')
        return when {
            e.length == 1 -> e in PRIMITIVES
            e in defs -> true
            platformOwner(e) -> true
            else -> load(e) != null
        }
    }

    private fun load(desc: String): Class<*>? = classes.getOrPut(desc) {
        if (!desc.startsWith("L") || !desc.endsWith(";")) null
        else try { Class.forName(desc.substring(1, desc.length - 1).replace('/', '.'), false, loader) } catch (_: Throwable) { null }
    }

    /** Resolution walk: the owner, then its superclasses and interfaces (in the plugin's dex or through [loader]), then Object. */
    private fun resolves(owner: String, key: String, polymorphicKey: String?, defs: Map<String, InDex>): Boolean {
        if (owner.startsWith("[")) return key.startsWith("clone(") || resolves(OBJECT, key, null, defs)
        val seen = HashSet<String>()
        val queue = ArrayDeque<String>().apply { add(owner) }
        while (queue.isNotEmpty()) {
            val t = queue.removeFirst()
            if (!seen.add(t)) continue
            val def = defs[t]
            if (def != null) {
                if (key in def.members) return true
                queue += def.supertypes
            } else {
                val c = load(t) ?: continue
                val ms = membersOf(c)
                if (key in ms || (polymorphicKey != null && polymorphicKey in ms)) return true
                c.superclass?.let { queue += descriptor(it) }
                c.interfaces.forEach { queue += descriptor(it) }
            }
        }
        if (OBJECT in seen) return false
        return load(OBJECT)?.let { key in membersOf(it) } ?: false
    }

    /** Non-private declared members as `name(desc)ret` / `name:type`; signature-polymorphic methods also as `name(*)`. */
    private fun membersOf(c: Class<*>): Set<String> = members.getOrPut(c) {
        try {
            buildSet {
                for (m in c.declaredMethods) {
                    if (Modifier.isPrivate(m.modifiers)) continue
                    add("${m.name}(${m.parameterTypes.joinToString("") { descriptor(it) }})${descriptor(m.returnType)}")
                    if (Modifier.isNative(m.modifiers) && m.isVarArgs && c.name in POLYMORPHIC_OWNERS) add("${m.name}(*)")
                }
                for (k in c.declaredConstructors) {
                    if (!Modifier.isPrivate(k.modifiers)) add("<init>(${k.parameterTypes.joinToString("") { descriptor(it) }})V")
                }
                for (f in c.declaredFields) if (!Modifier.isPrivate(f.modifiers)) add("${f.name}:${descriptor(f.type)}")
            }
        } catch (_: Throwable) {
            emptySet() // a class whose members cannot be reflected (a signature type is missing) links nothing
        }
    }

    /** Binary names (`a.b.C$D`) of every class the `.cs3`'s dex files define; empty when they cannot be read. */
    fun definedClasses(cs3: File): Set<String> = try {
        readDexes(cs3).flatMapTo(HashSet()) { dex -> dex.classes.map { it.type.removePrefix("L").removeSuffix(";").replace('/', '.') } }
    } catch (_: RuntimeException) {
        emptySet()
    }

    private fun readDexes(f: File): List<DexBackedDexFile> {
        val zip = try { ZipFile(f) } catch (e: IOException) { throw IllegalArgumentException("not a zip", e) }
        return zip.use { z ->
            val entries = z.entries().asSequence().filter { DEX_NAME.matches(it.name) }.toList()
            require(entries.isNotEmpty()) { "no classes.dex" }
            var total = 0L
            entries.map { e ->
                val bytes = z.getInputStream(e).use { readCapped(it, MAX_DEX_BYTES - total) }
                total += bytes.size
                try { DexBackedDexFile(null, bytes) } catch (x: RuntimeException) { throw IllegalArgumentException("not a dex: ${e.name}", x) }
            }
        }
    }

    private fun readCapped(input: InputStream, cap: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return out.toByteArray()
            require(out.size() + n <= cap) { "dex too large" }
            out.write(buf, 0, n)
        }
    }

    companion object {
        private const val OBJECT = "Ljava/lang/Object;"
        private const val PRIMITIVES = "ZBSCIJFDV"
        private val DEX_NAME = Regex("^classes\\d*\\.dex$")
        private val POLYMORPHIC_OWNERS = setOf("java.lang.invoke.MethodHandle", "java.lang.invoke.VarHandle")
        /** Uncompressed dex across the whole `.cs3` (the file itself is capped at 20 MB). */
        private const val MAX_DEX_BYTES = 96L shl 20

        private val PRIMITIVE_DESCRIPTORS = mapOf<Class<*>, String>(
            java.lang.Boolean.TYPE to "Z", java.lang.Byte.TYPE to "B", java.lang.Short.TYPE to "S", java.lang.Character.TYPE to "C",
            java.lang.Integer.TYPE to "I", java.lang.Long.TYPE to "J", java.lang.Float.TYPE to "F", java.lang.Double.TYPE to "D",
            java.lang.Void.TYPE to "V",
        )

        internal fun descriptor(c: Class<*>): String = PRIMITIVE_DESCRIPTORS[c]
            ?: if (c.isArray) c.name.replace('.', '/') else "L${c.name.replace('.', '/')};"
    }
}
