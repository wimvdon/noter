package be.wimvandonck.noter.logic

import be.wimvandonck.noter.data.model.Block
import be.wimvandonck.noter.data.model.Comment
import be.wimvandonck.noter.data.model.NotionUser
import be.wimvandonck.noter.data.notion.InlineTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentThreadsTest {
    private fun comment(id: String, discussion: String, time: String, author: String = "u1", block: String? = null, text: String = "t") =
        Comment(id, discussion, time, author, text = text, blockId = block)

    @Test
    fun `comments are grouped by discussion, the page's own first`() {
        val grouped = CommentThreads.group(
            listOf(
                comment("c1", "dBlock", "2026-11-21T09:00:00Z", block = "b1"),
                comment("c3", "dPage", "2026-11-21T11:00:00Z"),
                comment("c2", "dPage", "2026-11-21T10:00:00Z"),
                comment("c4", "dBlock", "2026-11-21T12:00:00Z", block = "b1"),
            ),
        )
        assertEquals(listOf("dPage", "dBlock"), grouped.map { it.id })
        assertEquals(listOf("c2", "c3"), grouped[0].comments.map { it.id })
        assertEquals(listOf("c1", "c4"), grouped[1].comments.map { it.id })
        assertNull(grouped[0].blockId)
        assertEquals("b1", grouped[1].blockId)
    }

    @Test
    fun `no comments, no discussions`() {
        assertTrue(CommentThreads.group(emptyList()).isEmpty())
    }

    @Test
    fun `a comment is the tokens own when the author matches, with or without dashes`() {
        val c = comment("c", "d", "t", author = "AB-CD")
        assertTrue(CommentThreads.isOwn(c, listOf("abcd")))
        assertTrue(CommentThreads.isOwn(c, listOf(null, "ab-cd")))
        assertFalse(CommentThreads.isOwn(c, listOf("other")))
        assertFalse(CommentThreads.isOwn(c, emptyList()))
        assertFalse(CommentThreads.isOwn(comment("c", "d", "t", author = ""), listOf("")))
    }

    @Test
    fun `the author is looked up among the known users, else the name Notion sent`() {
        val users = listOf(NotionUser("ab-cd", "Wim"))
        assertEquals("Wim", CommentThreads.authorName(comment("c", "d", "t", author = "abcd"), users))
        assertEquals("Avo", CommentThreads.authorName(comment("c", "d", "t", author = "zz").copy(authorName = "Avo"), users))
        assertNull(CommentThreads.authorName(comment("c", "d", "t", author = "zz"), users))
    }

    @Test
    fun `a block discussion quotes one line of its block when it is loaded`() {
        val blocks = listOf(Block("k1", notionId = "b1", text = "First line **bold**\nsecond"), Block("k2", notionId = "b2", text = "x"))
        val discussion = CommentThreads.group(listOf(comment("c", "d", "t", block = "B1"))).single()
        assertEquals("First line bold", CommentThreads.quote(discussion, blocks))
        assertNull(CommentThreads.quote(discussion, emptyList()))
        assertNull(CommentThreads.quote(CommentThreads.group(listOf(comment("c", "d", "t"))).single(), blocks))
    }

    @Test
    fun `a long quote is cut`() {
        val blocks = listOf(Block("k", notionId = "b1", text = "a".repeat(200)))
        val discussion = CommentThreads.group(listOf(comment("c", "d", "t", block = "b1"))).single()
        val quote = CommentThreads.quote(discussion, blocks, maxLength = 20)!!
        assertEquals(20, quote.length)
        assertTrue(quote.endsWith("…"))
    }

    @Test
    fun `times are read, odd ones are not`() {
        assertEquals(1_700_000_000_000L, CommentThreads.millis("2023-11-14T22:13:20.000Z"))
        assertNull(CommentThreads.millis("soon"))
    }

    @Test
    fun `only text counts as a comment to send`() {
        assertEquals("hi", CommentThreads.sendable("  hi \n"))
        assertNull(CommentThreads.sendable("  \n "))
    }

    @Test
    fun `a comment with a mention is not edited here`() {
        assertTrue(CommentThreads.editable(comment("c", "d", "t", text = "plain **text**")))
        assertFalse(CommentThreads.editable(comment("c", "d", "t", text = "hi " + InlineTokens.atom("{}", "@Wim"))))
    }
}
