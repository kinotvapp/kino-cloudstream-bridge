// GPL-3.0. JVM replacement for bridge/.../stubs/ImageModuleCoil.kt: no image loader at all.
package com.lagradost.cloudstream3.utils

import android.widget.ImageView

object ImageLoader {
    fun ImageView.loadImage(imageData: String?, headers: Map<String, String>? = null, builder: Any.() -> Unit = {}) = Unit
}
