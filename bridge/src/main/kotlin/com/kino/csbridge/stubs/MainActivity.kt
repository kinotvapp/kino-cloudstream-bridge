// Headless stand-in for upstream app/src/main/java/com/lagradost/cloudstream3/MainActivity.kt: same ABI, no UI. Members from cs3-symbols.tsv.
package com.lagradost.cloudstream3

import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.utils.Event
import java.io.File

/** Never declared in the manifest nor started; it exists so plugin references to its companion resolve. */
class MainActivity : AppCompatActivity() {
    companion object {
        val afterPluginsLoadedEvent = Event<Boolean>()
        val mainPluginsLoadedEvent = Event<Boolean>()
        val afterRepositoryLoadedEvent = Event<Boolean>()
        val bookmarksUpdatedEvent = Event<Boolean>()
        val reloadHomeEvent = Event<Boolean>()
        val reloadLibraryEvent = Event<Boolean>()
        val reloadAccountEvent = Event<Boolean>()

        var filesToDelete: Set<String> = setOf()

        fun deleteFileOnExit(file: File) {
            synchronized(this) { filesToDelete = filesToDelete + file.path }
        }
    }
}
