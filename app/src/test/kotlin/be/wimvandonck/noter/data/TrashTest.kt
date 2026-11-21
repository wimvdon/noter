package be.wimvandonck.noter.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import be.wimvandonck.noter.data.api.NotionClient
import be.wimvandonck.noter.data.api.NotionException
import be.wimvandonck.noter.data.model.DatabaseSetup
import be.wimvandonck.noter.data.model.PropertyValue
import be.wimvandonck.noter.data.model.NoteProperty
import be.wimvandonck.noter.data.model.TrashedPage
import be.wimvandonck.noter.data.store.CacheStore
import be.wimvandonck.noter.data.store.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Undo after moving a note to the trash, and the Trash list (restoring pages from Notion's trash). */
class TrashTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val notion = FakeNotion()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings = SettingsStore(
        PreferenceDataStoreFactory.create { folder.newFile("settings.preferences_pb").also { it.delete() } },
    )

    @After
    fun tearDown() {
        scope.cancel()
        notion.close()
    }

    private fun repository(): NotesRepository =
        NotesRepository(
            client = NotionClient(apiKey = { "secret_test" }, baseUrl = notion.baseUrl),
            hasKey = { true },
            settings = settings,
            cache = CacheStore(folder.newFolder("cache")),
            scope = scope,
        ).also { repo ->
            runBlocking { settings.update { it.copy(setups = it.setups + ("db" to DatabaseSetup())) } }
            repo.start()
        }

    private fun answerWith(vararg pages: String, extra: (Sent) -> Pair<Int, String>? = { null }) {
        notion.respond = { sent ->
            extra(sent) ?: when {
                sent.path.startsWith("/data_sources/db/query") -> 200 to FakeNotion.list(*pages)
                sent.path.startsWith("/data_sources/db") -> 200 to FakeNotion.database("db")
                sent.method == "PATCH" && sent.path.startsWith("/pages/") ->
                    200 to FakeNotion.page(sent.path.removePrefix("/pages/"), "db", "Old", inTrash = sent.body.contains("true"))
                else -> 200 to "{}"
            }
        }
    }

    private fun patches() = notion.requests.filter { it.method == "PATCH" }

    @Test
    fun `undo right after trashing sends nothing and brings the note back where it was, pinned`() = runBlocking {
        answerWith(FakeNotion.page("page-1", "db", "One"), FakeNotion.page("page-2", "db", "Two"))
        settings.update { it.copy(pinned = listOf("page-2", "page-1")) }
        val repo = repository()
        repo.refresh("db", userAsked = true)
        val before = repo.databases.value["db"]?.notes?.map { it.id }.orEmpty()

        repo.trashNote("page-1")
        val kept = repo.lastTrashed.value!!
        assertEquals("page-1", kept.note.id)
        assertEquals(before - "page-1", repo.databases.value["db"]?.notes?.map { it.id })
        assertEquals(listOf("page-2"), settings.current().pinned)

        repo.undoTrash(kept)
        repo.flush()

        assertEquals(before, repo.databases.value["db"]?.notes?.map { it.id })
        assertEquals(listOf("page-2", "page-1"), settings.current().pinned)
        assertTrue(patches().isEmpty())
        assertEquals(0, repo.pendingCount.first())
        assertNull(repo.lastTrashed.value)
    }

    @Test
    fun `undo after the trash was sent restores the page in Notion`() = runBlocking {
        answerWith(FakeNotion.page("page-1", "db", "One"))
        val repo = repository()
        repo.refresh("db", userAsked = true)
        repo.trashNote("page-1")
        val kept = repo.lastTrashed.value!!
        repo.flush()

        repo.undoTrash(kept)
        assertEquals(listOf("page-1"), repo.databases.value["db"]?.notes?.map { it.id })
        repo.flush()

        assertEquals(listOf(true, false), patches().map { it.body.contains("\"in_trash\":true") })
        assertTrue(patches().all { it.path == "/pages/page-1" })
        assertEquals(0, repo.pendingCount.first())
    }

    @Test
    fun `undo while Notion is failing keeps the note, and the change that was waiting is sent again`() = runBlocking {
        var online = true
        answerWith(FakeNotion.page("page-1", "db", "One")) { sent ->
            if (!online && sent.method == "PATCH") 502 to "{}" else null
        }
        val repo = repository()
        repo.refresh("db", userAsked = true)
        // A change that was waiting when the note went to the trash comes back with it.
        repo.updateProperty("page-1", NoteProperty("Name", "title", PropertyValue.Text("Edited")))
        repo.trashNote("page-1")
        val kept = repo.lastTrashed.value!!
        online = false
        repo.flush()
        // The trash didn't get through (a server error keeps it queued). Undo takes it back, or, when it was
        // being sent just then, restores the page behind it; either way the edit follows.
        repo.undoTrash(kept)
        online = true
        repo.flush()

        val kinds = patches().map { if (it.body.contains("\"in_trash\":true")) "trash" else if (it.body.contains("\"in_trash\":false")) "restore" else "edit" }
        assertEquals(kinds.toString(), kinds.sortedBy { listOf("trash", "restore", "edit").indexOf(it) }, kinds)
        assertEquals("edit", kinds.last())
        assertTrue(patches().last().body, patches().last().body.contains("Edited"))
        assertEquals("Edited", repo.databases.value["db"]?.notes?.single()?.title)
    }

    @Test
    fun `a note trashed and brought back accepts edits again`() = runBlocking {
        answerWith(FakeNotion.page("page-1", "db", "One"))
        val repo = repository()
        repo.refresh("db", userAsked = true)
        repo.trashNote("page-1")
        repo.undoTrash(repo.lastTrashed.value!!)

        repo.updateProperty("page-1", NoteProperty("Name", "title", PropertyValue.Text("New")))
        repo.flush()

        assertTrue(patches().any { it.body.contains("New") })
    }

    @Test
    fun `the trash is searched for pages, and each page says where it was`() = runBlocking {
        val inDatabase = FakeNotion.page("t1", "db", "In a database", inTrash = true)
        val subpage = FakeNotion.page("t2", "db", "Subpage", inTrash = true)
            .replace("""{"type":"database_id","database_id":"db"}""", """{"type":"page_id","page_id":"parent-page"}""")
        answerWith {
            when {
                it.method == "POST" && it.path == "/search" -> 200 to FakeNotion.list(inDatabase, subpage)
                it.path == "/pages/parent-page" -> 200 to FakeNotion.page("parent-page", "other", "Projects")
                else -> null
            }
        }
        val repo = repository()

        val pages = repo.trashedPages()

        val search = notion.requests.last { it.path == "/search" }
        assertTrue(search.body, search.body.contains("\"filter\":{\"property\":\"object\",\"value\":\"page\",\"in_trash\":true}"))
        assertEquals(listOf("In a database", "Subpage"), pages.map { it.title })
        assertEquals(listOf("Notes", "Projects"), pages.map { it.parentName })
    }

    @Test
    fun `the trash list fails when Notion can't be reached`() = runBlocking {
        answerWith { 502 to "{}" }
        val repo = repository()
        val failure = runCatching { repo.trashedPages() }.exceptionOrNull()
        assertTrue(failure is NotionException)
    }

    @Test
    fun `restoring a page sends in_trash false and shows it in its database again`() = runBlocking {
        answerWith(FakeNotion.page("page-2", "db", "Other"))
        val repo = repository()
        repo.refresh("db", userAsked = true)

        repo.restoreFromTrash(TrashedPage("t1", "Back again", parentId = "db", parentIsDatabase = true))

        val restore = patches().single()
        assertEquals("/pages/t1", restore.path)
        assertTrue(restore.body, restore.body.contains("\"in_trash\":false"))
        assertEquals(setOf("page-2", "t1"), repo.databases.value["db"]?.notes?.map { it.id }?.toSet())
    }

    @Test
    fun `restoring a page that is not in a database asks Home's list again`() = runBlocking {
        answerWith(extra = { sent ->
            when {
                sent.method == "PATCH" -> 200 to FakeNotion.page("t9", "db", "Loose").replace(
                    """{"type":"database_id","database_id":"db"}""",
                    """{"type":"workspace","workspace":true}""",
                )
                sent.method == "POST" && sent.path.startsWith("/search") -> 200 to FakeNotion.list()
                else -> null
            }
        })
        val repo = repository()

        repo.restoreFromTrash(TrashedPage("t9", "Loose"))
        repeat(50) {
            if (notion.requests.any { it.method == "POST" && it.path.startsWith("/search") }) return@repeat
            kotlinx.coroutines.delay(100)
        }

        assertTrue(notion.requests.any { it.method == "POST" && it.path.startsWith("/search") })
        assertTrue(patches().single().body.contains("\"in_trash\":false"))
    }

    @Test
    fun `a failed restore throws and changes nothing`() = runBlocking {
        answerWith(extra = { sent -> if (sent.method == "PATCH") 400 to """{"object":"error","status":400,"code":"validation_error","message":"parent is in trash"}""" else null })
        val repo = repository()
        val failure = runCatching { repo.restoreFromTrash(TrashedPage("t1", "X", parentId = "db", parentIsDatabase = true)) }.exceptionOrNull()
        assertTrue(failure is NotionException)
        assertTrue(repo.databases.value["db"]?.notes.orEmpty().isEmpty())
    }
}
