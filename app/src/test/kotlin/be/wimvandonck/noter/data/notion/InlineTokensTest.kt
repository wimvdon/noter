package be.wimvandonck.noter.data.notion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineTokensTest {
    private val wim = InlineTokens.atom("""{"type":"mention","mention":{"type":"user","user":{"id":"u1"}}}""", "@Wim")
    private val red = InlineTokens.spanOpen("red", underline = false)
    private val close = InlineTokens.SPAN_CLOSE.toString()

    @Test
    fun `display shows a mention's text and hides colour markers`() {
        assertEquals("Ask @Wim in red", InlineTokens.display("Ask $wim in ${red}red$close"))
        assertEquals(listOf(4 until 8), InlineTokens.layout("Ask $wim!").atoms.map { it.start until it.end })
    }

    @Test
    fun `typing before and after a mention keeps it whole`() {
        val text = "Ask $wim"
        val before = InlineTokens.edit(text, "Ask x@Wim", 5)
        assertEquals("Ask x$wim", before.stored)
        assertEquals(5, before.cursor)
        val after = InlineTokens.edit(text, "Ask @Wim!", 9)
        assertEquals("Ask $wim!", after.stored)
    }

    @Test
    fun `typing inside a mention goes after it`() {
        val edit = InlineTokens.edit("Ask $wim", "Ask @Wxim", 7)
        assertEquals("Ask ${wim}x", edit.stored)
        assertEquals("Ask @Wimx", edit.display)
        assertEquals(9, edit.cursor)
    }

    @Test
    fun `deleting part of a mention removes all of it`() {
        // Selecting "k @W" and deleting it.
        val edit = InlineTokens.edit("Ask $wim now", "Asim now", 2)
        assertEquals("As now", edit.stored)
        assertEquals("As now", edit.display)
        assertEquals(2, edit.cursor)
    }

    @Test
    fun `typing over a mention replaces it`() {
        val edit = InlineTokens.edit("Ask $wim", "Ask Jan", 7)
        assertEquals("Ask Jan", edit.stored)
        assertFalse(InlineTokens.hasTokens(edit.stored))
    }

    @Test
    fun `two mentions side by side stay apart`() {
        val text = "$wim$wim"
        val edit = InlineTokens.edit(text, "@Wim@Wi", 7)
        assertEquals(wim, edit.stored)
        assertEquals("@Wim", edit.display)
    }

    @Test
    fun `deleting coloured text keeps the colour of what is left`() {
        val text = "a ${red}red$close b"
        val edit = InlineTokens.edit(text, "a rd b", 3)
        assertEquals("a ${red}rd$close b", edit.stored)
        // All of the coloured text gone: no empty colour left behind.
        assertEquals("a  b", InlineTokens.edit(text, "a  b", 2).stored)
    }

    @Test
    fun `pasted marker characters are dropped`() {
        val edit = InlineTokens.edit("Ask $wim", "Ask @Wim x", 11)
        assertEquals("Ask $wim x", edit.stored)
        assertEquals("plain", InlineTokens.edit("", "plain", 6).stored)
    }

    @Test
    fun `normalize tidies stray, empty and unclosed colour markers`() {
        assertEquals("ab", InlineTokens.normalize("a${close}b"))
        assertEquals("ab", InlineTokens.normalize("a$red${close}b"))
        assertEquals("a${red}b$close", InlineTokens.normalize("a${red}b"))
        assertEquals("${red}ab$close", InlineTokens.normalize("${red}a$close${red}b$close"))
        assertEquals("x", InlineTokens.normalize("x"))
    }

    @Test
    fun `splitting a line keeps its colour on both lines and the mention whole`() {
        assertEquals(listOf("${red}ab$close", "${red}cd$close $wim"), InlineTokens.splitLines("${red}ab\ncd$close $wim"))
        assertEquals(listOf("a", "b"), InlineTokens.splitLines("a\nb"))
    }

    @Test
    fun `a mention's text has no line breaks`() {
        assertFalse(InlineTokens.atom("{}", "a\nb").contains('\n'))
        assertTrue(InlineTokens.hasTokens(wim))
    }
}
