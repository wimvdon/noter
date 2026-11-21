package be.wimvandonck.noter.data.notion

import be.wimvandonck.noter.data.api.objOrNull
import be.wimvandonck.noter.data.api.stringOrNull
import be.wimvandonck.noter.data.model.CustomEmoji
import be.wimvandonck.noter.data.model.PageIcon
import kotlinx.serialization.json.JsonObject

/** Notion's icon objects (of pages, databases and data sources) ⇄ [PageIcon]. */
object IconCodec {
    /** The icon in [icon] (a page's "icon"), or null when there is none or its kind is unknown. */
    fun parse(icon: JsonObject?): PageIcon? {
        val type = icon?.stringOrNull("type") ?: return null
        val body = icon.objOrNull(type)
        return when (type) {
            "emoji" -> icon.stringOrNull("emoji")?.let { PageIcon(emoji = it) }
            "icon" -> body?.stringOrNull("name")?.let { PageIcon(name = it, color = body.stringOrNull("color") ?: DEFAULT_COLOR) }
            "custom_emoji" -> body?.stringOrNull("url")?.let { PageIcon(url = it, customEmojiId = body.stringOrNull("id")) }
            "external" -> body?.stringOrNull("url")?.let { PageIcon(url = it) }
            "file" -> body?.stringOrNull("url")?.let { PageIcon(url = it, urlExpiry = body.stringOrNull("expiry_time")) }
            else -> null
        }
    }

    /** One custom emoji of the list from Notion (`{"id", "name", "url"}`), or null when it lacks an ID or image. */
    fun parseCustomEmoji(json: JsonObject): CustomEmoji? {
        val id = json.stringOrNull("id") ?: return null
        val url = json.stringOrNull("url") ?: return null
        return CustomEmoji(id, json.stringOrNull("name").orEmpty(), url)
    }

    /**
     * The icon to send Notion: [emoji] as an emoji, else [customEmojiId] as a custom emoji (Notion
     * only needs its ID), else null (the icon is removed).
     */
    fun request(emoji: String?, customEmojiId: String?): Map<String, Any?>? = when {
        emoji != null -> mapOf("type" to "emoji", "emoji" to emoji)
        customEmojiId != null -> mapOf("type" to "custom_emoji", "custom_emoji" to mapOf("id" to customEmojiId))
        else -> null
    }

    /** The icon the phone shows for a choice of [emoji] or custom emoji ([customEmojiId] with its image [url]). */
    fun choice(emoji: String?, customEmojiId: String?, url: String?): PageIcon? = when {
        emoji != null -> PageIcon(emoji = emoji)
        customEmojiId != null && url != null -> PageIcon(url = url, customEmojiId = customEmojiId)
        else -> null
    }

    /** Notion's default color for its own icons. */
    const val DEFAULT_COLOR = "gray"
}
