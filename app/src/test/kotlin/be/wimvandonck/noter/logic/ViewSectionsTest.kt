package be.wimvandonck.noter.logic

import be.wimvandonck.noter.data.model.DatabaseSchema
import be.wimvandonck.noter.data.model.NoteProperty
import be.wimvandonck.noter.data.model.NoteSummary
import be.wimvandonck.noter.data.model.NotionUser
import be.wimvandonck.noter.data.model.NotionView
import be.wimvandonck.noter.data.model.PropertySchema
import be.wimvandonck.noter.data.model.PropertyValue
import be.wimvandonck.noter.data.model.SelectOption
import be.wimvandonck.noter.data.model.StatusGroup
import be.wimvandonck.noter.data.model.ViewGroupBy
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewSectionsTest {
    private val todo = SelectOption("Not started", "gray", "s1")
    private val doing = SelectOption("In progress", "blue", "s2")
    private val review = SelectOption("In review", "purple", "s3")
    private val done = SelectOption("Done", "green", "s4")
    private val high = SelectOption("High", "red", "p1")
    private val low = SelectOption("Low", "gray", "p2")
    private val work = SelectOption("Work", "blue", "t1")
    private val home = SelectOption("Home", "green", "t2")

    private val schema = DatabaseSchema(
        "ds", "Tasks",
        listOf(
            PropertySchema("title", "Name", "title"),
            PropertySchema("pri", "Priority", "select", options = listOf(high, low)),
            // Notion lists Done before In review here; the groups decide the order.
            PropertySchema(
                "st", "Status", "status",
                options = listOf(todo, doing, done, review),
                groups = listOf(
                    StatusGroup("To-do", "gray", listOf("s1")),
                    StatusGroup("In progress", "blue", listOf("s2", "s3")),
                    StatusGroup("Complete", "green", listOf("s4")),
                ),
            ),
            PropertySchema("tg", "Tags", "multi_select", options = listOf(work, home)),
            PropertySchema("ck", "Done", "checkbox"),
            PropertySchema("who", "Owner", "people"),
            PropertySchema("due", "Due", "date"),
            PropertySchema("txt", "Notes", "rich_text"),
        ),
    )

    private fun note(id: String, vararg props: Pair<String, PropertyValue>) = NoteSummary(
        id = id,
        databaseId = "ds",
        title = id,
        properties = props.map { (name, value) -> NoteProperty(name, schema.property(name)!!.type, value) },
    )

    private fun select(option: SelectOption?) = PropertyValue.Select(option)
    private fun ids(sections: List<NoteSection>?) = sections!!.map { it.title to it.notes.map { n -> n.id } }
    private fun board(groupBy: ViewGroupBy, vararg notes: NoteSummary) = ViewSections.board(groupBy, schema, notes.toList())

    private val today = LocalDate.of(2026, 11, 21)
    private val brussels = ZoneId.of("Europe/Brussels")

    @Test
    fun `a select board has a section per option in the property's order, empty values last`() {
        val sections = board(
            ViewGroupBy("select", "pri"),
            note("a", "Priority" to select(low)),
            note("b", "Priority" to select(null)),
            note("c", "Priority" to select(high)),
            note("d", "Priority" to select(low)),
        )
        assertEquals(
            listOf(
                SectionTitle.Option("High", "red") to listOf("c"),
                SectionTitle.Option("Low", "gray") to listOf("a", "d"),
                SectionTitle.Empty("Priority") to listOf("b"),
            ),
            ids(sections),
        )
    }

    @Test
    fun `groups without notes stay unless the view hides them, and no empty section without empty notes`() {
        val notes = arrayOf(note("a", "Priority" to select(low)))
        assertEquals(listOf(SectionTitle.Option("High", "red") to emptyList(), SectionTitle.Option("Low", "gray") to listOf("a")), ids(board(ViewGroupBy("select", "pri"), *notes)))
        assertEquals(listOf(SectionTitle.Option("Low", "gray") to listOf("a")), ids(board(ViewGroupBy("select", "pri", hideEmpty = true), *notes)))
    }

    @Test
    fun `descending reverses the groups, the empty section stays last`() {
        val sections = board(ViewGroupBy("select", "pri", sort = "descending"), note("a", "Priority" to select(high)), note("b"))
        assertEquals(listOf("Low", "High", null), sections!!.map { (it.title as? SectionTitle.Option)?.name })
    }

    @Test
    fun `a status by option follows its groups' order`() {
        val sections = board(ViewGroupBy("status", "st", by = "option", hideEmpty = false), note("a", "Status" to select(review)))
        assertEquals(listOf("Not started", "In progress", "In review", "Done"), sections!!.map { (it.title as SectionTitle.Option).name })
    }

    @Test
    fun `a status by group has a section per group with the options in it`() {
        val sections = board(
            ViewGroupBy("status", "st", by = "group"),
            note("a", "Status" to select(review)),
            note("b", "Status" to select(done)),
            note("c", "Status" to select(doing)),
            note("d", "Status" to select(SelectOption("In review"))), // no ID: found by name
        )
        assertEquals(
            listOf(
                SectionTitle.Option("To-do", "gray") to emptyList(),
                SectionTitle.Option("In progress", "blue") to listOf("a", "c", "d"),
                SectionTitle.Option("Complete", "green") to listOf("b"),
            ),
            ids(sections),
        )
    }

    @Test
    fun `a note with several options is in each of their sections, with unique keys`() {
        val sections = board(
            ViewGroupBy("multi_select", "tg"),
            note("a", "Tags" to PropertyValue.MultiSelect(listOf(home, work))),
            note("b", "Tags" to PropertyValue.MultiSelect(emptyList())),
            note("c", "Tags" to PropertyValue.MultiSelect(listOf(SelectOption("Later", "pink", "t9")))),
        )
        assertEquals(
            listOf(
                SectionTitle.Option("Work", "blue") to listOf("a"),
                SectionTitle.Option("Home", "green") to listOf("a"),
                // An option the schema doesn't know yet comes after the known ones.
                SectionTitle.Option("Later", "pink") to listOf("c"),
                SectionTitle.Empty("Tags") to listOf("b"),
            ),
            ids(sections),
        )
        assertEquals(sections!!.size, sections.map { it.key }.toSet().size)
    }

    @Test
    fun `a checkbox board shows unchecked then checked`() {
        val sections = board(ViewGroupBy("checkbox", "ck"), note("a", "Done" to PropertyValue.Checkbox(true)), note("b"))
        assertEquals(listOf(SectionTitle.Checkbox(false) to listOf("b"), SectionTitle.Checkbox(true) to listOf("a")), ids(sections))
    }

    @Test
    fun `a people board has a section per person`() {
        val ann = NotionUser("u1", "Ann")
        val bob = NotionUser("u2", "Bob")
        val sections = board(
            ViewGroupBy("person", "who", sort = "descending"),
            note("a", "Owner" to PropertyValue.People(listOf(ann, bob))),
            note("b", "Owner" to PropertyValue.People(emptyList())),
        )
        assertEquals(
            listOf(SectionTitle.Option("Bob") to listOf("a"), SectionTitle.Option("Ann") to listOf("a"), SectionTitle.Empty("Owner") to listOf("b")),
            ids(sections),
        )
    }

    @Test
    fun `a property that is gone or can't be grouped shows one list`() {
        assertNull(board(ViewGroupBy("select", "gone")))
        assertNull(board(ViewGroupBy("text", "txt")))
    }

    @Test
    fun `a view shows sections only when it groups or is a calendar with a date`() {
        val notes = listOf(note("a"))
        assertNull(ViewSections.of(NotionView("v", type = "table"), schema, notes, today, brussels))
        assertNull(ViewSections.of(NotionView("v", type = "board", groupBy = ViewGroupBy("select", "pri")), null, notes, today, brussels))
        assertEquals(3, ViewSections.of(NotionView("v", type = "board", groupBy = ViewGroupBy("select", "pri")), schema, notes, today, brussels)!!.size)
        assertEquals(listOf(SectionTitle.NoDate), ViewSections.of(NotionView("v", type = "calendar", datePropertyId = "due"), schema, notes, today, brussels)!!.map { it.title })
        // A date property on a board view isn't an agenda.
        assertNull(ViewSections.of(NotionView("v", type = "board", datePropertyId = "due"), schema, notes, today, brussels))
    }

    private fun due(start: String?) = "Due" to PropertyValue.Date(start)

    @Test
    fun `an agenda has a section per day in order, each day by time, undated last`() {
        val sections = ViewSections.agenda(
            "due", schema,
            listOf(
                note("later", due("2026-11-22T15:00:00.000+01:00")),
                note("none", due(null)),
                note("allday", due("2026-11-22")),
                note("early", due("2026-11-22T08:00:00.000+01:00")),
                note("today", due("2026-11-21")),
                note("past", due("2026-11-01")),
                note("missing"),
            ),
            today, brussels,
        )
        assertEquals(
            listOf(
                SectionTitle.Day(LocalDate.of(2026, 11, 1), -20) to listOf("past"),
                SectionTitle.Day(today, 0) to listOf("today"),
                SectionTitle.Day(LocalDate.of(2026, 11, 22), 1) to listOf("allday", "early", "later"),
                SectionTitle.NoDate to listOf("none", "missing"),
            ),
            ids(sections),
        )
        assertEquals(1, ViewSections.firstUpcoming(sections!!))
    }

    @Test
    fun `a time counts on the day it is on this phone`() {
        // 23:30 UTC is already the next day in Brussels, still the same day in New York.
        val notes = listOf(note("a", due("2026-11-21T23:30:00.000+00:00")))
        assertEquals(LocalDate.of(2026, 11, 22), (ViewSections.agenda("due", schema, notes, today, brussels)!!.single().title as SectionTitle.Day).date)
        assertEquals(LocalDate.of(2026, 11, 21), (ViewSections.agenda("due", schema, notes, today, ZoneId.of("America/New_York"))!!.single().title as SectionTitle.Day).date)
    }

    @Test
    fun `times across zones are ordered by the moment`() {
        val sections = ViewSections.agenda(
            "due", schema,
            listOf(note("b", due("2026-11-21T10:00:00.000+01:00")), note("a", due("2026-11-21T08:30:00.000+00:00"))),
            today, brussels,
        )
        // 10:00 in Brussels is 09:00 UTC, after 08:30 UTC.
        assertEquals(listOf("a", "b"), sections!!.single().notes.map { it.id })
    }

    @Test
    fun `an agenda by created time, and nothing upcoming`() {
        val schemaWithCreated = schema.copy(properties = schema.properties + PropertySchema("ct", "Created", "created_time"))
        val notes = listOf(NoteSummary("a", "ds", "a", createdTime = "2026-11-02T09:00:00.000Z"))
        val sections = ViewSections.agenda("ct", schemaWithCreated, notes, today, brussels)!!
        assertEquals(SectionTitle.Day(LocalDate.of(2026, 11, 2), -19), sections.single().title)
        assertNull(ViewSections.firstUpcoming(sections))
        assertNull(ViewSections.agenda("txt", schema, notes, today, brussels))
    }
}
