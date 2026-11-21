package be.wimvandonck.noter.data.notion

import be.wimvandonck.noter.data.api.arrayOrNull
import be.wimvandonck.noter.data.api.asObjectOrNull
import be.wimvandonck.noter.data.api.boolOrNull
import be.wimvandonck.noter.data.api.contentOrNull
import be.wimvandonck.noter.data.api.objOrNull
import be.wimvandonck.noter.data.api.stringOrNull
import be.wimvandonck.noter.data.model.DatabaseSchema
import be.wimvandonck.noter.data.model.FileRef
import be.wimvandonck.noter.data.model.NoteProperty
import be.wimvandonck.noter.data.model.NoteSummary
import be.wimvandonck.noter.data.model.NotionUser
import be.wimvandonck.noter.data.model.PropertySchema
import be.wimvandonck.noter.data.model.PageTemplate
import be.wimvandonck.noter.data.model.PropertyValue
import be.wimvandonck.noter.data.model.SelectOption
import kotlinx.serialization.json.JsonObject

/** Reads Notion's database and page JSON into the app's models, and writes property values back. */
object PropertyCodec {

    fun parseSchema(database: JsonObject): DatabaseSchema {
        val id = database.stringOrNull("id").orEmpty()
        val title = InlineMarkdown.plainText(database.arrayOrNull("title")).trim()
        val properties = database.objOrNull("properties").orEmpty().mapNotNull { (name, element) ->
            val prop = element.asObjectOrNull() ?: return@mapNotNull null
            val type = prop.stringOrNull("type")?.takeIf { it !in NoteProperty.HIDDEN_TYPES } ?: return@mapNotNull null
            val config = prop.objOrNull(type)
            PropertySchema(
                id = prop.stringOrNull("id").orEmpty(),
                name = prop.stringOrNull("name") ?: name,
                type = type,
                options = parseOptions(type, config),
                relationDatabaseId = if (type == "relation") config?.stringOrNull("data_source_id") ?: config?.stringOrNull("database_id") else null,
                numberFormat = if (type == "number") config?.stringOrNull("format") else null,
                defaultOption = if (type == "status") config?.stringOrNull("default_option") else null,
            )
        }
        // A data source's parent is its database.
        val databaseId = database.objOrNull("parent")?.stringOrNull("database_id")
        return DatabaseSchema(id = id, title = title, properties = sortForDisplay(properties), databaseId = databaseId)
    }

    /** The templates in Notion's list (`GET /data_sources/{id}/templates`): the default one first. */
    fun parseTemplates(templates: List<JsonObject>): List<PageTemplate> =
        templates.mapNotNull { t ->
            val id = t.stringOrNull("id") ?: return@mapNotNull null
            PageTemplate(id, t.stringOrNull("name").orEmpty(), t.boolOrNull("is_default") == true)
        }.sortedByDescending { it.isDefault }

    /** Title first, then the database's own order is unknown to the API, so editable before computed, by name. */
    private fun sortForDisplay(properties: List<PropertySchema>): List<PropertySchema> =
        properties.sortedWith(
            compareBy<PropertySchema> { if (it.type == "title") 0 else if (it.type in NoteProperty.EDITABLE_TYPES) 1 else 2 }
                .thenBy { it.name.lowercase() },
        )

    /** A property's options; a status option also gets the name of the group that lists it. */
    internal fun parseOptions(type: String, config: JsonObject?): List<SelectOption> {
        val options = config?.arrayOrNull("options")?.mapNotNull { parseOption(it.asObjectOrNull()) }.orEmpty()
        if (type != "status") return options
        val groupOf = buildMap {
            config?.arrayOrNull("groups")?.forEach { element ->
                val group = element.asObjectOrNull() ?: return@forEach
                val name = group.stringOrNull("name") ?: return@forEach
                group.arrayOrNull("option_ids")?.forEach { id -> id.contentOrNull()?.let { put(it, name) } }
            }
        }
        return options.map { it.copy(group = it.group ?: it.id?.let(groupOf::get)) }
    }

    private fun parseOption(option: JsonObject?): SelectOption? {
        option ?: return null
        val name = option.stringOrNull("name") ?: return null
        return SelectOption(name = name, color = option.stringOrNull("color") ?: "default", id = option.stringOrNull("id"), group = option.stringOrNull("group"))
    }

    fun parsePage(page: JsonObject, databaseId: String): NoteSummary? {
        val id = page.stringOrNull("id") ?: return null
        val properties = page.objOrNull("properties").orEmpty().mapNotNull { (name, element) ->
            val prop = element.asObjectOrNull() ?: return@mapNotNull null
            val type = prop.stringOrNull("type")?.takeIf { it !in NoteProperty.HIDDEN_TYPES } ?: return@mapNotNull null
            NoteProperty(name = name, type = type, value = parseValue(type, prop))
        }.sortedWith(compareBy<NoteProperty> { if (it.type == "title") 0 else 1 }.thenBy { it.name.lowercase() })
        val title = properties.firstOrNull { it.type == "title" }?.value as? PropertyValue.Text
        val icon = IconCodec.parse(page.objOrNull("icon"))
        val cover = BlockCodec.fileLink(page.objOrNull("cover"))
        return NoteSummary(
            id = id,
            databaseId = databaseId,
            title = title?.markdown.orEmpty(),
            icon = icon,
            url = page.stringOrNull("url"),
            createdTime = page.stringOrNull("created_time").orEmpty(),
            lastEditedTime = page.stringOrNull("last_edited_time").orEmpty(),
            properties = properties,
            cover = cover?.first,
            coverExpiry = cover?.second,
        )
    }

    /** True when the page is in Notion's trash (older API versions call this "archived"). */
    fun isTrashed(page: JsonObject): Boolean =
        page.boolOrNull("in_trash") == true || page.boolOrNull("archived") == true

    fun parseValue(type: String, prop: JsonObject): PropertyValue = when (type) {
        "title", "rich_text" -> PropertyValue.Text(InlineMarkdown.fromRichText(prop.arrayOrNull(type)))
        "number" -> PropertyValue.Number(prop[type].contentOrNull()?.toDoubleOrNull())
        "select", "status" -> PropertyValue.Select(parseOption(prop.objOrNull(type)))
        "multi_select" -> PropertyValue.MultiSelect(prop.arrayOrNull(type)?.mapNotNull { parseOption(it.asObjectOrNull()) }.orEmpty())
        "date" -> prop.objOrNull("date").let { PropertyValue.Date(it?.stringOrNull("start"), it?.stringOrNull("end")) }
        "people" -> PropertyValue.People(prop.arrayOrNull("people")?.mapNotNull { parseUser(it.asObjectOrNull()) }.orEmpty())
        "checkbox" -> PropertyValue.Checkbox(prop.boolOrNull("checkbox") == true)
        "url", "email", "phone_number" -> PropertyValue.Plain(prop.stringOrNull(type))
        "relation" -> PropertyValue.Relation(
            ids = prop.arrayOrNull("relation")?.mapNotNull { it.asObjectOrNull()?.stringOrNull("id") }.orEmpty(),
            hasMore = prop.boolOrNull("has_more") == true,
        )
        "files" -> PropertyValue.Files(
            prop.arrayOrNull("files")?.mapNotNull { element ->
                val file = element.asObjectOrNull() ?: return@mapNotNull null
                val kind = file.stringOrNull("type")
                FileRef(
                    name = file.stringOrNull("name").orEmpty(),
                    url = kind?.let { file.objOrNull(it)?.stringOrNull("url") },
                )
            }.orEmpty(),
        )
        else -> PropertyValue.ReadOnly(readOnlyText(type, prop))
    }

    fun parseUser(user: JsonObject?): NotionUser? {
        user ?: return null
        val id = user.stringOrNull("id") ?: return null
        val name = user.stringOrNull("name")
            ?: user.objOrNull("person")?.stringOrNull("email")
            ?: ""
        return NotionUser(id, name)
    }

    /** Text for a value the app shows but can't edit. Dates stay ISO; the screen formats them. */
    fun readOnlyText(type: String, prop: JsonObject): String = when (type) {
        "formula" -> prop.objOrNull("formula")?.let { formula ->
            val kind = formula.stringOrNull("type")
            when (kind) {
                "date" -> dateText(formula.objOrNull("date"))
                null -> ""
                else -> formula[kind].contentOrNull().orEmpty()
            }
        }.orEmpty()
        "rollup" -> prop.objOrNull("rollup")?.let { rollup ->
            when (val kind = rollup.stringOrNull("type")) {
                "number" -> rollup["number"].contentOrNull().orEmpty()
                "date" -> dateText(rollup.objOrNull("date"))
                "array" -> rollup.arrayOrNull("array").orEmpty().mapNotNull { item ->
                    val obj = item.asObjectOrNull() ?: return@mapNotNull null
                    val itemType = obj.stringOrNull("type") ?: return@mapNotNull null
                    displayText(parseValue(itemType, obj))
                }.filter { it.isNotBlank() }.joinToString(", ")
                else -> kind?.let { rollup[it].contentOrNull() }.orEmpty()
            }
        }.orEmpty()
        "created_time", "last_edited_time" -> prop.stringOrNull(type).orEmpty()
        "created_by", "last_edited_by" -> parseUser(prop.objOrNull(type))?.name.orEmpty()
        "unique_id" -> prop.objOrNull("unique_id")?.let { uid ->
            val number = uid["number"].contentOrNull().orEmpty()
            uid.stringOrNull("prefix")?.let { "$it-$number" } ?: number
        }.orEmpty()
        "verification" -> prop.objOrNull("verification")?.stringOrNull("state").orEmpty()
        else -> ""
    }

    private fun dateText(date: JsonObject?): String {
        val start = date?.stringOrNull("start") ?: return ""
        val end = date.stringOrNull("end")
        return if (end != null) "$start → $end" else start
    }

    /** A short text for any value, for lists and read-only rows. */
    fun displayText(value: PropertyValue): String = when (value) {
        is PropertyValue.Text -> InlineMarkdown.stripped(value.markdown)
        is PropertyValue.Number -> value.value?.let { formatNumber(it) }.orEmpty()
        is PropertyValue.Select -> value.option?.name.orEmpty()
        is PropertyValue.MultiSelect -> value.options.joinToString(", ") { it.name }
        is PropertyValue.Date -> value.start?.let { start -> value.end?.let { "$start → $it" } ?: start }.orEmpty()
        is PropertyValue.People -> value.users.joinToString(", ") { it.name }
        is PropertyValue.Checkbox -> if (value.checked) "✓" else ""
        is PropertyValue.Plain -> value.value.orEmpty()
        is PropertyValue.Relation -> ""
        is PropertyValue.Files -> value.files.joinToString(", ") { it.name }
        is PropertyValue.ReadOnly -> value.display
    }

    fun formatNumber(value: Double): String =
        if (value == Math.floor(value) && !value.isInfinite() && kotlin.math.abs(value) < 1e15) {
            value.toLong().toString()
        } else {
            value.toString()
        }

    /**
     * The request body for one property, as Notion's "update page" and "create page" expect it, or
     * null when the property can't be written.
     */
    fun toRequest(property: NoteProperty): Any? {
        if (!property.isEditable) return null
        val value = property.value
        return when (property.type) {
            "title", "rich_text" ->
                mapOf(property.type to InlineMarkdown.toRichText((value as? PropertyValue.Text)?.markdown.orEmpty()))
            "number" -> mapOf("number" to (value as? PropertyValue.Number)?.value)
            "select", "status" ->
                mapOf(property.type to (value as? PropertyValue.Select)?.option?.let { mapOf("name" to it.name) })
            "multi_select" ->
                mapOf("multi_select" to (value as? PropertyValue.MultiSelect)?.options.orEmpty().map { mapOf("name" to it.name) })
            "date" -> mapOf(
                "date" to (value as? PropertyValue.Date)?.takeIf { it.start != null }?.let {
                    mapOf("start" to it.start, "end" to it.end)
                },
            )
            "people" ->
                mapOf("people" to (value as? PropertyValue.People)?.users.orEmpty().map { mapOf("object" to "user", "id" to it.id) })
            "checkbox" -> mapOf("checkbox" to ((value as? PropertyValue.Checkbox)?.checked == true))
            "url", "email", "phone_number" ->
                mapOf(property.type to (value as? PropertyValue.Plain)?.value?.trim()?.ifBlank { null })
            "relation" ->
                mapOf("relation" to (value as? PropertyValue.Relation)?.ids.orEmpty().map { mapOf("id" to it) })
            else -> null
        }
    }

    /**
     * The value for writing a files property back: [current] (the files the page has in Notion,
     * as Notion just sent them), without the one at [removeAt] when it is still called
     * [removeName] (else the first one called that), and with [added] (name and upload ID of
     * each) at the end.
     *
     * Notion replaces the whole list, so the files that stay are sent back too, as they came: a
     * file Notion hosts is only accepted as a recent answer had it (its link works for about an
     * hour), so [current] must come from Notion just before.
     */
    fun filesRequest(
        current: List<JsonObject>,
        removeAt: Int? = null,
        removeName: String? = null,
        added: List<Pair<String, String>> = emptyList(),
    ): Map<String, Any?> {
        val removed = removeAt?.takeIf { i -> removeName != null && current.getOrNull(i)?.stringOrNull("name") == removeName }
            ?: removeName?.let { name -> current.indexOfFirst { it.stringOrNull("name") == name }.takeIf { it >= 0 } }
        val kept = current.filterIndexed { i, _ -> i != removed }
        val new = added.map { (name, id) -> mapOf("name" to fileName(name), "type" to "file_upload", "file_upload" to mapOf("id" to id)) }
        return mapOf("files" to kept + new)
    }

    /** Notion takes names of at most 100 characters in a files property: a longer one keeps its extension. */
    fun fileName(name: String): String {
        if (name.length <= MAX_FILE_NAME) return name
        val ext = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length < 10 }?.let { ".$it" }.orEmpty()
        return name.take(MAX_FILE_NAME - ext.length) + ext
    }

    private const val MAX_FILE_NAME = 100

    /** Properties for creating a page: only the ones that have a value. */
    fun toCreateRequest(properties: List<NoteProperty>): Map<String, Any?> =
        properties.filter { it.isEditable && (!it.isEmpty || it.type == "title") }
            .mapNotNull { prop -> toRequest(prop)?.let { prop.name to it } }
            .toMap()

    /** An empty value of the right kind for a property of [schema], for new notes. */
    fun emptyValue(schema: PropertySchema): NoteProperty {
        val value = when (schema.type) {
            "title", "rich_text" -> PropertyValue.Text("")
            "number" -> PropertyValue.Number(null)
            "select", "status" -> PropertyValue.Select(null)
            "multi_select" -> PropertyValue.MultiSelect(emptyList())
            "date" -> PropertyValue.Date(null)
            "people" -> PropertyValue.People(emptyList())
            "checkbox" -> PropertyValue.Checkbox(false)
            "url", "email", "phone_number" -> PropertyValue.Plain(null)
            "relation" -> PropertyValue.Relation(emptyList())
            "files" -> PropertyValue.Files(emptyList())
            else -> PropertyValue.ReadOnly("")
        }
        return NoteProperty(schema.name, schema.type, value)
    }
}

private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
