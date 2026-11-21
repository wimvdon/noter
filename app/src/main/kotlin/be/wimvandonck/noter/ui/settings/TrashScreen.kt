package be.wimvandonck.noter.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.wimvandonck.noter.R
import be.wimvandonck.noter.data.model.TrashedPage
import be.wimvandonck.noter.logic.NotionDates
import be.wimvandonck.noter.ui.common.PageIconView
import be.wimvandonck.noter.ui.common.errorText
import be.wimvandonck.noter.ui.main.ListHeader
import be.wimvandonck.noter.ui.main.LoadFailed
import be.wimvandonck.noter.ui.main.LoadingRows
import be.wimvandonck.noter.ui.theme.EmptyState
import be.wimvandonck.noter.ui.theme.readableWidth

/**
 * Settings → Trash: the pages in Notion's trash (Notion empties it after 30 days), each with its
 * icon, title, the place it was in and Restore. Needs a connection; the usual loading, error and
 * empty states otherwise (DESIGN.md, "Empty and setup states"). Restoring puts the page back in
 * Notion and in Home's list or its database.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(viewModel: TrashViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbar.showSnackbar(
                when (message) {
                    TrashViewModel.TrashMessage.Restored -> resources.getString(R.string.trash_restored)
                    is TrashViewModel.TrashMessage.Failed ->
                        resources.getString(R.string.trash_restore_failed, errorText(resources, message.detail))
                },
            )
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_trash)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading && state.pages != null,
            onRefresh = viewModel::load,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            val pages = state.pages
            val error = state.error
            when {
                pages == null && error != null -> LoadFailed(error, viewModel::load)
                pages == null -> LoadingRows()
                pages.isEmpty() -> EmptyState(
                    Icons.Rounded.DeleteOutline,
                    stringResource(R.string.trash_empty_title),
                    body = stringResource(R.string.trash_empty_body),
                )
                else -> LazyColumn(Modifier.fillMaxSize().readableWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (state.groups.isEmpty()) {
                        items(pages, key = { it.id }) { page -> TrashRow(page, page.id in state.restoring, viewModel::restore) }
                    } else {
                        state.groups.forEach { group ->
                            item(key = "h:" + (group.workspace?.id ?: "")) {
                                ListHeader(group.workspace?.name?.ifBlank { null } ?: stringResource(R.string.workspace_unnamed))
                            }
                            items(group.items, key = { it.id }) { page -> TrashRow(page, page.id in state.restoring, viewModel::restore) }
                        }
                    }
                }
            }
        }
    }
}

/** One page in the trash: icon, title, where it was and when, and Restore (a spinner while it works). */
@Composable
private fun TrashRow(page: TrashedPage, restoring: Boolean, onRestore: (TrashedPage) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    ListItem(
        headlineContent = {
            Text(page.title.ifBlank { stringResource(R.string.untitled) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            val where = if (page.parentName.isNotBlank()) stringResource(R.string.trash_in, page.parentName) else ""
            val edited = if (page.lastEditedTime.isNotBlank()) stringResource(R.string.browse_edited, NotionDates.display(page.lastEditedTime, locale)) else ""
            val text = listOf(where, edited).filter { it.isNotBlank() }.joinToString(" · ")
            if (text.isNotBlank()) Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (page.icon != null) {
                    PageIconView(page.icon, size = 24.dp)
                } else {
                    Icon(Icons.Rounded.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        trailingContent = {
            if (restoring) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = { onRestore(page) }) { Text(stringResource(R.string.trash_restore)) }
            }
        },
    )
}
