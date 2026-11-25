package dev.e2m.ui

/** Tiny markdown -> HTML converter for narration (headings, lists, bold, italics, inline code, code blocks). */
object MiniMarkdown {
    fun toHtml(md: String): String {
        val out = StringBuilder()
        var inList = false
        var inCode = false
        val para = StringBuilder()
        fun flushPara() {
            if (para.isNotBlank()) out.append("<p>").append(inline(para.toString().trim())).append("</p>")
            para.clear()
        }
        fun closeList() { if (inList) { out.append("</ul>"); inList = false } }
        for (raw in md.lines()) {
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```")) {
                flushPara(); closeList()
                out.append(if (inCode) "</pre>" else "<pre>")
                inCode = !inCode
                continue
            }
            if (inCode) { out.append(esc(raw)).append('\n'); continue }
            val t = line.trim()
            when {
                t.isEmpty() -> { flushPara(); closeList() }
                t.startsWith("#") -> {
                    flushPara(); closeList()
                    val level = t.takeWhile { it == '#' }.length.coerceIn(1, 4) + 1
                    out.append("<h$level>").append(inline(t.trimStart('#').trim())).append("</h$level>")
                }
                t.startsWith("- ") || t.startsWith("* ") || t.matches(Regex("""\d+\.\s.*""")) -> {
                    flushPara()
                    if (!inList) { out.append("<ul>"); inList = true }
                    out.append("<li>").append(inline(t.replaceFirst(Regex("""^([-*]|\d+\.)\s+"""), ""))).append("</li>")
                }
                t.startsWith("|") -> { flushPara(); closeList(); out.append("<code>").append(esc(t)).append("</code><br/>") }
                else -> para.append(t).append(' ')
            }
        }
        flushPara(); closeList()
        if (inCode) out.append("</pre>")
        return out.toString()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun inline(s: String): String = esc(s)
        .replace(Regex("`([^`]+)`"), "<code>$1</code>")
        .replace(Regex("""\*\*([^*]+)\*\*"""), "<b>$1</b>")
        .replace(Regex("""(?<![*\w])\*([^*]+)\*(?![*\w])"""), "<i>$1</i>")
}
