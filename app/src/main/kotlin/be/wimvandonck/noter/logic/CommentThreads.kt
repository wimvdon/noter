package be.wimvandonck.noter.logic

import be.wimvandonck.noter.data.model.Block
import be.wimvandonck.noter.data.model.Comment
import be.wimvandonck.noter.data.model.Discussion
import be.wimvandonck.noter.data.model.NotionUser
import be.wimvandonck.noter.data.notion.InlineMarkdown
import be.wimvandonck.noter.data.notion.InlineTokens
import java.time.Instant

/** Turns a page's comments into what the comments sheet shows. */
object CommentThreads {
    /**
     * The conversations: the page's own first, then those about a block, each in the order of its
     * first comment. Comments within one are oldest first.
     */
    fun group(comments: List<Comment>): List<Discussion> {
        val byTime = comments.sortedBy { it.createdTime }
        return byTime.groupBy { it.discussionId }.map { (id, list) ->
            Discussion(id, list.firstNotNullOfOrNull { it.blockId }, list)
        }.sortedBy { it.blockId != null }
    }

    /** A comment is the token's own when its author is the token's bot (or the person it acts as). */
    fun isOwn(comment: Comment, mine: Collection<String?>): Boolean {
        val author = KeyRouter.normalize(comment.authorId)
        return author.isNotEmpty() && mine.any { it != null && KeyRouter.normalize(it) == author }
    }

    /** The name to show: the cached person, else the name Notion sent with the comment, else null. */
    fun authorName(comment: Comment, users: List<NotionUser>): String? {
        val id = KeyRouter.normalize(comment.authorId)
        return users.firstOrNull { KeyRouter.normalize(it.id) == id }?.name?.ifBlank { null }
            ?: comment.authorName.ifBlank { null }
    }

    /** One line of the text of the block [discussion] is about, if that block is in [blocks]. */
    fun quote(discussion: Discussion, blocks: List<Block>, maxLength: Int = 80): String? {
        val blockId = discussion.blockId?.let(KeyRouter::normalize) ?: return null
        val block = blocks.firstOrNull { it.notionId?.let(KeyRouter::normalize) == blockId } ?: return null
        val line = InlineMarkdown.stripped(block.text).lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        return if (line.length <= maxLength) line else line.take(maxLength - 1).trimEnd() + "…"
    }

    /** Milliseconds since 1970 of Notion's [iso] time, or null when it can't be read. */
    fun millis(iso: String): Long? = runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()

    /**
     * A comment with a mention or equation in it can't be changed here: they would show as stray
     * characters in the text field (Notion lets the owner edit it in Notion).
     */
    fun editable(comment: Comment): Boolean = comment.text.none(InlineTokens::isMarker)

    /** What to send for what was typed: null when there is no text (blanks around it don't count). */
    fun sendable(text: String): String? = text.trim().ifEmpty { null }
}
