package be.wimvandonck.noter.data.notion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashCodecTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun page(id: String, parent: String, title: String = "Old note", icon: String = "") =
        json(
            """{"object":"page","id":"$id","in_trash":true,"last_edited_time":"2026-11-01T10:00:00.000Z",
               "parent":$parent,$icon
               "properties":{"Name":{"id":"title","type":"title","title":[{"type":"text","plain_text":"$title","text":{"content":"$title"},"annotations":{}}]}}}""",
        )

    @Test
    fun `a trashed note of a database remembers its data source`() {
        val parsed = TrashCodec.parse(page("p1", """{"type":"data_source_id","data_source_id":"ds1"}""", icon = """"icon":{"type":"emoji","emoji":"📝"},"""))!!
        assertEquals("p1", parsed.id)
        assertEquals("Old note", parsed.title)
        assertEquals("ds1", parsed.parentId)
        assertTrue(parsed.parentIsDatabase)
        assertEquals("📝", parsed.icon?.emoji)
        assertEquals("2026-11-01T10:00:00.000Z", parsed.lastEditedTime)
    }

    @Test
    fun `a trashed subpage remembers its page`() {
        val parsed = TrashCodec.parse(page("p2", """{"type":"page_id","page_id":"parent"}"""))!!
        assertEquals("parent", parsed.parentId)
        assertFalse(parsed.parentIsDatabase)
    }

    @Test
    fun `a page at the top of the workspace has no place`() {
        assertNull(TrashCodec.parse(page("p3", """{"type":"workspace","workspace":true}"""))!!.parentId)
    }

    @Test
    fun `only pages are kept`() {
        val results = listOf(
            page("p1", """{"type":"workspace","workspace":true}"""),
            json("""{"object":"data_source","id":"ds","title":[]}"""),
        )
        assertEquals(listOf("p1"), TrashCodec.parseAll(results).map { it.id })
    }
}
