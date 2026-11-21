package be.wimvandonck.noter.data.notion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import be.wimvandonck.noter.data.model.NotionView
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewCodecTest {
    private fun parse(text: String) = ViewCodec.parse(Json.parseToJsonElement(text).jsonObject)

    @Test
    fun `a view is read with its filter, sorts and quick filters`() {
        val view = parse(
            """{"object":"view","id":"v1","name":"Open","type":"board","data_source_id":"ds1",
               "parent":{"type":"database_id","database_id":"db1"},
               "filter":{"property":"Done","checkbox":{"equals":false}},
               "sorts":[{"timestamp":"created_time","direction":"descending"}],
               "quick_filters":{"Status":{}},"configuration":{"type":"board"}}""",
        )!!
        assertEquals("v1", view.id)
        assertEquals("Open", view.name)
        assertEquals("board", view.type)
        assertEquals("ds1", view.dataSourceId)
        assertEquals(Json.parseToJsonElement("""{"property":"Done","checkbox":{"equals":false}}"""), view.filter)
        assertEquals(Json.parseToJsonElement("""[{"timestamp":"created_time","direction":"descending"}]"""), view.sorts)
        assertEquals(Json.parseToJsonElement("""{"Status":{}}"""), view.quickFilters)
    }

    @Test
    fun `what isn't set is null, and what isn't a view is nothing`() {
        val view = parse("""{"object":"view","id":"v1","type":"dashboard","data_source_id":null,"filter":null}""")!!
        assertNull(view.dataSourceId)
        assertNull(view.filter)
        assertNull(view.sorts)
        assertEquals("", view.name)
        assertNull(parse("""{"object":"page","id":"p1"}"""))
    }

    @Test
    fun `a view's visible properties are read in its order, hidden ones left out`() {
        val view = parse(
            """{"object":"view","id":"v1","type":"gallery","configuration":{"type":"gallery",
               "properties":[{"property_id":"title","visible":true},{"property_id":"p2","visible":false},
               {"property_id":"p3"},{"property_id":"p1","visible":true}],
               "cover":{"type":"page_cover"},"cover_size":"medium","card_layout":"compact"}}""",
        )!!
        assertEquals(listOf("title", "p3", "p1"), view.visibleProperties)
        assertEquals("page_cover", view.cover)
    }

    @Test
    fun `a view without configuration, or with other shapes, is read leniently`() {
        val none = parse("""{"object":"view","id":"v1","type":"table","configuration":null}""")!!
        assertNull(none.visibleProperties)
        assertNull(none.cover)
        val map = parse("""{"object":"view","id":"v1","type":"table","configuration":{"properties":{"Due":{"width":100},"Old":{"visible":false}},"cover":{"type":"none"}}}""")!!
        assertEquals(listOf("Due"), map.visibleProperties)
        assertNull(map.cover)
        val empty = parse("""{"object":"view","id":"v1","type":"board","configuration":{"properties":[],"cover":"page_content"}}""")!!
        assertEquals(emptyList<String>(), empty.visibleProperties)
        assertEquals("page_content", empty.cover)
    }

    @Test
    fun `a view kept before its configuration was read still loads`() {
        val old = Json { ignoreUnknownKeys = true }.decodeFromString<NotionView>("""{"id":"v1","name":"All","type":"table"}""")
        assertNull(old.visibleProperties)
        assertNull(old.cover)
    }
}
