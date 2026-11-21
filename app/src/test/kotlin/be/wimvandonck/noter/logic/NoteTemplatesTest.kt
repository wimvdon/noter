package be.wimvandonck.noter.logic

import be.wimvandonck.noter.data.model.Block
import be.wimvandonck.noter.data.model.NoteProperty
import be.wimvandonck.noter.data.model.NoteTemplate
import be.wimvandonck.noter.data.model.PageTemplate
import be.wimvandonck.noter.data.model.PropertyValue
import be.wimvandonck.noter.data.notion.PropertyCodec
import be.wimvandonck.noter.data.sync.PendingOp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteTemplatesTest {
    private val weekly = PageTemplate("t-weekly", "Weekly", isDefault = true)
    private val trip = PageTemplate("t-trip", "Trip", isDefault = false)

    @Test
    fun `a new note gets the default template, when there is one`() {
        assertEquals(NoteTemplate(null, "Europe/Brussels"), NoteTemplates.choose(TemplatePick.Auto, listOf(trip, weekly), "Europe/Brussels"))
        assertNull(NoteTemplates.choose(TemplatePick.Auto, listOf(trip), "Europe/Brussels"))
        assertNull(NoteTemplates.choose(TemplatePick.Auto, emptyList(), "Europe/Brussels"))
    }

    @Test
    fun `an empty note never gets a template, a chosen one does`() {
        assertNull(NoteTemplates.choose(TemplatePick.Empty, listOf(weekly), "UTC"))
        assertEquals(NoteTemplate("t-trip", "UTC"), NoteTemplates.choose(TemplatePick.Specific("t-trip"), listOf(weekly, trip), "UTC"))
        // A template that is gone from Notion's list: an empty note.
        assertNull(NoteTemplates.choose(TemplatePick.Specific("gone"), listOf(weekly), "UTC"))
    }

    @Test
    fun `the request names the template and the time zone`() {
        assertEquals(mapOf("type" to "default", "timezone" to "Europe/Brussels"), NoteTemplates.request(NoteTemplate(null, "Europe/Brussels")))
        assertEquals(
            mapOf("type" to "template_id", "template_id" to "t-trip", "timezone" to "UTC"),
            NoteTemplates.request(NoteTemplate("t-trip", "UTC")),
        )
        assertEquals(mapOf("type" to "default"), NoteTemplates.request(NoteTemplate(null, null)))
    }

    @Test
    fun `an empty title is not sent with a template, a typed one is`() {
        val empty = NoteProperty("Name", "title", PropertyValue.Text(""))
        val typed = NoteProperty("Name", "title", PropertyValue.Text("Monday"))
        val tags = NoteProperty("Tags", "multi_select", PropertyValue.MultiSelect(emptyList()))
        assertEquals(listOf(tags), NoteTemplates.createProperties(listOf(empty, tags)))
        assertEquals(listOf(typed, tags), NoteTemplates.createProperties(listOf(typed, tags)))
    }

    @Test
    fun `lines typed before the template arrived go after its content`() {
        val template = listOf(Block("a", notionId = "n1", text = "Agenda"), Block("b", notionId = "n2", text = "Notes"))
        val typed = listOf(Block("x", text = "Call Sam"), Block("y", text = ""))
        val merged = NoteTemplates.mergeTyped(template, typed)
        assertEquals(listOf("Agenda", "Notes", "Call Sam"), merged.map { it.text })
        assertNull(merged.last().notionId)
    }

    @Test
    fun `nothing typed means the template's content as it is`() {
        val template = listOf(Block("a", notionId = "n1", text = "Agenda"))
        assertEquals(template, NoteTemplates.mergeTyped(template, listOf(Block("x", text = ""))))
    }

    @Test
    fun `a line already merged is not added twice`() {
        val typed = Block("x", text = "Call Sam")
        val template = listOf(Block("a", notionId = "n1", text = "Agenda"), typed)
        assertEquals(template, NoteTemplates.mergeTyped(template, listOf(typed)))
        // Typed on after it was merged: the newer text stays.
        val edited = typed.copy(text = "Call Sam today")
        assertEquals(listOf("Agenda", "Call Sam today"), NoteTemplates.mergeTyped(template, listOf(edited)).map { it.text })
    }

    @Test
    fun `Notion's list of templates is read with the default first`() {
        val json = Json.parseToJsonElement(
            """{"templates":[{"id":"t-trip","name":"Trip","is_default":false},{"id":"t-weekly","name":"Weekly","is_default":true},{"name":"no id"}]}""",
        ).jsonObject
        val list = json["templates"]!!.let { (it as kotlinx.serialization.json.JsonArray).map { e -> e.jsonObject } }
        assertEquals(listOf(weekly, trip), PropertyCodec.parseTemplates(list))
    }

    @Test
    fun `the template of a queued note survives being stored`() {
        val op: PendingOp = PendingOp.CreatePage("local-1", "db", template = NoteTemplate("t-trip", "UTC"))
        val json = Json { classDiscriminator = "kind" }
        assertEquals(op, json.decodeFromString(PendingOp.serializer(), json.encodeToString(PendingOp.serializer(), op)))
        // A queue stored before templates existed still reads.
        val old = """{"kind":"create","pageId":"local-1","databaseId":"db"}"""
        assertEquals(PendingOp.CreatePage("local-1", "db"), json.decodeFromString(PendingOp.serializer(), old))
    }
}
