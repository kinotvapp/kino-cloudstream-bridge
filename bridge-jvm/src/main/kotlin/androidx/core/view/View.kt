// Apache-2.0 API shape (androidx.core), reimplemented for the JVM bridge.
package androidx.core.view

import android.view.View

var View.isVisible: Boolean
    get() = true
    set(_) = Unit

var View.isGone: Boolean
    get() = false
    set(_) = Unit
