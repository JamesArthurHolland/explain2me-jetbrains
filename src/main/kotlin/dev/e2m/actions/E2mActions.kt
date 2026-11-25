package dev.e2m.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import com.intellij.lang.LanguageCommenters
import com.intellij.psi.PsiDocumentManager
import dev.e2m.core.E2mFolding
import dev.e2m.core.E2mMarkers
import dev.e2m.core.E2mNotes
import dev.e2m.core.E2mPaths
import dev.e2m.ui.E2mPresenter
import dev.e2m.ui.StepView

abstract class E2mEditorAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }
}

class CollapseE2mAction : E2mEditorAction() {
    override fun actionPerformed(e: AnActionEvent) { e.getData(CommonDataKeys.EDITOR)?.let { E2mFolding.apply(it, false) } }
}

class ExpandE2mAction : E2mEditorAction() {
    override fun actionPerformed(e: AnActionEvent) { e.getData(CommonDataKeys.EDITOR)?.let { E2mFolding.apply(it, true) } }
}

class ToggleE2mAction : E2mEditorAction() {
    override fun actionPerformed(e: AnActionEvent) { e.getData(CommonDataKeys.EDITOR)?.let { E2mFolding.apply(it, null) } }
}

/** Opens the shadow note (`:f`) or shows the inline explanation (`:=`) covering the caret line. */
class OpenE2mNoteAction : E2mEditorAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val line = editor.caretModel.logicalPosition.line
        val marker = E2mMarkers.findCovering(editor.document, line)
        if (marker == null) {
            Messages.showInfoMessage(project, "No e2m explanation covers this line.", "Explain2Me"); return
        }
        if (marker.kind == E2mMarkers.Kind.FILE) {
            val vf = E2mPaths.findVirtual(E2mPaths.notePath(project, marker.payload))
            if (vf != null) { FileEditorManager.getInstance(project).openFile(vf, true); return }
        }
        val text = E2mNotes.text(project, marker) ?: "Shadow note '${marker.payload}' does not exist yet (.e2m/notes/${marker.payload}.md)."
        val file = editor.virtualFile
        val markerLine = editor.document.getLineNumber(marker.start)
        E2mPresenter.getInstance(project).show(
            StepView("note", -1, 0, E2mNotes.title(text).takeIf { marker.kind == E2mMarkers.Kind.FILE } ?: "Inline explanation", text,
                file?.let { "${E2mPaths.relative(project, it)}:${markerLine + 1}" }, null),
            null, null,
        )
    }
}

/** Inserts `// e2m :f <id>` above the caret line and creates/opens the shadow note. */
class AddE2mNoteAction : E2mEditorAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val id = Messages.showInputDialog(project, "Note id (stored as .e2m/notes/<id>.md):", "Add e2m Explanation", null)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val notePath = runCatching { E2mPaths.notePath(project, id) }.getOrElse {
            Messages.showErrorDialog(project, it.message, "Explain2Me"); return
        }
        val doc = editor.document
        val line = editor.caretModel.logicalPosition.line
        val psi = PsiDocumentManager.getInstance(project).getPsiFile(doc)
        val prefix = psi?.let { LanguageCommenters.INSTANCE.forLanguage(it.language)?.lineCommentPrefix?.trimEnd() } ?: "//"
        val start = doc.getLineStartOffset(line)
        val indent = doc.charsSequence.subSequence(start, doc.getLineEndOffset(line)).takeWhile { it == ' ' || it == '\t' }
        WriteCommandAction.runWriteCommandAction(project, "Add e2m Explanation", null, {
            doc.insertString(start, "$indent$prefix e2m :f $id\n")
        })
        val vf = if (java.nio.file.Files.exists(notePath)) E2mPaths.findVirtual(notePath)
        else E2mPaths.write(notePath, "# ${id.substringAfterLast('/')}\n\nExplain what the code below does…\n")
        vf?.let { FileEditorManager.getInstance(project).openFile(it, true) }
    }
}
