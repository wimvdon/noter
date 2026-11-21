package be.wimvandonck.noter.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import be.wimvandonck.noter.NoterApp
import be.wimvandonck.noter.R
import kotlinx.coroutines.flow.filterNotNull

/**
 * Shows "Moved to trash" with Undo in [snackbar] after a note was moved to the trash. A note's
 * screen closes right after trashing, so the screen it returns to shows this (the note's own
 * screen passes its page ID in [ignore] and leaves it alone).
 */
@Composable
fun TrashUndoEffect(snackbar: SnackbarHostState, ignore: String? = null) {
    val repository = (LocalContext.current.applicationContext as NoterApp).repository
    val resources = LocalResources.current
    LaunchedEffect(repository, ignore) {
        repository.lastTrashed.filterNotNull().collect { kept ->
            if (ignore != null && repository.resolve(ignore) == repository.resolve(kept.note.id)) return@collect
            repository.trashNoticeShown(kept)
            val result = snackbar.showSnackbar(
                resources.getString(R.string.trash_moved),
                actionLabel = resources.getString(R.string.undo),
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) repository.undoTrash(kept)
        }
    }
}
