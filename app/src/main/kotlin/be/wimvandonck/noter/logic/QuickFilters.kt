package be.wimvandonck.noter.logic

import be.wimvandonck.noter.data.model.DatabaseSchema
import be.wimvandonck.noter.data.model.NotionView
import be.wimvandonck.noter.data.model.PropertySchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * A quick filter of a Notion view: a pinned filter pill above the view. [key] is the property as
 * the view names it (its ID, or its name), [name] the property's name, and [preset] the condition
 * the pill holds (null when it has none yet: the user picks a value). [type] is the property's type.
 */
data class QuickChip(val key: String, val name: String, val type: String, val preset: JsonObject?)

/** What a chip without a value can be set to: an option, checked or not, or the signed-in person. */
data class QuickChoice(val kind: Kind, val name: String = "", val condition: JsonObject? = null) {
    enum class Kind { OPTION, CHECKED, UNCHECKED, ME }
}

/**
 * Notion's quick filters shown as chips (a view's `quick_filters`: keys are properties, values are
 * property filters without the `property` field). A chip that is on adds its condition to the
 * view's filter when its notes are asked.
 */
object QuickFilters {
    /** Property types a chip without a value can pick one for. */
    val PICKABLE_TYPES = setOf("select", "status", "multi_select", "checkbox", "people")

    /**
     * [view]'s quick filters worth a chip, in Notion's order: the ones with a condition, and the
     * ones without when a value can be picked for their property. A property the data source no
     * longer has is left out.
     */
    fun chips(view: NotionView?, schema: DatabaseSchema?): List<QuickChip> {
        val filters = view?.quickFilters as? JsonObject ?: return emptyList()
        schema ?: return emptyList()
        return filters.mapNotNull { (key, condition) ->
            val property = schema.properties.firstOrNull { it.id == key } ?: schema.property(key) ?: return@mapNotNull null
            val preset = (condition as? JsonObject)?.takeIf { it.isNotEmpty() }
            if (preset == null && property.type !in PICKABLE_TYPES) return@mapNotNull null
            QuickChip(key, property.name, property.type, preset)
        }
    }

    /**
     * What a chip says, as Notion's pills do: the property's name, with the value of a condition
     * that names one ("Status: Done"). [names] gives the name of a person by ID.
     */
    fun label(chip: QuickChip, condition: JsonObject? = chip.preset, names: Map<String, String> = emptyMap()): String {
        val value = condition?.let { valueOf(it, names) }
        return if (value.isNullOrBlank()) chip.name else "${chip.name}: $value"
    }

    private fun valueOf(condition: JsonObject, names: Map<String, String>): String? {
        val inner = condition.entries.firstOrNull { it.key != "type" }?.value as? JsonObject ?: return null
        val value = (inner["equals"] ?: inner["contains"]) as? JsonPrimitive ?: return null
        if (!value.isString) return null
        return names[value.content] ?: value.content.takeIf { !ID.matches(it) }
    }

    private val ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /** What a chip of [property] without a value can be set to (people: [QuickChoice.Kind.ME] only). */
    fun choices(property: PropertySchema?): List<QuickChoice> = when (property?.type) {
        "select", "status", "multi_select" -> property.options.map { QuickChoice(QuickChoice.Kind.OPTION, it.name, option(property.type, it.name)) }
        "checkbox" -> listOf(
            QuickChoice(QuickChoice.Kind.CHECKED, condition = checkbox(true)),
            QuickChoice(QuickChoice.Kind.UNCHECKED, condition = checkbox(false)),
        )
        "people" -> listOf(QuickChoice(QuickChoice.Kind.ME))
        else -> emptyList()
    }

    /** The condition "the option [name]" for a select, status or multi-select property. */
    fun option(type: String, name: String): JsonObject =
        JsonObject(mapOf(type to JsonObject(mapOf((if (type == "multi_select") "contains" else "equals") to JsonPrimitive(name)))))

    private fun checkbox(checked: Boolean) = JsonObject(mapOf("checkbox" to JsonObject(mapOf("equals" to JsonPrimitive(checked)))))

    /** The condition "includes person [userId]". */
    fun person(userId: String): JsonObject = JsonObject(mapOf("people" to JsonObject(mapOf("contains" to JsonPrimitive(userId)))))

    /** What of [active] (conditions by chip key) still has a chip in [view]: a view changed in Notion can drop one. */
    fun applicable(active: Map<String, JsonObject>, view: NotionView?, schema: DatabaseSchema?): Map<String, JsonObject> {
        if (active.isEmpty()) return active
        val keys = chips(view, schema).map { it.key }.toSet()
        return active.filterKeys { it in keys }
    }

    /** True when [condition] asks for a checked checkbox, false for an unchecked one, null for neither. */
    fun checked(condition: JsonObject?): Boolean? =
        ((condition?.get("checkbox") as? JsonObject)?.get("equals") as? JsonPrimitive)?.booleanOrNull
}
