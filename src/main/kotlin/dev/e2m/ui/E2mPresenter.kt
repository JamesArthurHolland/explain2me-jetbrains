package dev.e2m.ui

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import java.util.concurrent.CopyOnWriteArrayList

/** What the Explain2Me tool window shows. */
data class StepView(
    val source: String,
    val index: Int,
    val total: Int,
    val title: String?,
    val text: String,
    val location: String?,
    val details: String?,
)

/** Shows explanations in the editor (highlighted line + inline narration) and in the tool window. */
@Service(Service.Level.PROJECT)
class E2mPresenter(private val project: Project) : Disposable {
    fun interface Listener {
        fun changed()
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    @Volatile
    var current: StepView? = null
        private set
    @Volatile
    var statusLine: String = ""
        private set

    private var highlighter: RangeHighlighter? = null
    private var inlay: Inlay<*>? = null

    fun addListener(l: Listener, parent: Disposable) {
        listeners += l
        Disposer.register(parent) { listeners -= l }
    }

    fun status(text: String) {
        statusLine = text
        notifyListeners()
    }

    private fun notifyListeners() = ApplicationManager.getApplication().invokeLater({ listeners.forEach { it.changed() } }, project.disposed)

    /** Must be called on the EDT. [line0] may be null for steps without a location. */
    fun show(view: StepView, file: VirtualFile?, line0: Int?, inline: Boolean = true, focusToolWindow: Boolean = true) {
        clearEditorDecorations()
        current = view
        if (file != null && line0 != null) {
            val editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file, line0, 0), false)
            if (editor != null) decorate(editor, line0, view, inline)
        }
        if (focusToolWindow) ToolWindowManager.getInstance(project).getToolWindow("Explain2Me")?.show()
        listeners.forEach { it.changed() }
    }

    private fun decorate(editor: Editor, line0: Int, view: StepView, inline: Boolean) {
        val doc = editor.document
        if (line0 !in 0 until doc.lineCount) return
        highlighter = editor.markupModel.addLineHighlighter(
            line0, HighlighterLayer.SELECTION - 1, TextAttributes(null, NarrationRenderer.LINE, null, null, 0),
        )
        if (inline && view.text.isNotBlank()) {
            val start = doc.getLineStartOffset(line0)
            val lineText = doc.charsSequence.subSequence(start, doc.getLineEndOffset(line0))
            val firstNonWs = start + lineText.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val indent = editor.offsetToXY(firstNonWs).x
            inlay = editor.inlayModel.addBlockElement(start, false, true, 0, NarrationRenderer(view.title, view.text, indent))
        }
        editor.caretModel.moveToOffset(doc.getLineStartOffset(line0))
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }

    fun clearEditorDecorations() {
        highlighter?.dispose(); highlighter = null
        inlay?.let { Disposer.dispose(it) }; inlay = null
    }

    fun clear() {
        clearEditorDecorations()
        current = null
        listeners.forEach { it.changed() }
    }

    override fun dispose() {
        ApplicationManager.getApplication().invokeLater { clearEditorDecorations() }
    }

    companion object {
        fun getInstance(project: Project): E2mPresenter = project.service()

        /** Human readable rendering of variables / evaluation JSON for the tool window. */
        fun describe(variables: JsonElement?, evaluations: List<Pair<String, JsonElement?>>): String? {
            val sb = StringBuilder()
            if (evaluations.isNotEmpty()) {
                sb.append("Evaluated\n")
                evaluations.forEach { (expr, v) -> sb.append("  ").append(expr).append(" = ").append(valueText(v)).append('\n') }
            }
            if (variables is JsonArray && variables.size() > 0) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append("Variables\n")
                variables.forEach { render(it, 1, sb) }
            }
            return sb.toString().ifBlank { null }
        }

        private fun valueText(v: JsonElement?): String = when {
            v == null || v.isJsonNull -> "?"
            v.isJsonObject -> v.asJsonObject.get("value")?.asString ?: v.toString()
            v.isJsonPrimitive -> v.asString
            else -> v.toString()
        }

        private fun render(e: JsonElement, depth: Int, sb: StringBuilder) {
            if (!e.isJsonObject) return
            val o: JsonObject = e.asJsonObject
            sb.append("  ".repeat(depth)).append(o.get("name")?.asString ?: "?").append(" = ").append(o.get("value")?.asString ?: "")
            o.get("type")?.asString?.let { sb.append("  (").append(it).append(')') }
            sb.append('\n')
            (o.get("children") as? JsonArray)?.forEach { render(it, depth + 1, sb) }
        }
    }
}
