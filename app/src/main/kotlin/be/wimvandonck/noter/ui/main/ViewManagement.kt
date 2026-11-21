package be.wimvandonck.noter.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import be.wimvandonck.noter.R
import be.wimvandonck.noter.logic.NewViewType
import be.wimvandonck.noter.logic.ViewEdits
import be.wimvandonck.noter.ui.common.errorText

/**
 * The menu of the view shown (a tap on its tab again): Rename, Delete (not the last view), its
 * filters and sorts in Notion, and New view. Anything else about a view is set in Notion.
 */
@Composable
internal fun ViewMenu(
    expanded: Boolean,
    canDelete: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onOpenInNotion: () -> Unit,
    onNewView: () -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_rename)) },
            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
            onClick = { onDismiss(); onRename() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.view_filters_in_notion)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) },
            onClick = { onDismiss(); onOpenInNotion() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.new_view)) },
            leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
            onClick = { onDismiss(); onNewView() },
        )
        // Destructive, so apart from the rest (and it asks first).
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.delete_view_confirm)) },
            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
            enabled = canDelete,
            onClick = { onDismiss(); onDelete() },
        )
    }
}

/** New view, Rename view or Delete view, as [state] says; Notion's refusal shows in the dialog. */
@Composable
internal fun ViewEditDialog(
    state: ViewEditState,
    onCreate: (name: String, type: NewViewType) -> Unit,
    onRename: (name: String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state.kind) {
        ViewEditState.Kind.NEW -> NewViewDialog(state, onCreate, onDismiss)
        ViewEditState.Kind.RENAME -> RenameViewDialog(state, onRename, onDismiss)
        ViewEditState.Kind.DELETE -> DeleteViewDialog(state, onDelete, onDismiss)
    }
}

/** A name and a type (Table or List); filters, sorts and grouping are set in Notion. */
@Composable
private fun NewViewDialog(state: ViewEditState, onCreate: (String, NewViewType) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(NewViewType.TABLE) }
    val enabled = !state.busy
    val typeNames = mapOf(
        NewViewType.TABLE to stringResource(R.string.view_type_table),
        NewViewType.LIST to stringResource(R.string.view_type_list),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_view)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NameField(name, { name = it }, enabled, onDone = { if (ViewEdits.validName(name)) onCreate(name, type) })
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    NewViewType.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = type == option,
                            onClick = { type = option },
                            shape = SegmentedButtonDefaults.itemShape(index, NewViewType.entries.size),
                            enabled = enabled,
                        ) { Text(typeNames.getValue(option)) }
                    }
                }
                Hint(stringResource(R.string.new_view_hint))
                state.error?.let { Failure(it) }
            }
        },
        confirmButton = {
            BusyButton(stringResource(R.string.new_database_create), state.busy, enabled = enabled && ViewEdits.validName(name)) { onCreate(name, type) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = enabled) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun RenameViewDialog(state: ViewEditState, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(state.view?.name.orEmpty()) }
    val enabled = !state.busy
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_view)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NameField(name, { name = it }, enabled, onDone = { if (ViewEdits.validName(name)) onRename(name) })
                state.error?.let { Failure(it) }
            }
        },
        confirmButton = {
            BusyButton(stringResource(R.string.action_save), state.busy, enabled = enabled && ViewEdits.validName(name)) { onRename(name) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = enabled) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun DeleteViewDialog(state: ViewEditState, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val untitled = stringResource(R.string.untitled)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_view_title, state.view?.name?.ifBlank { untitled } ?: untitled)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.delete_view_body))
                state.error?.let { Failure(it) }
            }
        },
        confirmButton = { BusyButton(stringResource(R.string.delete_view_confirm), state.busy, enabled = !state.busy, onClick = onDelete) },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.busy) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun NameField(name: String, onChange: (String) -> Unit, enabled: Boolean, onDone: () -> Unit) {
    OutlinedTextField(
        value = name,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.view_name)) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Failure(error: String) {
    Text(
        stringResource(R.string.database_edit_failed, errorText(error)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

/** A dialog's confirm button with a small spinner while Notion is asked. */
@Composable
private fun BusyButton(label: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(label)
    }
}
