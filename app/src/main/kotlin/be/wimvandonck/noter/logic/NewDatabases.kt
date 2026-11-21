package be.wimvandonck.noter.logic

import kotlinx.serialization.Serializable

/**
 * A kind of property Noter can make (#71), in a new database or with Edit database: every kind
 * Notion's API makes (a status gets Notion's Not started, In progress and Done). A formula,
 * relation and rollup need [PropertySettings] too. Not buttons and verifications (the API can't
 * make them).
 */
@Serializable
enum class PropertyKind(val type: String) {
    TEXT("rich_text"),
    NUMBER("number"),
    SELECT("select"),
    MULTI_SELECT("multi_select"),
    STATUS("status"),
    DATE("date"),
    PERSON("people"),
    FILES("files"),
    CHECKBOX("checkbox"),
    URL("url"),
    EMAIL("email"),
    PHONE("phone_number"),
    CREATED_TIME("created_time"),
    CREATED_BY("created_by"),
    LAST_EDITED_TIME("last_edited_time"),
    LAST_EDITED_BY("last_edited_by"),
    FORMULA("formula"),
    RELATION("relation"),
    ROLLUP("rollup"),
    UNIQUE_ID("unique_id"),
    PLACE("place"),
    ;

    companion object {
        /** The kind of Notion [type] (null: one Noter can't make, such as the title). */
        fun of(type: String): PropertyKind? = entries.firstOrNull { it.type == type }
    }
}

/** What a new database is made from: nothing (its own properties), or one of Notion's starters. */
enum class DatabaseStarter(val type: String?) {
    EMPTY(null),
    TASKS("tasks"),
    PROJECTS("projects"),
}

/**
 * A property of a new database: its kind, name, for a select or multi-select the starting
 * options as the user typed them, separated by commas ([NewDatabases.parseOptions]), and what
 * some kinds need besides ([settings]: a formula, a relation's database, a number's format, …).
 */
@Serializable
data class NewProperty(
    val kind: PropertyKind,
    val name: String,
    val options: String = "",
    val settings: PropertySettings = PropertySettings(),
) {
    /** Whether [options] can be typed for this kind (a status keeps Notion's own). */
    val takesOptions: Boolean get() = kind == PropertyKind.SELECT || kind == PropertyKind.MULTI_SELECT

    /** Whether Notion has everything it needs to make it. */
    val isComplete: Boolean get() = PropertyConfigs.isComplete(kind, settings)
}

/**
 * A new database as Notion's `POST /databases` wants it (#71): a title, its title property and the
 * properties chosen, inside a page or at the top of the workspace. Notion makes the first data
 * source and a table view with it; Edit database changes it afterwards.
 */
object NewDatabases {
    /** What a new database has besides its title property, as in Notion: Tags. */
    val DEFAULT_KINDS = listOf(PropertyKind.MULTI_SELECT)

    /**
     * The kinds a new database's Add property offers: all but a rollup (it needs a relation that
     * is already there; Edit database adds one afterwards), and one unique ID at most.
     */
    fun addableKinds(properties: List<NewProperty>): List<PropertyKind> =
        PropertyConfigs.addable(hasRelation = false, hasUniqueId = properties.any { it.kind == PropertyKind.UNIQUE_ID })

    /** Whether Create can be tapped: with a starter always, else with a name and every property complete. */
    fun canCreate(title: String, properties: List<NewProperty>, starter: DatabaseStarter): Boolean =
        starter != DatabaseStarter.EMPTY || (title.isNotBlank() && properties.all { it.isComplete })

    /**
     * [properties] with a property of [kind] added at the end, named [defaultName] as Notion names
     * a new one of that kind, numbered when the name is taken ("Text 2"), as Notion does.
     */
    fun add(properties: List<NewProperty>, kind: PropertyKind, defaultName: String, titleName: String = ""): List<NewProperty> =
        properties + NewProperty(kind, uniqueName(defaultName, properties.map { it.name } + titleName))

    /** [name], or with the first number from 2 that makes it unlike every one of [taken] (in any case). */
    fun uniqueName(name: String, taken: List<String>): String {
        val base = name.trim()
        val used = taken.map { it.trim().lowercase() }.toSet()
        if (base.lowercase() !in used) return base
        return generateSequence(2) { it + 1 }.map { "$base $it" }.first { it.lowercase() !in used }
    }

    /** Longest option name Notion accepts. */
    private const val MAX_OPTION_LENGTH = 100

    /** The option names in [text]: split at commas, trimmed, none blank, none twice (in any case). */
    fun parseOptions(text: String): List<String> =
        text.split(',', '\n').map { it.trim().take(MAX_OPTION_LENGTH) }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

    /**
     * The request for database [title] inside page [parentPageId] (null: at the top of the
     * workspace). With [starter] Tasks or Projects Notion builds the properties (`database_type`,
     * which can't go with a schema), and a blank [title] leaves it to Notion to name it after the
     * type. Otherwise it has title property [titleName] and [properties] in their order, each with
     * its settings ([PropertyConfigs.config]): a blank name becomes [fallbackNames]' name for its
     * kind; names are made unique as in [add]. [inline] shows it inside the page rather than as a
     * page of its own (only inside a page).
     */
    fun request(
        title: String,
        parentPageId: String?,
        titleName: String,
        properties: List<NewProperty>,
        fallbackNames: Map<PropertyKind, String> = emptyMap(),
        starter: DatabaseStarter = DatabaseStarter.EMPTY,
        inline: Boolean = false,
    ): Map<String, Any?> {
        val request = linkedMapOf<String, Any?>(
            "parent" to if (parentPageId != null) mapOf("type" to "page_id", "page_id" to parentPageId) else mapOf("type" to "workspace", "workspace" to true),
        )
        if (starter.type == null || title.isNotBlank()) {
            request["title"] = listOf(mapOf("type" to "text", "text" to mapOf("content" to title.trim())))
        }
        if (inline && parentPageId != null) request["is_inline"] = true
        if (starter.type != null) {
            request["database_type"] = starter.type
            return request
        }
        val titleProperty = titleName.trim().ifBlank { "Name" }
        val schema = linkedMapOf<String, Any?>(titleProperty to mapOf("title" to emptyMap<String, Any?>()))
        properties.forEach { property ->
            val wanted = property.name.trim().ifBlank { fallbackNames[property.kind].orEmpty().ifBlank { property.kind.type } }
            val options = if (property.takesOptions) parseOptions(property.options) else emptyList()
            schema[uniqueName(wanted, schema.keys.toList())] = mapOf(property.kind.type to PropertyConfigs.config(property.kind, property.settings, options))
        }
        request["initial_data_source"] = mapOf("properties" to schema)
        return request
    }
}
