// Apache-2.0 API shape (androidx.core), reimplemented for the JVM bridge.
package androidx.core.content

import android.content.SharedPreferences

inline fun SharedPreferences.edit(commit: Boolean = false, action: SharedPreferences.Editor.() -> Unit) {
    val editor = edit()
    action(editor)
    if (commit) editor.commit() else editor.apply()
}
