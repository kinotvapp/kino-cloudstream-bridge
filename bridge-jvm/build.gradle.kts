// GPL-3.0 (this fork). The CloudStream bridge for a plain JVM (Windows desktop): the same ops and protocol as :bridge,
// run as a child process of Kino over stdin/stdout. See bridge-jvm/README.md.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
    // Same as :bridge, so the opt-in ABI matches what plugins were compiled against.
    compilerOptions { optIn.addAll("com.lagradost.cloudstream3.InternalAPI", "com.lagradost.cloudstream3.Prerelease") }
}

val upstreamAppSrc = layout.buildDirectory.dir("upstream-app-src")
val appSources = file("../bridge/app-sources.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
val copyAppSources by tasks.registering(Sync::class) {
    from(rootProject.file("app/src/main/java")) {
        appSources.forEach { include(it) }
        // Replaced by JVM versions in src/main/kotlin/jvmstubs (same FQCN and members).
        exclude("com/lagradost/cloudstream3/CloudStreamApp.kt")
    }
    into(upstreamAppSrc)
}

// Sources shared verbatim with :bridge (nothing is copied; the Android-only files stay out).
val bridgeKotlin = file("../bridge/src/main/kotlin")
val sharedFromBridge by tasks.registering(Sync::class) {
    from(bridgeKotlin) {
        include("com/kino/csbridge/**")
        // Android process, service and UI: replaced by the stdio service and the JVM shims.
        exclude(
            "com/kino/csbridge/BridgeApp.kt", "com/kino/csbridge/BridgeService.kt", "com/kino/csbridge/CallerCheck.kt",
            "com/kino/csbridge/ResultStream.kt", "com/kino/csbridge/settings/PluginSettingsActivity.kt", "com/kino/csbridge/settings/SettingsGuard.kt",
            "com/kino/csbridge/stubs/AppContextUtils.kt", "com/kino/csbridge/stubs/ImageModuleCoil.kt", "com/kino/csbridge/stubs/UIHelper.kt",
            // On the JVM the library already has com.lagradost.api.ContextHelper_jvmKt (the Android build names it _androidKt).
            "com/kino/csbridge/stubs/ContextHelper.jvm.kt",
        )
    }
    into(layout.buildDirectory.dir("bridge-shared-src"))
}

sourceSets {
    main {
        java.srcDir("src/shim/java")
        kotlin.srcDir("../bridge-contract/kotlin")
        kotlin.srcDir("../bridge-contract/desktop")
        kotlin.srcDir(upstreamAppSrc)
        kotlin.srcDir(layout.buildDirectory.dir("bridge-shared-src"))
    }
}
tasks.named("compileKotlin") { dependsOn(copyAppSources, sharedFromBridge) }

dependencies {
    implementation(project(":library"))
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jsoup)
    implementation(libs.ksoup)
    implementation(libs.ktor.http)
    implementation(libs.nicehttp)
    implementation(libs.rhino)
    implementation(libs.bundles.cryptography)
    implementation(libs.json)
    implementation(libs.annotation)
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("me.xdrop:fuzzywuzzy:1.4.0")
    implementation("com.android.tools.smali:smali-dexlib2:3.0.9")
    implementation("de.femtopedia.dex2jar:dex-translator:2.4.38")
    implementation("org.ow2.asm:asm:9.10.1")
    implementation("org.ow2.asm:asm-commons:9.10.1")
    // org.xmlpull.v1 is part of the Android platform; plugins parse XML with it.
    implementation("xmlpull:xmlpull:1.1.3.1")
    testImplementation(libs.junit)
}

application {
    mainClass.set("com.kino.csbridge.desktop.MainKt")
    // Plugin code recurses deeply (Jsoup, Jackson); the default 512 KB-1 MB stack is thin for some.
    applicationDefaultJvmArgs = listOf("-Xss2m")
}
