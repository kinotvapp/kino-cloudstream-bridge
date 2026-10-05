package com.kino.csbridge.plugins

import com.android.tools.smali.dexlib2.Opcode
import com.kino.csbridge.plugins.DexFixtures.EPISODE
import com.kino.csbridge.plugins.DexFixtures.MAIN_API
import com.kino.csbridge.plugins.DexFixtures.OBJECT
import com.kino.csbridge.plugins.DexFixtures.STRING
import com.kino.csbridge.plugins.DexFixtures.classDef
import com.kino.csbridge.plugins.DexFixtures.constClass
import com.kino.csbridge.plugins.DexFixtures.cs3
import com.kino.csbridge.plugins.DexFixtures.dex
import com.kino.csbridge.plugins.DexFixtures.fref
import com.kino.csbridge.plugins.DexFixtures.invoke
import com.kino.csbridge.plugins.DexFixtures.invokeRange
import com.kino.csbridge.plugins.DexFixtures.method
import com.kino.csbridge.plugins.DexFixtures.mref
import com.kino.csbridge.plugins.DexFixtures.newInstance
import com.kino.csbridge.plugins.DexFixtures.returnVoid
import com.kino.csbridge.plugins.DexFixtures.runner
import com.kino.csbridge.plugins.DexFixtures.sget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Plain JVM: the test classpath is the :bridge runtime classpath (plus the mockable android.jar). */
class SymbolCheckerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val loader = javaClass.classLoader!!
    private fun missing(vararg dexes: ByteArray) = SymbolChecker(loader).missing(cs3(tmp.newFile(), dexes.toList()))

    @Test fun `current Episode constructor resolves`() = assertEquals(emptyList<String>(), missing(DexFixtures.currentEpisodeDex()))

    @Test fun `old Episode default constructor resolves through the compat constructor`() =
        assertEquals(emptyList<String>(), missing(DexFixtures.oldEpisodeDex()))

    @Test fun `a member that stays refused is reported exactly`() {
        assertEquals(listOf(DexFixtures.GET_APIS_AS_LIST), missing(DexFixtures.refusedSymbolDex()))
    }

    @Test fun `member inherited through an interface resolves`() {
        // ArrayList declares no stream(): it is Collection's default method, reached through List.
        val d = dex(runner("Lfixture/Iface;", 1, listOf(
            invoke(Opcode.INVOKE_VIRTUAL, mref("Ljava/util/ArrayList;", "stream", emptyList(), "Ljava/util/stream/Stream;"), 0),
        )))
        assertEquals(emptyList<String>(), missing(d))
    }

    @Test fun `member inherited through an in-dex subclass resolves and an absent one is reported`() {
        val api = classDef("Lfixture/MyApi;", MAIN_API, listOf(
            method("Lfixture/MyApi;", "<init>", emptyList(), "V", DexFixtures.CONSTRUCTOR, 1, listOf(
                invoke(Opcode.INVOKE_DIRECT, mref(MAIN_API, "<init>", emptyList(), "V"), 0), returnVoid())),
        ))
        val user = runner("Lfixture/User;", 1, listOf(
            newInstance(0, "Lfixture/MyApi;"),
            invoke(Opcode.INVOKE_DIRECT, mref("Lfixture/MyApi;", "<init>", emptyList(), "V"), 0),
            invoke(Opcode.INVOKE_VIRTUAL, mref("Lfixture/MyApi;", "getMainUrl", emptyList(), STRING), 0),
            invoke(Opcode.INVOKE_VIRTUAL, mref("Lfixture/MyApi;", "getNoSuchThing", emptyList(), STRING), 0),
        ))
        assertEquals(listOf("Lfixture/MyApi;->getNoSuchThing()$STRING"), missing(dex(api, user)))
    }

    @Test fun `missing class, missing field and private member are reported`() {
        val d = dex(runner("Lfixture/Bad;", 1, listOf(
            constClass(0, "Lcom/lagradost/cloudstream3/NoSuchClass;"),
            sget(0, fref(MAIN_API, "NO_SUCH_FIELD", STRING)),
            // A private member exists but is not linkable from plugin code.
            sget(0, fref(STRING, "value", "[B")),
        )))
        assertEquals(listOf(
            "Lcom/lagradost/cloudstream3/MainAPI;->NO_SUCH_FIELD:$STRING",
            "Lcom/lagradost/cloudstream3/NoSuchClass;",
            "Ljava/lang/String;->value:[B",
        ), missing(d))
    }

    @Test fun `classes defined in another dex of the same plugin are not missing`() {
        val a = dex(runner("Lfixture/A;", 1, listOf(invoke(Opcode.INVOKE_STATIC, mref("Lfixture/B;", "run", emptyList(), "V")))))
        val b = dex(runner("Lfixture/B;", 1, emptyList()))
        assertEquals(emptyList<String>(), missing(a, b))
    }

    @Test fun `kotlin default variants and statics resolve as ordinary methods`() {
        // The top-level helper newEpisode(url, initializer = {}, fix = true) compiles to a static synthetic newEpisode$default.
        val d = dex(runner("Lfixture/Def;", 6, listOf(
            invokeRange(Opcode.INVOKE_STATIC_RANGE, mref("Lcom/lagradost/cloudstream3/MainAPIKt;", "newEpisode\$default",
                listOf(MAIN_API, STRING, "Lkotlin/jvm/functions/Function1;", "Z", "I", OBJECT), EPISODE), 6),
            invoke(Opcode.INVOKE_STATIC, mref("Ljava/lang/String;", "valueOf", listOf(OBJECT), STRING), 0),
            // The full Episode constructor's $default sibling as it exists today.
            invokeRange(Opcode.INVOKE_DIRECT_RANGE, mref(EPISODE, "<init>", DexFixtures.EPISODE_CTOR_CURRENT +
                listOf("I", "Lkotlin/jvm/internal/DefaultConstructorMarker;"), "V"), 6),
        )))
        assertEquals(emptyList<String>(), missing(d))
    }

    @Test fun `a file without dex is refused`() {
        val f = cs3(tmp.newFile(), emptyList())
        assertThrows(IllegalArgumentException::class.java) { SymbolChecker(loader).missing(f) }
    }

    @Test fun `junk bytes are refused`() {
        val f = tmp.newFile().apply { writeBytes(ByteArray(100) { 7 }) }
        assertThrows(IllegalArgumentException::class.java) { SymbolChecker(loader).missing(f) }
    }

    @Test fun `many missing symbols stay sorted and distinct`() {
        val d = dex(runner("Lfixture/Twice;", 1, listOf(
            constClass(0, "Lzz/Missing;"), constClass(0, "Lzz/Missing;"), constClass(0, "Laa/Missing;"),
        )))
        val m = missing(d)
        assertEquals(listOf("Laa/Missing;", "Lzz/Missing;"), m)
        assertTrue(m.size == m.distinct().size)
    }
}
