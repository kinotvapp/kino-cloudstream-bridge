// GPL-3.0 (this fork). Headless CloudStream runtime for Kino. See BRIDGE.md.
plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin), as upstream's :app does: no kotlin.android plugin.
    alias(libs.plugins.android.application)
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
    buildFeatures { aidl = true; buildConfig = true }
    sourceSets["main"].aidl.srcDir("../bridge-contract/aidl")
    sourceSets["main"].java.srcDir("../bridge-contract/kotlin")
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

dependencies {
    implementation(project(":library"))
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
