package be.wimvandonck.noter.logic

import be.wimvandonck.noter.data.model.TrashedPage

/** The Trash list in Settings: the pages Notion's trash holds, split by workspace. */
object TrashList {
    /**
     * [pages] by workspace (see [Workspaces.group]); the places a page was in count as well as the
     * page itself, since Notion only tells which workspace answered for what it found.
     */
    fun groups(pages: List<TrashedPage>, owners: Map<String, String>, workspaces: List<Workspace>): List<WorkspaceGroup<TrashedPage>> =
        Workspaces.group(pages, owners, workspaces) { listOf(it.id, it.parentId) }

    /** [pages] without the one with ID [id] (it was restored). */
    fun without(pages: List<TrashedPage>, id: String): List<TrashedPage> =
        pages.filterNot { KeyRouter.normalize(it.id) == KeyRouter.normalize(id) }
}
