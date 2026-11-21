package be.wimvandonck.noter.logic

import be.wimvandonck.noter.data.model.CardLayout
import be.wimvandonck.noter.data.model.DatabaseSetup
import be.wimvandonck.noter.data.model.NoteSort
import be.wimvandonck.noter.data.model.PageRef
import be.wimvandonck.noter.data.model.WorkspaceItem
import be.wimvandonck.noter.data.model.PropertyLayout
import be.wimvandonck.noter.data.model.PropertySchema
import be.wimvandonck.noter.data.model.SelectOption
import be.wimvandonck.noter.data.model.SortKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaEditsTest {
    private val title = PropertySchema("title", "Name", "title")
    private val tags = PropertySchema("a1", "Tags", "multi_select")
    private val due = PropertySchema("b2", "Due", "date")
    private val original = listOf(tags, title, due)

    @Test
    fun `starts with the title property first`() {
        val start = SchemaEdits.start(original)
        assertEquals(listOf("Name", "Tags", "Due"), start.map { it.name })
        assertFalse(start[0].removable)
        assertTrue(start[1].removable)
    }

    @Test
    fun `nothing changed sends nothing`() {
        assertTrue(SchemaEdits.change(original, SchemaEdits.start(original)).isEmpty)
    }

    @Test
    fun `a new name renames the property by its ID`() {
        val edited = SchemaEdits.start(original).map { if (it.id == "a1") it.copy(name = " Labels ") else it }
        val change = SchemaEdits.change(original, edited)
        assertEquals(mapOf("a1" to mapOf("name" to "Labels")), change.properties)
        assertEquals(mapOf("Tags" to "Labels"), change.renamed)
    }

    @Test
    fun `a blank name keeps the old one`() {
        val edited = SchemaEdits.start(original).map { if (it.id == "a1") it.copy(name = "  ") else it }
        assertTrue(SchemaEdits.change(original, edited).isEmpty)
    }

    @Test
    fun `a property left out is removed`() {
        val edited = SchemaEdits.start(original).filter { it.id != "b2" }
        val change = SchemaEdits.change(original, edited)
        assertEquals(mapOf("b2" to null), change.properties)
        assertEquals(setOf("Due"), change.removed)
    }

    @Test
    fun `a new property is added with its kind`() {
        val edited = SchemaEdits.add(SchemaEdits.start(original), PropertyKind.NUMBER, "Number")
        assertEquals(mapOf("Number" to mapOf("number" to mapOf("format" to "number"))), SchemaEdits.change(original, edited).properties)
    }

    @Test
    fun `a new property gets a number when its name is taken`() {
        val edited = SchemaEdits.add(SchemaEdits.start(original), PropertyKind.DATE, "Due")
        assertEquals("Due 2", edited.last().name)
        // Typed back to a taken name: still made unique.
        val typed = edited.dropLast(1) + edited.last().copy(name = "due")
        assertEquals(setOf("due 2"), SchemaEdits.change(original, typed).properties.keys)
    }

    @Test
    fun `a rename to a name another keeps gets a number`() {
        val edited = SchemaEdits.start(original).map { if (it.id == "b2") it.copy(name = "Tags") else it }
        assertEquals(mapOf("b2" to mapOf("name" to "Tags 2")), SchemaEdits.change(original, edited).properties)
    }

    @Test
    fun `a removed property's name is free for a rename`() {
        val edited = SchemaEdits.start(original).filter { it.id != "a1" }.map { if (it.id == "b2") it.copy(name = "Tags") else it }
        assertEquals(mapOf("a1" to null, "b2" to mapOf("name" to "Tags")), SchemaEdits.change(original, edited).properties)
    }

    @Test
    fun `the setup follows renamed and removed properties`() {
        val change = SchemaChange(mapOf("x" to null), renamed = mapOf("Tags" to "Labels"), removed = setOf("Due"))
        val setup = DatabaseSetup(
            quickFilters = listOf("Tags", "Due"),
            cards = CardLayout(properties = listOf("Tags", "Due")),
        )
        val changed = change.applyTo(setup)
        assertEquals(listOf("Labels"), changed.quickFilters)
        assertEquals(listOf("Labels"), changed.cards?.properties)
    }

    @Test
    fun `the note's layout follows renamed and removed properties`() {
        val change = SchemaChange(mapOf("x" to null), renamed = mapOf("Tags" to "Labels"), removed = setOf("Due"))
        val layout = PropertyLayout(order = listOf("Due", "Tags"), hidden = listOf("Tags"), onCard = listOf("Due"))
        assertEquals(PropertyLayout(order = listOf("Labels"), hidden = listOf("Labels"), onCard = emptyList()), change.applyTo(layout))
    }

    private val high = SelectOption("High", "red", "o1")
    private val low = SelectOption("Low", "gray", "o2")
    private val priority = PropertySchema("p1", "Priority", "select", options = listOf(high, low))
    private val stage = PropertySchema(
        "s1", "Stage", "status",
        options = listOf(SelectOption("Todo", "gray", "t1", "To-do"), SelectOption("Doing", "blue", "t2", "In progress")),
    )
    private val withOptions = listOf(title, priority, stage)

    private fun edit(id: String, change: (EditedProperty) -> EditedProperty) =
        SchemaEdits.start(withOptions).map { if (it.id == id) change(it) else it }

    private fun sentOptions(change: SchemaChange, id: String, type: String): Any? =
        ((change.properties[id] as Map<*, *>)[type] as Map<*, *>)["options"]

    @Test
    fun `options start as the property has them, only for the types that have options`() {
        val start = SchemaEdits.start(withOptions)
        assertNull(start[0].options)
        assertEquals(listOf(high, low), start[1].options)
    }

    @Test
    fun `untouched options send nothing`() {
        assertTrue(SchemaEdits.change(withOptions, SchemaEdits.start(withOptions)).isEmpty)
    }

    @Test
    fun `a new option is sent after the existing ones, which are kept by ID`() {
        val edited = edit("p1") { it.copy(options = SchemaEdits.addOption(it.options!!, " Medium ", "yellow")) }
        val change = SchemaEdits.change(withOptions, edited)
        assertEquals(
            mapOf(
                "p1" to mapOf(
                    "select" to mapOf(
                        "options" to listOf(
                            mapOf("id" to "o1", "name" to "High", "color" to "red"),
                            mapOf("id" to "o2", "name" to "Low", "color" to "gray"),
                            mapOf("name" to "Medium", "color" to "yellow"),
                        ),
                    ),
                ),
            ),
            change.properties,
        )
        assertTrue(change.renamed.isEmpty())
    }

    @Test
    fun `a new option with a taken name, whatever its case, or a blank one is not added`() {
        assertEquals(listOf(high, low), SchemaEdits.addOption(listOf(high, low), "high"))
        assertEquals(listOf(high, low), SchemaEdits.addOption(listOf(high, low), "  "))
        // Typed into added rows: repeated and blank names are dropped when sending.
        val edited = edit("p1") { it.copy(options = it.options!! + SelectOption("HIGH") + SelectOption(" ") + SelectOption("Mid") + SelectOption("mid")) }
        val sent = sentOptions(SchemaEdits.change(withOptions, edited), "p1", "select") as List<*>
        assertEquals(listOf("High", "Low", "Mid"), sent.map { (it as Map<*, *>)["name"] })
    }

    @Test
    fun `a changed color is sent with the option's ID`() {
        val edited = edit("p1") { it.copy(options = it.options!!.map { o -> if (o.id == "o2") o.copy(color = "green") else o }) }
        assertEquals(
            listOf(mapOf("id" to "o1", "name" to "High", "color" to "red"), mapOf("id" to "o2", "name" to "Low", "color" to "green")),
            sentOptions(SchemaEdits.change(withOptions, edited), "p1", "select"),
        )
    }

    @Test
    fun `a removed option is left out of the list and asked about`() {
        val edited = edit("p1") { it.copy(options = it.options!!.filter { o -> o.id != "o1" }) }
        assertEquals(
            listOf(mapOf("id" to "o2", "name" to "Low", "color" to "gray")),
            sentOptions(SchemaEdits.change(withOptions, edited), "p1", "select"),
        )
        assertEquals(listOf("Priority: High"), SchemaEdits.removedOptions(withOptions, edited))
    }

    @Test
    fun `removing every option sends an empty list`() {
        val edited = edit("p1") { it.copy(options = emptyList()) }
        assertEquals(emptyList<Any>(), sentOptions(SchemaEdits.change(withOptions, edited), "p1", "select"))
    }

    @Test
    fun `an added option is not a lost one`() {
        val edited = edit("p1") { it.copy(options = it.options!! + SelectOption("Mid")) }
        assertTrue(SchemaEdits.removedOptions(withOptions, edited).isEmpty())
    }

    @Test
    fun `multi-select options go under multi_select`() {
        val tagged = PropertySchema("m1", "Tags", "multi_select", options = listOf(SelectOption("a", "blue", "x1")))
        val schemas = listOf(title, tagged)
        val edited = SchemaEdits.start(schemas).map { if (it.id == "m1") it.copy(options = it.options!! + SelectOption("b", "pink")) else it }
        val change = SchemaEdits.change(schemas, edited)
        assertEquals(setOf("multi_select"), (change.properties["m1"] as Map<*, *>).keys)
    }

    @Test
    fun `a status option moves to another group and a new one gets its group`() {
        val edited = edit("s1") {
            it.copy(
                options = it.options!!.map { o -> if (o.id == "t1") o.copy(group = "Complete") else o } +
                    SelectOption("Shipped", "green", group = "Complete"),
            )
        }
        assertEquals(
            listOf(
                mapOf("id" to "t1", "name" to "Todo", "color" to "gray", "group" to "Complete"),
                mapOf("id" to "t2", "name" to "Doing", "color" to "blue"),
                mapOf("name" to "Shipped", "color" to "green", "group" to "Complete"),
            ),
            sentOptions(SchemaEdits.change(withOptions, edited), "s1", "status"),
        )
    }

    @Test
    fun `a rename and an option change go in one entry for the property`() {
        val edited = edit("p1") { it.copy(name = "Urgency", options = it.options!!.filter { o -> o.id != "o2" }) }
        val change = SchemaEdits.change(withOptions, edited)
        assertEquals(setOf("name", "select"), (change.properties["p1"] as Map<*, *>).keys)
        assertEquals(mapOf("Priority" to "Urgency"), change.renamed)
    }

    // ---- Kinds with settings, Change type, number formats ----

    private val price = PropertySchema("n1", "Price", "number", numberFormat = "euro")
    private val link = PropertySchema("r1", "Books", "relation", relationDatabaseId = "ds-books")
    private val schema = listOf(title, price, link, tags)

    private fun added(kind: PropertyKind, name: String, settings: PropertySettings) =
        SchemaEdits.start(schema) + EditedProperty(null, name, kind.type, kind, settings = settings)

    @Test
    fun `a new formula, relation, rollup, unique ID, place and status are sent with their settings`() {
        fun sent(kind: PropertyKind, settings: PropertySettings = PropertySettings()) =
            SchemaEdits.change(schema, added(kind, "New", settings)).properties["New"]
        assertEquals(mapOf("formula" to mapOf("expression" to "prop(\"Price\") * 2")), sent(PropertyKind.FORMULA, PropertySettings(formula = "prop(\"Price\") * 2")))
        assertEquals(
            mapOf("relation" to mapOf("data_source_id" to "ds-9", "type" to "dual_property", "dual_property" to emptyMap<String, Any?>())),
            sent(PropertyKind.RELATION, PropertySettings(relationTarget = "ds-9", twoWay = true)),
        )
        assertEquals(
            mapOf("rollup" to mapOf("relation_property_id" to "r1", "rollup_property_id" to "x", "function" to "count")),
            sent(PropertyKind.ROLLUP, PropertySettings(rollupRelation = "r1", rollupProperty = "x", rollupFunction = "count")),
        )
        assertEquals(mapOf("unique_id" to mapOf("prefix" to "BK")), sent(PropertyKind.UNIQUE_ID, PropertySettings(idPrefix = "BK")))
        assertEquals(mapOf("place" to emptyMap<String, Any?>()), sent(PropertyKind.PLACE))
        assertEquals(mapOf("status" to emptyMap<String, Any?>()), sent(PropertyKind.STATUS))
        assertEquals(mapOf("number" to mapOf("format" to "percent")), sent(PropertyKind.NUMBER, PropertySettings(numberFormat = "percent")))
    }

    @Test
    fun `a property without its settings is not ready to save`() {
        assertEquals(listOf("New"), SchemaEdits.incomplete(added(PropertyKind.FORMULA, "New", PropertySettings())))
        assertTrue(SchemaEdits.incomplete(added(PropertyKind.FORMULA, "New", PropertySettings(formula = "1"))).isEmpty())
    }

    @Test
    fun `a new type is sent under the property's ID, with a rename in the same entry`() {
        val edited = SchemaEdits.start(schema).map { if (it.id == "a1") SchemaEdits.changeType(it, PropertyKind.SELECT).copy(name = "Genre") else it }
        val change = SchemaEdits.change(schema, edited)
        assertEquals(mapOf("a1" to mapOf("name" to "Genre", "select" to emptyMap<String, Any?>())), change.properties)
        assertEquals(listOf("Tags"), SchemaEdits.changedTypes(schema, edited))
        // Its options are not asked about separately.
        assertTrue(SchemaEdits.removedOptions(schema, edited).isEmpty())
    }

    @Test
    fun `changing back to its own type sends nothing`() {
        val changed = SchemaEdits.changeType(SchemaEdits.start(schema).first { it.id == "a1" }, PropertyKind.TEXT)
        val back = SchemaEdits.changeType(changed, PropertyKind.MULTI_SELECT)
        assertNull(back.changedTo)
        assertTrue(SchemaEdits.change(schema, SchemaEdits.start(schema).map { if (it.id == "a1") back else it }).isEmpty)
    }

    @Test
    fun `a type change to a formula sends its expression`() {
        val edited = SchemaEdits.start(schema).map {
            if (it.id == "n1") SchemaEdits.changeType(it, PropertyKind.FORMULA).copy(settings = PropertySettings(formula = "1 + 1")) else it
        }
        assertEquals(mapOf("n1" to mapOf("formula" to mapOf("expression" to "1 + 1"))), SchemaEdits.change(schema, edited).properties)
    }

    @Test
    fun `an existing number keeps its format until another is chosen`() {
        val start = SchemaEdits.start(schema)
        assertEquals("euro", start.first { it.id == "n1" }.settings.numberFormat)
        assertTrue(SchemaEdits.change(schema, start).isEmpty)
        val edited = start.map { if (it.id == "n1") it.copy(settings = it.settings.copy(numberFormat = "dollar")) else it }
        assertEquals(mapOf("n1" to mapOf("number" to mapOf("format" to "dollar"))), SchemaEdits.change(schema, edited).properties)
    }

    @Test
    fun `the title can't change type, a rollup needs a saved relation, one unique ID at most`() {
        val start = SchemaEdits.start(schema)
        assertTrue(SchemaEdits.typeChanges(start.first { it.type == "title" }, schema, start).isEmpty())
        assertTrue(PropertyKind.ROLLUP in SchemaEdits.addableKinds(schema, start))
        // The relation itself can't become a rollup of itself.
        assertFalse(PropertyKind.ROLLUP in SchemaEdits.typeChanges(start.first { it.id == "r1" }, schema, start))
        val withoutRelation = start.filter { it.id != "r1" }
        assertFalse(PropertyKind.ROLLUP in SchemaEdits.addableKinds(schema, withoutRelation))
        val withId = SchemaEdits.add(start, PropertyKind.UNIQUE_ID, "ID")
        assertFalse(PropertyKind.UNIQUE_ID in SchemaEdits.addableKinds(schema, withId))
        assertEquals(listOf("r1"), SchemaEdits.rollupRelations(schema, start).map { it.id })
    }

    @Test
    fun `relation targets are Home's databases in the same workspace, one per data source`() {
        val items = listOf(
            WorkspaceItem("ds-1", isDatabase = true, title = "Books", databaseId = "db-1"),
            WorkspaceItem("db-2", isDatabase = true, title = "Health", sources = listOf(PageRef("s-a", "Skin"), PageRef("s-b", "Food"))),
            WorkspaceItem("page", isDatabase = false, title = "Page"),
            WorkspaceItem("ds-3", isDatabase = true, title = "Work"),
        )
        val owners = mapOf("ds1" to "home", "sa" to "home", "sb" to "home", "ds3" to "work")
        assertEquals(
            listOf(PageRef("ds-1", "Books"), PageRef("s-a", "Health · Skin"), PageRef("s-b", "Health · Food")),
            SchemaEdits.relationTargets(items, owners, "home"),
        )
        assertEquals(4, SchemaEdits.relationTargets(items, owners, null).size)
    }

    @Test
    fun `edited properties survive a rotation as JSON`() {
        val edited = added(PropertyKind.RELATION, "New", PropertySettings(relationTarget = "ds", twoWay = true)) +
            SchemaEdits.changeType(SchemaEdits.start(schema).first { it.id == "a1" }, PropertyKind.SELECT)
        val serializer = kotlinx.serialization.builtins.ListSerializer(EditedProperty.serializer())
        val json = kotlinx.serialization.json.Json.encodeToString(serializer, edited)
        assertEquals(edited, kotlinx.serialization.json.Json.decodeFromString(serializer, json))
    }
}
