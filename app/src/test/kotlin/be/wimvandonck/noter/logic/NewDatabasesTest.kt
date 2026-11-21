package be.wimvandonck.noter.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class NewDatabasesTest {
    private fun schema(request: Map<String, Any?>) = (request["initial_data_source"] as Map<*, *>)["properties"] as Map<*, *>

    @Test
    fun `inside a page, with the title property and the properties in their order`() {
        val properties = listOf(NewProperty(PropertyKind.MULTI_SELECT, "Tags"), NewProperty(PropertyKind.NUMBER, "Number"), NewProperty(PropertyKind.URL, "URL"))
        val request = NewDatabases.request(" Books ", "page-1", "Name", properties)
        assertEquals(mapOf("type" to "page_id", "page_id" to "page-1"), request["parent"])
        assertEquals(listOf(mapOf("type" to "text", "text" to mapOf("content" to "Books"))), request["title"])
        val schema = schema(request)
        assertEquals(listOf("Name", "Tags", "Number", "URL"), schema.keys.toList())
        assertEquals(mapOf("title" to emptyMap<String, Any?>()), schema["Name"])
        assertEquals(mapOf("multi_select" to emptyMap<String, Any?>()), schema["Tags"])
        assertEquals(mapOf("number" to mapOf("format" to "number")), schema["Number"])
        assertEquals(mapOf("url" to emptyMap<String, Any?>()), schema["URL"])
    }

    @Test
    fun `at the top of the workspace`() {
        val request = NewDatabases.request("Books", null, "Name", emptyList())
        assertEquals(mapOf("type" to "workspace", "workspace" to true), request["parent"])
    }

    @Test
    fun `several of a kind are numbered as Notion does`() {
        val list = listOf(PropertyKind.TEXT, PropertyKind.TEXT, PropertyKind.CHECKBOX, PropertyKind.TEXT)
            .fold(emptyList<NewProperty>()) { acc, kind -> NewDatabases.add(acc, kind, if (kind == PropertyKind.TEXT) "Text" else "Checkbox", "Name") }
        assertEquals(listOf("Text", "Text 2", "Checkbox", "Text 3"), list.map { it.name })
        assertEquals("name 2", NewDatabases.add(emptyList(), PropertyKind.TEXT, "name", "Name").single().name)
    }

    @Test
    fun `names made alike or blank while editing are still sent once each`() {
        val properties = listOf(NewProperty(PropertyKind.TEXT, "Notes"), NewProperty(PropertyKind.DATE, "notes"), NewProperty(PropertyKind.EMAIL, " "))
        val schema = schema(NewDatabases.request("Books", "p", " ", properties, mapOf(PropertyKind.EMAIL to "Email")))
        assertEquals(listOf("Name", "Notes", "notes 2", "Email"), schema.keys.toList())
    }

    @Test
    fun `a new database starts with Tags, as in Notion`() {
        assertEquals(listOf(PropertyKind.MULTI_SELECT), NewDatabases.DEFAULT_KINDS)
    }

    @Test
    fun `status is sent empty so Notion adds Not started, In progress and Done`() {
        val request = NewDatabases.request("Work", "p", "Name", listOf(NewProperty(PropertyKind.STATUS, "Status", "ignored, typed before switching")))
        assertEquals(mapOf("status" to emptyMap<String, Any?>()), schema(request)["Status"])
    }

    @Test
    fun `typed options go to a select and a multi-select, trimmed and once each`() {
        val properties = listOf(
            NewProperty(PropertyKind.SELECT, "Kind", " Red, green ,,RED,Blue "),
            NewProperty(PropertyKind.MULTI_SELECT, "Tags", "a"),
            NewProperty(PropertyKind.SELECT, "Empty", " , "),
        )
        val schema = schema(NewDatabases.request("Books", "p", "Name", properties))
        val names = listOf("Red", "green", "Blue").map { mapOf("name" to it) }
        assertEquals(mapOf("select" to mapOf("options" to names)), schema["Kind"])
        assertEquals(mapOf("multi_select" to mapOf("options" to listOf(mapOf("name" to "a")))), schema["Tags"])
        assertEquals(mapOf("select" to emptyMap<String, Any?>()), schema["Empty"])
    }

    @Test
    fun `options split at new lines too and are cut to Notion's limit`() {
        assertEquals(listOf("a", "b"), NewDatabases.parseOptions("a\nb"))
        assertEquals(100, NewDatabases.parseOptions("x".repeat(150)).single().length)
    }

    @Test
    fun `a starter sends its type with no schema, and Notion names it when the name is blank`() {
        val tasks = NewDatabases.request(" ", "p", "Name", listOf(NewProperty(PropertyKind.TEXT, "Text")), starter = DatabaseStarter.TASKS)
        assertEquals(mapOf("parent" to mapOf("type" to "page_id", "page_id" to "p"), "database_type" to "tasks"), tasks)
        val projects = NewDatabases.request("Q3", null, "Name", emptyList(), starter = DatabaseStarter.PROJECTS)
        assertEquals("projects", projects["database_type"])
        assertEquals(listOf(mapOf("type" to "text", "text" to mapOf("content" to "Q3"))), projects["title"])
        assertEquals(false, projects.containsKey("initial_data_source"))
    }

    @Test
    fun `an empty database always has a title and no database type`() {
        val request = NewDatabases.request("", "p", "Name", emptyList())
        assertEquals(true, request.containsKey("title"))
        assertEquals(false, request.containsKey("database_type"))
    }

    @Test
    fun `showing inside the page only applies inside a page`() {
        assertEquals(true, NewDatabases.request("B", "p", "Name", emptyList(), inline = true)["is_inline"])
        assertEquals(false, NewDatabases.request("B", "p", "Name", emptyList())["is_inline"] ?: false)
        assertEquals(false, NewDatabases.request("B", null, "Name", emptyList(), inline = true).containsKey("is_inline"))
        assertEquals(true, NewDatabases.request("", "p", "Name", emptyList(), starter = DatabaseStarter.TASKS, inline = true)["is_inline"])
    }

    @Test
    fun `a formula, relation, unique ID, place and number format go in the new database's schema`() {
        val properties = listOf(
            NewProperty(PropertyKind.FORMULA, "Double", settings = PropertySettings(formula = "prop(\"Price\") * 2")),
            NewProperty(PropertyKind.RELATION, "Author", settings = PropertySettings(relationTarget = "ds-authors")),
            NewProperty(PropertyKind.UNIQUE_ID, "ID", settings = PropertySettings(idPrefix = "BK")),
            NewProperty(PropertyKind.PLACE, "Where"),
            NewProperty(PropertyKind.NUMBER, "Price", settings = PropertySettings(numberFormat = "euro")),
        )
        val schema = schema(NewDatabases.request("Books", null, "Name", properties))
        assertEquals(mapOf("formula" to mapOf("expression" to "prop(\"Price\") * 2")), schema["Double"])
        assertEquals(
            mapOf("relation" to mapOf("data_source_id" to "ds-authors", "type" to "single_property", "single_property" to emptyMap<String, Any?>())),
            schema["Author"],
        )
        assertEquals(mapOf("unique_id" to mapOf("prefix" to "BK")), schema["ID"])
        assertEquals(mapOf("place" to emptyMap<String, Any?>()), schema["Where"])
        assertEquals(mapOf("number" to mapOf("format" to "euro")), schema["Price"])
    }

    @Test
    fun `Create waits for every property's settings, and a new database offers no rollup`() {
        val formula = NewProperty(PropertyKind.FORMULA, "F")
        assertEquals(false, NewDatabases.canCreate("Books", listOf(formula), DatabaseStarter.EMPTY))
        assertEquals(true, NewDatabases.canCreate("Books", listOf(formula.copy(settings = PropertySettings(formula = "1"))), DatabaseStarter.EMPTY))
        assertEquals(false, NewDatabases.canCreate(" ", emptyList(), DatabaseStarter.EMPTY))
        assertEquals(true, NewDatabases.canCreate("", listOf(formula), DatabaseStarter.TASKS))
        assertEquals(false, PropertyKind.ROLLUP in NewDatabases.addableKinds(emptyList()))
        assertEquals(true, PropertyKind.STATUS in NewDatabases.addableKinds(emptyList()))
        assertEquals(false, PropertyKind.UNIQUE_ID in NewDatabases.addableKinds(listOf(NewProperty(PropertyKind.UNIQUE_ID, "ID"))))
    }
}
