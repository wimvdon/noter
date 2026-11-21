package be.wimvandonck.noter.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import be.wimvandonck.noter.NoterApp
import be.wimvandonck.noter.data.NotesRepository
import be.wimvandonck.noter.data.api.NotionErrors
import be.wimvandonck.noter.data.api.NotionException
import be.wimvandonck.noter.data.model.TrashedPage
import be.wimvandonck.noter.data.store.ApiKeyStore
import be.wimvandonck.noter.logic.TrashList
import be.wimvandonck.noter.logic.Workspace
import be.wimvandonck.noter.logic.WorkspaceGroup
import be.wimvandonck.noter.logic.Workspaces
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What Settings' Trash screen shows. [pages] is null until Notion answered. */
data class TrashUiState(
    val loading: Boolean = true,
    val pages: List<TrashedPage>? = null,
    /** Why the list couldn't be loaded (an error code for `errorText`). */
    val error: String? = null,
    /** Every workspace with a token. */
    val workspaces: List<Workspace> = emptyList(),
    /** Pages split by workspace (several workspaces only; otherwise empty). */
    val groups: List<WorkspaceGroup<TrashedPage>> = emptyList(),
    /** Pages being restored right now (their IDs). */
    val restoring: Set<String> = emptySet(),
)

class TrashViewModel(
    private val repository: NotesRepository,
    keyStore: ApiKeyStore,
) : ViewModel() {
    private val loaded = MutableStateFlow(TrashUiState())

    private val _messages = MutableSharedFlow<TrashMessage>(extraBufferCapacity = 4)

    /** One-off results for a snackbar. */
    val messages: SharedFlow<TrashMessage> = _messages

    sealed interface TrashMessage {
        data object Restored : TrashMessage

        data class Failed(val detail: String) : TrashMessage
    }

    val state: StateFlow<TrashUiState> = combine(
        loaded,
        combine(keyStore.main, keyStore.extras) { main, extras -> Workspaces.of(listOf(main) + extras) },
        repository.owners,
    ) { ui, workspaces, owners ->
        val pages = ui.pages
        ui.copy(workspaces = workspaces, groups = if (pages != null && workspaces.size > 1) TrashList.groups(pages, owners, workspaces) else emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrashUiState())

    init {
        load()
    }

    /** Asks Notion for the trash again (what is shown stays while it does). */
    fun load() {
        loaded.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val pages = repository.trashedPages()
                loaded.update { it.copy(loading = false, pages = pages, error = null) }
            } catch (e: NotionException) {
                // What is shown stays when asking again fails; only an empty screen shows the error.
                val shown = loaded.value.pages != null
                loaded.update { it.copy(loading = false, error = if (shown) null else NotionErrors.of(e)) }
                if (shown) _messages.tryEmit(TrashMessage.Failed(NotionErrors.of(e)))
            }
        }
    }

    /** Takes [page] out of the trash (online only); it leaves the list when Notion has done it. */
    fun restore(page: TrashedPage) {
        if (page.id in loaded.value.restoring) return
        loaded.update { it.copy(restoring = it.restoring + page.id) }
        viewModelScope.launch {
            try {
                repository.restoreFromTrash(page)
                loaded.update { it.copy(pages = it.pages?.let { pages -> TrashList.without(pages, page.id) }) }
                _messages.tryEmit(TrashMessage.Restored)
            } catch (e: NotionException) {
                _messages.tryEmit(TrashMessage.Failed(NotionErrors.of(e)))
            } finally {
                loaded.update { it.copy(restoring = it.restoring - page.id) }
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as NoterApp
                TrashViewModel(app.repository, app.apiKeyStore)
            }
        }
    }
}
