package be.wimvandonck.noter.logic

import be.wimvandonck.noter.data.model.DatabaseSchema
import be.wimvandonck.noter.data.model.NotionView
import be.wimvandonck.noter.data.model.PropertySchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotionViewsTest {
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun view(id: String, type: String = "table", source: String? = "ds-1", filter: String? = null, sorts: String? = null, quick: String? = null) =
        NotionView(id, "View $id", type, source, filter?.let(::json), sorts?.let(::json), quick?.let(::json))

    private val schema = DatabaseSchema(
        "ds-1", "Tasks",
        listOf(
            PropertySchema("title", "Name", "title"),
            PropertySchema("a%3Bb", "Due", "date"),
            PropertySchema("xyz", "Files", "files"),
        ),
    )

    @Test
    fun `only views that list this data source's notes are offered`() {
        val views = listOf(view("1"), view("2", type = "form"), view("3", type = "dashboard", source = null), view("4", source = "ds-2"), view("5", type = "board", source = "ds1"))
        assertEquals(listOf("1", "5"), NotionViews.listed(views, "ds-1").map { it.id })
    }

    @Test
    fun `a database opens on Notion's first view, then on the view used last`() {
        val views = listOf(view("1"), view("2"))
        assertEquals("1", NotionViews.chosen(null, views))
        assertEquals("2", NotionViews.chosen("2", views))
        // A chosen view that is gone or hidden: the first shown one.
        assertEquals("1", NotionViews.chosen("gone", views))
        assertNull(NotionViews.chosen(null, emptyList()))
    }

    @Test
    fun `a view's filter and sorts are sent as they are, sorts by property name`() {
        val v = view("1", filter = """{"property":"Done","checkbox":{"equals":false}}""", sorts = """[{"property":"a%3Bb","direction":"ascending"},{"timestamp":"created_time","direction":"descending"}]""")
        val (filter, sorts) = NotionViews.query(v, schema)
        assertEquals(json("""{"property":"Done","checkbox":{"equals":false}}"""), filter)
        assertEquals(json("""[{"property":"Due","direction":"ascending"},{"timestamp":"created_time","direction":"descending"}]"""), sorts)
    }

    @Test
    fun `quick filters that are on join the view's filter, the ones off are not sent`() {
        val status = json("""{"status":{"equals":"Doing"}}""") as JsonObject
        val quick = mapOf("Status" to status)
        val v = { filter: String? -> view("1", filter = filter, quick = """{"Status":{"status":{"equals":"Doing"}},"Tag":{}}""") }
        val alone = NotionViews.query(v(null), schema, quick).first
        assertEquals(json("""{"property":"Status","status":{"equals":"Doing"}}"""), alone)

        val withAnd = NotionViews.query(v("""{"and":[{"property":"Done","checkbox":{"equals":false}}]}"""), schema, quick).first
        assertEquals(json("""{"and":[{"property":"Done","checkbox":{"equals":false}},{"property":"Status","status":{"equals":"Doing"}}]}"""), withAnd)

        val withOr = NotionViews.query(v("""{"or":[{"property":"A","checkbox":{"equals":true}}]}"""), schema, quick).first
        assertEquals(json("""{"and":[{"or":[{"property":"A","checkbox":{"equals":true}}]},{"property":"Status","status":{"equals":"Doing"}}]}"""), withOr)

        val two = NotionViews.query(v("""{"property":"Done","checkbox":{"equals":false}}"""), schema, quick + ("Done" to (json("""{"checkbox":{"equals":true}}""") as JsonObject))).first
        assertEquals(
            json("""{"and":[{"property":"Done","checkbox":{"equals":false}},{"property":"Done","checkbox":{"equals":true}},{"property":"Status","status":{"equals":"Doing"}}]}"""),
            two,
        )

        // Nothing on: the view's filter alone, whatever quick filters it has.
        assertNull(NotionViews.query(v(null), schema).first)
    }

    @Test
    fun `a view's notes are kept per combination of quick filters`() {
        val a = json("""{"status":{"equals":"Doing"}}""") as JsonObject
        val b = json("""{"checkbox":{"equals":true}}""") as JsonObject
        assertEquals("v1", NotionViews.resultKey("v1"))
        val key = NotionViews.resultKey("v1", mapOf("Status" to a, "Done" to b))
        // The order the chips were turned on doesn't matter.
        assertEquals(key, NotionViews.resultKey("v1", mapOf("Done" to b, "Status" to a)))
        assertNotEquals(key, NotionViews.resultKey("v1", mapOf("Status" to a)))
        assertNotEquals(NotionViews.resultKey("v1", mapOf("Status" to a)), NotionViews.resultKey("v1", mapOf("Status" to b)))
        assertEquals("v1", NotionViews.viewOf(key))
        assertEquals("v1", NotionViews.viewOf("v1"))
    }

    @Test
    fun `no filter and no sorts ask for every note in Notion's order`() {
        assertEquals(null to null, NotionViews.query(view("1", sorts = "[]"), schema))
    }

    @Test
    fun `notes made since the view was asked show first`() {
        val created = mapOf("a" to "2026-11-01T10:00:00.000Z", "new" to "2026-11-12T10:00:05.000Z", "old" to "2026-11-01T09:00:00.000Z")
        assertEquals(listOf("new", "a"), NotionViews.members(listOf("a"), "2026-11-12T10:00:00Z", created))
    }

    private val configured = DatabaseSchema(
        "ds-1", "Tasks",
        listOf(
            PropertySchema("title", "Name", "title"),
            PropertySchema("p1", "Status", "status"),
            PropertySchema("p2", "Due", "date"),
            PropertySchema("p3", "Owner", "people"),
        ),
    )

    private fun shown(id: String, type: String = "table", properties: List<String>? = null, cover: String? = null, source: String = "ds-1") =
        NotionView(id, "View $id", type, source, visibleProperties = properties, cover = cover)

    @Test
    fun `a view's property names follow its order, without the title and what the source no longer has`() {
        val view = shown("1", properties = listOf("title", "p3", "Gone", "p1", "Due"))
        assertEquals(listOf("Owner", "Status", "Due"), NotionViews.propertyNames(view, configured))
        assertNull(NotionViews.propertyNames(shown("2"), configured))
        assertNull(NotionViews.propertyNames(view, null))
        assertNull(NotionViews.propertyNames(null, configured))
    }

    @Test
    fun `a note follows the table view's order, else the first view that has one`() {
        val board = shown("1", "board", listOf("p1"))
        val table = shown("2", "table", listOf("p2"))
        val unknown = shown("3", "table")
        assertEquals("2", NotionViews.orderingView(listOf(board, table), "ds-1")?.id)
        assertEquals("1", NotionViews.orderingView(listOf(unknown, board), "ds-1")?.id)
        assertNull(NotionViews.orderingView(listOf(unknown, shown("4", "form", listOf("p1")), shown("5", source = "ds-2", properties = listOf("p1"))), "ds-1"))
    }

    @Test
    fun `each source shows the view chosen last, else its first`() {
        val views = mapOf("ds-1" to listOf(shown("a"), shown("b")), "ds-2" to listOf(shown("c", source = "ds-2")), "ds-3" to emptyList())
        val result = NotionViews.shownViews(views, mapOf("ds-1" to "b"))
        assertEquals("b", result["ds-1"]?.id)
        assertEquals("c", result["ds-2"]?.id)
        assertNull(result["ds-3"])
    }
}
