package be.wimvandonck.noter.logic

import be.wimvandonck.noter.data.model.NotionView

/** The kinds of view the app can add: a table or a list. Board, calendar and the rest are made in Notion. */
enum class NewViewType(val type: String) {
    TABLE("table"),
    LIST("list"),
}

/**
 * Light changes to a database's Notion views from the database screen (Notion's Views API): a new
 * table or list view, a new name, or deleting a view. Filters, sorts, grouping and every other
 * setting stay in Notion.
 */
object ViewEdits {
    /** What Notion's `POST /views` is sent for a new view [name] of [type] on data source [dataSourceId] of database [databaseId]. */
    fun createBody(databaseId: String, dataSourceId: String, name: String, type: NewViewType): Map<String, Any?> = mapOf(
        "database_id" to databaseId,
        "data_source_id" to dataSourceId,
        "name" to name.trim(),
        "type" to type.type,
    )

    /** What Notion's `PATCH /views/{id}` is sent to rename a view (only the name; the rest stays as it is). */
    fun renameBody(name: String): Map<String, Any?> = mapOf("name" to name.trim())

    /** A name Notion can take: not empty. */
    fun validName(name: String): Boolean = name.isNotBlank()

    /** A rename that changes something: a valid name unlike [old] (spaces at the ends don't count). */
    fun renames(old: String, name: String): Boolean = validName(name) && name.trim() != old.trim()

    /**
     * Whether a view can be deleted when the data source shows [shown] views: not its last one (Notion
     * never deletes a database's last view, and a data source without one would show nothing here).
     */
    fun canDelete(shown: List<NotionView>): Boolean = shown.size > 1

    /** [views] with view [viewId] named [name]. */
    fun renamed(views: List<NotionView>, viewId: String, name: String): List<NotionView> =
        views.map { if (plain(it.id) == plain(viewId)) it.copy(name = name.trim()) else it }

    /** [views] without view [viewId]. */
    fun without(views: List<NotionView>, viewId: String): List<NotionView> = views.filterNot { plain(it.id) == plain(viewId) }

    /** [views] with [view] added at the end (Notion adds a new view after the others), or put in place of the same view. */
    fun added(views: List<NotionView>, view: NotionView): List<NotionView> =
        if (views.any { plain(it.id) == plain(view.id) }) views.map { if (plain(it.id) == plain(view.id)) view else it } else views + view

    /** Notion's web link to view [viewId] of the database at [databaseUrl], where its filters and sorts are set. */
    fun notionUrl(databaseUrl: String, viewId: String): String = databaseUrl + "?v=" + plain(viewId)

    private fun plain(id: String) = id.replace("-", "")
}
