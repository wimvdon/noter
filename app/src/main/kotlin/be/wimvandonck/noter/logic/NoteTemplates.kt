package be.wimvandonck.noter.logic

import be.wimvandonck.noter.data.model.Block
import be.wimvandonck.noter.data.model.NoteProperty
import be.wimvandonck.noter.data.model.NoteTemplate
import be.wimvandonck.noter.data.model.PageTemplate
import be.wimvandonck.noter.data.model.PropertyValue

/** What the user asked for when making a new note in a data source. */
sealed interface TemplatePick {
    /** The data source's default template when it has one, else an empty note (what + and the plus button do). */
    data object Auto : TemplatePick

    /** An empty note, whatever templates there are. */
    data object Empty : TemplatePick

    /** This template. */
    data class Specific(val id: String) : TemplatePick
}

/** Page templates (Notion's "New from template"): who gets one, and the request that applies it. */
object NoteTemplates {
    /** The template a new note is made from, or null for an empty one. */
    fun choose(pick: TemplatePick, templates: List<PageTemplate>, timezone: String?): NoteTemplate? = when (pick) {
        TemplatePick.Empty -> null
        TemplatePick.Auto -> templates.firstOrNull { it.isDefault }?.let { NoteTemplate(null, timezone) }
        is TemplatePick.Specific -> templates.firstOrNull { it.id == pick.id }?.let { NoteTemplate(it.id, timezone) }
    }

    /** The `template` field of Notion's create-page request. */
    fun request(template: NoteTemplate): Map<String, Any?> = buildMap {
        if (template.id == null) {
            put("type", "default")
        } else {
            put("type", "template_id")
            put("template_id", template.id)
        }
        if (!template.timezone.isNullOrBlank()) put("timezone", template.timezone)
    }

    /**
     * The properties to send with a note made from a template: only what the user filled in. An
     * empty title is left out, so the template's own title isn't overwritten.
     */
    fun createProperties(properties: List<NoteProperty>): List<NoteProperty> =
        properties.filterNot { it.type == "title" && (it.value as? PropertyValue.Text)?.markdown.isNullOrBlank() }

    /**
     * The content of a note made from a template once Notion has applied it ([template]), with
     * what was typed meanwhile ([typed]) after it. Typed lines that are still empty are dropped,
     * the others are new blocks. A line typed on top of one that was already merged (same key)
     * stays as typed.
     */
    fun mergeTyped(template: List<Block>, typed: List<Block>): List<Block> {
        val typedByKey = typed.associateBy { it.key }
        val templateKeys = template.map { it.key }.toSet()
        val kept = template.map { block -> typedByKey[block.key]?.takeIf { it.notionId == null } ?: block }
        val added = typed.filter { it.key !in templateKeys && it.text.isNotBlank() }
            .map { it.copy(notionId = null, parentId = null) }
        return kept + added
    }
}
