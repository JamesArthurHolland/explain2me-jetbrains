package dev.e2m.debug

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.frame.*
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.Icon

/**
 * Small synchronous adapters over the asynchronous XDebugger tree API. They are only ever called from
 * the server's worker threads (never the EDT), so blocking with a timeout is fine.
 */
object Values {
    private const val CHILD_TIMEOUT_MS = 5_000L
    private const val VALUE_TIMEOUT_MS = 3_000L
    const val MAX_CHILDREN = 50

    data class Child(val name: String, val value: XValue)

    fun children(container: XValueContainer, limit: Int = MAX_CHILDREN): List<Child> {
        val node = CollectingNode(limit)
        container.computeChildren(node)
        return runCatching { node.done.get(CHILD_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrElse { node.snapshot() }
    }

    data class Presentation(val type: String?, val value: String, val hasChildren: Boolean)

    fun present(value: XValue): Presentation {
        val node = PresentationNode()
        value.computePresentation(node, XValuePlace.TREE)
        val first = runCatching { node.first.get(VALUE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrNull()
            ?: return Presentation(null, "<timed out>", false)
        // Some debuggers first report a placeholder ("Collecting data...") and update it shortly after.
        var current = first
        val deadline = System.currentTimeMillis() + if (looksPending(first.value)) 2_000 else 150
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
            current = node.latest ?: current
            if (current !== first && !looksPending(current.value)) break
        }
        return node.latest ?: current
    }

    private fun looksPending(v: String) = v.contains("Collecting data") || v == "..." || v.isEmpty()

    /** JSON for a named value, recursively expanding children up to [depth]. */
    fun toJson(name: String, value: XValue, depth: Int): JsonObject {
        val p = present(value)
        val o = JsonObject()
        o.addProperty("name", name)
        p.type?.let { o.addProperty("type", it) }
        o.addProperty("value", p.value)
        if (p.hasChildren && depth > 0) {
            val arr = JsonArray()
            children(value).forEach { arr.add(toJson(it.name, it.value, depth - 1)) }
            o.add("children", arr)
        } else if (p.hasChildren) {
            o.addProperty("hasChildren", true)
        }
        return o
    }

    fun frameText(frame: XStackFrame): String {
        val sb = StringBuilder()
        frame.customizeTextPresentation(object : ColoredTextContainer {
            override fun append(fragment: String, attributes: SimpleTextAttributes) {
                sb.append(fragment)
            }
        })
        return sb.toString()
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    private class CollectingNode(private val limit: Int) : XCompositeNode {
        val done = CompletableFuture<List<Child>>()
        private val items = ArrayList<Child>()

        @Synchronized
        fun snapshot(): List<Child> = items.toList()

        @Synchronized
        override fun addChildren(children: XValueChildrenList, last: Boolean) {
            for (i in 0 until children.size()) {
                if (items.size >= limit) break
                items += Child(children.getName(i), children.getValue(i))
            }
            if (last || items.size >= limit) done.complete(items.toList())
        }

        override fun tooManyChildren(remaining: Int) {
            done.complete(snapshot())
        }

        override fun setAlreadySorted(alreadySorted: Boolean) {}
        override fun setErrorMessage(errorMessage: String) {
            done.complete(snapshot())
        }

        override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) {
            done.complete(snapshot())
        }

        override fun setMessage(message: String, icon: Icon?, attributes: SimpleTextAttributes, link: XDebuggerTreeNodeHyperlink?) {}
        override fun isObsolete(): Boolean = false
    }

    private class PresentationNode : XValueNode {
        val first = CompletableFuture<Presentation>()
        @Volatile
        var latest: Presentation? = null

        private fun set(p: Presentation) {
            latest = p
            first.complete(p)
        }

        override fun setPresentation(icon: Icon?, type: String?, value: String, hasChildren: Boolean) {
            set(Presentation(type, value, hasChildren))
        }

        override fun setPresentation(icon: Icon?, presentation: XValuePresentation, hasChildren: Boolean) {
            val sb = StringBuilder()
            presentation.renderValue(TextRenderer(sb))
            set(Presentation(presentation.type, sb.toString(), hasChildren))
        }

        override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {}
        override fun isObsolete(): Boolean = false
    }

    private class TextRenderer(private val sb: StringBuilder) : XValuePresentation.XValueTextRenderer {
        override fun renderValue(value: String) { sb.append(value) }
        override fun renderStringValue(value: String) { sb.append('"').append(value).append('"') }
        override fun renderNumericValue(value: String) { sb.append(value) }
        override fun renderKeywordValue(value: String) { sb.append(value) }
        override fun renderValue(value: String, key: TextAttributesKey) { sb.append(value) }
        override fun renderStringValue(value: String, additionalSpecialCharsToHighlight: String?, maxLength: Int) {
            sb.append('"').append(value).append('"')
        }
        override fun renderComment(comment: String) { sb.append(' ').append(comment) }
        override fun renderSpecialSymbol(symbol: String) { sb.append(symbol) }
        override fun renderError(error: String) { sb.append(error) }
    }
}
