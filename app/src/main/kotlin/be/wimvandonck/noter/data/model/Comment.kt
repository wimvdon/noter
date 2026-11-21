package be.wimvandonck.noter.data.model

/** One comment on a page, as Notion has it (see CommentCodec). */
data class Comment(
    val id: String,
    /** The conversation it is in: the first comment and every reply share it. */
    val discussionId: String,
    /** Notion's ISO 8601 time of writing. */
    val createdTime: String,
    /** Who wrote it (Notion gives only the ID; [authorName] is the name Notion shows, when it says). */
    val authorId: String,
    val authorName: String = "",
    /** The text as inline Markdown (see InlineMarkdown). */
    val text: String,
    /** The block the conversation is about; null for the page itself. */
    val blockId: String? = null,
)

/** The comments of one conversation, oldest first. */
data class Discussion(
    val id: String,
    val blockId: String?,
    val comments: List<Comment>,
)
