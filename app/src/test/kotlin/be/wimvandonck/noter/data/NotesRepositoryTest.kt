package be.wimvandonck.noter.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import be.wimvandonck.noter.data.api.NotionClient
import be.wimvandonck.noter.data.api.NotionException
import be.wimvandonck.noter.logic.MoveDestination
import be.wimvandonck.noter.data.model.Block
import be.wimvandonck.noter.data.model.BlockType
import be.wimvandonck.noter.data.model.DatabaseSchema
import be.wimvandonck.noter.data.model.DatabaseSetup
import be.wimvandonck.noter.data.model.NoteSummary
import be.wimvandonck.noter.data.model.PageIcon
import be.wimvandonck.noter.data.model.PinnedDatabase
import be.wimvandonck.noter.data.model.PropertySchema
import be.wimvandonck.noter.data.model.NoteProperty
import be.wimvandonck.noter.data.model.PropertyValue
import be.wimvandonck.noter.logic.NotionViews
import be.wimvandonck.noter.logic.SharedContent
import be.wimvandonck.noter.logic.TemplatePick
import be.wimvandonck.noter.data.model.CardLayout
import be.wimvandonck.noter.data.store.CacheStore
import be.wimvandonck.noter.data.store.DatabaseCache
import be.wimvandonck.noter.data.sync.PendingOp
import be.wimvandonck.noter.data.store.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NotesRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val notion = FakeNotion()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
        notion.close()
    }

    private fun repository(
        cache: CacheStore = CacheStore(folder.newFolder("cache")),
        settings: SettingsStore = newSettings(),
    ): NotesRepository =
        NotesRepository(
            client = NotionClient(apiKey = { "secret_test" }, baseUrl = notion.baseUrl),
            hasKey = { true },
            settings = settings,
            cache = cache,
            scope = scope,
            templateDelaysMs = listOf(50L, 100L),
            timeZone = { "Europe/Brussels" },
        ).also { repo ->
            runBlocking { settings.update { it.copy(setups = it.setups + ("db" to DatabaseSetup())) } }
            repo.start()
        }

    private fun newSettings() = SettingsStore(PreferenceDataStoreFactory.create { folder.newFile("settings-${java.util.UUID.randomUUID()}.preferences_pb").also { it.delete() } })

    @Test
    fun `a new note stays open while Notion creates it`() = runBlocking {
        notion.respond = { sent ->
            when {
                sent.path.startsWith("/data_sources/db/query") -> 200 to FakeNotion.list()
                sent.path.startsWith("/data_sources/db") -> 200 to FakeNotion.database("db")
                sent.method == "POST" && sent.path == "/pages" -> 200 to FakeNotion.page("page-1", "db")
                else -> 200 to "{}"
            }
        }
        val repo = repository()
        val localId = checkNotNull(repo.createNote("db"))
        val seen = Collections.synchronizedList(mutableListOf<String?>())
        val watcher = scope.launch { repo.note(localId).collect { seen += it?.id } }

        repo.flush()

        val note = withTimeout(5_000) { repo.note(localId).first { it?.id == "page-1" } }
        assertEquals("page-1", note?.id)
        assertEquals("page-1", repo.resolve(localId))
        watcher.cancel()
        // The editor never saw the note disappear.
        assertFalse(seen.toString(), seen.contains(null))
    }

    @Test
    fun `a page Notion made without answering is not made again`() = runBlocking {
        val made = FakeNotion.page("page-1", "db").replace("2026-10-16T10:00:00.000Z", java.time.Instant.now().toString())
        var created = false
        notion.respond = { sent ->
            when {
                sent.method == "POST" && sent.path == "/pages" -> {
                    // Notion makes the page, but the answer is a server error.
                    created = true
                    502 to "{}"
                }
                sent.path.startsWith("/data_sources/db/query") ->
                    200 to if (created && sent.body.contains("created_time")) FakeNotion.list(made) else FakeNotion.list()
                sent.path.startsWith("/data_sources/db") -> 200 to FakeNotion.database("db")
                else -> 200 to "{}"
            }
        }
        val repo = repository()
        val localId = checkNotNull(repo.createNote("db"))
        repo.flush()
        assertEquals(1, repo.pendingCount.first())
        repo.flush()

        assertEquals(1, notion.requests.count { it.method == "POST" && it.path == "/pages" })
        assertEquals("page-1", repo.resolve(localId))
        assertEquals(0, repo.pendingCount.first())
    }

    @Test
    fun `a page that wasn't made after a lost answer is made on the next try`() = runBlocking {
        var tries = 0
        notion.respond = { sent ->
            when {
                sent.method == "POST" && sent.path == "/pages" -> if (++tries == 1) 502 to "{}" else 200 to FakeNotion.page("page-2", "db")
                sent.path.startsWith("/data_sources/db/query") -> 200 to FakeNotion.list()
                sent.path.startsWith("/data_sources/db") -> 200 to FakeNotion.database("db")
                else -> 200 to "{}"
            }
        }
        val repo = repository()
        val localId = checkNotNull(repo.createNote("db"))
        repo.flush()
        repo.flush()

        assertEquals(2, notion.requests.count { it.method == "POST" && it.path == "/pages" })
        assertEquals("page-2", repo.resolve(localId))
    }

    @Test
    fun `edits to a note in the trash are not sent`() = runBlocking {
        notion.respond = { sent ->
            when {
                sent.path.startsWith("/data_sources/db/query") -> 200 to FakeNotion.list(FakeNotion.page("page-1", "db", "Old"))
                sent.path.startsWith("/data_sources/db") -> 200 to FakeNotion.database("db")
                sent.method == "PATCH" && sent.path == "/pages/page-1" -> 200 to FakeNotion.page("page-1", "db", inTrash = true)
                else -> 200 to "{}"
            }
        }
        val repo = repository()
        repo.refresh("db", userAsked = true)
        assertEquals(listOf("page-1"), repo.databases.value["db"]?.notes?.map { it.id })

        repo.trashNote("page-1")
        // An editor that is closing hands over what was still waiting.
        repo.updateProperty("page-1", NoteProperty("Name", "title", PropertyValue.Text("New")))
        repo.saveContent("page-1", emptyList())
        repo.flush()

        val patches = notion.requests.filter { it.method == "PATCH" }
        assertEquals(1, patches.size)
        assertTrue(patches.single().body, patches.single().body.contains("\"in_trash\":true"))
    }

    @Test
    fun `saving a note that was trashed in Notion fails quietly`() = runBlocking {
        notion.respond = { sent ->
            when {
                sent.path.startsWith("/data_sources/db/query") -> 200 to FakeNotion.list(FakeNotion.page("page-1", "db", "Old"))
                sent.path.startsWith("/data_sources/db") -> 200 to FakeNotion.database("db")
                sent.method == "PATCH" -> 400 to FakeNotion.ARCHIVED
                else -> 200 to "{}"
            }
        }
        val repo = repository()
        repo.refresh("db", userAsked = true)
        val events = Collections.synchronizedList(mutableListOf<RepoEvent>())
        val collector = scope.launch { repo.events.collect { events += it } }

        repo.updateProperty("page-1", NoteProperty("Name", "title", PropertyValue.Text("New")))
        repo.flush()

        collector.cancel()
        assertTrue(events.toString(), events.isEmpty())
        assertEquals(0, repo.pendingCount.first())
    }

    @Test
    fun `tables come with their rows, and expired images are downloaded again`() = runBlocking {
        var signature = 1
        notion.respond = { sent ->
            when {
                sent.path.startsWith("/data_sources/db/query") -> 200 to FakeNotion.list(FakeNotion.page("page-1", "db", "Old"))
                sent.path.startsWith("/data_sources/db") -> 200 to FakeNotion.database("db")
                sent.path == "/pages/page-1" -> 200 to FakeNotion.page("page-1", "db", "Old")
                sent.path.startsWith("/blocks/page-1/children") -> 200 to FakeNotion.list(
                    """{"id":"img","type":"image","image":{"type":"file","caption":[],
                        "file":{"url":"https://s3/cat.png?sig=${signature++}","expiry_time":"2000-01-01T00:00:00.000Z"}}}""",
                    """{"id":"tbl","type":"table","has_children":true,"table":{"table_width":1,"has_column_header":false}}""",
                )
                sent.path.startsWith("/blocks/tbl/children") ->
                    200 to FakeNotion.list("""{"type":"table_row","table_row":{"cells":[[{"plain_text":"A1"}]]}}""")
                else -> 200 to "{}"
            }
        }
        val repo = repository()
        repo.refresh("db", userAsked = true)

        val first = checkNotNull(repo.fetchContent("page-1"))
        assertEquals(listOf(listOf("A1")), first.blocks.single { it.rawType == "table" }.rows)
        // The image's link has expired: opening the note again gets a fresh one.
        val second = checkNotNull(repo.fetchContent("page-1"))
        assertEquals("https://s3/cat.png?sig=2", second.blocks.single { it.rawType == "image" }.url)
        // Blocks keep their keys, so the editor doesn't redraw everything.
        assertEquals(first.blocks.map { it.key }, second.blocks.map { it.key })
    }

    @Test
    fun `pages from Browse and linked pages open in the editor and can be pinned`() = runBlocking {
        notion.respond = { sent ->
            when {
                sent.path.startsWith("/data_sources/db/query") -> 200 to FakeNotion.list()
                sent.path.startsWith("/data_sources/db") -> 200 to FakeNotion.database("db")
                sent.path == "/search" -> 200 to FakeNotion.list(
                    """{"object":"page","id":"top","parent":{"type":"workspace","workspace":true},
                        "properties":{"title":{"id":"title","type":"title","title":[]}}}""",
                    FakeNotion.page("row", "db", "A note"),
                )
                sent.path == "/pages/linked" ->
                    200 to """{"object":"page","id":"linked","parent":{"type":"page_id","page_id":"top"},"properties":{"title":{"id":"title","type":"title","title":[]}}}"""
                else -> 200 to "{}"
            }
        }
        val repo = repository()
        val items = repo.browse("")
        // Without a search, a database's notes are left out.
        assertEquals(listOf("top"), items.map { it.id })

        val id = repo.openNote(items.single().note!!)
        assertEquals("top", repo.note(id).first()?.id)
        assertEquals("linked", repo.openPage("linked"))
        assertEquals(NotesRepository.PAGES, repo.note("linked").first()?.databaseId)

        repo.togglePin("linked")
        repo.updateProperty("linked", NoteProperty("title", "title", PropertyValue.Text("Renamed")))
        assertEquals("Renamed", repo.note("linked").first()?.title)
    }

    /** Notion with one page whose content is [blocks]; appended blocks get IDs new-1, new-2, … */
    private fun notionWithContent(blocks: MutableList<String>, onAppend: (Sent) -> Pair<Int, String>? = { null }) {
        var next = 0
        notion.respond = { sent ->
            when {
                sent.path.startsWith("/data_sources/db/query") -> 200 to FakeNotion.list(FakeNotion.page("page-1", "db", "List"))
                sent.path.startsWith("/data_sources/db") -> 200 to FakeNotion.database("db")
                sent.path == "/pages/page-1" -> 200 to FakeNotion.page("page-1", "db", "List")
                sent.method == "GET" && sent.path.startsWith("/blocks/page-1/children") -> 200 to FakeNotion.list(*blocks.toTypedArray())
                sent.method == "PATCH" && sent.path == "/blocks/page-1/children" -> {
                    val texts = Regex("\"content\":\"([^\"]*)\"").findAll(sent.body).map { it.groupValues[1] }.toList()
                    val added = texts.map { FakeNotion.todo("new-${++next}", it) }
                    blocks += added
                    onAppend(sent) ?: (200 to FakeNotion.list(*added.toTypedArray()))
                }
                else -> 200 to "{}"
            }
        }
    }

    @Test
    fun `new to-dos are added once, also when the editor saves again without their IDs`() = runBlocking {
        val blocks = mutableListOf(FakeNotion.todo("t1", "Milk"))
        notionWithContent(blocks)
        val repo = repository()
        repo.refresh("db", userAsked = true)
        val loaded = checkNotNull(repo.fetchContent("page-1")).blocks
        val eggs = Block("eggs", type = BlockType.TODO, text = "Eggs")

        repo.saveContent("page-1", loaded + eggs)
        repo.flush()
        // The editor still has its own copy, without the ID Notion gave the new to-do.
        repo.saveContent("page-1", loaded + eggs.copy(checked = true))
        repo.flush()

        val appends = notion.requests.filter { it.method == "PATCH" && it.path == "/blocks/page-1/children" }
        assertEquals(1, appends.size)
        assertTrue(notion.requests.none { it.method == "DELETE" })
        assertTrue(notion.requests.any { it.method == "PATCH" && it.path == "/blocks/new-1" && it.body.contains("\"checked\":true") })
        assertEquals(0, repo.pendingCount.first())
    }

    @Test
    fun `a to-do Notion added without answering is not added again`() = runBlocking {
        val blocks = mutableListOf(FakeNotion.todo("t1", "Milk"))
        var fail = true
        // Notion adds the block, but the answer is a server error (once).
        notionWithContent(blocks) { if (fail) { fail = false; 502 to "{}" } else null }
        val repo = repository()
        repo.refresh("db", userAsked = true)
        val loaded = checkNotNull(repo.fetchContent("page-1")).blocks

        repo.saveContent("page-1", loaded + Block("eggs", type = BlockType.TODO, text = "Eggs"))
        repo.flush()
        assertEquals(1, repo.pendingCount.first())
        repo.flush()

        val appends = notion.requests.filter { it.method == "PATCH" && it.path == "/blocks/page-1/children" }
        assertEquals(1, appends.size)
        assertEquals(2, blocks.size)
        assertEquals(0, repo.pendingCount.first())
        assertEquals("new-1", repo.cachedContent("page-1")?.blocks?.last()?.notionId)
    }

    @Test
    fun `nested blocks are downloaded, and new ones go into their parent`() = runBlocking {
        val inside = mutableListOf(FakeNotion.todo("c1", "Inside"))
        var next = 0
        notion.respond = { sent ->
            when {
                sent.path.startsWith("/data_sources/db/query") -> 200 to FakeNotion.list(FakeNotion.page("page-1", "db", "List"))
                sent.path.startsWith("/data_sources/db") -> 200 to FakeNotion.database("db")
                sent.path == "/pages/page-1" -> 200 to FakeNotion.page("page-1", "db", "List")
                sent.method == "GET" && sent.path.startsWith("/blocks/page-1/children") -> 200 to FakeNotion.list(
                    """{"object":"block","id":"t","type":"toggle","has_children":true,"toggle":{"rich_text":[{"plain_text":"Toggle","text":{"content":"Toggle"}}]}}""",
                )
                sent.method == "GET" && sent.path.startsWith("/blocks/t/children") -> 200 to FakeNotion.list(*inside.toTypedArray())
                sent.method == "PATCH" && sent.path == "/blocks/t/children" -> {
                    val added = FakeNotion.todo("new-${++next}", "Added")
                    inside += added
                    200 to FakeNotion.list(added)
                }
                else -> 200 to "{}"
            }
        }
        val repo = repository()
        repo.refresh("db", userAsked = true)
        val loaded = checkNotNull(repo.fetchContent("page-1")).blocks
        assertEquals(listOf("t", "c1"), loaded.map { it.notionId })
        assertEquals(listOf(0, 1), loaded.map { it.depth })

        repo.saveContent("page-1", loaded + Block("x", type = BlockType.TODO, text = "Added", depth = 1))
        repo.flush()

        val append = notion.requests.single { it.method == "PATCH" && it.path == "/blocks/t/children" }
        assertTrue(append.body, append.body.contains("\"position\":{\"type\":\"after_block\",\"after_block\":{\"id\":\"c1\"}}"))
        assertEquals(0, repo.pendingCount.first())
    }

    @Test
    fun `a setup saved before data sources moves to its database's data source`() = runBlocking {
        notion.respond = { sent ->
            when {
                sent.path.startsWith("/data_sources/ds-1/query") -> 200 to FakeNotion.list(FakeNotion.page("page-1", "ds-1", "Note"))
                sent.path.startsWith("/data_sources/ds-1") -> 200 to FakeNotion.database("ds-1")
                // The old key is a database: Notion has no data source by that ID.
                sent.path.startsWith("/data_sources/db") -> 404 to """{"object":"error","status":404,"code":"object_not_found","message":"Not found"}"""
                sent.path == "/databases/db" -> 200 to """{"object":"database","id":"db","data_sources":[{"id":"ds-1","name":"Notes"},{"id":"ds-2","name":"Archive"}]}"""
                else -> 200 to "{}"
            }
        }
        val repo = repository()
        repo.updateSettings { it.copy(order = mapOf("db" to listOf("page-1"))) }

        repo.refresh("db", userAsked = true)

        val settings = repo.currentSettings()
        assertEquals(setOf("ds-1"), settings.setups.keys)
        assertEquals(listOf("page-1"), settings.order["ds-1"])
        assertEquals(listOf("page-1"), repo.databases.value["ds-1"]?.notes?.map { it.id })
        assertFalse(repo.databases.value.containsKey("db"))
        // Known now: no more lookups for it.
        assertEquals("ds-1", repo.resolveSource("db"))
        assertEquals(1, notion.requests.count { it.path == "/databases/db" })
    }

    @Test
    fun `a database is asked about first, also when Notion answers for its ID as a data source`() = runBlocking {
        notion.respond = { sent ->
            when {
                sent.path == "/databases/health" -> 200 to """{"object":"database","id":"health","data_sources":[{"id":"skin","name":"Skin"},{"id":"food","name":"Food"}]}"""
                // Notion answering for a data source's ID with its database.
                sent.path == "/databases/food" -> 200 to """{"object":"database","id":"health","data_sources":[{"id":"skin","name":"Skin"},{"id":"food","name":"Food"}]}"""
                sent.path.startsWith("/data_sources/") -> 200 to FakeNotion.database("health")
                else -> 200 to "{}"
            }
        }
        val repo = repository()
        assertEquals("skin", repo.resolveSource("health"))
        assertEquals("food", repo.resolveSource("food"))
        assertEquals(listOf("skin", "food"), repo.dataSourcesOf("health")?.map { it.id })
    }

    @Test
    fun `pages are made inside a page, and a refused top-level page is removed again`() = runBlocking {
        notion.respond = { sent ->
            when {
                sent.method == "POST" && sent.path == "/pages" && sent.body.contains("\"workspace\":true") ->
                    400 to """{"object":"error","status":400,"code":"validation_error","message":"Can't create pages at the workspace level"}"""
                sent.method == "POST" && sent.path == "/pages" ->
                    200 to """{"object":"page","id":"sub-1","parent":{"type":"page_id","page_id":"top"},"properties":{"title":{"id":"title","type":"title","title":[]}}}"""
                else -> 200 to "{}"
            }
        }
        val repo = repository()
        val events = Collections.synchronizedList(mutableListOf<RepoEvent>())
        val collector = scope.launch { repo.events.collect { events += it } }

        val sub = repo.createPage("top")
        repo.flush()
        val created = notion.requests.single { it.method == "POST" && it.path == "/pages" }
        assertTrue(created.body, created.body.contains("\"page_id\":\"top\""))
        assertEquals("sub-1", repo.resolve(sub))

        val topLevel = repo.createPage(null)
        repo.flush()
        assertEquals(null, repo.note(topLevel).first())
        assertEquals(0, repo.pendingCount.first())
        collector.cancel()
        assertTrue(events.toString(), events.any { it is RepoEvent.CreateFailed && it.topLevel })
    }

    @Test
    fun `what is in columns is downloaded and new lines go into their column`() = runBlocking {
        val column = mutableListOf(FakeNotion.todo("c1", "In column"))
        notion.respond = { sent ->
            when {
                sent.path.startsWith("/data_sources/db/query") -> 200 to FakeNotion.list(FakeNotion.page("page-1", "db", "List"))
                sent.path.startsWith("/data_sources/db") -> 200 to FakeNotion.database("db")
                sent.path == "/pages/page-1" -> 200 to FakeNotion.page("page-1", "db", "List")
                sent.method == "GET" && sent.path.startsWith("/blocks/page-1/children") ->
                    200 to FakeNotion.list("""{"object":"block","id":"list","type":"column_list","has_children":true,"column_list":{}}""")
                sent.method == "GET" && sent.path.startsWith("/blocks/list/children") ->
                    200 to FakeNotion.list("""{"object":"block","id":"col","type":"column","has_children":true,"column":{}}""")
                sent.method == "GET" && sent.path.startsWith("/blocks/col/children") -> 200 to FakeNotion.list(*column.toTypedArray())
                sent.method == "PATCH" && sent.path == "/blocks/col/children" -> 200 to FakeNotion.list(FakeNotion.todo("new-1", "Added"))
                else -> 200 to "{}"
            }
        }
        val repo = repository()
        repo.refresh("db", userAsked = true)
        val loaded = checkNotNull(repo.fetchContent("page-1")).blocks
        assertEquals(listOf("list", "col", "c1"), loaded.map { it.notionId })
        assertEquals(listOf(0, 1, 2), loaded.map { it.depth })

        repo.saveContent("page-1", loaded + Block("x", type = BlockType.TODO, text = "Added", depth = 2))
        repo.flush()

        assertTrue(notion.requests.any { it.method == "PATCH" && it.path == "/blocks/col/children" })
        assertEquals(0, repo.pendingCount.first())
    }

    @Test
    fun `every database with a copy here is read at start, and a pinned page without one is downloaded`() = runBlocking {
        notion.respond = { sent ->
            when {
                sent.method == "GET" && sent.path == "/pages/p-1" ->
                    200 to """{"object":"page","id":"p-1","parent":{"type":"workspace","workspace":true},"properties":{"title":{"id":"title","type":"title","title":[{"plain_text":"Journal"}]}}}"""
                else -> 200 to "{}"
            }
        }
        val cache = CacheStore(folder.newFolder("cache"))
        cache.saveDatabase("ds-x", DatabaseCache(notes = listOf(NoteSummary("n-1", "ds-x", "Kept"))))
        val settings = newSettings()
        settings.update { it.copy(pinned = listOf("n-1", "p-1")) }

        val repo = repository(cache, settings)

        withTimeout(5_000) { repo.databases.first { db -> db[NotesRepository.PAGES]?.notes?.any { it.id == "p-1" } == true } }
        assertEquals(listOf("n-1"), repo.databases.value["ds-x"]?.notes?.map { it.id })
        assertEquals(listOf("ds-x"), repo.pinnedSources())
        // The note with a copy isn't downloaded again.
        assertEquals(listOf("/pages/p-1"), notion.requests.filter { it.path.startsWith("/pages/") }.map { it.path })
    }

    @Test
    fun `a new subpage of a page's notebook from before is still made inside the page`() = runBlocking {
        notion.respond = { sent ->
            when {
                sent.method == "POST" && sent.path == "/pages" ->
                    200 to """{"object":"page","id":"s-1","parent":{"type":"page_id","page_id":"parent"},"properties":{"title":{"id":"title","type":"title","title":[]}}}"""
                else -> 200 to "{}"
            }
        }
        val cache = CacheStore(folder.newFolder("cache"))
        cache.saveDatabase("page:parent", DatabaseCache(notes = listOf(NoteSummary("local-1", "page:parent", ""))))
        cache.saveOutbox(listOf(PendingOp.CreatePage("local-1", "page:parent", "parent")))

        val repo = repository(cache)
        withTimeout(5_000) { repo.databases.first { it["page:parent"]?.notes?.isNotEmpty() == true } }
        repo.flush()

        val create = notion.requests.single { it.method == "POST" && it.path == "/pages" }
        assertTrue(create.body, create.body.contains("\"page_id\":\"parent\""))
        assertEquals("s-1", repo.resolve("local-1"))
        // Its copy stays as it is: nothing to ask Notion about it.
        repo.refresh("page:parent", userAsked = true)
        assertFalse(notion.requests.any { it.path.contains("parent/children") })
    }

    @Test
    fun `Home's databases and pages are kept on this phone`() = runBlocking {
        notion.respond = { sent ->
            when {
                sent.method == "POST" && sent.path == "/search" -> 200 to FakeNotion.list(
                    """{"object":"data_source","id":"ds-1","title":[{"plain_text":"Tasks"}],"parent":{"type":"database_id","database_id":"db-1"},"last_edited_time":"2026-10-01T10:00:00.000Z"}""",
                    """{"object":"page","id":"p-1","parent":{"type":"workspace","workspace":true},"properties":{"title":{"id":"title","type":"title","title":[{"plain_text":"Journal"}]}}}""",
                    FakeNotion.page("row-1", "ds-1", "A note"),
                )
                sent.path == "/databases/db-1" -> 200 to """{"object":"database","id":"db-1","title":[{"plain_text":"Tasks"}],"data_sources":[{"id":"ds-1","name":"Tasks"}]}"""
                else -> 200 to "{}"
            }
        }
        val cache = CacheStore(folder.newFolder("cache"))
        val repo = repository(cache)
        repo.refreshRecent()
        // A database's notes aren't listed, only databases and pages.
        assertEquals(listOf("ds-1", "p-1"), repo.recent.value?.map { it.id })
        assertEquals("Tasks", repo.knownItem("ds1")?.title)

        notion.respond = { 503 to "{}" }
        val again = repository(cache, newSettings())
        withTimeout(5_000) { again.recent.first { it != null } }
        assertEquals(listOf("ds-1", "p-1"), again.recent.value?.map { it.id })
    }

    @Test
    fun `a database's setup is kept at once, and databases are pinned and unpinned`() = runBlocking {
        val repo = repository()
        val schema = DatabaseSchema("ds-9", "Tasks", listOf(PropertySchema("st", "Status", "status")))

        repo.updateSetup("ds-9") { it.copy(cards = CardLayout(cover = true)) }
        assertEquals(DatabaseSetup(cards = CardLayout(cover = true)), repo.currentSettings().setups["ds-9"])
        // Written without dashes: the same data source.
        repo.updateSetup("ds9") { it.copy(cards = CardLayout(content = false)) }
        assertEquals(DatabaseSetup(cards = CardLayout(content = false)), repo.currentSettings().setups["ds-9"])
        assertEquals(setOf("db", "ds-9"), repo.currentSettings().setups.keys)

        repo.togglePinDatabase(PinnedDatabase("ds-9", "Tasks"))
        repo.togglePinDatabase(PinnedDatabase("db-2", "Books"))
        assertEquals(listOf("db-2", "ds-9"), repo.currentSettings().pinnedDatabases.map { it.id })
        repo.renamePinnedDatabase("ds9", "Todo", PageIcon(emoji = "✅"))
        assertEquals(PinnedDatabase("ds-9", "Todo", PageIcon(emoji = "✅")), repo.currentSettings().pinnedDatabases.last())
        repo.togglePinDatabase(PinnedDatabase("ds9", "Todo"))
        assertEquals(listOf("db-2"), repo.currentSettings().pinnedDatabases.map { it.id })
    }

    @Test
    fun `links to Notion use the database a data source is in`() = runBlocking {
        notion.respond = { sent ->
            when (sent.path) {
                "/data_sources/ds-1" -> 200 to """{"object":"data_source","id":"ds-1","title":[],"parent":{"type":"database_id","database_id":"db-1"},"properties":{}}"""
                else -> 404 to """{"object":"error","status":404,"code":"object_not_found","message":"Not found"}"""
            }
        }
        val repo = repository()
        assertEquals("db-1", repo.databaseOf("ds-1"))
        // Notion can't tell: no link to a database, the screen falls back to the data source.
        assertEquals(null, repo.databaseOf("ds-2"))
    }

    @Test
    fun `a refresh stopped halfway doesn't block the next one`() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        notion.respond = { sent ->
            when {
                sent.path.startsWith("/data_sources/db/query") -> {
                    started.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    200 to FakeNotion.list(FakeNotion.page("page-1", "db", "Hello"))
                }
                sent.path.startsWith("/data_sources/db") -> 200 to FakeNotion.database("db")
                else -> 200 to "{}"
            }
        }
        val repo = repository()
        // The screen that asked for the refresh closes while Notion is still answering.
        val job = scope.launch { repo.refresh("db", userAsked = true) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        job.cancel()
        job.join()
        release.countDown()
        assertFalse(repo.databases.value["db"]?.refreshing == true)

        repo.refresh("db", userAsked = true)
        assertEquals(listOf("page-1"), repo.databases.value["db"]?.notes?.map { it.id })
    }

    @Test
    fun `pull-to-refresh asks Notion again for people and linked pages kept here`() = runBlocking {
        val people = mutableListOf("""{"object":"user","id":"u1","type":"person","name":"Ann"}""")
        notion.respond = { sent ->
            when {
                sent.path.startsWith("/users") -> 200 to FakeNotion.list(*people.toTypedArray())
                sent.path.startsWith("/data_sources/rel/query") -> 200 to FakeNotion.list(FakeNotion.page("r1", "rel", "One"))
                else -> 200 to "{}"
            }
        }
        val repo = repository()
        assertEquals(listOf("Ann"), repo.users().map { it.name })
        repo.relatedPages("rel")
        // Someone new in Notion: kept for a day, so not seen yet.
        people += """{"object":"user","id":"u2","type":"person","name":"Bob"}"""
        assertEquals(listOf("Ann"), repo.users().map { it.name })
        val pageQueries = notion.requests.count { it.path.startsWith("/data_sources/rel/query") }
        repo.relatedPages("rel")
        assertEquals(pageQueries, notion.requests.count { it.path.startsWith("/data_sources/rel/query") })

        repo.refreshLookups()

        assertEquals(listOf("Ann", "Bob"), repo.users().map { it.name })
        repo.relatedPages("rel")
        assertEquals(pageQueries + 1, notion.requests.count { it.path.startsWith("/data_sources/rel/query") })
    }

    @Test
    fun `a database's Notion views are read, and a view's notes asked with its filter and sorts`() = runBlocking {
        notion.respond = { sent ->
            when {
                sent.path.startsWith("/views?database_id=dbase") ->
                    200 to FakeNotion.list("""{"object":"view","id":"v3"}""", """{"object":"view","id":"v1"}""", """{"object":"view","id":"v2"}""")
                sent.path == "/views/v3" -> 200 to """{"object":"view","id":"v3","name":"Other","type":"table","data_source_id":"other"}"""
                sent.path == "/data_sources/db" ->
                    200 to """{"object":"data_source","id":"db","parent":{"type":"database_id","database_id":"dbase"},"title":[],"properties":{"Name":{"id":"title","name":"Name","type":"title","title":{}}}}"""
                sent.path == "/views/v1" ->
                    200 to """{"object":"view","id":"v1","name":"Open","type":"table","data_source_id":"db",
                              "filter":{"property":"Done","checkbox":{"equals":false}},"sorts":[{"timestamp":"created_time","direction":"ascending"}]}"""
                sent.path == "/views/v2" -> 404 to """{"object":"error","status":404,"code":"object_not_found","message":"Not found"}"""
                sent.path.startsWith("/data_sources/db/query") -> 200 to FakeNotion.list(FakeNotion.page("p2", "db"), FakeNotion.page("p1", "db"))
                else -> 200 to "{}"
            }
        }
        val repo = repository()

        repo.openViews("db")
        // Asked by the database the data source is in; a view Notion won't show is left out.
        val views = repo.views.value["db"]!!.views
        assertEquals(listOf("v3", "v1"), views.map { it.id })
        assertEquals(listOf("v1"), NotionViews.listed(views, "db").map { it.id })

        repo.refreshViewResults("db", "v1")
        assertEquals(listOf("p2", "p1"), repo.views.value["db"]!!.results["v1"]!!.ids)
        val query = notion.requests.last { it.path.startsWith("/data_sources/db/query") }
        assertTrue(query.path, query.path.contains("filter_properties=title"))
        assertTrue(query.body, query.body.contains(""""filter":{"property":"Done","checkbox":{"equals":false}}"""))
        assertTrue(query.body, query.body.contains(""""sorts":[{"timestamp":"created_time","direction":"ascending"}]"""))

        // Asked again within a minute: the answer from before is used.
        val asked = notion.requests.size
        repo.refreshViewResults("db", "v1")
        repo.openViews("db")
        assertEquals(asked, notion.requests.size)
    }

    @Test
    fun `a view's notes with a quick filter on are asked with it and kept apart`() = runBlocking {
        notion.respond = { sent ->
            when {
                sent.path.startsWith("/views?database_id=dbase") -> 200 to FakeNotion.list("""{"object":"view","id":"v1"}""")
                sent.path == "/data_sources/db" ->
                    200 to """{"object":"data_source","id":"db","parent":{"type":"database_id","database_id":"dbase"},"title":[],"properties":{"Name":{"id":"title","name":"Name","type":"title","title":{}}}}"""
                sent.path == "/views/v1" ->
                    200 to """{"object":"view","id":"v1","name":"Open","type":"table","data_source_id":"db",
                              "filter":{"property":"Done","checkbox":{"equals":false}},"quick_filters":{"Status":{"status":{"equals":"Doing"}}}}"""
                sent.path.startsWith("/data_sources/db/query") ->
                    if (sent.body.contains("Doing")) 200 to FakeNotion.list(FakeNotion.page("p2", "db")) else 200 to FakeNotion.list(FakeNotion.page("p2", "db"), FakeNotion.page("p1", "db"))
                else -> 200 to "{}"
            }
        }
        val repo = repository()
        repo.openViews("db")
        val doing = mapOf("Status" to (Json.parseToJsonElement("""{"status":{"equals":"Doing"}}""") as JsonObject))

        repo.refreshViewResults("db", "v1")
        assertFalse(notion.requests.last { it.path.startsWith("/data_sources/db/query") }.body.contains("Doing"))
        repo.refreshViewResults("db", "v1", quick = doing)

        val query = notion.requests.last { it.path.startsWith("/data_sources/db/query") }
        assertTrue(query.body, query.body.contains(""""filter":{"and":[{"property":"Done","checkbox":{"equals":false}},{"property":"Status","status":{"equals":"Doing"}}]}"""))
        val results = repo.views.value["db"]!!.results
        assertEquals(listOf("p2", "p1"), results["v1"]!!.ids)
        assertEquals(listOf("p2"), results[NotionViews.resultKey("v1", doing)]!!.ids)

        // Turned on again within a minute: the copy kept for it is used.
        val asked = notion.requests.size
        repo.refreshViewResults("db", "v1", quick = doing)
        assertEquals(asked, notion.requests.size)
    }

    @Test
    fun `the view shown is kept per data source`() = runBlocking {
        val settings = newSettings()
        val repo = repository(settings = settings)
        repo.chooseView("ds-1", "v2")
        // Written without dashes: the same data source.
        repo.chooseView("ds1", "v1")
        val now = settings.current()
        assertEquals(mapOf("ds-1" to "v1"), now.views)
    }

    @Test
    fun `asking Notion about views says so, and what went wrong`() = runBlocking {
        notion.respond = { sent ->
            when {
                sent.path == "/data_sources/db" ->
                    200 to """{"object":"data_source","id":"db","parent":{"type":"database_id","database_id":"dbase"},"title":[],"properties":{}}"""
                sent.path.startsWith("/views?") -> 400 to """{"object":"error","status":400,"code":"validation_error","message":"Not allowed"}"""
                else -> 200 to "{}"
            }
        }
        val repo = repository()
        repo.openViews("db")
        val status = repo.viewStatus.value["db"]
        assertFalse(status!!.asking)
        assertTrue(status.error != null)
        assertTrue(repo.views.value["db"] == null)
    }

    @Test
    fun `a change reaching Notion is noted, so the view shown is asked again`() = runBlocking {
        notion.respond = { sent ->
            when {
                sent.path.startsWith("/data_sources/db/query") -> 200 to FakeNotion.list()
                sent.path.startsWith("/data_sources/db") -> 200 to FakeNotion.database("db")
                sent.method == "POST" && sent.path == "/pages" -> 200 to FakeNotion.page("page-1", "db")
                else -> 200 to "{}"
            }
        }
        val repo = repository()
        assertEquals(0L, repo.lastSentMs.value)
        repo.createNote("db")
        repo.flush()
        assertTrue(repo.lastSentMs.value > 0)
    }

    // ---- Page templates ----

    private val templatesJson =
        """{"templates":[{"id":"t-trip","name":"Trip","is_default":false},{"id":"t-weekly","name":"Weekly","is_default":true}],"has_more":false}"""

    /** A data source with templates; [templateBlocks] is what Notion has in a new page once the template is applied. */
    private fun notionWithTemplates(templateBlocks: MutableList<String> = mutableListOf(), applied: () -> Boolean = { true }) {
        var next = 0
        notion.respond = { sent ->
            when {
                sent.method == "GET" && sent.path.startsWith("/data_sources/db/templates") -> 200 to templatesJson
                sent.path.startsWith("/data_sources/db/query") -> 200 to FakeNotion.list()
                sent.path.startsWith("/data_sources/db") -> 200 to FakeNotion.database("db")
                sent.method == "POST" && sent.path == "/pages" -> 200 to FakeNotion.page("page-1", "db")
                sent.method == "GET" && sent.path == "/pages/page-1" -> 200 to FakeNotion.page("page-1", "db", "From template")
                sent.method == "GET" && sent.path.startsWith("/blocks/page-1/children") ->
                    200 to FakeNotion.list(*(if (applied()) templateBlocks.toTypedArray() else emptyArray()))
                sent.method == "PATCH" && sent.path == "/blocks/page-1/children" -> {
                    val texts = Regex("\"content\":\"([^\"]*)\"").findAll(sent.body).map { it.groupValues[1] }.toList()
                    val added = texts.map { FakeNotion.todo("new-${++next}", it) }
                    templateBlocks += added
                    200 to FakeNotion.list(*added.toTypedArray())
                }
                else -> 200 to "{}"
            }
        }
    }

    private fun createBody() = notion.requests.single { it.method == "POST" && it.path == "/pages" }.body

    @Test
    fun `a new note gets the default template, with the phone's time zone and no content of its own`() = runBlocking {
        notionWithTemplates()
        val repo = repository()
        repo.createNote("db")
        repo.flush()

        assertEquals(listOf("t-weekly", "t-trip"), repo.databases.value["db"]?.schema?.templates?.map { it.id })
        val body = createBody()
        assertTrue(body, body.contains("\"template\":{\"type\":\"default\",\"timezone\":\"Europe/Brussels\"}"))
        assertFalse(body, body.contains("children"))
        // The empty title would replace the template's own.
        assertFalse(body, body.contains("\"title\""))
    }

    @Test
    fun `a chosen template is sent by its ID, and an empty note gets none`() = runBlocking {
        notionWithTemplates()
        val repo = repository()
        repo.createNote("db", template = TemplatePick.Specific("t-trip"))
        repo.flush()
        assertTrue(createBody(), createBody().contains("\"template\":{\"type\":\"template_id\",\"template_id\":\"t-trip\",\"timezone\":\"Europe/Brussels\"}"))

        notion.requests.clear()
        repo.createNote("db", template = TemplatePick.Empty)
        repo.flush()
        assertFalse(createBody(), createBody().contains("template"))
        assertTrue(createBody(), createBody().contains("\"title\""))
    }

    @Test
    fun `a database without templates makes plain notes`() = runBlocking {
        notion.respond = { sent ->
            when {
                sent.path.startsWith("/data_sources/db/templates") -> 200 to """{"templates":[],"has_more":false}"""
                sent.path.startsWith("/data_sources/db/query") -> 200 to FakeNotion.list()
                sent.path.startsWith("/data_sources/db") -> 200 to FakeNotion.database("db")
                sent.method == "POST" && sent.path == "/pages" -> 200 to FakeNotion.page("page-1", "db")
                else -> 200 to "{}"
            }
        }
        val repo = repository()
        repo.createNote("db")
        repo.flush()
        assertFalse(createBody(), createBody().contains("template"))
    }

    @Test
    fun `notes shared to the app never use a template`() = runBlocking {
        notionWithTemplates()
        val repo = repository()
        repo.saveShared(SharedContent("Idea", "Some text", null), "db", null)
        val body = createBody()
        assertFalse(body, body.contains("template"))
        assertTrue(body, body.contains("children"))
    }

    @Test
    fun `when Notion refuses the template the note is made empty`() = runBlocking {
        var refused = false
        notionWithTemplates()
        val inner = notion.respond
        notion.respond = { sent ->
            if (sent.method == "POST" && sent.path == "/pages" && sent.body.contains("\"template\"")) {
                refused = true
                400 to """{"object":"error","status":400,"code":"validation_error","message":"no default template"}"""
            } else {
                inner(sent)
            }
        }
        val repo = repository()
        val localId = checkNotNull(repo.createNote("db"))
        repo.flush()
        assertTrue(refused)
        assertEquals("page-1", repo.resolve(localId))
        assertEquals(2, notion.requests.count { it.method == "POST" && it.path == "/pages" })
    }

    @Test
    fun `the template's content and values are downloaded after Notion applied it`() = runBlocking {
        var applied = false
        val blocks = mutableListOf(FakeNotion.todo("tb1", "Agenda"))
        notionWithTemplates(blocks) { applied }
        val repo = repository()
        val localId = checkNotNull(repo.createNote("db"))
        val replaced = scope.launch { repo.contentReplaced.first { repo.resolve(it) == "page-1" } }
        repo.flush()
        assertEquals(emptyList<Block>(), repo.cachedContent(localId)?.blocks.orEmpty())
        // Notion is still busy with the template at the first look.
        applied = true
        withTimeout(5_000) { replaced.join() }

        assertEquals(listOf("Agenda"), repo.cachedContent("page-1")?.blocks?.map { it.text })
        assertEquals("From template", repo.note(localId).first()?.title)
        // Nothing was written into the note from here.
        assertTrue(notion.requests.none { it.method == "PATCH" })
    }

    @Test
    fun `lines typed before the template arrived are added after its content, once`() = runBlocking {
        var applied = false
        val blocks = mutableListOf(FakeNotion.todo("tb1", "Agenda"))
        notionWithTemplates(blocks) { applied }
        val repo = repository()
        val localId = checkNotNull(repo.createNote("db"))
        repo.saveContent(localId, listOf(Block("typed", type = BlockType.TODO, text = "Call Sam")))
        val replaced = scope.launch { repo.contentReplaced.first { repo.resolve(it) == "page-1" } }
        repo.flush()
        // Held back: nothing of the typed line reaches the page before the template.
        assertTrue(notion.requests.none { it.method == "PATCH" })
        applied = true
        withTimeout(5_000) { replaced.join() }
        // The held-back lines are queued once the template is in (after the event), so wait for them to be sent.
        withTimeout(5_000) {
            while (notion.requests.none { it.method == "PATCH" } || repo.pendingCount.first() > 0) kotlinx.coroutines.delay(20)
        }

        assertEquals(listOf("Agenda", "Call Sam"), repo.cachedContent("page-1")?.blocks?.map { it.text })
        val appends = notion.requests.filter { it.method == "PATCH" && it.path == "/blocks/page-1/children" }
        assertEquals(1, appends.size)
        assertTrue(appends.single().body, appends.single().body.contains("after_block"))
        assertTrue(appends.single().body, appends.single().body.contains("tb1"))
        assertEquals(2, blocks.size)
    }

    @Test
    fun `typed lines still go in when the template never arrives`() = runBlocking {
        notionWithTemplates(mutableListOf()) { false }
        val repo = repository()
        val localId = checkNotNull(repo.createNote("db"))
        repo.saveContent(localId, listOf(Block("typed", type = BlockType.TODO, text = "Call Sam")))
        repo.flush()
        withTimeout(5_000) { while (repo.pendingCount.first() > 0 || notion.requests.none { it.method == "PATCH" }) kotlinx.coroutines.delay(20) }

        assertEquals(1, notion.requests.count { it.method == "PATCH" && it.path == "/blocks/page-1/children" })
        assertEquals(listOf("Call Sam"), repo.cachedContent("page-1")?.blocks?.map { it.text })
    }

    private val movedPage = """{"object":"page","id":"page-1","parent":{"type":"page_id","page_id":"target"},
        "properties":{"title":{"id":"title","type":"title","title":[{"type":"text","plain_text":"List","text":{"content":"List"},"annotations":{}}]}}}"""

    @Test
    fun `a note moved to a page is sent at once and is a page on its own afterwards`() = runBlocking {
        notion.respond = { sent ->
            when {
                sent.path.startsWith("/data_sources/db/query") -> 200 to FakeNotion.list(FakeNotion.page("page-1", "db", "List"))
                sent.path.startsWith("/data_sources/db") -> 200 to FakeNotion.database("db")
                sent.method == "POST" && sent.path == "/pages/page-1/move" -> 200 to "{}"
                sent.path == "/pages/page-1" -> 200 to movedPage
                else -> 200 to "{}"
            }
        }
        val repo = repository()
        repo.refresh("db", userAsked = true)

        repo.moveNote("page-1", MoveDestination("target", "Target", null, MoveDestination.Kind.PAGE))

        val move = notion.requests.single { it.path == "/pages/page-1/move" }
        assertEquals("""{"parent":{"type":"page_id","page_id":"target"}}""", move.body)
        assertEquals(NotesRepository.PAGES, repo.note("page-1").first()?.databaseId)
        assertEquals(emptyList<String>(), repo.databases.value["db"]?.notes?.map { it.id })
    }

    @Test
    fun `a note moved into a database is a note of its data source, and Home's list no longer shows it`() = runBlocking {
        notion.respond = { sent ->
            when {
                sent.path.startsWith("/data_sources/db/query") -> 200 to FakeNotion.list()
                sent.path.startsWith("/data_sources/other/query") -> 200 to FakeNotion.list()
                sent.path.startsWith("/data_sources/other") -> 200 to FakeNotion.database("other")
                sent.method == "POST" && sent.path == "/search" -> 200 to FakeNotion.list(
                    """{"object":"page","id":"page-1","parent":{"type":"workspace","workspace":true},"properties":{"title":{"id":"title","type":"title","title":[{"plain_text":"List"}]}}}""",
                )
                sent.method == "POST" && sent.path == "/pages/page-1/move" -> 200 to "{}"
                sent.path == "/pages/page-1" -> 200 to FakeNotion.page("page-1", "other", "List")
                else -> 200 to "{}"
            }
        }
        val repo = repository()
        repo.refreshRecent()
        repo.openPage("page-1")
        assertEquals(listOf("page-1"), repo.recent.value?.map { it.id })

        repo.moveNote("page-1", MoveDestination("other", "Other", null, MoveDestination.Kind.DATA_SOURCE))

        assertEquals("""{"parent":{"type":"data_source_id","data_source_id":"other"}}""", notion.requests.single { it.path == "/pages/page-1/move" }.body)
        assertEquals("other", repo.note("page-1").first()?.databaseId)
        assertEquals(emptyList<String>(), repo.recent.value?.map { it.id })
    }

    @Test
    fun `a move Notion refuses leaves the note where it was`() = runBlocking {
        notion.respond = { sent ->
            when {
                sent.path.startsWith("/data_sources/db/query") -> 200 to FakeNotion.list(FakeNotion.page("page-1", "db", "List"))
                sent.path.startsWith("/data_sources/db") -> 200 to FakeNotion.database("db")
                sent.path == "/pages/page-1/move" -> 400 to """{"object":"error","status":400,"code":"validation_error","message":"Can't move"}"""
                else -> 200 to "{}"
            }
        }
        val repo = repository()
        repo.refresh("db", userAsked = true)

        val error = runCatching { repo.moveNote("page-1", MoveDestination("target", "Target", null, MoveDestination.Kind.PAGE)) }.exceptionOrNull()

        assertTrue(error.toString(), error is NotionException)
        assertEquals("db", repo.note("page-1").first()?.databaseId)
    }

    @Test
    fun `a database moved to a page or the top is sent as its new parent, and Home's tree follows`() = runBlocking {
        notion.respond = { sent ->
            when {
                sent.method == "POST" && sent.path == "/search" -> 200 to FakeNotion.list(
                    """{"object":"data_source","id":"ds-1","title":[{"plain_text":"Tasks"}],"parent":{"type":"database_id","database_id":"db-1"}}""",
                )
                sent.path == "/databases/db-1" && sent.method == "GET" ->
                    200 to """{"object":"database","id":"db-1","title":[{"plain_text":"Tasks"}],"parent":{"type":"workspace","workspace":true},"data_sources":[{"id":"ds-1","name":"Tasks"}]}"""
                else -> 200 to "{}"
            }
        }
        val repo = repository()
        repo.refreshRecent()

        repo.moveDatabase("db-1", "ds-1", MoveDestination("p-1", "Journal", null, MoveDestination.Kind.PAGE))
        assertEquals("""{"parent":{"type":"page_id","page_id":"p-1"}}""", notion.requests.last { it.method == "PATCH" }.body)
        assertEquals("p-1", repo.recent.value?.single()?.parentId)

        repo.moveDatabase("db-1", "ds-1", MoveDestination("", "", null, MoveDestination.Kind.WORKSPACE_TOP))
        val patch = notion.requests.last { it.method == "PATCH" }
        assertEquals("/databases/db-1", patch.path)
        assertEquals("""{"parent":{"type":"workspace","workspace":true}}""", patch.body)
        assertEquals(null, repo.recent.value?.single()?.parentId)
    }
}
