package be.wimvandonck.noter.ui.note

import android.content.Intent
import android.content.res.Resources
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import be.wimvandonck.noter.R
import be.wimvandonck.noter.data.UploadProblem
import be.wimvandonck.noter.data.model.PropertyValue
import be.wimvandonck.noter.logic.FileUploads
import be.wimvandonck.noter.ui.common.errorText
import be.wimvandonck.noter.ui.theme.AppShapes

/** What the files property editor needs from the note's screen (null: the property is shown read-only). */
class FileActions(
    /** Opens the file picker for property [name]. */
    val onAdd: (name: String) -> Unit,
    /** Removes the file at the index with the name from the property. */
    val onRemove: (property: String, index: Int, name: String) -> Unit,
    /** Files on their way to each property, by property name. */
    val uploading: Map<String, List<FileUploadState>>,
)

/** Why a photo or file couldn't be added, in the user's language. */
fun uploadProblemText(resources: Resources, problem: UploadProblem, detail: String): String = when (problem) {
    UploadProblem.OFFLINE -> resources.getString(R.string.error_offline)
    UploadProblem.NOT_SAVED -> resources.getString(R.string.upload_not_saved)
    UploadProblem.TOO_LARGE, UploadProblem.FAILED ->
        if (detail.isBlank()) resources.getString(R.string.upload_failed) else resources.getString(R.string.upload_failed_detail, errorText(resources, detail))
}

fun tooLargeText(resources: Resources, limitBytes: Long): String =
    resources.getString(R.string.upload_too_large, FileUploads.megabytes(limitBytes).toInt())

/**
 * A photo or file on its way into the note's content: a spinner while it is sent; when it failed,
 * why, with Retry and Remove. Outlined like the blocks the app can't edit.
 */
@Composable
fun UploadPlaceholder(upload: FileUploadState, onRetry: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val failed = upload.problem
    Surface(
        shape = AppShapes.Item,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (failed != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp, end = 8.dp),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = if (failed != null) 0.dp else 12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (failed == null) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                } else {
                    Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                }
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    if (upload.name.isNotEmpty()) {
                        Text(upload.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        if (failed == null) {
                            stringResource(R.string.uploading)
                        } else {
                            uploadProblemText(LocalResources.current, failed, upload.detail)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failed == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (failed != null) {
                Row(Modifier.align(Alignment.End)) {
                    TextButton(onClick = onRemove) { Text(stringResource(R.string.upload_remove)) }
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
            }
        }
    }
}

/**
 * A files property: its files as chips (tap to open, × to remove, after asking) and an "Add file"
 * chip; files on their way show with a spinner.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilesEditor(property: String, value: PropertyValue.Files, actions: FileActions) {
    val context = LocalContext.current
    var removing by remember { mutableStateOf<Pair<Int, String>?>(null) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        value.files.forEachIndexed { index, file ->
            // A tap opens a small menu (open, remove), so removing never sits on a tiny target.
            var menu by remember { mutableStateOf(false) }
            Box {
                AssistChip(
                    onClick = { menu = true },
                    label = { Text(file.name.ifBlank { stringResource(R.string.untitled) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Rounded.AttachFile, null, Modifier.size(AssistChipDefaults.IconSize)) },
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val url = file.url
                    if (url != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_open_link)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) },
                            onClick = {
                                menu = false
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.upload_remove)) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                        onClick = {
                            menu = false
                            removing = index to file.name
                        },
                    )
                }
            }
        }
        actions.uploading[property].orEmpty().forEach { upload ->
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(upload.name.ifEmpty { stringResource(R.string.uploading) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { CircularProgressIndicator(Modifier.size(AssistChipDefaults.IconSize), strokeWidth = 2.dp) },
            )
        }
        AssistChip(
            onClick = { actions.onAdd(property) },
            label = { Text(stringResource(R.string.add_file)) },
            leadingIcon = { Icon(if (value.files.isEmpty()) Icons.Rounded.AttachFile else Icons.Rounded.Add, null, Modifier.size(AssistChipDefaults.IconSize)) },
        )
    }
    removing?.let { (index, name) ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.file_remove_title)) },
            text = { Text(stringResource(R.string.file_remove_body, name)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    actions.onRemove(property, index, name)
                }) { Text(stringResource(R.string.upload_remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
