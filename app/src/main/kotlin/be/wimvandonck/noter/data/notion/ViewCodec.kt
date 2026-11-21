package be.wimvandonck.noter.data.notion

import be.wimvandonck.noter.data.api.stringOrNull
import be.wimvandonck.noter.data.model.NotionView
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Reads Notion's views (`GET /views/{id}`). */
object ViewCodec {
    /** The view in [json], or null when it isn't one. */
    fun parse(json: JsonObject): NotionView? {
        if (json.stringOrNull("object") != "view") return null
        val id = json.stringOrNull("id") ?: return null
        val configuration = json["configuration"] as? JsonObject
        return NotionView(
            id = id,
            name = json.stringOrNull("name").orEmpty(),
            type = json.stringOrNull("type").orEmpty(),
            dataSourceId = json.stringOrNull("data_source_id"),
            filter = json["filter"].orNull(),
            sorts = json["sorts"].orNull(),
            quickFilters = json["quick_filters"].orNull(),
            visibleProperties = visibleProperties(configuration?.get("properties")),
            cover = cover(configuration?.get("cover")),
        )
    }

    /**
     * The properties a view's configuration shows, in its order: `properties` is a list of
     * `{property_id, visible}` (one that doesn't say is shown), read leniently also as an object by
     * property. Null when it has none.
     */
    private fun visibleProperties(properties: JsonElement?): List<String>? = when (properties) {
        is JsonArray -> properties.mapNotNull { entry ->
            when (entry) {
                is JsonObject -> (entry.stringOrNull("property_id") ?: entry.stringOrNull("id"))?.takeIf { entry.visible() }
                is JsonPrimitive -> entry.contentOrNull
                else -> null
            }
        }
        is JsonObject -> properties.mapNotNull { (key, entry) -> key.takeIf { (entry as? JsonObject)?.visible() != false } }
        else -> null
    }

    private fun JsonObject.visible(): Boolean = (this["visible"] as? JsonPrimitive)?.contentOrNull != "false"

    /** The cover setting of a gallery or board view: the kind of cover it shows, null for none. */
    private fun cover(cover: JsonElement?): String? {
        val kind = when (cover) {
            is JsonObject -> cover.stringOrNull("type") ?: "page_cover"
            is JsonPrimitive -> cover.contentOrNull
            else -> null
        }
        return kind?.takeIf { it.isNotEmpty() && it != "none" }
    }

    private fun JsonElement?.orNull(): JsonElement? = this?.takeUnless { it is JsonNull }
}
