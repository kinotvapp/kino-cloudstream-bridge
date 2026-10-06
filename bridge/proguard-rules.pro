# GPL-3.0 (this fork). Bridge R8 rules.
#
# Plugins (.cs3) are dex files loaded at runtime by a PathClassLoader whose parent is this APK: every class, method and
# field they use is linked BY NAME against the classes here, and R8 cannot see any of those uses. So everything a
# plugin can reach is kept whole, unrenamed and unoptimised:
#   - the packages below (measured in docs/superpowers/research/cloudstream/02-cs3-api-surface.md §5: what real plugins
#     link against, compileOnly, never bundled), with every member, including the hidden compat constructors and their
#     synthetic `$default` forms (BRIDGE.md "Compat ABI");
#   - no renaming anywhere (-dontobfuscate): plugin code and the bridge's own reflection see the names they expect,
#     and crash logs stay readable;
#   - no optimisation (-dontoptimize): R8 must not devirtualise, inline or strip based on a closed-world view of a
#     class hierarchy that plugins extend at runtime (MainAPI, ExtractorApi, Interceptor, Plugin, ...).
# What is left to R8 is dropping classes nothing can reach: the dex parser and its Guava/protobuf (bridge-internal),
# unused parts of the bridge itself.
# verifyPluginAbi<Variant> (bridge/build.gradle.kts) runs the symbol oracle and release-symbols.tsv against the
# release dex and fails assembleRelease on any gap.

-dontobfuscate
-dontoptimize
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, Exceptions, MethodParameters, SourceFile, LineNumberTable

# CloudStream: :library (cloudstream3, api), the copied :app classes and the bridge-owned replacements
# (WebViewResolver, CloudflareKiller, the stubs). NiceHttp is com.lagradost.nicehttp.
-keep class com.lagradost.** { *; }

# Libraries plugins link against.
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class org.jsoup.** { *; }
-keep class com.fleeksoft.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class org.mozilla.** { *; }
-keep class io.ktor.** { *; }
-keep class dev.whyoleg.cryptography.** { *; }
-keep class com.google.gson.** { *; }
-keep class me.xdrop.** { *; }
-keep class coil3.** { *; }
-keep class org.conscrypt.** { *; }
-keep class org.schabi.newpipe.** { *; }
# Settings / donation UIs of about a third of all plugins reference these (appcompat, fragment, preference,
# recyclerview, core, lifecycle, ...). Nothing headless shows them, but the install-time ABI check (SymbolChecker)
# refuses a plugin that references a missing class, so release must hold what debug holds.
-keep class androidx.** { *; }

# Desktop-JDK APIs Android does not have, referenced by kept library code that only runs on a desktop JVM (Rhino's
# javax.script engine and dynalink linker, Jackson's and Rhino's java.beans support, Ktor's IDE debugger probe).
# Named one by one (never -dontwarn **): any other missing class still fails the build.
-dontwarn java.beans.**
-dontwarn java.lang.management.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**

# Ours: the AIDL contract (Kino binds to it) and the service.
-keep class com.kino.csbridge.contract.** { *; }
-keep class com.kino.csbridge.BridgeService { *; }

# RedirectFlag / IpcInterceptor read the calling client through this public method of OkHttp's internal call class
# (allowRedirects=false and the plugin interceptors the shim runs depend on it). okhttp3.** above keeps it already;
# named here so narrowing the package rule cannot silently drop it (release-symbols.tsv checks it in the release dex).
-keepclassmembers class okhttp3.internal.connection.RealCall {
    public okhttp3.OkHttpClient getClient();
}

# BridgeApp sets CloudStreamApp.context reflectively (CloudStreamContext.kt: initForBridge): the companion's private
# setter, and the backing field written when the setter is missing. Without them getKey/setKey silently do nothing.
-keepclassmembers class com.lagradost.cloudstream3.CloudStreamApp$Companion {
    private void setContext(android.content.Context);
}
-keepclassmembers class com.lagradost.cloudstream3.CloudStreamApp {
    private static java.lang.ref.WeakReference _context;
}
