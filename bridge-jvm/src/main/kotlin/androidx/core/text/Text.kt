// Apache-2.0 API shape (androidx.core), reimplemented for the JVM bridge: no spans, the text is returned as is.
package androidx.core.text

import android.text.Spanned

object HtmlCompat {
    const val FROM_HTML_MODE_LEGACY = 63

    @JvmStatic
    fun fromHtml(source: String, flags: Int): Spanned = source.toSpanned()
}

private class PlainSpanned(private val text: String) : Spanned, CharSequence by text {
    override fun toString(): String = text
}

fun String.toSpanned(): Spanned = PlainSpanned(this)
