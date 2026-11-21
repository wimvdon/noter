package be.wimvandonck.noter.logic

import be.wimvandonck.noter.data.model.DatabaseSchema
import be.wimvandonck.noter.data.model.NotionView
import be.wimvandonck.noter.data.model.PropertySchema
import be.wimvandonck.noter.data.model.SelectOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickFiltersTest {
    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject

    private val schema = DatabaseSchema(
        "ds", "Tasks",
        listOf(
            PropertySchema("title", "Name", "title"),
            PropertySchema("p1", "Status", "status", listOf(SelectOption("Todo"), SelectOption("Done"))),
            PropertySchema("p2", "Tags", "multi_select", listOf(SelectOption("Home"))),
            PropertySchema("p3", "Owner", "people"),
            PropertySchema("p4", "Done?", "checkbox"),
            PropertySchema("p5", "Due", "date"),
        ),
    )

    private fun view(quick: String?) = NotionView("v", "View", "table", "ds", quickFilters = quick?.let(Json::parseToJsonElement))

    @Test
    fun `a chip is named like Notion's pill, with the value of a set condition`() {
        val chips = QuickFilters.chips(view("""{"p1":{"status":{"equals":"Done"}},"p2":{}}"""), schema)
        assertEquals(listOf("Status", "Tags"), chips.map { it.name })
        assertEquals("Status: Done", QuickFilters.label(chips[0]))
        // Without a value: just the property's name.
        assertEquals("Tags", QuickFilters.label(chips[1]))
        assertEquals("Tags: Home", QuickFilters.label(chips[1], QuickFilters.option("multi_select", "Home")))
    }

    @Test
    fun `values that aren't words are left out of the name`() {
        val checkbox = QuickChip("p4", "Done?", "checkbox", obj("""{"checkbox":{"equals":true}}"""))
        assertEquals("Done?", QuickFilters.label(checkbox))
        val owner = QuickChip("p3", "Owner", "people", QuickFilters.person("2c1f4e0a-1b2c-4d5e-8f90-123456789abc"))
        assertEquals("Owner", QuickFilters.label(owner))
        assertEquals("Owner: Ann", QuickFilters.label(owner, names = mapOf("2c1f4e0a-1b2c-4d5e-8f90-123456789abc" to "Ann")))
        val emptiness = QuickChip("p1", "Status", "status", obj("""{"status":{"is_not_empty":true}}"""))
        assertEquals("Status", QuickFilters.label(emptiness))
    }

    @Test
    fun `chips without a condition are kept only when a value can be picked, and gone properties are left out`() {
        val quick = """{"p1":{},"p3":{},"p4":{},"Tags":{},"title":{},"gone":{"select":{"equals":"x"}},"p5":{"date":{"is_empty":true}}}"""
        val chips = QuickFilters.chips(view(quick), schema)
        // Keys by ID or by name both find the property; the title is no chip, a date only with a condition.
        assertEquals(listOf("Status", "Owner", "Done?", "Tags", "Due"), chips.map { it.name })
        assertEquals(listOf("p1", "p3", "p4", "Tags", "p5"), chips.map { it.key })
        assertEquals(emptyList<QuickChip>(), QuickFilters.chips(view(null), schema))
        assertEquals(emptyList<QuickChip>(), QuickFilters.chips(view(quick), null))
        assertEquals(emptyList<QuickChip>(), QuickFilters.chips(null, schema))
    }

    @Test
    fun `what a chip without a value can be set to`() {
        val status = QuickFilters.choices(schema.property("Status"))
        assertEquals(listOf("Todo", "Done"), status.map { it.name })
        assertEquals(obj("""{"status":{"equals":"Done"}}"""), status[1].condition)
        assertEquals(obj("""{"multi_select":{"contains":"Home"}}"""), QuickFilters.choices(schema.property("Tags")).single().condition)
        val checkbox = QuickFilters.choices(schema.property("Done?"))
        assertEquals(listOf(QuickChoice.Kind.CHECKED, QuickChoice.Kind.UNCHECKED), checkbox.map { it.kind })
        assertEquals(obj("""{"checkbox":{"equals":false}}"""), checkbox[1].condition)
        // The signed-in person's ID comes later.
        assertEquals(listOf(QuickChoice.Kind.ME), QuickFilters.choices(schema.property("Owner")).map { it.kind })
        assertEquals(obj("""{"people":{"contains":"u1"}}"""), QuickFilters.person("u1"))
        assertTrue(QuickFilters.choices(schema.property("Due")).isEmpty())
        assertTrue(QuickFilters.choices(null).isEmpty())
    }

    @Test
    fun `a chip turned on that the view no longer has is dropped`() {
        val on = mapOf("p1" to obj("""{"status":{"equals":"Done"}}"""), "p3" to QuickFilters.person("u1"))
        val applied = QuickFilters.applicable(on, view("""{"p1":{}}"""), schema)
        assertEquals(setOf("p1"), applied.keys)
        assertEquals(emptyMap<String, JsonObject>(), QuickFilters.applicable(emptyMap(), null, null))
    }

    @Test
    fun `the combined filter asks the view's filter and the chips that are on, by their key`() {
        val v = NotionView("v", "View", "table", "ds", filter = obj("""{"property":"Done?","checkbox":{"equals":false}}"""), quickFilters = obj("""{"p1":{"status":{"equals":"Todo"}}}"""))
        val on = mapOf("p1" to QuickFilters.option("status", "Done"))
        assertEquals(
            Json.parseToJsonElement("""{"and":[{"property":"Done?","checkbox":{"equals":false}},{"property":"p1","status":{"equals":"Done"}}]}"""),
            NotionViews.query(v, schema, on).first,
        )
    }
}
