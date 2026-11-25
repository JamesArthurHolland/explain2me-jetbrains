package dev.e2m.core

import com.intellij.openapi.project.Project
import java.nio.file.Files

/** Reads the explanation text behind a marker. */
object E2mNotes {
    fun text(project: Project, marker: E2mMarkers.Marker): String? = when (marker.kind) {
        E2mMarkers.Kind.INLINE -> marker.payload
        E2mMarkers.Kind.FILE -> runCatching {
            val p = E2mPaths.notePath(project, marker.payload)
            if (Files.exists(p)) Files.readString(p) else null
        }.getOrNull()
        E2mMarkers.Kind.END -> null
    }

    /** First markdown heading (or first line) of a note, used for fold placeholders. */
    fun title(noteText: String?): String? =
        noteText?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }?.trimStart('#')?.trim()
}
