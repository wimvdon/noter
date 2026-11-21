package be.wimvandonck.noter.data.notion

import be.wimvandonck.noter.data.api.asObjectOrNull
import be.wimvandonck.noter.data.api.boolOrNull
import be.wimvandonck.noter.data.api.objOrNull
import be.wimvandonck.noter.data.api.stringOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A piece of text with one set of formatting. [atom] is set for a mention or inline equation:
 * the rich text item to send back to Notion as it is ([text] is what it shows).
 */
data class TextRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
    /** Notion's text colour or background ("red", "blue_background", …); null is the default. */
    val color: String? = null,
    val underline: Boolean = false,
    val atom: String? = null,
) {
    fun sameStyle(other: TextRun): Boolean =
        atom == null && other.atom == null &&
            bold == other.bold && italic == other.italic && strike == other.strike && code == other.code && link == other.link &&
            color == other.color && underline == other.underline
}

/**
 * Converts Notion rich text to inline Markdown and back:
 * `**bold**`, `*italic*`, `~~strikethrough~~`, `` `code` `` and `[text](url)`.
 *
 * Markdown is only produced where the text has that formatting, so plain notes stay plain. A
 * marker only counts when it opens right before a non-space character and is closed later, so
 * "5 * 3" stays as typed.
 *
 * What Markdown can't say is kept with [InlineTokens] in the same text: mentions and inline
 * equations as atoms (shown as their text, sent back unchanged), Notion's colours and underline
 * as spans around the text they apply to.
 */
object InlineMarkdown {
    /** Notion accepts at most 2000 characters per rich text item. */
    private const val MAX_ITEM_LENGTH = 2000

    // ---- Notion JSON -> runs -> Markdown ----

    fun runsFromRichText(richText: JsonArray?): List<TextRun> =
        richText.orEmpty().mapNotNull { element ->
            val item = element.asObjectOrNull() ?: return@mapNotNull null
            val text = InlineTokens.sanitize(
                item.stringOrNull("plain_text")
                    ?: item.objOrNull("text")?.stringOrNull("content")
                    ?: return@mapNotNull null,
            )
            val annotations = item.objOrNull("annotations")
            val color = annotations?.stringOrNull("color")?.takeIf { it != "default" }
            val underline = annotations?.boolOrNull("underline") == true
            val type = item.stringOrNull("type")
            if (type != "text") {
                atomRequest(item)?.let { return@mapNotNull TextRun(text.replace('\n', ' '), atom = it.toString()) }
            }
            val link = if (type == "text") item.objOrNull("text")?.objOrNull("link")?.stringOrNull("url") else item.stringOrNull("href")
            TextRun(
                text = text,
                bold = annotations?.boolOrNull("bold") == true,
                italic = annotations?.boolOrNull("italic") == true,
                strike = annotations?.boolOrNull("strikethrough") == true,
                code = annotations?.boolOrNull("code") == true,
                link = link,
                color = color,
                underline = underline,
            )
        }

    /**
     * The request to write a mention or equation back as Notion sent it, or null for one the
     * API can't write (a link preview): that one is kept as its text with its link.
     */
    internal fun atomRequest(item: JsonObject): JsonObject? {
        val body: JsonObject = when (item.stringOrNull("type")) {
            "equation" -> {
                val expression = item.objOrNull("equation")?.stringOrNull("expression") ?: return null
                buildJsonObject { put("expression", expression) }
            }
            "mention" -> {
                val mention = item.objOrNull("mention") ?: return null
                val kind = mention.stringOrNull("type") ?: return null
                val value = mention.objOrNull(kind) ?: return null
                val inner: JsonObject = when (kind) {
                    "user", "page", "database" -> buildJsonObject { put("id", value.stringOrNull("id") ?: return null) }
                    "date" -> {
                        value.stringOrNull("start") ?: return null
                        JsonObject(value.filterKeys { it in setOf("start", "end", "time_zone") })
                    }
                    "template_mention" -> value
                    "custom_emoji" -> {
                        value.stringOrNull("id") ?: return null
                        JsonObject(value.filterKeys { it in setOf("id", "name", "url") })
                    }
                    else -> return null
                }
                buildJsonObject {
                    put("type", kind)
                    put(kind, inner)
                }
            }
            else -> return null
        }
        val type = item.stringOrNull("type")!!
        return buildJsonObject {
            put("type", type)
            put(type, body)
            item.objOrNull("annotations")?.let { annotations ->
                put("annotations", JsonObject(annotations.filterKeys { it in ANNOTATION_KEYS }))
            }
        }
    }

    private val ANNOTATION_KEYS = setOf("bold", "italic", "strikethrough", "underline", "code", "color")

    fun fromRichText(richText: JsonArray?): String = toMarkdown(runsFromRichText(richText))

    /** Plain text of a rich text array, without any formatting. */
    fun plainText(richText: JsonArray?): String =
        InlineTokens.sanitize(richText.orEmpty().joinToString("") { it.asObjectOrNull()?.stringOrNull("plain_text").orEmpty() })

    fun toMarkdown(runs: List<TextRun>): String {
        val merged = merge(runs)
        val plain = render(merged, escape = false)
        // Escape only when the text would otherwise read back differently (it contains markers).
        return if (merge(parse(plain)) == merged) plain else render(merged, escape = true)
    }

    private fun render(runs: List<TextRun>, escape: Boolean): String {
        val out = StringBuilder()
        var span: String? = null
        fun inSpan(next: String?) {
            if (next == span) return
            if (span != null) out.append(InlineTokens.SPAN_CLOSE)
            if (next != null) out.append(next)
            span = next
        }
        for (run in runs) {
            if (run.atom != null) {
                inSpan(null)
                out.append(InlineTokens.atom(run.atom, run.text))
                continue
            }
            inSpan(if (run.color != null || run.underline) InlineTokens.spanOpen(run.color, run.underline) else null)
            var body = if (run.code) run.text.replace("`", "'") else if (escape) escapeText(run.text) else run.text
            if (run.code) body = "`$body`"
            if (run.strike) body = "~~$body~~"
            if (run.italic) body = "*$body*"
            if (run.bold) body = "**$body**"
            if (run.link != null) body = "[$body](${run.link})"
            out.append(body)
        }
        inSpan(null)
        return out.toString()
    }

    /** [text] as Markdown that reads back as exactly this text (its markers escaped). */
    fun escape(text: String): String = escapeText(InlineTokens.sanitize(text))

    private fun escapeText(text: String): String =
        buildString { text.forEach { if (it in SPECIAL) append('\\'); append(it) } }

    private const val SPECIAL = "\\*~`[]"

    // ---- Markdown -> runs -> Notion JSON ----

    fun parse(markdown: String): List<TextRun> {
        val runs = mutableListOf<TextRun>()
        Parser().parseInto(markdown, TextRun(""), runs)
        return merge(runs)
    }

    /** Reads Markdown; the span (colour, underline) carries on into and out of a link's text. */
    private class Parser {
        var color: String? = null
        var underline = false

        fun parseInto(source: String, style: TextRun, out: MutableList<TextRun>) {
            var bold = style.bold
            var italic = style.italic
            var strike = style.strike
            val buffer = StringBuilder()
            fun flush() {
                if (buffer.isNotEmpty()) {
                    out += TextRun(buffer.toString(), bold, italic, strike, code = false, link = style.link, color = color, underline = underline)
                    buffer.clear()
                }
            }
            var i = 0
            while (i < source.length) {
                val c = source[i]
                when {
                    c == InlineTokens.ATOM_OPEN && InlineTokens.atomAt(source, i) != null -> {
                        val atom = InlineTokens.atomAt(source, i)!!
                        flush()
                        out += TextRun(atom.label, atom = atom.json)
                        i = atom.end
                    }
                    c == InlineTokens.SPAN_OPEN && InlineTokens.spanAt(source, i) != null -> {
                        val span = InlineTokens.spanAt(source, i)!!
                        flush(); color = span.color; underline = span.underline; i = span.end
                    }
                    c == InlineTokens.SPAN_CLOSE -> {
                        flush(); color = null; underline = false; i += 1
                    }
                    InlineTokens.isMarker(c) -> i += 1
                    c == '\\' && i + 1 < source.length && source[i + 1] in SPECIAL -> {
                        buffer.append(source[i + 1]); i += 2
                    }
                    c == '`' && opens(source, i, "`") -> {
                        val end = closing(source, "`", i + 1)
                        flush()
                        out += TextRun(source.substring(i + 1, end), bold, italic, strike, code = true, link = style.link, color = color, underline = underline)
                        i = end + 1
                    }
                    source.startsWith("**", i) && (bold || opens(source, i, "**")) -> {
                        flush(); bold = !bold; i += 2
                    }
                    source.startsWith("~~", i) && (strike || opens(source, i, "~~")) -> {
                        flush(); strike = !strike; i += 2
                    }
                    c == '*' && (italic || opens(source, i, "*")) -> {
                        flush(); italic = !italic; i += 1
                    }
                    c == '[' && style.link == null && linkAt(source, i) != null -> {
                        val (textEnd, urlEnd) = linkAt(source, i)!!
                        flush()
                        val url = source.substring(textEnd + 2, urlEnd)
                        parseInto(source.substring(i + 1, textEnd), TextRun("", bold, italic, strike, link = url), out)
                        i = urlEnd + 1
                    }
                    else -> {
                        buffer.append(c); i += 1
                    }
                }
            }
            flush()
        }

        /** A marker opens formatting when a non-space follows and the same marker comes later. */
        private fun opens(source: String, at: Int, marker: String): Boolean {
            val next = at + marker.length
            if (next >= source.length || source[next].isWhitespace()) return false
            if (marker == "*" && source[next] == '*') return false
            val end = closing(source, marker, next + 1)
            if (end <= next) return false
            // Code is taken literally, so it can't hold a mention or a colour change.
            return marker != "`" || source.substring(at, end).none(InlineTokens::isMarker)
        }

        /** Where [marker] comes next from [from], not counting the text of mentions; -1 if nowhere. */
        private fun closing(source: String, marker: String, from: Int): Int {
            var i = from
            while (i < source.length) {
                if (source[i] == InlineTokens.ATOM_OPEN) {
                    InlineTokens.atomAt(source, i)?.let { i = it.end; continue }
                }
                if (source.startsWith(marker, i)) return i
                i++
            }
            return -1
        }

        /** For "[text](url)" at [at]: the index of "]" and of ")". */
        private fun linkAt(source: String, at: Int): Pair<Int, Int>? {
            val textEnd = source.indexOf("](", at + 1).takeIf { it > at + 1 } ?: return null
            val text = source.substring(at + 1, textEnd)
            if (text.contains('\n') || text.any { it in InlineTokens.ATOM_OPEN..InlineTokens.ATOM_CLOSE }) return null
            val urlEnd = source.indexOf(')', textEnd + 2).takeIf { it > textEnd + 2 } ?: return null
            val url = source.substring(textEnd + 2, urlEnd)
            if (url.any { it.isWhitespace() || InlineTokens.isMarker(it) }) return null
            return textEnd to urlEnd
        }
    }

    /** Notion rich text items for [markdown] (an empty list for empty text). */
    fun toRichText(markdown: String): List<Map<String, Any?>> =
        parse(markdown).flatMap { run ->
            run.atom?.let { atom ->
                val request = runCatching { Json.parseToJsonElement(atom) as? JsonObject }.getOrNull()
                if (request != null) return@flatMap listOf(request)
            }
            run.text.chunked(MAX_ITEM_LENGTH).map { chunk ->
                mapOf(
                    "type" to "text",
                    "text" to mapOf("content" to chunk, "link" to run.link?.let { mapOf("url" to it) }),
                    "annotations" to buildMap {
                        put("bold", run.bold)
                        put("italic", run.italic)
                        put("strikethrough", run.strike)
                        put("code", run.code)
                        if (run.underline) put("underline", true)
                        if (run.color != null) put("color", run.color)
                    },
                )
            }
        }

    /** Notion rich text items for [text] taken literally (code blocks: no Markdown, mentions as their text). */
    fun plainRichText(text: String): List<Map<String, Any?>> =
        InlineTokens.display(text).chunked(MAX_ITEM_LENGTH).map { chunk -> mapOf("type" to "text", "text" to mapOf("content" to chunk)) }

    /** Text without Markdown markers, for previews and sorting (mentions as their text). */
    fun stripped(markdown: String): String = parse(markdown).joinToString("") { it.text }

    /** [markdown] as plain Markdown to copy elsewhere: mentions as their text, no colours. */
    fun portable(markdown: String): String =
        if (!InlineTokens.hasTokens(markdown)) markdown else toMarkdown(parse(markdown).map { it.copy(atom = null, color = null, underline = false) })

    private fun merge(runs: List<TextRun>): List<TextRun> {
        val out = mutableListOf<TextRun>()
        for (run in runs) {
            if (run.text.isEmpty() && run.atom == null) continue
            val last = out.lastOrNull()
            if (last != null && last.sameStyle(run)) out[out.size - 1] = last.copy(text = last.text + run.text) else out += run
        }
        return out
    }
}
