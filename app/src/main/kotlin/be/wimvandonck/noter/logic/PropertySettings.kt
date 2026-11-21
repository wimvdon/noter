package be.wimvandonck.noter.logic

import kotlinx.serialization.Serializable

/**
 * What a property of some kinds needs besides its name, as Add property's small form (or Change
 * type's) has it: a number's [numberFormat], a formula's [formula], a relation's target data source
 * ([relationTarget], shown as [relationTargetName]) and whether the other database shows it too
 * ([twoWay]), a rollup's relation property ([rollupRelation], an ID of this data source), the
 * property it rolls up ([rollupProperty], an ID of the related data source) and its
 * [rollupFunction], and a unique ID's [idPrefix] (blank: none).
 */
@Serializable
data class PropertySettings(
    val numberFormat: String = PropertyConfigs.DEFAULT_NUMBER_FORMAT,
    val formula: String = "",
    val relationTarget: String? = null,
    val relationTargetName: String = "",
    val twoWay: Boolean = false,
    val rollupRelation: String? = null,
    val rollupProperty: String? = null,
    val rollupFunction: String = PropertyConfigs.DEFAULT_ROLLUP_FUNCTION,
    val idPrefix: String = "",
)

/**
 * The settings Notion's API takes for each kind of property (`POST /databases`, `PATCH
 * /data_sources`), which kinds can be added, and which type changes Notion allows.
 */
object PropertyConfigs {
    const val DEFAULT_NUMBER_FORMAT = "number"
    const val DEFAULT_ROLLUP_FUNCTION = "show_original"

    /** The number formats offered, most used first (Notion has about 45; others stay as they are). */
    val NUMBER_FORMATS = listOf("number", "number_with_commas", "percent", "euro", "dollar", "pound", "yen")

    /** The rollup functions offered for any property. */
    private val ANY_FUNCTIONS = listOf("show_original", "count", "count_values", "unique", "empty", "not_empty")
    private val NUMBER_FUNCTIONS = listOf("sum", "average", "min", "max")
    private val DATE_FUNCTIONS = listOf("earliest_date", "latest_date")
    private val CHECKBOX_FUNCTIONS = listOf("checked", "percent_checked")

    /** Every rollup function the app offers, for naming them. */
    val ROLLUP_FUNCTIONS = ANY_FUNCTIONS + NUMBER_FUNCTIONS + DATE_FUNCTIONS + CHECKBOX_FUNCTIONS

    /** The rollup functions that make sense for a related property of [type] (null: not known yet). */
    fun rollupFunctions(type: String?): List<String> = ANY_FUNCTIONS + when (type) {
        "number", "formula", "rollup" -> NUMBER_FUNCTIONS
        "date", "created_time", "last_edited_time" -> DATE_FUNCTIONS
        "checkbox" -> CHECKBOX_FUNCTIONS
        else -> emptyList()
    }

    /** [function] when it suits a related property of [type], else the first one that does. */
    fun fitFunction(function: String, type: String?): String =
        function.takeIf { it in rollupFunctions(type) } ?: DEFAULT_ROLLUP_FUNCTION

    /** Kinds that can't be made without [PropertySettings] filled in. */
    val NEEDS_SETTINGS = setOf(PropertyKind.FORMULA, PropertyKind.RELATION, PropertyKind.ROLLUP)

    /** Kinds with a small form under their name (the ones that need settings, a number's format, an ID's prefix). */
    val HAS_SETTINGS = NEEDS_SETTINGS + setOf(PropertyKind.NUMBER, PropertyKind.UNIQUE_ID)

    /** Whether [settings] has everything Notion needs to make a property of [kind]. */
    fun isComplete(kind: PropertyKind, settings: PropertySettings): Boolean = when (kind) {
        PropertyKind.FORMULA -> settings.formula.isNotBlank()
        PropertyKind.RELATION -> !settings.relationTarget.isNullOrBlank()
        PropertyKind.ROLLUP -> !settings.rollupRelation.isNullOrBlank() && !settings.rollupProperty.isNullOrBlank() && settings.rollupFunction.isNotBlank()
        else -> true
    }

    /**
     * What Notion is sent under the kind's type for a property of [kind]: a number's format, a
     * select's starting [options], a formula's expression, a relation's data source (one-way, or
     * two-way so the other database shows it too), a rollup's relation, property and function, a
     * unique ID's prefix (null: none); nothing for the others (a status gets Notion's options).
     */
    fun config(kind: PropertyKind, settings: PropertySettings = PropertySettings(), options: List<String> = emptyList()): Map<String, Any?> = when (kind) {
        PropertyKind.NUMBER -> mapOf("format" to settings.numberFormat.ifBlank { DEFAULT_NUMBER_FORMAT })
        PropertyKind.SELECT, PropertyKind.MULTI_SELECT ->
            if (options.isEmpty()) emptyMap() else mapOf("options" to options.map { mapOf("name" to it) })
        PropertyKind.FORMULA -> mapOf("expression" to settings.formula.trim())
        PropertyKind.RELATION -> {
            val way = if (settings.twoWay) "dual_property" else "single_property"
            mapOf("data_source_id" to settings.relationTarget, "type" to way, way to emptyMap<String, Any?>())
        }
        PropertyKind.ROLLUP -> mapOf(
            "relation_property_id" to settings.rollupRelation,
            "rollup_property_id" to settings.rollupProperty,
            "function" to settings.rollupFunction,
        )
        PropertyKind.UNIQUE_ID -> mapOf("prefix" to settings.idPrefix.trim().ifBlank { null })
        else -> emptyMap()
    }

    /** Types Notion can't change a property from or to. */
    private val FIXED_TYPES = setOf("title", "place")

    /**
     * The kinds a property of Notion [type] can become (Change type): none for the title and a
     * place, never a place; a rollup only when the database has a relation ([hasRelation]) and a
     * unique ID only when it has none yet ([hasUniqueId]).
     */
    fun typeChanges(type: String, hasRelation: Boolean, hasUniqueId: Boolean): List<PropertyKind> =
        if (type in FIXED_TYPES) {
            emptyList()
        } else {
            addable(hasRelation, hasUniqueId).filter { it != PropertyKind.PLACE && it.type != type }
        }

    /** The kinds Add property offers: all, but a rollup only with a relation to roll up, and one unique ID at most. */
    fun addable(hasRelation: Boolean, hasUniqueId: Boolean): List<PropertyKind> =
        PropertyKind.entries.filter { (it != PropertyKind.ROLLUP || hasRelation) && (it != PropertyKind.UNIQUE_ID || !hasUniqueId) }
}
