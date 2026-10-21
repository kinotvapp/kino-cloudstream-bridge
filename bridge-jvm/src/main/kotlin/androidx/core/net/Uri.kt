// Apache-2.0 API shape (androidx.core), reimplemented for the JVM bridge.
package androidx.core.net

import android.net.Uri

fun String.toUri(): Uri = Uri.parse(this)

fun java.io.File.toUri(): Uri = Uri.parse(toURI().toString())
