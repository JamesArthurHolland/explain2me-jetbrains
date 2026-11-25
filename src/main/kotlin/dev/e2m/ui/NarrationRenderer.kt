package dev.e2m.ui

import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*

/** Block inlay drawn above the explained line: the AI's narration, right inside the code. */
class NarrationRenderer(title: String?, text: String, private val indentPx: Int) : EditorCustomElementRenderer {
    private val lines: List<String> = buildList {
        if (!title.isNullOrBlank()) add("ⓔ $title")
        text.lines().forEach { addAll(wrap(it, 100)) }
    }.ifEmpty { listOf("ⓔ") }

    private fun wrap(s: String, width: Int): List<String> {
        if (s.length <= width) return listOf(s)
        val out = ArrayList<String>()
        var cur = StringBuilder()
        for (word in s.split(' ')) {
            if (cur.isNotEmpty() && cur.length + word.length + 1 > width) {
                out += cur.toString(); cur = StringBuilder()
            }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(word)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private fun font(inlay: Inlay<*>): Font = inlay.editor.colorsScheme.getFont(EditorFontType.PLAIN)
    private val pad get() = JBUI.scale(6)

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val fm = inlay.editor.contentComponent.getFontMetrics(font(inlay))
        return indentPx + lines.maxOf { fm.stringWidth(it) } + pad * 3
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int = lines.size * inlay.editor.lineHeight + pad * 2

    override fun paint(inlay: Inlay<*>, g: Graphics, r: Rectangle, textAttributes: TextAttributes) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val x = r.x + indentPx
            val w = r.width - indentPx - pad
            val y = r.y + JBUI.scale(2)
            val h = r.height - JBUI.scale(4)
            g2.color = BACKGROUND
            g2.fillRoundRect(x, y, w, h, JBUI.scale(8), JBUI.scale(8))
            g2.color = ACCENT
            g2.fillRect(x, y + JBUI.scale(2), JBUI.scale(3), h - JBUI.scale(4))
            g2.font = font(inlay)
            val lh = inlay.editor.lineHeight
            val ascent = g2.fontMetrics.ascent
            lines.forEachIndexed { i, line ->
                g2.color = if (i == 0 && line.startsWith("ⓔ")) ACCENT else FOREGROUND
                g2.drawString(line, x + pad * 2, y + pad + i * lh + ascent)
            }
        } finally {
            g2.dispose()
        }
    }

    companion object {
        val BACKGROUND = JBColor(Color(0xEEF3FF), Color(0x25324D))
        val ACCENT = JBColor(Color(0x3574F0), Color(0x6C9CF7))
        val FOREGROUND = JBColor(Color(0x2B2D30), Color(0xDFE1E5))
        val LINE = JBColor(Color(0xFFF4C7), Color(0x4B4226))
    }
}
