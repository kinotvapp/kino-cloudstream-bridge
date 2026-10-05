// Headless stand-in for upstream app/src/main/java/com/lagradost/cloudstream3/utils/ImageModuleCoil.kt: same ABI, no UI. Members from cs3-symbols.tsv and those the included upstream files use.
package com.lagradost.cloudstream3.utils

import android.widget.ImageView
import coil3.PlatformContext
import coil3.request.ImageRequest

/** No images are shown in the bridge: a bare coil loader for CloudStreamApp, and loadImage does nothing. */
object ImageLoader {
    fun buildImageLoader(context: PlatformContext): coil3.ImageLoader = coil3.ImageLoader.Builder(context).build()

    fun ImageView.loadImage(
        imageData: String?,
        headers: Map<String, String>? = null,
        builder: ImageRequest.Builder.() -> Unit = {}
    ) = Unit
}
