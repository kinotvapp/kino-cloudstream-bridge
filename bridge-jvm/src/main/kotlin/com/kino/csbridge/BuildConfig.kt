package com.kino.csbridge

/** What AGP generates for the Android bridge; the JVM bridge's build number comes from the distribution. */
object BuildConfig {
    const val DEBUG = false
    const val UPSTREAM_COMMIT = "4e2516d56ecd42c22c52e461980625fe3f3d559a"
    val VERSION_CODE: Int = System.getProperty("kino.bridge.versionCode")?.toIntOrNull() ?: 1
}
