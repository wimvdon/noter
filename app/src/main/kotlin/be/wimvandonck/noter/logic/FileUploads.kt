package be.wimvandonck.noter.logic

import be.wimvandonck.noter.data.model.Block
import be.wimvandonck.noter.data.sync.ContentDiff

/** A photo or file the user picked on the phone, read into memory to send to Notion. */
class PickedFile(val name: String, val mimeType: String, val bytes: ByteArray) {
    val size: Long get() = bytes.size.toLong()
}

/**
 * Photos and files sent to Notion (Notion's File Upload API, one request per file): how big they
 * may be, which block shows them, and where that block goes in the note.
 */
object FileUploads {
    /** Notion takes a file in one request up to 20 MiB; bigger files need a multi-part upload, which the app doesn't do. */
    const val SINGLE_PART_MAX_BYTES = 20L * 1024 * 1024

    /** Notion's image blocks only take these kinds of image; other pictures go in a file block. */
    private val IMAGE_TYPES = setOf(
        "image/gif", "image/heic", "image/jpeg", "image/png", "image/svg+xml",
        "image/tiff", "image/webp", "image/vnd.microsoft.icon", "image/x-icon",
    )

    private val EXTENSIONS = mapOf(
        "image/gif" to "gif", "image/heic" to "heic", "image/jpeg" to "jpg", "image/png" to "png",
        "image/svg+xml" to "svg", "image/tiff" to "tiff", "image/webp" to "webp", "application/pdf" to "pdf",
        "text/plain" to "txt",
    )

    /**
     * The largest file the app sends: what the workspace allows ([workspaceMax], Notion says it
     * for each token: 5 MiB on a free plan), and never more than one request takes.
     */
    fun limit(workspaceMax: Long?): Long =
        workspaceMax?.takeIf { it > 0 }?.coerceAtMost(SINGLE_PART_MAX_BYTES) ?: SINGLE_PART_MAX_BYTES

    fun fits(size: Long, limit: Long): Boolean = size in 1..limit

    /** The limit in whole megabytes (MiB) for the message that a file is too big, rounded down. */
    fun megabytes(bytes: Long): Long = (bytes / (1024 * 1024)).coerceAtLeast(1)

    /** Notion's block for a file of [mimeType]: "image" for the images it can show, else "file". */
    fun blockType(mimeType: String): String = if (mimeType.lowercase() in IMAGE_TYPES) "image" else "file"

    fun isImage(mimeType: String): Boolean = blockType(mimeType) == "image"

    /**
     * The name the file gets in Notion: the picked name, with an extension when it has none and
     * one is known for its kind (Notion checks the extension against the kind).
     */
    fun fileName(name: String?, mimeType: String): String {
        val base = name?.trim()?.takeIf { it.isNotEmpty() } ?: "file"
        if (base.substringAfterLast('/').contains('.')) return base
        val ext = EXTENSIONS[mimeType.lowercase()] ?: return base
        return "$base.$ext"
    }

    /**
     * [blocks] with [block] after the block [anchorKey] and what is nested in it, at its depth and
     * in its parent; at the end of the page when [anchorKey] is null or not there. [byNotionId]:
     * the anchor is found by its Notion ID (Notion's copy, which has no keys of its own here).
     */
    fun insertAfter(blocks: List<Block>, anchorKey: String?, block: Block, byNotionId: Boolean = false): List<Block> {
        val index = anchorKey?.let { key -> blocks.indexOfFirst { if (byNotionId) it.notionId == key else it.key == key } } ?: -1
        if (index < 0) return blocks + block.copy(depth = 0, parentId = null)
        val anchor = blocks[index]
        var end = index + 1
        while (end < blocks.size && blocks[end].depth > anchor.depth) end++
        val placed = block.copy(depth = anchor.depth, parentId = ContentDiff.parentIndexes(blocks)[index]?.let { blocks[it].notionId })
        return blocks.subList(0, end) + placed + blocks.subList(end, blocks.size)
    }

    /**
     * Where a new file block goes in Notion: in the anchor's parent (null: the page), after the
     * anchor. Null when the anchor isn't in Notion yet (it has to be sent first).
     */
    fun target(blocks: List<Block>, anchorKey: String?): Target? {
        val index = anchorKey?.let { key -> blocks.indexOfFirst { it.key == key } } ?: -1
        if (index < 0) return Target(parentId = null, afterId = null)
        val anchor = blocks[index]
        val afterId = anchor.notionId ?: return null
        val parent = ContentDiff.parentIndexes(blocks)[index]?.let { blocks[it] }
        if (parent != null && parent.notionId == null) return null
        return Target(parentId = parent?.notionId, afterId = afterId)
    }

    /**
     * The line a file on its way shows under: the last line of [anchorKey]'s nested content (or
     * the line itself), where the file's block will go. Null: at the end of the note.
     */
    fun shownAfter(blocks: List<Block>, anchorKey: String?): String? {
        val index = anchorKey?.let { key -> blocks.indexOfFirst { it.key == key } } ?: -1
        if (index < 0) return null
        var end = index
        while (end + 1 < blocks.size && blocks[end + 1].depth > blocks[index].depth) end++
        return blocks[end].key
    }

    /** [parentId]: the block it goes into (null: the page); [afterId]: the block it follows (null: at the end). */
    data class Target(val parentId: String?, val afterId: String?)
}
