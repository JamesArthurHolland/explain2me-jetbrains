package dev.e2m.core

import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.lang.Language
import com.intellij.lang.folding.LanguageFolding
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `lang.foldingBuilder language="any"` is only a *fallback*: the platform uses it for languages that have
 * no folding builder of their own. To make e2m folds appear next to e.g. Java/Kotlin/Go folds, the builder
 * is additionally attached to every registered language that has its own builders.
 */
object E2mFoldingRegistrar {
    private val done = AtomicBoolean(false)
    private val builder = E2mFoldingBuilder()

    fun ensureRegistered() {
        if (!done.compareAndSet(false, true)) return
        val app = ApplicationManager.getApplication()
        for (lang in Language.getRegisteredLanguages()) {
            if (lang == Language.ANY) continue
            val existing = LanguageFolding.INSTANCE.allForLanguage(lang)
            if (existing.isEmpty() || existing.any { it is E2mFoldingBuilder }) continue
            LanguageFolding.INSTANCE.addExplicitExtension(lang, builder, app)
        }
    }

    /** Recomputes folding in already open editors (e.g. those restored before the plugin registered). */
    fun refreshEditors(project: Project) {
        ApplicationManager.getApplication().invokeLater({
            val mgr = CodeFoldingManager.getInstance(project)
            EditorFactory.getInstance().allEditors.filter { it.project == project }.forEach { mgr.scheduleAsyncFoldingUpdate(it) }
        }, project.disposed)
    }
}
