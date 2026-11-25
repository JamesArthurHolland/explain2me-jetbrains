package dev.e2m.core

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.project.Project

/** Expand / collapse only the fold regions that belong to e2m markers. */
object E2mFolding {
    fun e2mRegions(editor: Editor): List<FoldRegion> {
        val doc = editor.document
        return editor.foldingModel.allFoldRegions.filter { r ->
            r.isValid && (r.group?.toString()?.startsWith(E2mFoldingBuilder.GROUP_PREFIX) == true ||
                E2mMarkers.isMarkerText(doc.charsSequence.subSequence(r.startOffset, r.endOffset)))
        }
    }

    /** @param expand null toggles: collapses if any region is expanded, otherwise expands. */
    fun apply(editor: Editor, expand: Boolean?): Int {
        val regions = e2mRegions(editor)
        if (regions.isEmpty()) return 0
        val target = expand ?: regions.none { it.isExpanded }
        editor.foldingModel.runBatchFoldingOperation {
            regions.forEach { it.isExpanded = target }
        }
        return regions.size
    }

    fun applyToProject(project: Project, expand: Boolean): Int =
        EditorFactory.getInstance().allEditors.filter { it.project == project }.sumOf { apply(it, expand) }
}
