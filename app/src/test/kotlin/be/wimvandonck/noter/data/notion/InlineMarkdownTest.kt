package be.wimvandonck.noter.data.notion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import be.wimvandonck.noter.data.api.toJsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineMarkdownTest {
    private fun richText(json: String): JsonArray = Json.parseToJsonElement(json).jsonArray

    private fun item(text: String, bold: Boolean = false, italic: Boolean = false, code: Boolean = false, link: String? = null) =
        """{"type":"text","plain_text":"$text","text":{"content":"$text","link":${link?.let { "{\"url\":\"$it\"}" } ?: "null"}},
           "annotations":{"bold":$bold,"italic":$italic,"strikethrough":false,"underline":false,"code":$code,"color":"default"}}"""

    @Test
    fun `plain text stays plain`() {
        assertEquals("Buy milk", InlineMarkdown.fromRichText(richText("[${item("Buy milk")}]")))
    }

    @Test
    fun `formatting becomes markdown`() {
        val md = InlineMarkdown.fromRichText(
            richText("[${item("Buy ")},${item("milk", bold = true)},${item(" and ")},${item("eggs", italic = true)},${item(" x", code = true)}]"),
        )
        assertEquals("Buy **milk** and *eggs*` x`", md)
    }

    @Test
    fun `links become markdown links`() {
        assertEquals("[site](https://a.b)", InlineMarkdown.fromRichText(richText("[${item("site", link = "https://a.b")}]")))
    }

    @Test
    fun `markdown parses back to the same runs`() {
        val runs = InlineMarkdown.parse("a **b** *c* ~~d~~ `e` [f](https://x.y)")
        assertEquals(
            listOf(
                TextRun("a "), TextRun("b", bold = true), TextRun(" "), TextRun("c", italic = true), TextRun(" "),
                TextRun("d", strike = true), TextRun(" "), TextRun("e", code = true), TextRun(" "), TextRun("f", link = "https://x.y"),
            ),
            runs,
        )
    }

    @Test
    fun `a lone star is not formatting`() {
        assertEquals(listOf(TextRun("5 * 3 = 15")), InlineMarkdown.parse("5 * 3 = 15"))
        assertEquals(listOf(TextRun("2*3")), InlineMarkdown.parse("2*3"))
    }

    @Test
    fun `text with markers is escaped so it reads back the same`() {
        val md = InlineMarkdown.toMarkdown(listOf(TextRun("*not italic*")))
        assertEquals("\\*not italic\\*", md)
        assertEquals("*not italic*", InlineMarkdown.stripped(md))
    }

    @Test
    fun `rich text request keeps formatting`() {
        val items = InlineMarkdown.toRichText("hi **there**")
        assertEquals(2, items.size)
        @Suppress("UNCHECKED_CAST")
        val annotations = items[1]["annotations"] as Map<String, Any?>
        assertEquals(true, annotations["bold"])
    }

    @Test
    fun `long text is split into items of at most 2000 characters`() {
        val items = InlineMarkdown.toRichText("x".repeat(4500))
        assertEquals(3, items.size)
    }

    @Test
    fun `empty text gives no rich text items`() {
        assertEquals(emptyList<Any>(), InlineMarkdown.toRichText(""))
    }

    // ---- Mentions, equations, colours (see InlineTokens) ----

    private val annotations = """"annotations":{"bold":false,"italic":false,"strikethrough":false,"underline":false,"code":false,"color":"default"}"""

    private val userMention =
        """{"type":"mention","mention":{"type":"user","user":{"object":"user","id":"u1","name":"Wim","type":"person","person":{"email":"w@x.be"}}},
           $annotations,"plain_text":"@Wim","href":null}"""

    private val pageMention =
        """{"type":"mention","mention":{"type":"page","page":{"id":"p1"}},$annotations,"plain_text":"Groceries","href":"https://www.notion.so/p1"}"""

    private val dateMention =
        """{"type":"mention","mention":{"type":"date","date":{"start":"2026-11-21","end":null,"time_zone":null}},$annotations,"plain_text":"2026-11-21","href":null}"""

    private val equation =
        """{"type":"equation","equation":{"expression":"e=mc^2"},$annotations,"plain_text":"e=mc^2","href":null}"""

    private val linkPreview =
        """{"type":"mention","mention":{"type":"link_preview","link_preview":{"url":"https://github.com/x"}},$annotations,"plain_text":"https://github.com/x","href":"https://github.com/x"}"""

    private fun colored(text: String, color: String = "default", underline: Boolean = false, bold: Boolean = false) =
        """{"type":"text","plain_text":"$text","text":{"content":"$text","link":null},
           "annotations":{"bold":$bold,"italic":false,"strikethrough":false,"underline":$underline,"code":false,"color":"$color"}}"""

    /** What Notion gets for [markdown], as JSON objects. */
    private fun sent(markdown: String): List<JsonObject> = InlineMarkdown.toRichText(markdown).map { it.toJsonElement().jsonObject }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun JsonObject.content(): String = this["text"]!!.jsonObject["content"]!!.jsonPrimitive.content

    private fun JsonObject.annotation(name: String): String? = this["annotations"]!!.jsonObject[name]?.jsonPrimitive?.content

    private val userRequest by lazy { json("""{"type":"mention","mention":{"type":"user","user":{"id":"u1"}},$annotations}""") }

    @Test
    fun `a user mention shows its name and goes back as the same mention`() {
        val md = InlineMarkdown.fromRichText(richText("[${item("Ask ")},$userMention,${item(" today")}]"))
        assertEquals("Ask @Wim today", InlineMarkdown.stripped(md))
        assertEquals("Ask @Wim today", InlineTokens.display(md))
        val items = sent(md)
        assertEquals(3, items.size)
        assertEquals(userRequest, items[1])
        assertEquals("Ask ", items[0].content())
        assertEquals(" today", items[2].content())
    }

    @Test
    fun `page and date mentions and equations go back unchanged`() {
        val md = InlineMarkdown.fromRichText(richText("[$pageMention,${item(" on ")},$dateMention,${item(": ")},$equation]"))
        assertEquals("Groceries on 2026-11-21: e=mc^2", InlineMarkdown.stripped(md))
        val items = sent(md)
        assertEquals(json("""{"type":"mention","mention":{"type":"page","page":{"id":"p1"}},$annotations}"""), items[0])
        assertEquals(
            json("""{"type":"mention","mention":{"type":"date","date":{"start":"2026-11-21","end":null,"time_zone":null}},$annotations}"""),
            items[2],
        )
        assertEquals(json("""{"type":"equation","equation":{"expression":"e=mc^2"},$annotations}"""), items[4])
    }

    @Test
    fun `a mention keeps its own formatting`() {
        val bold = userMention.replace(""""bold":false""", """"bold":true""").replace(""""color":"default"""", """"color":"red"""")
        val items = sent(InlineMarkdown.fromRichText(richText("[$bold]")))
        assertEquals("true", items[0].annotation("bold"))
        assertEquals("red", items[0].annotation("color"))
    }

    @Test
    fun `template mentions and custom emoji are kept`() {
        val template = """{"type":"mention","mention":{"type":"template_mention","template_mention":{"type":"template_mention_date","template_mention_date":"today"}},
            $annotations,"plain_text":"@Today","href":null}"""
        val emoji = """{"type":"mention","mention":{"type":"custom_emoji","custom_emoji":{"id":"e1","name":"bufo","url":"https://e/x.png"}},
            $annotations,"plain_text":":bufo:","href":null}"""
        val md = InlineMarkdown.fromRichText(richText("[$template,$emoji]"))
        assertEquals("@Today:bufo:", InlineTokens.display(md))
        val items = sent(md)
        assertEquals(
            json("""{"type":"template_mention","template_mention":{"type":"template_mention_date","template_mention_date":"today"}}"""),
            items[0]["mention"],
        )
        assertEquals(json("""{"id":"e1","name":"bufo","url":"https://e/x.png"}"""), items[1]["mention"]!!.jsonObject["custom_emoji"])
    }

    @Test
    fun `a link preview the API can't write stays as a link`() {
        val md = InlineMarkdown.fromRichText(richText("[$linkPreview]"))
        assertEquals("[https://github.com/x](https://github.com/x)", md)
    }

    @Test
    fun `editing text around a mention keeps the mention`() {
        val md = InlineMarkdown.fromRichText(richText("[${item("Ask ")},$userMention]"))
        // Each change is one keystroke or paste, as the editor sends them.
        val first = InlineTokens.edit(md, "Please Ask @Wim", 7)
        val second = InlineTokens.edit(first.stored, "Please ask @Wim", 8)
        val typed = "Please ask @Wim now"
        val edit = InlineTokens.edit(second.stored, typed, typed.length)
        assertEquals(typed, edit.display)
        assertEquals(typed.length, edit.cursor)
        val items = sent(edit.stored)
        assertEquals(userRequest, items[1])
        assertEquals("Please ask ", items[0].content())
        assertEquals(" now", items[2].content())
    }

    @Test
    fun `deleting a mention's text removes the mention`() {
        val md = InlineMarkdown.fromRichText(richText("[${item("Ask ")},$userMention,${item("!")}]"))
        // Backspace after "@Wim" takes the whole mention.
        val edit = InlineTokens.edit(md, "Ask @Wi!", 7)
        assertEquals("Ask !", edit.display)
        assertEquals(4, edit.cursor)
        assertFalse(InlineTokens.hasTokens(edit.stored))
        assertEquals(listOf("Ask !"), sent(edit.stored).map { it.content() })
    }

    @Test
    fun `markdown markers in a mention's name are not formatting`() {
        val star = """{"type":"mention","mention":{"type":"page","page":{"id":"p2"}},$annotations,"plain_text":"*Ideas*","href":null}"""
        val ideas = InlineMarkdown.fromRichText(richText("[$star]"))
        val md = "see $ideas and *more*"
        assertEquals("see *Ideas* and more", InlineMarkdown.stripped(md))
        assertEquals(TextRun("more", italic = true), InlineMarkdown.parse(md).last())
        // A star before a mention doesn't pair with a star in the mention's name.
        val half = InlineTokens.atom("{}", "b*")
        assertEquals(TextRun("*a "), InlineMarkdown.parse("*a $half").first())
    }

    @Test
    fun `colour and underline are kept for unchanged text`() {
        val md = InlineMarkdown.fromRichText(richText("[${item("Plain ")},${colored("red", "red")},${item(" and ")},${colored("under", underline = true)}]"))
        assertEquals("Plain red and under", InlineTokens.display(md))
        val items = sent(md)
        assertNull(items[0].annotation("color"))
        assertEquals("red", items[1].annotation("color"))
        assertEquals("true", items[3].annotation("underline"))
    }

    @Test
    fun `colour stays when its text is edited`() {
        val md = InlineMarkdown.fromRichText(richText("[${colored("red", "red", bold = true)},${item(" text")}]"))
        assertEquals("**red** text", InlineTokens.display(md))
        // Typing at the end of the red word continues in red, as in Notion.
        val edit = InlineTokens.edit(md, "**redder** text", 8)
        assertEquals(listOf(TextRun("redder", bold = true, color = "red"), TextRun(" text")), InlineMarkdown.parse(edit.stored))
    }

    @Test
    fun `colours and mentions read back the same`() {
        val md = InlineMarkdown.fromRichText(
            richText("[${colored("a", "blue_background")},$userMention,${colored("b", "red", underline = true)},$equation]"),
        )
        assertEquals(md, InlineMarkdown.toMarkdown(InlineMarkdown.parse(md)))
        assertEquals(md, InlineTokens.normalize(md))
        assertEquals("a@Wimbe=mc^2", InlineMarkdown.stripped(md))
    }

    @Test
    fun `text from Notion can't fake a mention`() {
        val md = InlineMarkdown.fromRichText(richText("[${item("abc")}]"))
        assertEquals("abc", md)
        assertFalse(InlineTokens.hasTokens(InlineMarkdown.escape("xy")))
    }

    @Test
    fun `plain markdown to copy has mentions as text and no colours`() {
        val md = InlineMarkdown.fromRichText(richText("[${colored("Hi", "red", bold = true)},${item(" ")},$userMention]"))
        assertEquals("**Hi** @Wim", InlineMarkdown.portable(md))
        assertEquals("plain *md*", InlineMarkdown.portable("plain *md*"))
    }

    @Test
    fun `text stored before mentions were kept reads as before`() {
        assertEquals(listOf(TextRun("Ask "), TextRun("Wim", bold = true)), InlineMarkdown.parse("Ask **Wim**"))
        assertEquals("Ask **Wim**", InlineTokens.display("Ask **Wim**"))
        assertEquals(InlineTokens.Edit("Ask **Wim**!", "Ask **Wim**!", 12), InlineTokens.edit("Ask **Wim**", "Ask **Wim**!", 12))
    }

    @Test
    fun `a code block sends its mentions as text`() {
        val md = InlineMarkdown.fromRichText(richText("[${item("run ")},$userMention]"))
        assertEquals(listOf(mapOf("type" to "text", "text" to mapOf("content" to "run @Wim"))), InlineMarkdown.plainRichText(md))
        assertTrue(InlineMarkdown.plainRichText("a *b*").single()["text"].toString().contains("a *b*"))
    }
}
