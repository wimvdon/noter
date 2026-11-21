package be.wimvandonck.noter.logic

import be.wimvandonck.noter.data.model.DatabaseSchema
import be.wimvandonck.noter.data.model.NotionView
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A database shows its notes through its Notion views, one tab each: read from Notion and never
 * changed here. The tabs follow Notion's order.
 */
object NotionViews {
    /** View types that list a data source's pages; forms, charts and dashboards don't. */
    val LISTED_TYPES = setOf("table", "board", "list", "calendar", "timeline", "gallery", "map")

    /** The views of [views] that list the notes of data source [sourceId], in Notion's order. */
    fun listed(views: List<NotionView>, sourceId: String): List<NotionView> =
        views.filter { it.type in LISTED_TYPES && it.dataSourceId != null && plain(it.dataSourceId) == plain(sourceId) }

    /**
     * The view a data source shows, of its [shown] views: the one chosen last ([choice]) while it is
     * still shown, else the first (null: no views).
     */
    fun chosen(choice: String?, shown: List<NotionView>): String? =
        shown.firstOrNull { choice != null && plain(it.id) == plain(choice) }?.id ?: shown.firstOrNull()?.id

    /**
     * The view each data source shows ([views]: its listed views by data source ID, [choices]: the
     * view chosen last by data source ID), for what the cards of its notes show.
     */
    fun shownViews(views: Map<String, List<NotionView>>, choices: Map<String, String>): Map<String, NotionView> =
        views.mapNotNull { (sourceId, all) ->
            val shown = listed(all, sourceId)
            val choice = choices[sourceId] ?: choices.entries.firstOrNull { plain(it.key) == plain(sourceId) }?.value
            val id = chosen(choice, shown) ?: return@mapNotNull null
            shown.firstOrNull { it.id == id }?.let { sourceId to it }
        }.toMap()

    /**
     * The names of the properties [view] shows, in its order, without the title (null: the view's
     * configuration isn't known). A property the data source no longer has is left out.
     */
    fun propertyNames(view: NotionView?, schema: DatabaseSchema?): List<String>? {
        val visible = view?.visibleProperties ?: return null
        schema ?: return null
        return visible.mapNotNull { ref -> schema.properties.firstOrNull { it.id == ref || it.name == ref } }
            .filter { it.type != "title" }
            .map { it.name }
            .distinct()
    }

    /**
     * The view whose order of properties a note follows: a table view when the data source has
     * one, else its first listed view whose configuration is known.
     */
    fun orderingView(views: List<NotionView>, sourceId: String): NotionView? {
        val withConfig = listed(views, sourceId).filter { it.visibleProperties != null }
        return withConfig.firstOrNull { it.type == "table" } ?: withConfig.firstOrNull()
    }

    /** True when [view] shows the header image on its cards. */
    fun showsCover(view: NotionView?): Boolean = view?.cover != null

    /**
     * What Notion is asked for [view]'s notes: its filter with the conditions of the quick filters
     * that are on ([quick]: conditions by property, added to an "and" the filter already has, so it
     * nests no deeper), and its sorts. A sort names its property by name, as Notion's query asks
     * ([schema] gives the name of a property named by ID).
     */
    fun query(view: NotionView, schema: DatabaseSchema?, quick: Map<String, JsonObject> = emptyMap()): Pair<JsonElement?, JsonElement?> {
        val conditions = quick.toSortedMap().map { (property, condition) -> JsonObject(mapOf("property" to JsonPrimitive(property)) + condition) }
        val filter = view.filter as? JsonObject
        val combined = when {
            conditions.isEmpty() -> filter
            filter == null -> if (conditions.size == 1) conditions.single() else JsonObject(mapOf("and" to JsonArray(conditions)))
            filter.keys == setOf("and") && filter["and"] is JsonArray -> JsonObject(mapOf("and" to JsonArray((filter["and"] as JsonArray) + conditions)))
            else -> JsonObject(mapOf("and" to JsonArray(listOf(filter) + conditions)))
        }
        val sorts = (view.sorts as? JsonArray)?.map { sort ->
            val obj = sort as? JsonObject ?: return@map sort
            val property = obj.string("property") ?: return@map sort
            val name = schema?.properties?.firstOrNull { it.id == property }?.name ?: return@map sort
            JsonObject(obj + ("property" to JsonPrimitive(name)))
        }?.let(::JsonArray)?.takeIf { it.isNotEmpty() }
        return combined to sorts
    }

    /**
     * What a view's notes are kept under: its ID, and with quick filters on ([quick]) the same
     * followed by those conditions, so every combination has its own copy.
     */
    fun resultKey(viewId: String, quick: Map<String, JsonObject> = emptyMap()): String =
        if (quick.isEmpty()) viewId else viewId + "|" + quick.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }

    /** The view a [resultKey] is of. */
    fun viewOf(resultKey: String): String = resultKey.substringBefore('|')

    /**
     * The IDs a view shows, in its order ([ids], as Notion listed them when asked at [fetchedAt],
     * ISO 8601), with the notes made since ([createdTimes], by ID: they can't be in it yet) first.
     */
    fun members(ids: List<String>, fetchedAt: String, createdTimes: Map<String, String>): List<String> {
        val known = ids.toSet()
        val newer = createdTimes.filter { (id, created) -> id !in known && created > fetchedAt }
            .entries.sortedByDescending { it.value }.map { it.key }
        return newer + ids
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun plain(id: String) = id.replace("-", "")
}
