package be.wimvandonck.noter.logic

import be.wimvandonck.noter.data.model.NotionView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewEditsTest {
    private val table = NotionView("v-1", "All", "table", "ds")
    private val list = NotionView("v2", "Open", "list", "ds")

    @Test
    fun `a new view names its database, data source, name and type, and nothing else`() {
        assertEquals(
            mapOf("database_id" to "db", "data_source_id" to "ds", "name" to "Reading", "type" to "list"),
            ViewEdits.createBody("db", "ds", "  Reading ", NewViewType.LIST),
        )
        assertEquals("table", ViewEdits.createBody("db", "ds", "x", NewViewType.TABLE)["type"])
    }

    @Test
    fun `a rename sends only the name`() {
        assertEquals(mapOf("name" to "Later"), ViewEdits.renameBody(" Later "))
    }

    @Test
    fun `a name needs more than spaces, and a rename has to change it`() {
        assertFalse(ViewEdits.validName("  "))
        assertTrue(ViewEdits.validName("A"))
        assertFalse(ViewEdits.renames("All", " All "))
        assertFalse(ViewEdits.renames("All", " "))
        assertTrue(ViewEdits.renames("All", "Everything"))
    }

    @Test
    fun `the last view can't be deleted`() {
        assertFalse(ViewEdits.canDelete(listOf(table)))
        assertFalse(ViewEdits.canDelete(emptyList()))
        assertTrue(ViewEdits.canDelete(listOf(table, list)))
    }

    @Test
    fun `the views here follow a change, with or without dashes in the ID`() {
        assertEquals(listOf("Everything", "Open"), ViewEdits.renamed(listOf(table, list), "v1", " Everything").map { it.name })
        assertEquals(listOf("v2"), ViewEdits.without(listOf(table, list), "v1").map { it.id })
        val added = NotionView("v3", "New", "table", "ds")
        assertEquals(listOf("v-1", "v2", "v3"), ViewEdits.added(listOf(table, list), added).map { it.id })
        // Already there (the list was asked again meanwhile): kept once, in its place.
        assertEquals(listOf("v-1", "v2"), ViewEdits.added(listOf(table, list), list.copy(name = "Shown")).map { it.id })
    }

    @Test
    fun `a view opens in Notion on its database's page`() {
        assertEquals("https://www.notion.so/abc?v=12ab", ViewEdits.notionUrl("https://www.notion.so/abc", "12-ab"))
    }
}
