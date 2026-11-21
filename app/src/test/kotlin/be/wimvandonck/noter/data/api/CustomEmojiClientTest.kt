package be.wimvandonck.noter.data.api

import be.wimvandonck.noter.data.FakeNotion
import be.wimvandonck.noter.logic.Workspaces
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomEmojiClientTest {
    private fun emoji(id: String) = """{"id":"$id","name":"e$id","url":"https://x/$id.png"}"""

    @Test
    fun `custom emojis are read page by page`() = runBlocking {
        FakeNotion { sent ->
            if (sent.path == "/custom_emojis") {
                200 to """{"object":"list","type":"custom_emoji","results":[${emoji("1")}],"has_more":true,"next_cursor":"c2"}"""
            } else {
                200 to """{"object":"list","type":"custom_emoji","results":[${emoji("2")}],"has_more":false,"next_cursor":null}"""
            }
        }.use { notion ->
            val client = NotionClient(apiKey = { "a" }, baseUrl = notion.baseUrl)
            assertEquals(listOf("1", "2"), client.listCustomEmojis(null).map { it.stringOrNull("id") })
            assertEquals(listOf("/custom_emojis", "/custom_emojis?start_cursor=c2"), notion.requests.map { it.path })
            assertEquals(listOf("GET", "GET"), notion.requests.map { it.method })
        }
    }

    @Test
    fun `the emojis of a workspace are asked with its token`() = runBlocking {
        FakeNotion { 200 to FakeNotion.list(emoji("1")) }.use { notion ->
            val client = NotionClient(apiKey = { "a" }, extraKeys = { listOf("b") }, baseUrl = notion.baseUrl)
            client.listCustomEmojis(Workspaces.id("b"))
            client.listCustomEmojis(null)
            client.listCustomEmojis("removed")
            assertEquals(listOf("Bearer b", "Bearer a", "Bearer a"), notion.requests.map { it.auth })
        }
    }

    @Test
    fun `a page's icon is set to a custom emoji by its ID, and to an emoji or nothing`() = runBlocking {
        FakeNotion { 200 to """{"object":"page","id":"p1"}""" }.use { notion ->
            val client = NotionClient(apiKey = { "a" }, baseUrl = notion.baseUrl)
            client.updatePageIcon("p1", null, "45ce")
            client.updatePageIcon("p1", "🥑")
            client.updatePageIcon("p1", null)
            assertEquals(
                listOf(
                    """{"icon":{"type":"custom_emoji","custom_emoji":{"id":"45ce"}}}""",
                    """{"icon":{"type":"emoji","emoji":"🥑"}}""",
                    """{"icon":null}""",
                ),
                notion.requests.map { it.body },
            )
            assertEquals(listOf("PATCH", "PATCH", "PATCH"), notion.requests.map { it.method })
        }
    }

    @Test
    fun `a new page is made with a custom emoji icon`() = runBlocking {
        FakeNotion { 200 to """{"object":"page","id":"new"}""" }.use { notion ->
            val client = NotionClient(apiKey = { "a" }, baseUrl = notion.baseUrl)
            client.createPage("ds1", emptyMap(), emptyList(), customEmojiId = "45ce")
            assertEquals(true, notion.requests.single().body.contains(""""icon":{"type":"custom_emoji","custom_emoji":{"id":"45ce"}}"""))
        }
    }
}
