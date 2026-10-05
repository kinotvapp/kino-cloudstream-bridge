package com.kino.csbridge.plugins

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Synthetic `.cs3` files written with dexlib2. No real plugin binaries are downloaded or committed: each test builds the
 * exact references it needs. In src/sharedTest: compiled into both the JVM tests and androidTest.
 */
object DexFixtures {
    const val OBJECT = "Ljava/lang/Object;"
    const val STRING = "Ljava/lang/String;"
    const val EPISODE = "Lcom/lagradost/cloudstream3/Episode;"
    const val MAIN_API = "Lcom/lagradost/cloudstream3/MainAPI;"
    const val PLUGIN = "Lcom/lagradost/cloudstream3/plugins/Plugin;"

    /** The Episode constructor at the pinned upstream (score: Score, runTime: Int?). */
    val EPISODE_CTOR_CURRENT = listOf(STRING, STRING, "Ljava/lang/Integer;", "Ljava/lang/Integer;", STRING,
        "Lcom/lagradost/cloudstream3/Score;", STRING, "Ljava/lang/Long;", "Ljava/lang/Integer;")

    /** The `$default` Episode constructor older plugins were compiled against (rating: Int?): absent at the pinned upstream. */
    val EPISODE_CTOR_OLD = listOf(STRING, STRING, "Ljava/lang/Integer;", "Ljava/lang/Integer;", STRING,
        "Ljava/lang/Integer;", STRING, "Ljava/lang/Long;", "I", "Lkotlin/jvm/internal/DefaultConstructorMarker;")

    fun mref(owner: String, name: String, params: List<String>, ret: String) = ImmutableMethodReference(owner, name, params, ret)
    fun fref(owner: String, name: String, type: String) = ImmutableFieldReference(owner, name, type)

    fun invokeRange(op: Opcode, ref: ImmutableMethodReference, count: Int): Instruction = ImmutableInstruction3rc(op, 0, count, ref)
    fun invoke(op: Opcode, ref: ImmutableMethodReference, vararg regs: Int): Instruction {
        val r = regs + IntArray(5 - regs.size)
        return ImmutableInstruction35c(op, regs.size, r[0], r[1], r[2], r[3], r[4], ref)
    }
    fun newInstance(reg: Int, type: String): Instruction = ImmutableInstruction21c(Opcode.NEW_INSTANCE, reg, ImmutableTypeReference(type))
    fun constClass(reg: Int, type: String): Instruction = ImmutableInstruction21c(Opcode.CONST_CLASS, reg, ImmutableTypeReference(type))
    fun constString(reg: Int, s: String): Instruction = ImmutableInstruction21c(Opcode.CONST_STRING, reg, ImmutableStringReference(s))
    fun sget(reg: Int, f: ImmutableFieldReference): Instruction = ImmutableInstruction21c(Opcode.SGET_OBJECT, reg, f)
    fun returnVoid(): Instruction = ImmutableInstruction10x(Opcode.RETURN_VOID)
    fun throwReg(reg: Int): Instruction = ImmutableInstruction11x(Opcode.THROW, reg)

    const val CONSTRUCTOR = 0x10001 // ACC_PUBLIC | ACC_CONSTRUCTOR

    fun method(owner: String, name: String, params: List<String>, ret: String, flags: Int, registers: Int, code: List<Instruction>) =
        ImmutableMethod(owner, name, params.map { ImmutableMethodParameter(it, null, null) }, ret, flags, null, null,
            ImmutableMethodImplementation(registers, code, null, null))

    /** `public static void run()` whose body is [code]; [registers] must cover the registers the code names. */
    fun runner(type: String, registers: Int, code: List<Instruction>, superclass: String = OBJECT, interfaces: List<String> = emptyList()): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, superclass, interfaces, null, null, null,
            listOf(method(type, "run", emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, registers, code + returnVoid())))

    fun classDef(type: String, superclass: String, methods: List<ImmutableMethod>, interfaces: List<String> = emptyList()): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, superclass, interfaces, null, null, null, methods)

    fun dex(vararg classes: ClassDef): ByteArray {
        val store = MemoryDataStore()
        DexPool.writeTo(store, ImmutableDexFile(Opcodes.forApi(24), classes.toList()))
        return store.data
    }

    /** A `.cs3`-like zip: `classes.dex`, `classes2.dex`, … and `manifest.json`. */
    fun cs3(out: File, dexes: List<ByteArray>, manifest: String? = manifest("Fixture", "fixture.FixturePlugin")): File {
        ZipOutputStream(out.outputStream()).use { z ->
            dexes.forEachIndexed { i, d ->
                z.putNextEntry(ZipEntry(if (i == 0) "classes.dex" else "classes${i + 1}.dex")); z.write(d); z.closeEntry()
            }
            if (manifest != null) { z.putNextEntry(ZipEntry("manifest.json")); z.write(manifest.toByteArray()); z.closeEntry() }
        }
        return out
    }

    fun manifest(name: String, pluginClass: String, version: Int = 3) =
        """{"name":"$name","pluginClassName":"$pluginClass","version":$version,"requiresResources":false}"""

    fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    /** A dex calling the CURRENT Episode constructor: links against the bridge. */
    fun currentEpisodeDex(type: String = "Lfixture/Current;"): ByteArray = dex(runner(type, 10, listOf(
        newInstance(0, EPISODE),
        invokeRange(Opcode.INVOKE_DIRECT_RANGE, mref(EPISODE, "<init>", EPISODE_CTOR_CURRENT, "V"), 10),
    )))

    /** A dex calling the OLD `$default` Episode constructor: must be refused. */
    fun oldEpisodeDex(type: String = "Lfixture/Old;"): ByteArray = dex(runner(type, 11, listOf(
        newInstance(0, EPISODE),
        invokeRange(Opcode.INVOKE_DIRECT_RANGE, mref(EPISODE, "<init>", EPISODE_CTOR_OLD, "V"), 11),
    )))

    /**
     * A real, verifiable plugin: `fixture.DexPlugin extends Plugin` whose `load(Context)` registers `fixture.DexApi`
     * (`extends MainAPI`, name [providerName]); with [throwAfterRegister] it then throws IllegalStateException.
     */
    fun pluginDex(providerName: String = "DexFixture", throwAfterRegister: Boolean = false): ByteArray {
        val api = "Lfixture/DexApi;"
        val plugin = "Lfixture/DexPlugin;"
        val apiClass = classDef(api, MAIN_API, listOf(
            method(api, "<init>", emptyList(), "V", CONSTRUCTOR, 2, listOf(
                invoke(Opcode.INVOKE_DIRECT, mref(MAIN_API, "<init>", emptyList(), "V"), 1),
                constString(0, providerName),
                invoke(Opcode.INVOKE_VIRTUAL, mref(api, "setName", listOf(STRING), "V"), 1, 0),
                returnVoid())),
        ))
        val ise = "Ljava/lang/IllegalStateException;"
        val tail = if (throwAfterRegister) listOf(newInstance(0, ise), invoke(Opcode.INVOKE_DIRECT, mref(ise, "<init>", emptyList(), "V"), 0), throwReg(0))
            else listOf(returnVoid())
        val pluginClass = classDef(plugin, PLUGIN, listOf(
            method(plugin, "<init>", emptyList(), "V", CONSTRUCTOR, 1, listOf(
                invoke(Opcode.INVOKE_DIRECT, mref(PLUGIN, "<init>", emptyList(), "V"), 0), returnVoid())),
            method(plugin, "load", listOf("Landroid/content/Context;"), "V", 0x1, 3, listOf(
                newInstance(0, api),
                invoke(Opcode.INVOKE_DIRECT, mref(api, "<init>", emptyList(), "V"), 0),
                invoke(Opcode.INVOKE_VIRTUAL, mref(plugin, "registerMainAPI", listOf(MAIN_API), "V"), 1, 0),
            ) + tail),
        ))
        return dex(apiClass, pluginClass)
    }
}
