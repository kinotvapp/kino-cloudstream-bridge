// GPL-3.0 (this fork). Headless CloudStream runtime for Kino. See BRIDGE.md.
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
        buildConfigField("String", "KOTLIN_VERSION", "\"${KotlinVersion.CURRENT}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    buildFeatures { aidl = true; buildConfig = true }
    sourceSets["main"].aidl.srcDir("../bridge-contract/aidl")
    sourceSets["main"].kotlin.directories.add(file("../bridge-contract/kotlin").absolutePath)
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
