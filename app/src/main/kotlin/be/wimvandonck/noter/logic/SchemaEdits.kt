package be.wimvandonck.noter.logic

import be.wimvandonck.noter.data.model.DatabaseSetup
import be.wimvandonck.noter.data.model.PageRef
import be.wimvandonck.noter.data.model.WorkspaceItem
import be.wimvandonck.noter.data.model.PropertyLayout
import be.wimvandonck.noter.data.model.PropertySchema
import be.wimvandonck.noter.data.model.SelectOption
import kotlinx.serialization.Serializable

/**
 * A property in Edit database: one the data source has ([id], its Notion [type]) or one being
 * added ([id] null, [kind] set). [name] is what the user typed. An existing property can get
 * another type ([changedTo], Change type). [settings]: what a new or changed kind needs (a
 * formula, a relation's database, …), and an existing number's format.
 */
@Serializable
data class EditedProperty(
    val id: String?,
    val name: String,
    val type: String,
    val kind: PropertyKind? = null,
    /** The options of a select, multi-select or status the data source has, as edited (null: not one of those, or a new property). */
    val options: List<SelectOption>? = null,
    val changedTo: PropertyKind? = null,
    val settings: PropertySettings = PropertySettings(),
) {
    /** The title property can be renamed, not removed (Notion doesn't allow it). */
    val removable: Boolean get() = type != "title"

    /** The kind it is made or changed to (null: an existing property keeping its type). */
    val newKind: PropertyKind? get() = changedTo ?: kind

    /** Its Notion type after saving. */
    val currentType: String get() = newKind?.type ?: type

    /** Whether Notion has everything it needs for the new or changed kind. */
    val isComplete: Boolean get() = newKind?.let { PropertyConfigs.isComplete(it, settings) } ?: true

    /** Whether its options can be edited: an existing select, multi-select or status keeping its type. */
    val editsOptions: Boolean get() = id != null && changedTo == null && options != null

    /** Whether it has a small form under its name: a kind with settings, or an existing number (its format). */
    val hasSettings: Boolean get() = newKind?.let { it in PropertyConfigs.HAS_SETTINGS } ?: (type == "number")
}

/**
 * What Edit database changes in the data source's properties: Notion's `PATCH /data_sources`
 * [properties] (empty: nothing), and the names that changed ([renamed], old to new) or went
 * ([removed]), for what this phone keeps about them by name.
 */
data class SchemaChange(
    val properties: Map<String, Any?>,
    val renamed: Map<String, String>,
    val removed: Set<String>,
) {
    val isEmpty: Boolean get() = properties.isEmpty()

    /** A database's cards (and its first chip row from before) with the renamed properties' new names, without the removed ones. */
    fun applyTo(setup: DatabaseSetup): DatabaseSetup = setup.copy(
        quickFilters = names(setup.quickFilters),
        cards = setup.cards?.let { it.copy(properties = names(it.properties)) },
    )

    /** How the note shows its properties (order, hidden ones), likewise. */
    fun applyTo(layout: PropertyLayout): PropertyLayout = layout.copy(
        order = names(layout.order),
        hidden = names(layout.hidden),
        onCard = names(layout.onCard),
    )

    private fun rename(name: String) = renamed[name] ?: name

    private fun names(list: List<String>) = list.filter { it !in removed }.map(::rename)
}

object SchemaEdits {
    /** The data source's properties as Edit database starts: the title property first, then Notion's order. */
    fun start(properties: List<PropertySchema>): List<EditedProperty> =
        properties.sortedBy { it.type != "title" }.map {
            EditedProperty(
                it.id, it.name, it.type,
                options = it.options.takeIf { _ -> it.type in OPTION_TYPES },
                settings = PropertySettings(numberFormat = it.numberFormat ?: PropertyConfigs.DEFAULT_NUMBER_FORMAT),
            )
        }

    /** The kinds Add property offers in Edit database (a rollup needs a relation that is saved, see [rollupRelations]). */
    fun addableKinds(original: List<PropertySchema>, edited: List<EditedProperty>): List<PropertyKind> =
        PropertyConfigs.addable(rollupRelations(original, edited).isNotEmpty(), edited.any { it.currentType == "unique_id" })

    /** The kinds [property] can be changed to (Change type; see [PropertyConfigs.typeChanges]). */
    fun typeChanges(property: EditedProperty, original: List<PropertySchema>, edited: List<EditedProperty>): List<PropertyKind> {
        if (property.id == null) return emptyList()
        val otherId = edited.any { it !== property && it.currentType == "unique_id" }
        return PropertyConfigs.typeChanges(property.type, rollupRelations(original, edited).any { it.id != property.id }, otherId)
    }

    /** The relations a new rollup can use: the data source's own, saved and kept (with the data source they link to). */
    fun rollupRelations(original: List<PropertySchema>, edited: List<EditedProperty>): List<PropertySchema> =
        original.filter { o ->
            o.type == "relation" && o.relationDatabaseId != null && edited.any { it.id == o.id && it.changedTo == null }
        }

    /**
     * The data sources a relation can link to: the databases in Home's list ([items]), one row per
     * data source ("Database · Source" for a database of several), in [items]' order. With a
     * [workspace], only the ones [owners] (workspace by ID without dashes) doesn't place elsewhere.
     */
    fun relationTargets(items: List<WorkspaceItem>, owners: Map<String, String>, workspace: String?): List<PageRef> {
        return items.filter { it.isDatabase }.flatMap { item ->
            if (item.sources.size > 1) item.sources.map { PageRef(it.id, "${item.title} · ${it.title}") } else listOf(PageRef(item.sources.singleOrNull()?.id ?: item.id, item.title))
        }.filter { target ->
            workspace == null || owners[target.id.replace("-", "")].let { it == null || it == workspace }
        }.distinctBy { it.id.replace("-", "") }
    }

    /** [property] changed to [kind] ([kind] its own type again: back as it was). */
    fun changeType(property: EditedProperty, kind: PropertyKind): EditedProperty =
        property.copy(changedTo = kind.takeIf { it.type != property.type })

    /** The names of the properties that are not ready to save (a formula without its expression, a relation without its database, …). */
    fun incomplete(edited: List<EditedProperty>): List<String> = edited.filterNot { it.isComplete }.map { it.name }

    /** The names of the existing properties that get another type, for asking before their values may be lost. */
    fun changedTypes(original: List<PropertySchema>, edited: List<EditedProperty>): List<String> {
        val byId = original.associateBy { it.id }
        return edited.filter { p -> p.id != null && p.changedTo != null && byId[p.id]?.type != p.changedTo.type }.mapNotNull { byId[it.id]?.name }
    }

    val OPTION_TYPES = setOf("select", "multi_select", "status")

    /** The groups of a status option, in Notion's order. */
    val STATUS_GROUPS = listOf("To-do", "In progress", "Complete")

    /** [options] with a new option [name] at the end (nothing when blank or when an option has that name, whatever its case). */
    fun addOption(options: List<SelectOption>, name: String, color: String = "default", group: String? = null): List<SelectOption> {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || options.any { it.name.trim().equals(trimmed, ignoreCase = true) }) return options
        return options + SelectOption(trimmed, color, group = group)
    }

    /** The names of the options [edited] has lost, as "Property: option", for asking before the notes lose them. */
    fun removedOptions(original: List<PropertySchema>, edited: List<EditedProperty>): List<String> {
        val byId = edited.filter { it.id != null }.associateBy { it.id }
        return original.flatMap { old ->
            // A property changing type is asked about as a whole (see changedTypes).
            val now = byId[old.id]?.takeIf { it.changedTo == null }?.options ?: return@flatMap emptyList()
            old.options.filter { o -> now.none { it.id == o.id && o.id != null || it.id == null && it.name.equals(o.name, ignoreCase = true) } }
                .map { "${old.name}: ${it.name}" }
        }
    }

    /** The options as Notion's update takes them: the whole list to keep (see [optionsBody]), new ones last, blank and repeated names dropped. */
    private fun cleanOptions(options: List<SelectOption>): List<SelectOption> {
        val seen = mutableSetOf<String>()
        options.filter { it.id != null }.forEach { seen += it.name.trim().lowercase() }
        val fresh = options.filter { it.id == null }.mapNotNull { option ->
            val name = option.name.trim()
            if (name.isEmpty() || !seen.add(name.lowercase())) null else option.copy(name = name)
        }
        return options.filter { it.id != null } + fresh
    }

    /** What the property's options are in the update: existing ones by ID (a group only when it changed), new ones with their name. */
    private fun optionsBody(old: PropertySchema, options: List<SelectOption>): List<Map<String, Any?>> {
        val before = old.options.associateBy { it.id }
        return cleanOptions(options).map { option ->
            buildMap {
                if (option.id != null) put("id", option.id)
                put("name", option.name)
                put("color", option.color)
                val group = option.group
                if (old.type == "status" && group != null && (option.id == null || before[option.id]?.group != group)) put("group", group)
            }
        }
    }

    private fun optionsChanged(old: PropertySchema, options: List<SelectOption>): Boolean {
        val clean = cleanOptions(options)
        val before = old.options
        return clean.size != before.size || clean.zip(before).any { (a, b) ->
            a.id != b.id || a.name != b.name || a.color != b.color || (old.type == "status" && a.group != null && a.group != b.group)
        }
    }

    /** [list] with a new property of [kind] at the end, named [defaultName] (numbered when taken, as Notion does). */
    fun add(list: List<EditedProperty>, kind: PropertyKind, defaultName: String): List<EditedProperty> =
        list + EditedProperty(null, NewDatabases.uniqueName(defaultName, list.map { it.name }), kind.type, kind)

    /**
     * What turns [original] into [edited]. A property left out is removed, a new name renames it
     * (a blank one keeps the old), a property without an ID is added (a blank name becomes
     * [fallbackNames]' name for its kind). Names stay unique: one that clashes gets a number.
     * An existing property keeps its ID: a new type is sent as that type's settings under it
     * (`{"<id>": {"select": {}}}`), a number's new format as `{"number": {"format": …}}`.
     */
    fun change(original: List<PropertySchema>, edited: List<EditedProperty>, fallbackNames: Map<PropertyKind, String> = emptyMap()): SchemaChange {
        val byId = original.associateBy { it.id }
        val kept = edited.filter { it.id != null && it.id in byId }
        val removed = original.filter { o -> kept.none { it.id == o.id } }
        // Unchanged names are taken first, so a renamed or new property never pushes them aside.
        val wanted = edited.map { property ->
            val old = property.id?.let(byId::get)
            property to when {
                old != null -> property.name.trim().ifBlank { old.name }
                else -> property.name.trim().ifBlank { property.kind?.let { fallbackNames[it] }.orEmpty().ifBlank { property.type } }
            }
        }
        val taken = wanted.filter { (p, name) -> p.id != null && byId[p.id]?.name == name }.map { it.second }.toMutableList()
        val body = linkedMapOf<String, Any?>()
        val renamed = linkedMapOf<String, String>()
        removed.forEach { body[it.id] = null }
        wanted.forEach { (property, name) ->
            val old = property.id?.let(byId::get)
            val newType = property.changedTo?.takeIf { old != null && it.type != old.type }
            val options = property.options?.takeIf { newType == null && old != null && old.type in OPTION_TYPES && optionsChanged(old, it) }
            val format = property.settings.numberFormat.takeIf {
                newType == null && old?.type == "number" && it.isNotBlank() && it != (old.numberFormat ?: PropertyConfigs.DEFAULT_NUMBER_FORMAT)
            }
            if (old != null && old.name == name && options == null && newType == null && format == null) return@forEach
            val final = if (old != null && old.name == name) name else NewDatabases.uniqueName(name, taken).also { taken += it }
            when {
                old != null -> {
                    val entry = linkedMapOf<String, Any?>()
                    if (final != old.name) {
                        entry["name"] = final
                        renamed[old.name] = final
                    }
                    when {
                        newType != null -> entry[newType.type] = PropertyConfigs.config(newType, property.settings)
                        options != null -> entry[old.type] = mapOf("options" to optionsBody(old, options))
                        format != null -> entry["number"] = mapOf("format" to format)
                    }
                    if (entry.isNotEmpty()) body[old.id] = entry
                }
                property.kind != null -> body[final] = mapOf(property.kind.type to PropertyConfigs.config(property.kind, property.settings))
            }
        }
        return SchemaChange(body, renamed, removed.map { it.name }.toSet())
    }
}

