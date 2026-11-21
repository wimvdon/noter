package be.wimvandonck.noter.logic

import be.wimvandonck.noter.data.model.DatabaseInfo
import be.wimvandonck.noter.data.model.PageIcon
import be.wimvandonck.noter.data.model.PinnedDatabase
import be.wimvandonck.noter.data.model.WorkspaceItem

/**
 * What "Move to…" moves: a page or note, or a database. [ids] are its own IDs (a database's and its
 * data sources'), [parents] where it is now (the page or data source it is in), so those aren't
 * offered again.
 */
data class MoveSubject(val ids: List<String>, val isDatabase: Boolean, val parents: List<String> = emptyList())

/** A place the subject can be moved to: a page, a database's data source, or the top of the workspace. */
data class MoveDestination(
    val id: String,
    val name: String,
    val icon: PageIcon? = null,
    val kind: Kind,
) {
    enum class Kind { PAGE, DATA_SOURCE, WORKSPACE_TOP }

    /** The new parent as Notion takes it (`POST /pages/{id}/move` and `PATCH /databases/{id}`). */
    fun parentBody(): Map<String, Any?> = when (kind) {
        Kind.PAGE -> mapOf("type" to "page_id", "page_id" to id)
        Kind.DATA_SOURCE -> mapOf("type" to "data_source_id", "data_source_id" to id)
        Kind.WORKSPACE_TOP -> mapOf("type" to "workspace", "workspace" to true)
    }
}

/**
 * The places the subject can go, all in its own workspace (Notion can't move anything to another
 * one): [workspace] is that workspace (null when it isn't known), [top] whether the top of the
 * workspace is one of them (databases only, and not with a connection's token).
 */
data class MoveOptions(
    val workspace: Workspace?,
    val top: Boolean,
    val destinations: List<MoveDestination>,
)

/** The places "Move to…" offers, from what Home knows (the databases and pages of its list, and the pinned databases). */
object MoveTargets {
    /**
     * The places [subject] can be moved to whose name has every word of [query] (all when blank):
     * - a page or note: another database's data source, or a page;
     * - a database: a page, or the top of the workspace ([MoveOptions.top]).
     * Not itself, what it is in now, or (for a page) what is inside it. With several [workspaces]
     * only those in the subject's own; when that isn't known, none. Best matches first.
     */
    fun options(
        subject: MoveSubject,
        recent: List<WorkspaceItem>?,
        pinned: List<PinnedDatabase>,
        infos: Map<String, DatabaseInfo>,
        owners: Map<String, String>,
        workspaces: List<Workspace>,
        query: String = "",
    ): MoveOptions {
        val several = workspaces.size > 1
        val own = Workspaces.ownerOf(subject.ids, owners, workspaces) ?: workspaces.singleOrNull()
        fun inOwnWorkspace(id: String): Boolean =
            !several || (own != null && Workspaces.ownerOf(listOf(id), owners, workspaces)?.id == own.id)

        val items = recent.orEmpty()
        val excluded = (subject.ids + subject.parents).map(::plain).toSet()
        val inside = descendants(subject.ids.map(::plain).toSet(), items)

        val pages = items
            .filter { !it.isDatabase && plain(it.id) !in excluded && plain(it.id) !in inside && inOwnWorkspace(it.id) }
            .map { MoveDestination(it.id, it.title, it.icon, MoveDestination.Kind.PAGE) }
        val databases = if (subject.isDatabase) {
            emptyList()
        } else {
            NoteTargets.of(pinned, recent, infos)
                .filter { plain(it.databaseId) !in excluded && plain(it.databaseId) !in inside && inOwnWorkspace(it.databaseId) }
                .map { MoveDestination(it.databaseId, it.name, it.icon, MoveDestination.Kind.DATA_SOURCE) }
        }
        val top = subject.isDatabase && query.isBlank() && own?.canAddTopLevel == true
        return MoveOptions(own, top, matching(databases + pages, query))
    }

    /** [destinations] whose name has every word of [query], the best match first; all of them when it is blank. */
    fun matching(destinations: List<MoveDestination>, query: String): List<MoveDestination> {
        if (query.isBlank()) return destinations
        val words = NoteSearch.plain(query).split(' ').filter { it.isNotEmpty() }
        return destinations
            .filter { dest -> NoteSearch.plain(dest.name).let { name -> words.all(name::contains) } }
            .sortedBy { SearchResults.score(it.name, query) }
    }

    /** The IDs (without dashes) of everything in [items] that is inside one of [roots], at any depth. */
    private fun descendants(roots: Set<String>, items: List<WorkspaceItem>): Set<String> {
        val found = mutableSetOf<String>()
        var frontier = roots
        while (frontier.isNotEmpty()) {
            val next = items
                .filter { item -> item.parentId?.let(::plain) in frontier && plain(item.id) !in found && plain(item.id) !in roots }
                .map { plain(it.id) }
                .toSet()
            found += next
            frontier = next
        }
        return found
    }

    /**
     * Home's list after the move of [movedIds] to [destination]: a page or database moved to a page
     * is in it now, one moved to the top of the workspace is at the top, and a page moved into a
     * database is a note there, which the list leaves out.
     */
    fun afterMove(items: List<WorkspaceItem>, movedIds: Set<String>, destination: MoveDestination): List<WorkspaceItem> {
        val moved = movedIds.map(::plain).toSet()
        return when (destination.kind) {
            MoveDestination.Kind.DATA_SOURCE -> items.filterNot { plain(it.id) in moved }
            MoveDestination.Kind.PAGE -> items.map { if (plain(it.id) in moved) it.copy(parentId = destination.id) else it }
            MoveDestination.Kind.WORKSPACE_TOP -> items.map { if (plain(it.id) in moved) it.copy(parentId = null) else it }
        }
    }

    private fun plain(id: String) = id.replace("-", "")
}
