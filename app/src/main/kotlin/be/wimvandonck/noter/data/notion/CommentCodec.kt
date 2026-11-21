package be.wimvandonck.noter.data.notion

import be.wimvandonck.noter.data.api.arrayOrNull
import be.wimvandonck.noter.data.api.objOrNull
import be.wimvandonck.noter.data.api.stringOrNull
import be.wimvandonck.noter.data.model.Comment
import kotlinx.serialization.json.JsonObject

/** Notion's comment object ⇄ [Comment], and the bodies that add or change one. */
object CommentCodec {
    fun parse(json: JsonObject): Comment? {
        val id = json.stringOrNull("id") ?: return null
        val discussion = json.stringOrNull("discussion_id") ?: return null
        val parent = json.objOrNull("parent")
        return Comment(
            id = id,
            discussionId = discussion,
            createdTime = json.stringOrNull("created_time").orEmpty(),
            authorId = json.objOrNull("created_by")?.stringOrNull("id").orEmpty(),
            authorName = json.objOrNull("display_name")?.stringOrNull("resolved_name").orEmpty(),
            text = InlineMarkdown.fromRichText(json.arrayOrNull("rich_text")),
            blockId = parent?.stringOrNull("block_id"),
        )
    }

    /** A new comment on page [pageId], or (with [discussionId]) a reply: exactly one target, as Notion wants. */
    fun addBody(pageId: String, discussionId: String?, markdown: String): Map<String, Any?> = buildMap {
        if (discussionId != null) put("discussion_id", discussionId) else put("parent", mapOf("page_id" to pageId))
        put("rich_text", InlineMarkdown.toRichText(markdown))
    }

    fun editBody(markdown: String): Map<String, Any?> = mapOf("rich_text" to InlineMarkdown.toRichText(markdown))
}
