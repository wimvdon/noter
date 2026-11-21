package be.wimvandonck.noter.ui.note

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration

/**
 * Shows inline Markdown while typing: the text between `**`, `*`, `~~` and `` ` `` gets its
 * style and the markers are dimmed. The text itself is unchanged (the markers stay visible and
 * editable), so cursor positions need no mapping.
 *
 * [mentions] are where the shown text has mentions (see InlineTokens.Layout): they get the link
 * colour, and Markdown markers in their text don't count. [markdown] false styles only those.
 */
class MarkdownStyling(
    private val markerColor: Color,
    private val linkColor: Color,
    private val mentions: List<IntRange> = emptyList(),
    private val markdown: Boolean = true,
) : VisualTransformation {
    fun withMentions(ranges: List<IntRange>): MarkdownStyling = MarkdownStyling(markerColor, linkColor, ranges, markdown)

    override fun filter(text: AnnotatedString): TransformedText {
        val shown = text.text
        val hasMarkers = markdown && shown.any { it == '*' || it == '~' || it == '`' || it == '[' }
        if (!hasMarkers && mentions.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val builder = AnnotatedString.Builder(text)
        // The ranges come from the stored text, which can be a keystroke behind the field.
        val inText = mentions.filter { !it.isEmpty() && it.first >= 0 && it.last < shown.length }
        inText.forEach { builder.addStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.Medium), it.first, it.last + 1) }
        if (!hasMarkers) return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
        // A mention's text is matched as plain letters, so a star in it never starts bold or italic.
        val raw = if (inText.isEmpty()) {
            shown
        } else {
            val chars = shown.toCharArray()
            inText.forEach { range -> range.forEach { chars[it] = 'x' } }
            String(chars)
        }
        val marker = SpanStyle(color = markerColor)
        fun style(regex: Regex, open: Int, close: Int, span: SpanStyle) {
            regex.findAll(raw).forEach { match ->
                val start = match.range.first
                val end = match.range.last + 1
                builder.addStyle(marker, start, start + open)
                builder.addStyle(span, start + open, end - close)
                builder.addStyle(marker, end - close, end)
            }
        }
        style(BOLD, 2, 2, SpanStyle(fontWeight = FontWeight.Bold))
        style(ITALIC, 1, 1, SpanStyle(fontStyle = FontStyle.Italic))
        style(STRIKE, 2, 2, SpanStyle(textDecoration = TextDecoration.LineThrough))
        style(CODE, 1, 1, SpanStyle(fontFamily = FontFamily.Monospace))
        LINK.findAll(raw).forEach { match ->
            val label = match.groups[1]!!.range
            builder.addStyle(marker, match.range.first, label.first)
            builder.addStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline), label.first, label.last + 1)
            builder.addStyle(marker, label.last + 1, match.range.last + 1)
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }

    override fun equals(other: Any?): Boolean =
        other is MarkdownStyling && other.markerColor == markerColor && other.linkColor == linkColor &&
            other.mentions == mentions && other.markdown == markdown

    override fun hashCode(): Int = ((markerColor.hashCode() * 31 + linkColor.hashCode()) * 31 + mentions.hashCode()) * 31 + markdown.hashCode()

    private companion object {
        val BOLD = Regex("""\*\*(?=\S)(.+?)\*\*""")
        val ITALIC = Regex("""(?<![*\\])\*(?=[^\s*])(.+?)(?<![\\*])\*(?!\*)""")
        val STRIKE = Regex("""~~(?=\S)(.+?)~~""")
        val CODE = Regex("""`[^`\n]+`""")
        val LINK = Regex("""\[([^\]\n]+)]\(\S+?\)""")
    }
}
