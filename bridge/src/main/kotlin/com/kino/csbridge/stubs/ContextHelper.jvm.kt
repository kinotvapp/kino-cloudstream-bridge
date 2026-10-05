// Headless stand-in for upstream library/src/jvmMain/kotlin/com/lagradost/api/ContextHelper.jvm.kt: same ABI, no UI. Members from cs3-symbols.tsv.
// Plugins compile against upstream's library-jvm jar, whose facade is ContextHelper_jvmKt; on Android :library names
// it ContextHelper_androidKt, so a plugin calling getContext() would not link. This facade forwards to the Android one.
@file:JvmName("ContextHelper_jvmKt")

package com.lagradost.api

@JvmName("getContext")
fun jvmFacadeGetContext(): Any? = getContext()
