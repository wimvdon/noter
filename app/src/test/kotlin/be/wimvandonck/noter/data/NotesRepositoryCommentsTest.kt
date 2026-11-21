package be.wimvandonck.noter.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import be.wimvandonck.noter.data.api.NotionClient
import be.wimvandonck.noter.data.api.NotionErrors
import be.wimvandonck.noter.data.api.NotionException
import be.wimvandonck.noter.data.store.CacheStore
import be.wimvandonck.noter.data.store.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Comments in a note: the requests the app sends (see FakeNotion) and what it keeps. */
class NotesRepositoryCommentsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val notion = FakeNotion()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
        notion.close()
    }

    private fun repository() = NotesRepository(
        client = NotionClient(apiKey = { "secret_test" }, baseUrl = notion.baseUrl),
        hasKey = { true },
        settings = SettingsStore(PreferenceDataStoreFactory.create { folder.newFile("settings.preferences_pb").also { it.delete() } }),
        cache = CacheStore(folder.newFolder("cache")),
        scope = scope,
    )

    private fun comment(id: String, discussion: String = "d1", text: String = "Hi", author: String = "bot-1") =
        """{"object":"comment","id":"$id","discussion_id":"$discussion","created_time":"2026-11-21T10:00:00.000Z",
           "parent":{"type":"page_id","page_id":"p1"},"created_by":{"object":"user","id":"$author"},
           "rich_text":[{"type":"text","plain_text":"$text","text":{"content":"$text"}}]}"""

    @Test
    fun `comments are read from the page and kept while the app runs`() = runBlocking {
        notion.respond = { 200 to FakeNotion.list(comment("c1"), comment("c2", text = "Second")) }
        val repo = repository()
        assertNull(repo.cachedComments("p1"))

        val comments = repo.comments("p1")

        assertEquals(listOf("c1", "c2"), comments.map { it.id })
        val sent = notion.requests.single()
        assertEquals("GET", sent.method)
        assertTrue(sent.path, sent.path.startsWith("/comments?block_id=p1"))
        assertEquals(comments, repo.cachedComments("p1"))
    }

    @Test
    fun `every page of comments is read`() = runBlocking {
        notion.respond = { sent ->
            if ("start_cursor=next" in sent.path) {
                200 to FakeNotion.list(comment("c2"))
            } else {
                200 to """{"object":"list","results":[${comment("c1")}],"has_more":true,"next_cursor":"next"}"""
            }
        }
        assertEquals(listOf("c1", "c2"), repository().comments("p1").map { it.id })
        assertEquals(2, notion.requests.size)
    }

    @Test
    fun `a page comment names the page as its parent`() = runBlocking {
        notion.respond = { sent -> if (sent.method == "POST") 200 to comment("c9", text = "New") else 200 to FakeNotion.list() }
        val repo = repository()
        repo.comments("p1")

        val added = repo.addComment("p1", "New **one**")

        val post = notion.requests.last()
        assertEquals("POST", post.method)
        assertEquals("/comments", post.path)
        val body = Json.parseToJsonElement(post.body).jsonObject
        assertEquals("p1", body["parent"]!!.jsonObject["page_id"]!!.jsonPrimitive.content)
        assertTrue(!body.containsKey("discussion_id"))
        assertEquals(2, body["rich_text"]!!.jsonArray.size)
        assertEquals("c9", added.id)
        assertEquals(listOf("c9"), repo.cachedComments("p1")!!.map { it.id })
    }

    @Test
    fun `a reply names the discussion and no parent`() = runBlocking {
        notion.respond = { 200 to comment("c9", discussion = "d1") }
        repository().addComment("p1", "Thanks", discussionId = "d1")

        val body = Json.parseToJsonElement(notion.requests.single().body).jsonObject
        assertEquals("d1", body["discussion_id"]!!.jsonPrimitive.content)
        assertTrue(!body.containsKey("parent"))
    }

    @Test
    fun `an edit sends the new text to that comment and updates the kept one`() = runBlocking {
        notion.respond = { sent ->
            when (sent.method) {
                "PATCH" -> 200 to comment("c1", text = "Changed")
                else -> 200 to FakeNotion.list(comment("c1"), comment("c2"))
            }
        }
        val repo = repository()
        val original = repo.comments("p1").first()

        repo.editComment("p1", original, "Changed")

        val patch = notion.requests.last()
        assertEquals("PATCH", patch.method)
        assertEquals("/comments/c1", patch.path)
        val text = Json.parseToJsonElement(patch.body).jsonObject["rich_text"]!!.jsonArray[0].jsonObject["text"]!!.jsonObject["content"]!!.jsonPrimitive.content
        assertEquals("Changed", text)
        assertEquals(listOf("Changed", "Hi"), repo.cachedComments("p1")!!.map { it.text })
    }

    @Test
    fun `deleting removes the comment in Notion and from the kept ones`() = runBlocking {
        notion.respond = { sent -> if (sent.method == "DELETE") 200 to "{}" else 200 to FakeNotion.list(comment("c1"), comment("c2")) }
        val repo = repository()
        val first = repo.comments("p1").first()

        repo.deleteComment("p1", first)

        val delete = notion.requests.last()
        assertEquals("DELETE", delete.method)
        assertEquals("/comments/c1", delete.path)
        assertEquals(listOf("c2"), repo.cachedComments("p1")!!.map { it.id })
    }

    @Test
    fun `a connection without the comments capability gets a clear error`() = runBlocking {
        notion.respond = { 403 to """{"object":"error","status":403,"code":"restricted_resource","message":"Insufficient permissions for this endpoint."}""" }
        val error = runCatching { repository().comments("p1") }.exceptionOrNull() as NotionException
        assertEquals(NotionErrors.COMMENTS_FORBIDDEN, NotionErrors.ofComments(error))
        // Everywhere else a 403 keeps Notion's own words.
        assertEquals("Insufficient permissions for this endpoint.", NotionErrors.of(error))
    }

    @Test
    fun `the tokens own user id is known from who am I`() = runBlocking {
        notion.respond = { 200 to """{"object":"user","id":"bot-1","type":"bot","name":"Noter","bot":{"owner":{"type":"workspace","workspace":true},"workspace_name":"Home"}}""" }
        assertEquals("bot-1", repository().tokenInfo().id)
    }
}
