package be.wimvandonck.noter.logic

import be.wimvandonck.noter.data.model.CustomEmoji
import be.wimvandonck.noter.data.model.DatabaseInfo
import be.wimvandonck.noter.data.model.PageIcon
import be.wimvandonck.noter.data.model.PinnedDatabase
import be.wimvandonck.noter.data.model.WorkspaceItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseEditTest {
    private val star = PageIcon(emoji = "⭐")

    @Test
    fun `a new name is sent as Notion's title, trimmed`() {
        val body = DatabaseEdit.rename("  Books ").body()
        assertEquals(listOf(mapOf("type" to "text", "text" to mapOf("content" to "Books"))), body["title"])
        assertFalse("icon" in body)
    }

    @Test
    fun `an icon is sent as an emoji, and removed as null`() {
        assertEquals(mapOf("icon" to mapOf("type" to "emoji", "emoji" to "📚")), DatabaseEdit.icon("📚").body())
        val removed = DatabaseEdit.icon(null).body()
        assertEquals(setOf("icon"), removed.keys)
        assertEquals(null, removed["icon"])
    }

    @Test
    fun `a custom emoji is sent as its ID and shown from its image`() {
        val bufo = CustomEmoji("45ce", "bufo", "https://x/b.png")
        val edit = DatabaseEdit.icon(null, bufo)
        assertEquals(mapOf("icon" to mapOf("type" to "custom_emoji", "custom_emoji" to mapOf("id" to "45ce"))), edit.body())
        assertEquals(PageIcon(url = "https://x/b.png", customEmojiId = "45ce"), edit.icon(star))
    }

    @Test
    fun `Home's rows of the database and its data source change, others don't`() {
        val items = listOf(
            WorkspaceItem("db-1", isDatabase = true, title = "Old", icon = star),
            WorkspaceItem("src1", isDatabase = true, title = "Old"),
            WorkspaceItem("other", isDatabase = false, title = "Page", icon = star),
        )
        val renamed = DatabaseEdit.rename("New").applyTo(items, setOf("db1", "src1"))
        assertEquals(listOf("New", "New", "Page"), renamed.map { it.title })
        assertEquals(star, renamed[0].icon)

        val noIcon = DatabaseEdit.icon(null).applyTo(items, setOf("db1"))
        assertEquals(listOf(null, null, star), noIcon.map { it.icon })
        assertEquals("Old", noIcon[0].title)
    }

    @Test
    fun `pins and what is kept about the database follow`() {
        val pins = listOf(PinnedDatabase("src-1", "Old"), PinnedDatabase("x", "X", star))
        assertEquals(
            listOf(PinnedDatabase("src-1", "Old", PageIcon(emoji = "📚")), PinnedDatabase("x", "X", star)),
            DatabaseEdit.icon("📚").applyToPins(pins, setOf("src1")),
        )
        assertEquals(DatabaseInfo("d", "New", star), DatabaseEdit.rename("New").applyTo(DatabaseInfo("d", "Old", star)))
    }

    private val inPage = DatabaseInfo("d", "Books", parentId = "p", description = "Old", locked = false, inline = false)

    private fun of(info: DatabaseInfo?, description: String = "Old", locked: Boolean = false, inline: Boolean = false, title: String = "Books") =
        DatabaseEdit.of("Books", title, null, null, false, info, description, locked, inline)

    @Test
    fun `nothing changed is an empty edit`() {
        assertTrue(of(inPage).isEmpty)
    }

    @Test
    fun `description, lock and inline go to the database, only name and icon to its data source`() {
        val edit = of(inPage, description = " New text ", locked = true, inline = true, title = "Reading")
        assertEquals(
            mapOf(
                "title" to listOf(mapOf("type" to "text", "text" to mapOf("content" to "Reading"))),
                "description" to listOf(mapOf("type" to "text", "text" to mapOf("content" to "New text"))),
                "is_locked" to true,
                "is_inline" to true,
            ),
            edit.body(),
        )
        assertEquals(setOf("title"), edit.sourceBody().keys)
        assertEquals(DatabaseInfo("d", "Reading", parentId = "p", description = "New text", locked = true, inline = true), edit.applyTo(inPage))
    }

    @Test
    fun `an emptied description is sent as no text, a long one in pieces`() {
        assertEquals(emptyList<Any>(), of(inPage, description = "").body()["description"])
        assertEquals(listOf(2000, 500), DatabaseEdit.richText("a".repeat(2500)).map { ((it["text"] as Map<*, *>)["content"] as String).length })
    }

    @Test
    fun `inline only for a database in a page, and nothing but the name when the database isn't known`() {
        assertNull(of(inPage.copy(parentId = null), inline = true).inline)
        val unknown = of(null, description = "x", locked = true, inline = true)
        assertTrue(unknown.isEmpty)
    }
}
