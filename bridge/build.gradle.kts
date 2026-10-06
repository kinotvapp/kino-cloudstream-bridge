// GPL-3.0 (this fork). Headless CloudStream runtime for Kino. See BRIDGE.md.
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin), as upstream's :app does: no kotlin.android plugin.
    alias(libs.plugins.android.application)
    // Upstream :app applies it too; the included app files use @Serializable (generated serializers are ABI).
    alias(libs.plugins.kotlin.serialization)
}

// The upstream commit this fork is pinned at. A constant, not `git rev-parse`, so builds are
// reproducible offline. Bump it together with the rebase onto a newer upstream commit.
val upstreamCommit = "4e2516d56ecd42c22c52e461980625fe3f3d559a"

android {
    namespace = "com.kino.csbridge"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "com.kino.csbridge"
        minSdk = 24
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = (project.findProperty("bridgeVersionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("bridgeVersionName") as String?) ?: "0.1.0"
        buildConfigField("String", "UPSTREAM_COMMIT", "\"$upstreamCommit\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    buildFeatures { aidl = true; buildConfig = true }
    sourceSets["main"].aidl.srcDir("../bridge-contract/aidl")
    sourceSets["main"].kotlin.directories.add(file("../bridge-contract/kotlin").absolutePath)
    // Test helpers both the JVM tests and the on-device tests compile (dexlib2-written .cs3 fixtures).
    sourceSets["test"].kotlin.directories.add(file("src/sharedTest/kotlin").absolutePath)
    sourceSets["androidTest"].kotlin.directories.add(file("src/sharedTest/kotlin").absolutePath)
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        create("kino") {
            System.getenv("KINO_KEYSTORE_PATH")?.let { storeFile = file(it) }
            storePassword = System.getenv("KINO_KEYSTORE_PASSWORD"); keyAlias = System.getenv("KINO_KEY_ALIAS"); keyPassword = System.getenv("KINO_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("KINO_KEYSTORE_PATH") != null) signingConfig = signingConfigs.getByName("kino")
        }
    }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST", "**/module-info.class") }
    // Keep META-INF/*.kotlin_module: stripping it breaks resolution of top-level declarations (spike finding).
}

// ---- Upstream :app classes plugins link against ----------------------------------------------------------
// The files listed in app-sources.txt are copied unchanged from upstream app/src/main/java into the build dir and
// compiled here; what would drag UI in is replaced by headless stubs in src/main/kotlin/com/kino/csbridge/stubs.
val appSources = file("app-sources.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
val upstreamAppSrc = layout.buildDirectory.dir("upstream-app-src")
val copyAppSources by tasks.registering(Sync::class) {
    from(rootProject.file("app/src/main/java")) { appSources.forEach { include(it) } }
    into(upstreamAppSrc)
}
// AGP 9 built-in Kotlin has no `kotlin { sourceSets }` block: the android source set's kotlin directories are what
// it compiles (srcDir() is deprecated there). preBuild runs before every compile task, so the copy is in place.
android.sourceSets["main"].kotlin.directories.add(upstreamAppSrc.get().asFile.absolutePath)
tasks.named("preBuild") { dependsOn(copyAppSources) }

// ---- Upstream :library classes the bridge replaces ------------------------------------------------------------
// The library's Android WebViewResolver creates a real WebView, which this process must never do. The bridge has its
// own class with the same name and ABI (src/main/kotlin/com/kino/csbridge/stubs/WebViewResolver.kt) that asks Kino's
// hidden WebView instead (IHost.webView). The library's compiled copy (the class and its inner classes, NOT the
// WebViewResolver_androidKt helpers) is dropped from every classpath of this module by an artifact transform on the
// :library class artifacts: upstream sources stay untouched, and `android { packaging }` cannot drop classes.
// Library extractors that call WebViewResolver (StreamWish, Filemoon, ...) get the bridge's class too.
val replacedLibraryClasses = listOf("com/lagradost/cloudstream3/network/WebViewResolver")
val replacedClassesDropped = Attribute.of("com.kino.csbridge.replacedClassesDropped", Boolean::class.javaObjectType)

abstract class DropReplacedLibraryClasses : TransformAction<DropReplacedLibraryClasses.Parameters> {
    interface Parameters : TransformParameters {
        /** Internal class names (`a/b/C`): `a/b/C.class` and every `a/b/C$*.class` are dropped. */
        @get:Input val classNames: ListProperty<String>
    }

    // Class artifacts: classpath normalisation (entry paths and contents count, timestamps and the file's name don't).
    @get:InputArtifact @get:Classpath
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val input = inputArtifact.get().asFile
        val names = parameters.classNames.get()
        fun dropped(path: String) = path.endsWith(".class") && names.any { path == "$it.class" || path.startsWith("$it\$") }
        when {
            input.isDirectory -> {
                val files = input.walkTopDown().filter { it.isFile }.toList()
                if (files.none { dropped(it.relativeTo(input).invariantSeparatorsPath) }) { outputs.dir(input); return }
                val out = outputs.dir(input.name + "-bridge")
                files.forEach { f ->
                    val rel = f.relativeTo(input).invariantSeparatorsPath
                    if (!dropped(rel)) f.copyTo(out.resolve(rel), overwrite = true)
                }
            }
            input.isFile && input.name.endsWith(".jar") -> {
                val any = ZipFile(input).use { z -> z.entries().asSequence().any { dropped(it.name) } }
                if (!any) { outputs.file(input); return }
                val out = outputs.file(input.nameWithoutExtension + "-bridge.jar")
                ZipInputStream(input.inputStream().buffered()).use { zin ->
                    ZipOutputStream(out.outputStream().buffered()).use { zout ->
                        while (true) {
                            val e = zin.nextEntry ?: break
                            if (dropped(e.name)) continue
                            // Same time as the input entry (a fixed one when it has none): a reproducible jar.
                            zout.putNextEntry(ZipEntry(e.name).apply { time = if (e.time >= 0) e.time else 315532800000L })
                            zin.copyTo(zout)
                            zout.closeEntry()
                        }
                    }
                }
            }
            else -> outputs.file(input)
        }
    }
}

// Gradle only runs a transform when no variant matches as is, and a variant that says nothing about an attribute
// matches any requested value. So :library's class variants must SAY they still hold the replaced classes. That is
// declared here, on :library's model, from this script (library/build.gradle.kts is upstream and stays untouched).
// Only the class-artifact secondary variants carry it (never the configuration: graph resolution would then pick
// :library's JVM target instead); every other :library artifact matches as is, and consumers that don't ask for the
// attribute (upstream :app) see no difference.
// Gradle refuses two transform chains of the same length, and AGP can reach dex both from these variants and from
// :library's primary `jar` variant. So the directory is stripped straight into AGP's `android-classes` (the input of its
// dexing), one step shorter than any other chain; jar requests (compile, unit tests) get the stripped classes jar.
// HostWebViewResolverTest checks the result: one WebViewResolver on the test classpath, the bridge's.
val strippedClassVariants = mapOf(
    "android-classes-jar" to "android-classes-jar",
    "android-classes-directory" to "android-classes", // AGP's variant name is "android-classes-directory-A...": prefix
)
project(":library").configurations.matching { it.name == "androidRuntimeElements" || it.name == "androidApiElements" }.configureEach {
    outgoing.variants.matching { v -> strippedClassVariants.keys.any { v.name == it || v.name.startsWith("$it-") } }.configureEach {
        attributes.attribute(replacedClassesDropped, false)
    }
}

dependencies {
    attributesSchema { attribute(replacedClassesDropped) }
    strippedClassVariants.forEach { (from, to) ->
        registerTransform(DropReplacedLibraryClasses::class) {
            this.from.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, from).attribute(replacedClassesDropped, false)
            this.to.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, to).attribute(replacedClassesDropped, true)
            parameters { classNames.set(replacedLibraryClasses) }
        }
    }
}
// Guard 1, at configuration time: the transform only runs for :library variants tagged above. If an AGP or upstream bump
// renames them, nothing is tagged and the library's WebViewResolver silently comes back (D8/R8 would then fail late on
// a duplicate class, with a message that does not name the cause). Today 3 are tagged: runtime jar + directory, api jar.
gradle.projectsEvaluated {
    val lib = project(":library")
    val tagged = listOf("androidRuntimeElements", "androidApiElements").mapNotNull { lib.configurations.findByName(it) }
        .sumOf { c -> c.outgoing.variants.count { it.attributes.getAttribute(replacedClassesDropped) == false } }
    if (tagged < 2) throw GradleException(
        "bridge: only $tagged :library class variant(s) carry replacedClassesDropped=false, so DropReplacedLibraryClasses " +
            "would not run and the library's WebViewResolver (a real WebView) would stay. Update strippedClassVariants " +
            "in bridge/build.gradle.kts to the variant names AGP now gives :library.",
    )
}

configurations.configureEach {
    if (isCanBeResolved && (name.endsWith("RuntimeClasspath") || name.endsWith("CompileClasspath"))) {
        attributes.attribute(replacedClassesDropped, true)
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions {
        // Same as upstream :app, so interface and opt-in ABI matches what plugins were compiled against.
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.ENABLE)
        optIn.addAll("com.lagradost.cloudstream3.InternalAPI", "com.lagradost.cloudstream3.Prerelease")
    }
}

dependencies {
    implementation(project(":library"))
    // UI base classes the headless stubs keep in their supertypes (as upstream :app declares them).
    implementation(libs.appcompat)
    implementation(libs.fragment.ktx)
    // Mirrors upstream :app / :library runtime dependencies for plugins (catalog aliases, same versions).
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.io.core)
    implementation(libs.kotlinx.atomicfu)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jsoup)
    implementation(libs.ksoup)
    implementation(libs.ktor.http)
    implementation(libs.nicehttp)
    implementation(libs.rhino)
    implementation(libs.bundles.cryptography)
    implementation(libs.bundles.coil)
    implementation(libs.conscrypt.android)
    implementation(libs.core.ktx)
    implementation(libs.annotation)
    implementation(libs.preference.ktx)
    // Deprecated by upstream but still given to plugins; literal versions exactly as upstream's :app declares them.
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("me.xdrop:fuzzywuzzy:1.4.0")
    implementation("com.android.tools.smali:smali-dexlib2:3.0.9") // ABI check (Task 5)
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation("org.robolectric:robolectric:4.16") // not in upstream's catalog; test only
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.core)
    androidTestImplementation("androidx.test:runner:1.7.0") // AndroidJUnitRunner; same AndroidX Test release as ext-junit 1.3.0
}

// ---- Symbol-coverage oracle (scripts/symbol-coverage.py) -------------------------------------------------
// Every symbol in src/test/resources/cs3-symbols.tsv (measured across 583 real .cs3 plugins) must resolve on the
// debug classpath: our compiled classes + :library + dependency class jars + android.jar, minus symbol-allowlist.txt.
val oracleClasspathFile = layout.buildDirectory.file("symbol-coverage/debug-classpath.txt")
val oracleDependencyJars = provider {
    // The variant configuration exists only after AGP finishes configuring, hence the lazy lookup.
    configurations.getByName("debugRuntimeClasspath").incoming.artifactView {
        attributes { attribute(Attribute.of("artifactType", String::class.java), "android-classes-jar") }
    }.files
}
val oracleBootClasspath = provider { }.flatMap { androidComponents.sdkComponents.bootClasspath } // android.jar; read late
val oracleClassDirs = listOf(
    layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"),
    layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes"),
)
val writeOracleClasspath by tasks.registering {
    description = "Writes the debug class dirs + class jars the symbol oracle reads."
    dependsOn("compileDebugKotlin", "compileDebugJavaWithJavac")
    inputs.files(oracleDependencyJars)
    inputs.files(oracleBootClasspath)
    outputs.file(oracleClasspathFile)
    // Locals only inside doLast: script-level references cannot be stored in the configuration cache.
    val dirs = oracleClassDirs.map { it.get().asFile }
    val jars = oracleDependencyJars
    val boot = oracleBootClasspath
    val out = oracleClasspathFile
    doLast {
        val lines = dirs.map { it.absolutePath } + jars.get().files.map { it.absolutePath } + boot.get().map { it.asFile.absolutePath }
        out.get().asFile.apply { parentFile.mkdirs(); writeText(lines.joinToString("\n") + "\n") }
    }
}
val symbolCoverageSelfTest by tasks.registering(Exec::class) {
    description = "Unit tests of the symbol oracle's class-file reader, resolution walk and allowlist parser."
    group = "verification"
    val script = rootProject.file("scripts/test_symbol_coverage.py")
    inputs.files(script, rootProject.file("scripts/symbol-coverage.py"))
    commandLine("python3", script.absolutePath)
}
val symbolCoverage by tasks.registering(Exec::class) {
    description = "Fails when a symbol real CloudStream plugins link against is missing from the bridge classpath."
    group = "verification"
    dependsOn(writeOracleClasspath, symbolCoverageSelfTest)
    val script = rootProject.file("scripts/symbol-coverage.py")
    val tsv = file("src/test/resources/cs3-symbols.tsv")
    val allow = file("symbol-allowlist.txt")
    inputs.files(script, tsv, oracleClasspathFile)
    inputs.files(allow).optional()
    commandLine(
        "python3", script.absolutePath, "--symbols", tsv.absolutePath, "--allowlist", allow.absolutePath,
        "--classpath-file", oracleClasspathFile.get().asFile.absolutePath,
    )
}
tasks.named("check") { dependsOn(symbolCoverage) }

// ---- Guard 2: the APK itself (every variant, release included) ----------------------------------------------------
// Scans the dex of the variant's APK: exactly one com.lagradost.cloudstream3.network.WebViewResolver, none of the
// library copy's inner classes, and no method of android.webkit.WebView / CookieManager referenced anywhere (calling
// one would load the WebView provider into this process), except the allowlisted ones below. Wired into assemble<Variant>.
abstract class VerifyReplacedClasses : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val apkDir: DirectoryProperty
    @get:OutputFile abstract val report: RegularFileProperty

    private class Dex(val b: ByteBuffer) {
        fun u4(at: Int) = b.getInt(at)
        fun u2(at: Int) = b.getShort(at).toInt() and 0xffff
        fun string(i: Int): String {
            var p = u4(u4(0x3C) + 4 * i)
            while (b.get(p).toInt() and 0x80 != 0) p++ // skip the uleb128 utf16 length
            p++
            val out = ByteArrayOutputStream()
            while (true) { val c = b.get(p++); if (c.toInt() == 0) break; out.write(c.toInt()) }
            return out.toString(Charsets.UTF_8) // MUTF-8: exact for the ASCII names checked here
        }
        fun type(i: Int) = string(u4(u4(0x44) + 4 * i))
        fun classes() = (0 until u4(0x60)).map { type(u4(u4(0x64) + 32 * it)) }
        fun methods() = (0 until u4(0x58)).map { val at = u4(0x5C) + 8 * it; type(u2(at)) + "->" + string(u4(at + 4)) }
    }

    @TaskAction fun verify() {
        val apks = apkDir.get().asFile.walkTopDown().filter { it.isFile && it.name.endsWith(".apk") }.toList()
        if (apks.isEmpty()) throw GradleException("verifyReplacedClasses: no APK in ${apkDir.get().asFile}")
        val lines = mutableListOf<String>()
        for (apk in apks) {
            val classes = mutableListOf<String>()
            val methods = mutableSetOf<String>()
            ZipFile(apk).use { z ->
                z.entries().asSequence().filter { it.name.matches(Regex("classes\\d*\\.dex")) }.forEach { e ->
                    val dex = Dex(ByteBuffer.wrap(z.getInputStream(e).readBytes()).order(ByteOrder.LITTLE_ENDIAN))
                    classes += dex.classes(); methods += dex.methods()
                }
            }
            val resolver = "Lcom/lagradost/cloudstream3/network/WebViewResolver;"
            val problems = mutableListOf<String>()
            val n = classes.count { it == resolver }
            if (n != 1) problems += "$n definitions of WebViewResolver (expected exactly 1, the bridge's)"
            classes.filter { it.startsWith("Lcom/lagradost/cloudstream3/network/WebViewResolver\$resolveUsingWebView\$") }
                .forEach { problems += "library WebViewResolver inner class $it" }
            methods.filter { (it.startsWith("Landroid/webkit/WebView;->") || it.startsWith("Landroid/webkit/CookieManager;->")) && it !in WEBKIT_ALLOWED }
                .forEach { problems += "webkit method referenced: $it" }
            if (problems.isNotEmpty()) throw GradleException("verifyReplacedClasses ${apk.name}:\n  " + problems.joinToString("\n  "))
            lines += "${apk.name}: ${classes.size} classes, WebViewResolver x1, no WebView/CookieManager use"
        }
        report.get().asFile.apply { parentFile.mkdirs(); writeText(lines.joinToString("\n") + "\n") }
    }

    companion object {
        /**
         * androidx.core LinkifyCompat calls the static WebView.findAddress (Linkify MAP_ADDRESSES); nothing headless
         * reaches it (Task 8 review F13).
         */
        val WEBKIT_ALLOWED = setOf("Landroid/webkit/WebView;->findAddress")
    }
}

// ---- Guard 3: the plugin ABI in the APK's dex (release: after R8) ------------------------------------------------
// The symbol oracle (cs3-symbols.tsv minus symbol-allowlist.txt) and release-symbols.tsv (what the bridge itself needs
// by name: reflection targets, the compat constructors and their $default forms) run against the variant's dex + the
// boot classpath. A keep-rule gap in proguard-rules.pro then fails the build, not a plugin on a TV. Wired into
// assemble<Variant> for minified variants (debug has no R8 step; symbolCoverage covers its classpath).
abstract class VerifyPluginAbi : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val apkDir: DirectoryProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val bootClasspath: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val script: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val symbols: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val allowlist: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val reflective: RegularFileProperty
    @get:OutputFile abstract val report: RegularFileProperty
    @get:Inject abstract val execOps: ExecOperations

    @TaskAction fun verify() {
        val apks = apkDir.get().asFile.walkTopDown().filter { it.isFile && it.name.endsWith(".apk") }.toList()
        if (apks.isEmpty()) throw GradleException("verifyPluginAbi: no APK in ${apkDir.get().asFile}")
        val lines = mutableListOf<String>()
        for (apk in apks) {
            val out = ByteArrayOutputStream()
            val result = execOps.exec {
                commandLine(
                    listOf("python3", script.get().asFile.absolutePath, "--symbols", symbols.get().asFile.absolutePath,
                        "--allowlist", allowlist.get().asFile.absolutePath, "--reflective", reflective.get().asFile.absolutePath,
                        apk.absolutePath) + bootClasspath.files.map { it.absolutePath },
                )
                standardOutput = out; errorOutput = out
                isIgnoreExitValue = true
            }
            val text = out.toString(Charsets.UTF_8)
            if (result.exitValue != 0) throw GradleException("verifyPluginAbi ${apk.name}: plugin ABI missing from the dex\n$text")
            lines += "${apk.name}:"; lines += text.lines().filter { it.isNotBlank() }
        }
        report.get().asFile.apply { parentFile.mkdirs(); writeText(lines.joinToString("\n") + "\n") }
    }
}

androidComponents.onVariants { variant ->
    val cap = variant.name.replaceFirstChar { it.uppercase() }
    val apk = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK)
    val verify = tasks.register<VerifyReplacedClasses>("verifyReplacedClasses$cap") {
        description = "Fails when the $cap APK holds the library's WebViewResolver or calls WebView/CookieManager."
        group = "verification"
        apkDir.set(apk)
        report.set(layout.buildDirectory.file("verifyReplacedClasses/${variant.name}.txt"))
    }
    val abi = tasks.register<VerifyPluginAbi>("verifyPluginAbi$cap") {
        description = "Fails when a symbol plugins link against (or the bridge reaches by name) is missing from the $cap dex."
        group = "verification"
        apkDir.set(apk)
        bootClasspath.from(oracleBootClasspath)
        script.set(rootProject.file("scripts/symbol-coverage.py"))
        symbols.set(file("src/test/resources/cs3-symbols.tsv"))
        allowlist.set(file("symbol-allowlist.txt"))
        reflective.set(file("release-symbols.tsv"))
        report.set(layout.buildDirectory.file("verifyPluginAbi/${variant.name}.txt"))
    }
    val minified = (variant as? com.android.build.api.variant.CanMinifyCode)?.isMinifyEnabled == true
    tasks.matching { it.name == "assemble$cap" }.configureEach { dependsOn(verify); if (minified) dependsOn(abi) }
}
