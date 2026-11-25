package dev.e2m.core

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.FoldingGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPlainTextFile
import com.intellij.psi.util.PsiTreeUtil

/**
 * Registered for language "any", so it runs next to the language's own folding builder.
 * Every e2m marker comment becomes its own fold region; the e2m actions only touch these regions,
 * which is what makes "collapse/expand e2m explanations" independent from other comments.
 */
class E2mFoldingBuilder : FoldingBuilderEx(), DumbAware {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val text = document.charsSequence
        if (!E2mMarkers.mayContain(text)) return FoldingDescriptor.EMPTY_ARRAY
        val file = root.containingFile ?: return FoldingDescriptor.EMPTY_ARRAY
        val checkPsi = file !is PsiPlainTextFile
        val project = root.project
        val result = ArrayList<FoldingDescriptor>()
        for (m in E2mMarkers.findAll(text)) {
            if (m.end - m.start < 3) continue
            if (checkPsi) {
                val el = file.findElementAt(m.start) ?: continue
                if (PsiTreeUtil.getParentOfType(el, PsiComment::class.java, false) == null) continue
            }
            val node = file.findElementAt(m.start)?.node ?: root.node ?: continue
            result += FoldingDescriptor(node, TextRange(m.start, m.end), FoldingGroup.newGroup(GROUP_PREFIX + m.start), placeholder(project, m))
        }
        return result.toTypedArray()
    }

    private fun placeholder(project: com.intellij.openapi.project.Project, m: E2mMarkers.Marker): String = when (m.kind) {
        E2mMarkers.Kind.FILE -> {
            val title = E2mNotes.title(E2mNotes.text(project, m))
            "ⓔ ${m.payload}" + (title?.let { " — ${shorten(it)}" } ?: "")
        }
        E2mMarkers.Kind.INLINE -> "ⓔ " + shorten(m.payload.lineSequence().firstOrNull().orEmpty())
        E2mMarkers.Kind.END -> "ⓔ ◂"
    }

    private fun shorten(s: String, max: Int = 60) = if (s.length <= max) s else s.take(max - 1) + "…"

    override fun getPlaceholderText(node: ASTNode): String = "ⓔ…"

    override fun isCollapsedByDefault(node: ASTNode): Boolean = E2mSettings.getInstance().collapseByDefault

    companion object {
        const val GROUP_PREFIX = "e2m:"
    }
}
