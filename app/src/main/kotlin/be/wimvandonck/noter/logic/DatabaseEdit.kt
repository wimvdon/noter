package be.wimvandonck.noter.logic

import be.wimvandonck.noter.data.model.CustomEmoji
import be.wimvandonck.noter.data.model.DatabaseInfo
import be.wimvandonck.noter.data.model.PageIcon
import be.wimvandonck.noter.data.model.PinnedDatabase
import be.wimvandonck.noter.data.model.WorkspaceItem
import be.wimvandonck.noter.data.notion.IconCodec

/**
 * What Edit database changes about the database itself, made in Notion at once. [title]: the new
 * name (null: kept). [setsIcon]: the icon changes, to [emoji] or the workspace's [custom] emoji
 * (both null: removed). [description], [locked] (in Notion's own app) and [inline] (shown inside
 * its page): null when kept.
 */
data class DatabaseEdit(
    val title: String? = null,
    val emoji: String? = null,
    val setsIcon: Boolean = false,
    val custom: CustomEmoji? = null,
    val description: String? = null,
    val locked: Boolean? = null,
    val inline: Boolean? = null,
) {
    /** Nothing changes. */
    val isEmpty: Boolean get() = title == null && !setsIcon && description == null && locked == null && inline == null

    /** What `PATCH /databases` is sent. */
    fun body(): Map<String, Any?> = sourceBody() + buildMap {
        description?.let { put("description", richText(it)) }
        locked?.let { put("is_locked", it) }
        inline?.let { put("is_inline", it) }
    }

    /** What the database's only data source is sent, so Notion's lists show its name and icon too (empty: nothing). */
    fun sourceBody(): Map<String, Any?> = buildMap {
        title?.let { put("title", richText(it)) }
        if (setsIcon) put("icon", IconCodec.request(emoji, custom?.id))
    }

    fun title(old: String): String = title ?: old

    fun icon(old: PageIcon?): PageIcon? = if (setsIcon) IconCodec.choice(emoji, custom?.id, custom?.url) else old

    /** Home's list and the last search: the database's rows ([ids]: the database and its data source) changed. */
    fun applyTo(items: List<WorkspaceItem>, ids: Set<String>): List<WorkspaceItem> =
        items.map { if (plain(it.id) in ids) it.copy(title = title(it.title), icon = icon(it.icon)) else it }

    /** The pinned databases on Home, likewise. */
    fun applyToPins(pins: List<PinnedDatabase>, ids: Set<String>): List<PinnedDatabase> =
        pins.map { if (plain(it.id) in ids) it.copy(title = title(it.title), icon = icon(it.icon)) else it }

    fun applyTo(info: DatabaseInfo): DatabaseInfo = info.copy(
        title = title(info.title),
        icon = icon(info.icon),
        description = description ?: info.description,
        locked = locked ?: info.locked,
        inline = inline ?: info.inline,
    )

    companion object {
        /** Longest text Notion takes in one rich text piece. */
        private const val MAX_PIECE = 2000

        /** [text] as Notion's rich text: pieces of at most 2000 characters (empty: none). */
        fun richText(text: String): List<Map<String, Any?>> =
            text.chunked(MAX_PIECE).map { mapOf("type" to "text", "text" to mapOf("content" to it)) }

        /**
         * What Edit database saves about database [info] (null: not known, so only the name and
         * icon): what differs from it. A [newTitle] differing from [oldTitle], a changed icon
         * ([iconChanged]), a new [description] (trimmed), [locked], and [inline] only for a
         * database inside a page.
         */
        fun of(
            oldTitle: String,
            newTitle: String,
            emoji: String?,
            custom: CustomEmoji?,
            iconChanged: Boolean,
            info: DatabaseInfo?,
            description: String,
            locked: Boolean,
            inline: Boolean,
        ) = DatabaseEdit(
            title = newTitle.trim().takeIf { it.isNotEmpty() && it != oldTitle },
            emoji = emoji,
            setsIcon = iconChanged,
            custom = custom,
            description = description.trim().takeIf { info != null && it != info.description },
            locked = locked.takeIf { info != null && it != info.locked },
            inline = inline.takeIf { info?.parentId != null && it != info.inline },
        )

        fun rename(title: String) = DatabaseEdit(title = title.trim())

        fun icon(emoji: String?, custom: CustomEmoji? = null) = DatabaseEdit(emoji = emoji, setsIcon = true, custom = custom)

        /** IDs compared without dashes (Notion gives both). */
        fun plain(id: String): String = id.replace("-", "")
    }
}
