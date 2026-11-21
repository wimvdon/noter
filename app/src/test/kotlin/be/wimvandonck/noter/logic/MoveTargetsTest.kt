package be.wimvandonck.noter.logic

import be.wimvandonck.noter.data.model.PageIcon
import be.wimvandonck.noter.data.model.PageRef
import be.wimvandonck.noter.data.model.PinnedDatabase
import be.wimvandonck.noter.data.model.WorkspaceItem
import be.wimvandonck.noter.data.notion.TokenKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveTargetsTest {
    private val work = Workspace("w1", "Work", TokenKind.PERSONAL)
    private val club = Workspace("w2", "Club", TokenKind.CONNECTION)

    private fun page(id: String, title: String, parent: String? = null) = WorkspaceItem(id, isDatabase = false, title = title, parentId = parent)
    private fun database(id: String, title: String, parent: String? = null) = WorkspaceItem(id, isDatabase = true, title = title, parentId = parent)

    private val recent = listOf(
        page("p-journal", "Journal"),
        page("p-ideas", "Ideas", parent = "p-journal"),
        page("p-deep", "Deep idea", parent = "p-ideas"),
        database("ds-tasks", "Tasks"),
        database("ds-books", "Books", parent = "p-journal"),
    )

    private fun options(subject: MoveSubject, query: String = "", workspaces: List<Workspace> = listOf(work), owners: Map<String, String> = emptyMap(), list: List<WorkspaceItem>? = recent) =
        MoveTargets.options(subject, list, emptyList(), emptyMap(), owners, workspaces, query)

    @Test
    fun `a note can go to another database's data source or to a page, not where it is`() {
        val note = MoveSubject(listOf("row-1"), isDatabase = false, parents = listOf("ds-tasks"))

        val result = options(note)

        assertEquals(listOf("ds-books", "p-journal", "p-ideas", "p-deep"), result.destinations.map { it.id })
        assertEquals(MoveDestination.Kind.DATA_SOURCE, result.destinations.first().kind)
        assertFalse(result.top)
    }

    @Test
    fun `a page is not offered itself, its parent or what is inside it`() {
        val page = MoveSubject(listOf("p-ideas"), isDatabase = false, parents = listOf("p-journal"))

        val ids = options(page).destinations.map { it.id }

        assertEquals(listOf("ds-tasks", "ds-books"), ids)
        // Not into a database that is inside it either.
        val journal = MoveSubject(listOf("p-journal"), isDatabase = false)
        assertEquals(listOf("ds-tasks"), options(journal).destinations.map { it.id })
    }

    @Test
    fun `a database can go into a page or to the top of the workspace, not into a database`() {
        val db = MoveSubject(listOf("db-tasks", "ds-tasks"), isDatabase = true)

        val result = options(db)

        assertEquals(listOf("p-journal", "p-ideas", "p-deep"), result.destinations.map { it.id })
        assertTrue(result.top)
        // Not where it is now.
        val inJournal = MoveSubject(listOf("db-books", "ds-books"), isDatabase = true, parents = listOf("p-journal"))
        assertEquals(listOf("p-ideas", "p-deep"), options(inJournal).destinations.map { it.id })
    }

    @Test
    fun `the top of the workspace is not offered with a connection's token or while searching`() {
        val db = MoveSubject(listOf("ds-tasks"), isDatabase = true)

        assertFalse(options(db, workspaces = listOf(club)).top)
        assertFalse(options(db, query = "jour").top)
    }

    @Test
    fun `with several workspaces only the item's own is offered, and none when it isn't known`() {
        val owners = mapOf("p-journal" to "w1", "p-ideas" to "w2", "ds-tasks" to "w1", "ds-books" to "w2", "row-1" to "w1").mapKeys { KeyRouter.normalize(it.key) }
        val note = MoveSubject(listOf("row-1"), isDatabase = false)

        val result = options(note, workspaces = listOf(work, club), owners = owners)

        assertEquals(listOf("ds-tasks", "p-journal"), result.destinations.map { it.id })
        assertEquals(work, result.workspace)

        val unknown = MoveSubject(listOf("row-9"), isDatabase = false)
        assertTrue(options(unknown, workspaces = listOf(work, club), owners = owners).destinations.isEmpty())
    }

    @Test
    fun `a pinned database of several data sources offers each of them`() {
        val pins = listOf(PinnedDatabase("db-health", "Health", PageIcon(emoji = "🩺")))
        val infos = mapOf("db-health" to be.wimvandonck.noter.data.model.DatabaseInfo("db-health", "Health", sources = listOf(PageRef("ds-skin", "Skin"), PageRef("ds-food", "Food"))))

        val result = MoveTargets.options(MoveSubject(listOf("row-1"), false), emptyList(), pins, infos, emptyMap(), listOf(work))

        assertEquals(listOf("Health · Skin", "Health · Food"), result.destinations.map { it.name })
        assertEquals("🩺", result.destinations.first().icon?.emoji)
    }

    @Test
    fun `typing narrows the places by every word, ignoring case and accents, the best match first`() {
        val items = listOf(page("a", "Ideas for the café"), page("b", "Café"), page("c", "Cafetaria menu"), page("d", "Recipes"))

        val found = options(MoveSubject(listOf("x"), false), "CAFE", list = items).destinations.map { it.id }

        assertEquals(listOf("b", "c", "a"), found)
        assertTrue(options(MoveSubject(listOf("x"), false), "zzz", list = items).destinations.isEmpty())
    }

    @Test
    fun `before Home's list is known there are no pages to offer`() {
        assertTrue(options(MoveSubject(listOf("x"), false), list = null).destinations.isEmpty())
    }

    @Test
    fun `the new parent goes to Notion as a page, a data source or the workspace`() {
        assertEquals(mapOf("type" to "page_id", "page_id" to "p1"), MoveDestination("p1", "A", null, MoveDestination.Kind.PAGE).parentBody())
        assertEquals(mapOf("type" to "data_source_id", "data_source_id" to "ds1"), MoveDestination("ds1", "B", null, MoveDestination.Kind.DATA_SOURCE).parentBody())
        assertEquals(mapOf("type" to "workspace", "workspace" to true), MoveDestination("", "", null, MoveDestination.Kind.WORKSPACE_TOP).parentBody())
    }

    @Test
    fun `Home's list follows a move`() {
        val toPage = MoveDestination("p-ideas", "Ideas", null, MoveDestination.Kind.PAGE)
        assertEquals("p-ideas", MoveTargets.afterMove(recent, setOf("ds-tasks"), toPage).first { it.id == "ds-tasks" }.parentId)

        val top = MoveDestination("", "", null, MoveDestination.Kind.WORKSPACE_TOP)
        assertEquals(null, MoveTargets.afterMove(recent, setOf("ds-books"), top).first { it.id == "ds-books" }.parentId)

        val intoDatabase = MoveDestination("ds-tasks", "Tasks", null, MoveDestination.Kind.DATA_SOURCE)
        assertEquals(listOf("p-journal", "p-deep", "ds-tasks", "ds-books"), MoveTargets.afterMove(recent, setOf("p-ideas"), intoDatabase).map { it.id })
    }
}
