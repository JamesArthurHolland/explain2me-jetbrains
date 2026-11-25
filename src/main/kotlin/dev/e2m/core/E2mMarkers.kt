package dev.e2m.core

import com.intellij.openapi.editor.Document

/**
 * Parser for e2m explanation comments. It is purely text based, so it works for every language
 * whose comments start with //, #, slash-star, --, ; or <!--. Supported forms (shown with //):
 *
 *  - `// e2m :f regression/slope` : the explanation lives in `.e2m/notes/regression/slope.md`
 *  - a block comment starting with `e2m :=` : hand written, possibly multi-line, inline explanation
 *  - `// e2m := text` : single line explanation, continued by directly following `//` lines
 *  - `// e2m :end` : optional, closes the code block explained by the marker above
 */
object E2mMarkers {
    enum class Kind { FILE, INLINE, END }

    data class Marker(
        val kind: Kind,
        /** Offset where the comment (including its opener) starts. */
        val start: Int,
        /** Exclusive end offset of the whole marker comment (including continuation lines). */
        val end: Int,
        /** Note reference for [Kind.FILE], explanation text for [Kind.INLINE], empty for [Kind.END]. */
        val payload: String,
    )

    private val MARKER = Regex("""(//+|#+|/\*+|--|;+|<!--)[ \t]*e2m[ \t]*:(end|f|=)""")

    /** Quick check used to skip files that cannot contain markers. */
    fun mayContain(text: CharSequence): Boolean = text.contains("e2m")

    fun isMarkerText(text: CharSequence): Boolean = MARKER.find(text)?.range?.first?.let { firstNonBlank(text) == it } == true

    private fun firstNonBlank(text: CharSequence): Int = text.indexOfFirst { !it.isWhitespace() }

    fun findAll(text: CharSequence): List<Marker> {
        if (!mayContain(text)) return emptyList()
        val result = ArrayList<Marker>()
        var searchFrom = 0
        while (true) {
            val m = MARKER.find(text, searchFrom) ?: break
            val marker = parse(text, m)
            result += marker
            searchFrom = maxOf(marker.end, m.range.last + 1)
        }
        return result
    }

    private fun parse(text: CharSequence, m: MatchResult): Marker {
        val opener = m.groupValues[1]
        val kind = when (m.groupValues[2]) {
            "f" -> Kind.FILE
            "=" -> Kind.INLINE
            else -> Kind.END
        }
        val start = m.range.first
        val bodyStart = m.range.last + 1
        val block = opener.startsWith("/*") || opener == "<!--"

        val end: Int
        val rawBody: String
        if (block) {
            val terminator = if (opener == "<!--") "-->" else "*/"
            val close = indexOf(text, terminator, bodyStart)
            end = if (close < 0) lineEnd(text, bodyStart) else close + terminator.length
            rawBody = text.subSequence(bodyStart, if (close < 0) end else close).toString()
                .lines().joinToString("\n") { it.trim().removePrefix("*").trim() }
        } else {
            var e = lineEnd(text, bodyStart)
            val lines = mutableListOf(text.subSequence(bodyStart, e).toString().trim())
            if (kind == Kind.INLINE) {
                // Swallow directly following line comments that use the same opener (continuation lines).
                while (e < text.length) {
                    val nextStart = e + 1
                    val nextEnd = lineEnd(text, nextStart)
                    val line = text.subSequence(nextStart, nextEnd).toString().trim()
                    if (!line.startsWith(opener) || MARKER.containsMatchIn(line)) break
                    val content = line.removePrefix(opener).trim()
                    if (content.isEmpty() && line.length == opener.length) break
                    lines += content
                    e = nextEnd
                }
            }
            end = e
            rawBody = lines.joinToString("\n")
        }
        val payload = when (kind) {
            Kind.FILE -> rawBody.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
            Kind.INLINE -> rawBody.trim()
            Kind.END -> ""
        }
        return Marker(kind, start, end, payload)
    }

    private fun lineEnd(text: CharSequence, from: Int): Int {
        var i = from
        while (i < text.length && text[i] != '\n') i++
        return i
    }

    private fun indexOf(text: CharSequence, s: String, from: Int): Int {
        var i = from
        while (i <= text.length - s.length) {
            if (text.regionMatches(i, s, 0, s.length)) return i
            i++
        }
        return -1
    }

    /**
     * The explanation that covers [line] (0-based): the nearest FILE/INLINE marker at or above the line
     * whose block contains it. A block ends at the matching `:end` marker, or at the first blank line
     * after the marker when no `:end` is used.
     */
    fun findCovering(document: Document, line: Int): Marker? {
        val text = document.charsSequence
        val markers = findAll(text)
        if (markers.isEmpty()) return null
        val offset = document.getLineEndOffset(line.coerceIn(0, maxOf(0, document.lineCount - 1)))
        var best: Marker? = null
        for ((i, m) in markers.withIndex()) {
            if (m.kind == Kind.END || m.start > offset) continue
            val blockEnd = blockEnd(document, markers, i)
            if (offset <= blockEnd) best = m
        }
        return best
    }

    private fun blockEnd(document: Document, markers: List<Marker>, index: Int): Int {
        val m = markers[index]
        val next = markers.getOrNull(index + 1)
        if (next != null && next.kind == Kind.END) return next.end
        // Otherwise: until the next blank line (or the next marker).
        val text = document.charsSequence
        var line = document.getLineNumber(m.end) + 1
        while (line < document.lineCount) {
            val s = document.getLineStartOffset(line)
            val e = document.getLineEndOffset(line)
            if (text.subSequence(s, e).isBlank() || (next != null && next.start in s..e)) {
                return document.getLineEndOffset(line - 1)
            }
            line++
        }
        return text.length
    }
}
