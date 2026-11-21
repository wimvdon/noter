package be.wimvandonck.noter.ui.common

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Workspaces
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import be.wimvandonck.noter.R
import be.wimvandonck.noter.data.NotesRepository
import be.wimvandonck.noter.data.api.NotionErrors
import be.wimvandonck.noter.data.api.NotionException
import be.wimvandonck.noter.data.model.DatabaseInfo
import be.wimvandonck.noter.data.model.PageIcon
import be.wimvandonck.noter.logic.MoveDestination
import be.wimvandonck.noter.logic.MoveOptions
import be.wimvandonck.noter.logic.MoveSubject
import be.wimvandonck.noter.logic.MoveTargets
import be.wimvandonck.noter.logic.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What "Move to…" moves, and where it is asked from. */
sealed interface MoveTarget {
    /** A page or note. */
    data class Page(val pageId: String) : MoveTarget

    /** A database ([databaseId] null: asked from Notion by its data source [sourceId]). */
    data class Database(val databaseId: String?, val sourceId: String) : MoveTarget
}

/** The "Move to…" sheet while it is open. */
data class MoveUiState(
    val target: MoveTarget,
    /** The name of what is moved (blank: untitled). */
    val title: String,
    val query: String = "",
    /** Null until Home's list is known. */
    val options: MoveOptions? = null,
    val severalWorkspaces: Boolean = false,
    val moving: Boolean = false,
    /** What went wrong (see NotionErrors), shown in the sheet; it stays open to try again. */
    val error: String? = null,
) {
    val isDatabase: Boolean get() = target is MoveTarget.Database
}

/**
 * "Move to…" for a ViewModel (a page's ⋮, a database's ⋮): which places the item can go to (from
 * Home's list and the pinned databases, in its own workspace, see MoveTargets), and sending the
 * move to Notion at once. Nothing is queued: when Notion refuses or can't be reached the sheet
 * stays open with the error.
 */
class MoveController(
    private val scope: CoroutineScope,
    private val repository: NotesRepository,
    workspaces: Flow<List<Workspace>>,
) {
    private data class Open(val target: MoveTarget, val subject: MoveSubject, val title: String)
    private data class Progress(val query: String = "", val moving: Boolean = false, val error: String? = null)

    private val open = MutableStateFlow<Open?>(null)
    private val progress = MutableStateFlow(Progress())
    private val infos = MutableStateFlow<Map<String, DatabaseInfo>>(emptyMap())

    private val places = combine(repository.recent, repository.settingsFlow(), infos) { recent, settings, known -> Triple(recent, settings.pinnedDatabases, known) }

    /** The open sheet, null when it is closed. */
    val state: StateFlow<MoveUiState?> = combine(open, progress, places, combine(repository.owners, workspaces, ::Pair)) { opened, now, (recent, pins, known), (owners, spaces) ->
        opened?.let {
            MoveUiState(
                target = it.target,
                title = it.title,
                query = now.query,
                options = recent?.let { _ -> MoveTargets.options(it.subject, recent, pins, known, owners, spaces, now.query) },
                severalWorkspaces = spaces.size > 1,
                moving = now.moving,
                error = now.error,
            )
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** Opens the sheet for page or note [pageId] called [title]. */
    fun openPage(pageId: String, title: String) {
        val id = repository.resolve(pageId)
        scope.launch {
            val note = repository.note(id).firstOrNull()
            val parents = listOfNotNull(
                note?.databaseId?.takeIf { it != NotesRepository.PAGES },
                repository.knownItem(id)?.parentId,
            )
            show(Open(MoveTarget.Page(id), MoveSubject(listOf(id), isDatabase = false, parents = parents), title))
        }
    }

    /** Opens the sheet for the database whose data source [sourceId] is shown, called [title]. */
    fun openDatabase(databaseId: String?, sourceId: String, title: String) {
        scope.launch {
            val info = databaseId?.let { repository.databaseInfo(it, fresh = false) }
            val ids = listOfNotNull(databaseId, sourceId) + info?.sources.orEmpty().map { it.id }
            val parents = listOfNotNull(info?.parentId ?: repository.knownItem(sourceId)?.parentId)
            show(Open(MoveTarget.Database(databaseId, sourceId), MoveSubject(ids.distinct(), isDatabase = true, parents = parents), title))
        }
    }

    private fun show(opened: Open) {
        progress.value = Progress()
        open.value = opened
        scope.launch {
            // A pinned database of several data sources offers each of them.
            repository.currentSettings().pinnedDatabases.forEach { pin ->
                repository.databaseInfo(pin.id, fresh = false)?.let { info -> infos.update { it + (pin.id to info) } }
            }
        }
    }

    fun search(query: String) {
        progress.update { it.copy(query = query) }
    }

    fun dismiss() {
        if (!progress.value.moving) open.value = null
    }

    /** Moves the item to [destination]; [onMoved] is called with it once Notion has done it. */
    fun move(destination: MoveDestination, onMoved: (MoveDestination) -> Unit) {
        val opened = open.value ?: return
        if (progress.value.moving) return
        progress.update { it.copy(moving = true, error = null) }
        scope.launch {
            try {
                when (val target = opened.target) {
                    is MoveTarget.Page -> repository.moveNote(target.pageId, destination)
                    is MoveTarget.Database -> repository.moveDatabase(target.databaseId, target.sourceId, destination)
                }
                progress.value = Progress()
                open.value = null
                onMoved(destination)
            } catch (e: NotionException) {
                progress.update { it.copy(moving = false, error = NotionErrors.of(e)) }
            }
        }
    }
}

/**
 * The "Move to…" sheet: a search field, then the places the item can go to, each a row (a page or
 * a database, with its icon), and for a database the top of the workspace first. Tapping one moves it.
 */
@Composable
fun MoveSheet(
    state: MoveUiState,
    onSearch: (String) -> Unit,
    onPick: (MoveDestination) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = state.options
    val enabled = !state.moving
    NoterSheet(onDismiss = onDismiss) {
        Text(
            stringResource(R.string.action_move_to),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp).semantics { heading() },
        )
        Text(
            state.title.ifBlank { stringResource(R.string.untitled) },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        val workspaceName = options?.workspace?.name
        if (state.severalWorkspaces && !workspaceName.isNullOrBlank()) {
            Text(
                workspaceName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp),
            )
        }
        var query by rememberSaveable { mutableStateOf("") }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; onSearch(it) },
            label = { Text(stringResource(R.string.search)) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (!state.isDatabase && options?.destinations?.any { it.kind == MoveDestination.Kind.DATA_SOURCE } == true) {
            Text(
                stringResource(R.string.move_hint_properties),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
        state.error?.let {
            Text(
                stringResource(R.string.move_failed, errorText(it)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
        if (state.moving) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
        when {
            options == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp))
            !options.top && options.destinations.isEmpty() -> Text(
                stringResource(if (state.query.isBlank()) R.string.move_nowhere else R.string.browse_nothing_found),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            else -> LazyColumn(Modifier.fillMaxWidth()) {
                if (options.top) {
                    item(key = "__top") {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.new_page_top_level)) },
                            leadingContent = { Icon(Icons.Rounded.Workspaces, contentDescription = null) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            modifier = Modifier.clickable(enabled = enabled) { onPick(MoveDestination("", "", null, MoveDestination.Kind.WORKSPACE_TOP)) },
                        )
                    }
                }
                items(options.destinations, key = { it.kind.name + it.id }) { destination ->
                    DestinationRow(destination, enabled, onClick = { onPick(destination) })
                }
                item(key = "__end") { Box(Modifier.padding(bottom = 24.dp)) }
            }
        }
    }
}

@Composable
private fun DestinationRow(destination: MoveDestination, enabled: Boolean, onClick: () -> Unit) {
    val isDatabase = destination.kind == MoveDestination.Kind.DATA_SOURCE
    ListItem(
        headlineContent = { Text(destination.name.ifBlank { stringResource(R.string.untitled) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(stringResource(if (isDatabase) R.string.move_kind_database else R.string.move_kind_page)) },
        leadingContent = { DestinationIcon(destination.icon, isDatabase) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

@Composable
private fun DestinationIcon(icon: PageIcon?, isDatabase: Boolean) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        if (icon != null) {
            PageIconView(icon, size = 24.dp)
        } else {
            Icon(if (isDatabase) Icons.Rounded.TableChart else Icons.Rounded.Description, contentDescription = null)
        }
    }
}

/** The name shown after a move ("Moved to …"): the top of the workspace has none of its own. */
fun movedName(resources: Resources, destination: MoveDestination): String =
    when {
        destination.kind == MoveDestination.Kind.WORKSPACE_TOP -> resources.getString(R.string.new_page_top_level)
        destination.name.isBlank() -> resources.getString(R.string.untitled)
        else -> destination.name
    }
