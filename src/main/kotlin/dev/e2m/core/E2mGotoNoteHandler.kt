package dev.e2m.core

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil

/** Ctrl/Cmd+click on `e2m :f some/id` opens `.e2m/notes/some/id.md`. */
class E2mGotoNoteHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        val comment = sourceElement?.let { PsiTreeUtil.getParentOfType(it, PsiComment::class.java, false) } ?: return null
        val project = comment.project
        val marker = E2mMarkers.findAll(comment.text).firstOrNull { it.kind == E2mMarkers.Kind.FILE } ?: return null
        val vf = runCatching { E2mPaths.findVirtual(E2mPaths.notePath(project, marker.payload)) }.getOrNull() ?: return null
        val psi = PsiManager.getInstance(project).findFile(vf) ?: return null
        return arrayOf(psi)
    }
}
