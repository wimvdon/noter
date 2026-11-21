package be.wimvandonck.noter.data.notion

import be.wimvandonck.noter.data.api.stringOrNull
import be.wimvandonck.noter.data.model.TrashedPage
import kotlinx.serialization.json.JsonObject

/** Reads the pages Notion's search finds in the trash (see [be.wimvandonck.noter.data.api.NotionClient.searchTrash]). */
object TrashCodec {
    /** The trashed pages in [results], in the same order; anything that isn't a page is left out. */
    fun parseAll(results: List<JsonObject>): List<TrashedPage> = results.mapNotNull(::parse)

    fun parse(json: JsonObject): TrashedPage? {
        if (json.stringOrNull("object") != "page") return null
        val id = json.stringOrNull("id") ?: return null
        val note = PropertyCodec.parsePage(json, "trash") ?: return null
        val database = WorkspaceCodec.parentDatabase(json)
        return TrashedPage(
            id = id,
            title = InlineMarkdown.stripped(note.title),
            icon = note.icon,
            lastEditedTime = note.lastEditedTime,
            parentId = database ?: WorkspaceCodec.parentPage(json),
            parentIsDatabase = database != null,
        )
    }
}
