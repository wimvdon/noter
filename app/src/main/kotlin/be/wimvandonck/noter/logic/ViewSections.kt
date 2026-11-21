package be.wimvandonck.noter.logic

import be.wimvandonck.noter.data.model.DatabaseSchema
import be.wimvandonck.noter.data.model.NoteSummary
import be.wimvandonck.noter.data.model.NotionView
import be.wimvandonck.noter.data.model.PropertySchema
import be.wimvandonck.noter.data.model.PropertyValue
import be.wimvandonck.noter.data.model.SelectOption
import be.wimvandonck.noter.data.model.ViewGroupBy
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** What a section of a view's notes is about, for its header. */
sealed interface SectionTitle {
    /** An option of a select, multi-select or status, a status group, or a person (no colour). */
    data class Option(val name: String, val color: String = "default") : SectionTitle

    /** The notes without a value ("No [property]"). */
    data class Empty(val property: String) : SectionTitle

    /** A checkbox's notes that are checked, or not. */
    data class Checkbox(val checked: Boolean) : SectionTitle

    /** A day of an agenda; [daysFromToday]: 0 today, 1 tomorrow, -1 yesterday. */
    data class Day(val date: LocalDate, val daysFromToday: Long) : SectionTitle

    /** An agenda's notes without a date (last). */
    data object NoDate : SectionTitle
}

/** A section of a view's notes: a header ([title], the number of [notes]) and the notes under it. */
data class NoteSection(val key: String, val title: SectionTitle, val notes: List<NoteSummary>)

/**
 * A Notion view's notes in sections, as Notion shows them on a bigger screen: a board (or a grouped
 * table or list) as one section per group, a calendar or timeline as an agenda of days. A phone
 * shows them one below the other. Read from the view's configuration; nothing is changed in Notion.
 */
object ViewSections {
    /** The kinds of property whose groups can be shown (others show the notes as one list). */
    val GROUPED_TYPES = setOf("select", "status", "multi_select", "checkbox", "people")

    /**
     * [notes] (in the view's order) in the sections [view] shows, or null when it shows them as one
     * list: no grouping or date, or a property that the data source no longer has or that can't
     * be grouped here. [today] and [zone] place an agenda's dates.
     */
    fun of(view: NotionView?, schema: DatabaseSchema?, notes: List<NoteSummary>, today: LocalDate, zone: ZoneId): List<NoteSection>? {
        view ?: return null
        schema ?: return null
        if (view.type in AGENDA_TYPES) view.datePropertyId?.let { return agenda(it, schema, notes, today, zone) }
        return view.groupBy?.let { board(it, schema, notes) }
    }

    /** The index in [sections] of the first day from [SectionTitle.Day.daysFromToday] 0 on (null: none), to open an agenda there. */
    fun firstUpcoming(sections: List<NoteSection>): Int? =
        sections.indexOfFirst { (it.title as? SectionTitle.Day)?.daysFromToday?.let { days -> days >= 0 } == true }.takeIf { it >= 0 }

    /**
     * One section per group of [groupBy]'s property, in the order of its options (a status by
     * group: in the order of its groups), reversed when sorted descending; notes without a value
     * last, under "No …", when there are any. A note with several options or people is in each of
     * their sections, as in Notion. Groups without notes stay, unless the view hides them.
     */
    fun board(groupBy: ViewGroupBy, schema: DatabaseSchema, notes: List<NoteSummary>): List<NoteSection>? {
        val property = find(schema, groupBy.propertyId) ?: return null
        if (property.type !in GROUPED_TYPES) return null
        val descending = groupBy.sort == "descending"
        val sections = when (property.type) {
            "checkbox" -> checkbox(property, notes, descending)
            "people" -> people(property, notes, groupBy.sort)
            "status" -> if (groupBy.by == "group" && property.groups.isNotEmpty()) statusGroups(property, notes, descending) else options(property, notes, descending)
            else -> options(property, notes, descending)
        }
        return if (groupBy.hideEmpty) sections.filter { it.notes.isNotEmpty() } else sections
    }

    private fun options(property: PropertySchema, notes: List<NoteSummary>, descending: Boolean): List<NoteSection> {
        val known = if (property.type == "status") statusOrder(property) else property.options
        val chosen = notes.associateWith { optionsOf(it, property.name) }
        // Options a note has that the schema doesn't know (yet) come after the known ones.
        val extra = chosen.values.flatten().filter { option -> known.none { same(it, option) } }.distinctBy { it.id ?: it.name }
        val order = (known + extra).let { if (descending) it.reversed() else it }
        val sections = order.map { option ->
            NoteSection("o:" + (option.id ?: option.name), SectionTitle.Option(option.name, option.color), notes.filter { note -> chosen.getValue(note).any { same(it, option) } })
        }
        return sections + empty(property, notes.filter { chosen.getValue(it).isEmpty() })
    }

    /** A status's options in the order of its groups (each group's options in Notion's order). */
    private fun statusOrder(property: PropertySchema): List<SelectOption> {
        if (property.groups.isEmpty()) return property.options
        return property.options.sortedBy { option ->
            property.groups.indexOfFirst { option.id != null && option.id in it.optionIds }.let { if (it < 0) Int.MAX_VALUE else it }
        }
    }

    private fun statusGroups(property: PropertySchema, notes: List<NoteSummary>, descending: Boolean): List<NoteSection> {
        val groups = if (descending) property.groups.reversed() else property.groups
        val groupOf = notes.associateWith { note ->
            val option = optionsOf(note, property.name).firstOrNull() ?: return@associateWith null
            val id = option.id ?: property.options.firstOrNull { it.name == option.name }?.id
            groups.firstOrNull { id != null && id in it.optionIds }
        }
        val sections = groups.map { group ->
            NoteSection("g:" + group.name, SectionTitle.Option(group.name, group.color), notes.filter { groupOf[it] == group })
        }
        return sections + empty(property, notes.filter { groupOf[it] == null })
    }

    private fun checkbox(property: PropertySchema, notes: List<NoteSummary>, descending: Boolean): List<NoteSection> {
        val checked = notes.associateWith { (it.property(property.name)?.value as? PropertyValue.Checkbox)?.checked == true }
        val order = if (descending) listOf(true, false) else listOf(false, true)
        return order.map { value -> NoteSection("c:$value", SectionTitle.Checkbox(value), notes.filter { checked.getValue(it) == value }) }
    }

    private fun people(property: PropertySchema, notes: List<NoteSummary>, sort: String): List<NoteSection> {
        val chosen = notes.associateWith { (it.property(property.name)?.value as? PropertyValue.People)?.users.orEmpty() }
        val users = chosen.values.flatten().distinctBy { it.id }.let { all ->
            when (sort) {
                "ascending" -> all.sortedBy { it.name.lowercase() }
                "descending" -> all.sortedByDescending { it.name.lowercase() }
                else -> all
            }
        }
        val sections = users.map { user ->
            NoteSection("p:" + user.id, SectionTitle.Option(user.name), notes.filter { note -> chosen.getValue(note).any { it.id == user.id } })
        }
        return sections + empty(property, notes.filter { chosen.getValue(it).isEmpty() })
    }

    private fun empty(property: PropertySchema, notes: List<NoteSummary>): List<NoteSection> =
        if (notes.isEmpty()) emptyList() else listOf(NoteSection("none", SectionTitle.Empty(property.name), notes))

    /**
     * An agenda: one section per day of the date property [propertyId] (a date, or when the note
     * was made or last edited), days in order, each day's notes by their date (a whole day before
     * a time), and the notes without a date last. A date with a time counts on the day it is in
     * [zone]. Null when the data source has no such property.
     */
    fun agenda(propertyId: String, schema: DatabaseSchema, notes: List<NoteSummary>, today: LocalDate, zone: ZoneId): List<NoteSection>? {
        val property = find(schema, propertyId) ?: return null
        val start: (NoteSummary) -> String? = when (property.type) {
            "date" -> { note -> (note.property(property.name)?.value as? PropertyValue.Date)?.start }
            "created_time" -> { note -> note.createdTime.ifBlank { null } }
            "last_edited_time" -> { note -> note.lastEditedTime.ifBlank { null } }
            else -> return null
        }
        val placed = notes.map { note -> note to start(note)?.let { place(it, zone) } }
        val days = placed.filter { it.second != null }
            .sortedWith(compareBy({ it.second!!.date }, { it.second!!.moment != null }, { it.second!!.moment }))
            .groupBy { it.second!!.date }
            .map { (date, dated) -> NoteSection("d:$date", SectionTitle.Day(date, ChronoUnit.DAYS.between(today, date)), dated.map { it.first }) }
        val undated = placed.filter { it.second == null }.map { it.first }
        return if (undated.isEmpty()) days else days + NoteSection("nodate", SectionTitle.NoDate, undated)
    }

    /** Where a date goes: its day in [zone], and its moment when it has a time. */
    private data class Placed(val date: LocalDate, val moment: Long?)

    private fun place(iso: String, zone: ZoneId): Placed? {
        val date = NotionDates.parseDate(iso, zone) ?: return null
        val moment = if (NotionDates.hasTime(iso)) runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull() else null
        return Placed(date, moment)
    }

    private fun optionsOf(note: NoteSummary, name: String): List<SelectOption> = when (val value = note.property(name)?.value) {
        is PropertyValue.Select -> listOfNotNull(value.option)
        is PropertyValue.MultiSelect -> value.options
        else -> emptyList()
    }

    private fun same(a: SelectOption, b: SelectOption): Boolean = if (a.id != null && b.id != null) a.id == b.id else a.name == b.name

    /** The property [ref] names (an ID, as a view's configuration does, or a name). */
    private fun find(schema: DatabaseSchema, ref: String): PropertySchema? =
        schema.properties.firstOrNull { it.id == ref } ?: schema.properties.firstOrNull { decoded(it.id) == decoded(ref) } ?: schema.properties.firstOrNull { it.name == ref }

    private fun decoded(id: String): String = runCatching { java.net.URLDecoder.decode(id, "UTF-8") }.getOrDefault(id)

    private val AGENDA_TYPES = setOf("calendar", "timeline")
}
