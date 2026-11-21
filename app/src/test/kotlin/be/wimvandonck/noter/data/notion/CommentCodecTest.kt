package be.wimvandonck.noter.data.notion

import be.wimvandonck.noter.data.model.Comment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CommentCodecTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `a page comment is read with its author, time and text`() {
        val comment = CommentCodec.parse(
            obj(
                """{"object":"comment","id":"c1","discussion_id":"d1","created_time":"2026-11-21T10:00:00.000Z",
                   "parent":{"type":"page_id","page_id":"p1"},"created_by":{"object":"user","id":"u1"},
                   "display_name":{"type":"user","resolved_name":"Avo Cado"},
                   "rich_text":[{"type":"text","plain_text":"Hello ","text":{"content":"Hello "}},
                                {"type":"text","plain_text":"world","text":{"content":"world"},"annotations":{"bold":true}}]}""",
            ),
        )
        assertEquals(
            Comment("c1", "d1", "2026-11-21T10:00:00.000Z", "u1", "Avo Cado", "Hello **world**", blockId = null),
            comment,
        )
    }

    @Test
    fun `a comment on a block knows the block`() {
        val comment = CommentCodec.parse(
            obj("""{"id":"c2","discussion_id":"d2","parent":{"type":"block_id","block_id":"b1"},"created_by":{"id":"u1"},"rich_text":[]}"""),
        )
        assertEquals("b1", comment?.blockId)
        assertEquals("", comment?.text)
    }

    @Test
    fun `an object without an id or discussion is no comment`() {
        assertNull(CommentCodec.parse(obj("""{"id":"c3"}""")))
        assertNull(CommentCodec.parse(obj("""{"discussion_id":"d"}""")))
    }

    @Test
    fun `a new comment names the page, a reply only the discussion`() {
        val onPage = CommentCodec.addBody("p1", null, "Hi")
        assertEquals(mapOf("page_id" to "p1"), onPage["parent"])
        assertFalse(onPage.containsKey("discussion_id"))

        val reply = CommentCodec.addBody("p1", "d1", "Hi")
        assertEquals("d1", reply["discussion_id"])
        assertFalse(reply.containsKey("parent"))
    }

    @Test
    fun `the text goes as rich text, bold kept`() {
        val body = CommentCodec.editBody("a **b**")
        @Suppress("UNCHECKED_CAST")
        val items = body["rich_text"] as List<Map<String, Any?>>
        assertEquals(2, items.size)
        assertEquals("a ", (items[0]["text"] as Map<*, *>)["content"])
        assertEquals(true, (items[1]["annotations"] as Map<*, *>)["bold"])
    }
}
