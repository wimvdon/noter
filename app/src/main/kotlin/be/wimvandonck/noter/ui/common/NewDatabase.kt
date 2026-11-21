package be.wimvandonck.noter.ui.common

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import be.wimvandonck.noter.R
import be.wimvandonck.noter.data.NotesRepository
import be.wimvandonck.noter.data.api.NotionErrors
import be.wimvandonck.noter.data.api.NotionException
import be.wimvandonck.noter.logic.DatabaseStarter
import be.wimvandonck.noter.logic.NewDatabases
import be.wimvandonck.noter.logic.NewProperty
import be.wimvandonck.noter.logic.PropertyKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The New database dialog while it is open (#71): where the database goes ([parentPageId], null:
 * the top of [workspace]), whether Notion is making it, and what went wrong.
 */
data class NewDatabaseState(
    val parentPageId: String?,
    val workspace: String? = null,
    val creating: Boolean = false,
    val error: String? = null,
)

/** Opens, sends and closes the New database dialog for a ViewModel (Home's +, a page's ⋮). */
class DatabaseCreation(private val scope: CoroutineScope, private val repository: NotesRepository) {
    private val _state = MutableStateFlow<NewDatabaseState?>(null)

    /** The open dialog, null when it is closed. */
    val state: StateFlow<NewDatabaseState?> get() = _state

    fun open(parentPageId: String?, workspace: String? = null) {
        _state.value = NewDatabaseState(parentPageId, workspace)
    }

    fun dismiss() {
        if (_state.value?.creating != true) _state.value = null
    }

    /** Asks Notion for the database; [onCreated] gets the ID to open it by. The dialog stays open with the error when it fails. */
    fun create(
        title: String,
        titleName: String,
        properties: List<NewProperty>,
        names: Map<PropertyKind, String>,
        starter: DatabaseStarter,
        inline: Boolean,
        onCreated: (String) -> Unit,
    ) {
        val target = _state.value ?: return
        if (target.creating || (title.isBlank() && starter == DatabaseStarter.EMPTY)) return
        _state.value = target.copy(creating = true, error = null)
        scope.launch {
            try {
                val id = repository.createDatabase(NewDatabases.request(title, target.parentPageId, titleName, properties, names, starter, inline), target.workspace)
                _state.value = null
                onCreated(id)
            } catch (e: NotionException) {
                _state.value = target.copy(creating = false, error = NotionErrors.of(e))
            }
        }
    }
}

/** The names Notion gives a new property of each kind, in the user's language. */
@Composable
internal fun kindNames(): Map<PropertyKind, String> = mapOf(
    PropertyKind.TEXT to stringResource(R.string.property_kind_text),
    PropertyKind.NUMBER to stringResource(R.string.property_kind_number),
    PropertyKind.SELECT to stringResource(R.string.property_kind_select),
    PropertyKind.MULTI_SELECT to stringResource(R.string.property_kind_multi_select),
    PropertyKind.STATUS to stringResource(R.string.property_kind_status),
    PropertyKind.DATE to stringResource(R.string.property_kind_date),
    PropertyKind.PERSON to stringResource(R.string.property_kind_person),
    PropertyKind.FILES to stringResource(R.string.property_kind_files),
    PropertyKind.CHECKBOX to stringResource(R.string.property_kind_checkbox),
    PropertyKind.URL to stringResource(R.string.property_kind_url),
    PropertyKind.EMAIL to stringResource(R.string.property_kind_email),
    PropertyKind.PHONE to stringResource(R.string.property_kind_phone),
    PropertyKind.CREATED_TIME to stringResource(R.string.property_kind_created_time),
    PropertyKind.CREATED_BY to stringResource(R.string.property_kind_created_by),
    PropertyKind.LAST_EDITED_TIME to stringResource(R.string.property_kind_last_edited_time),
    PropertyKind.LAST_EDITED_BY to stringResource(R.string.property_kind_last_edited_by),
)

/** The properties being set up, kept across rotation as "KIND name options" strings. */
private val PropertiesSaver = listSaver<List<NewProperty>, String>(
    save = { list -> list.map { it.kind.name + "\u0000" + it.name + "\u0000" + it.options } },
    restore = { saved -> saved.mapNotNull { line -> line.split("\u0000", limit = 3).takeIf { it.size == 3 }?.let { NewProperty(PropertyKind.valueOf(it[0]), it[1], it[2]) } } },
)

/**
 * A database's name and what it starts from: Empty (its properties besides the title: Tags at
 * first, as in Notion; Add property adds one of any kind the API can make, named as Notion names
 * it, several of a kind allowed, with starting options for a select) or one of Notion's Tasks and
 * Projects, which come with their own properties. Inside a page it can show in the page. Anything
 * else (formats, views) is changed in Notion afterwards.
 */
@Composable
fun NewDatabaseDialog(
    state: NewDatabaseState,
    onCreate: (
        title: String,
        titleName: String,
        properties: List<NewProperty>,
        names: Map<PropertyKind, String>,
        starter: DatabaseStarter,
        inline: Boolean,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    val names = kindNames()
    val titleName = stringResource(R.string.new_database_title_property)
    val tags = stringResource(R.string.new_database_tags)
    var title by rememberSaveable { mutableStateOf("") }
    var properties by rememberSaveable(stateSaver = PropertiesSaver) {
        mutableStateOf(NewDatabases.DEFAULT_KINDS.fold(emptyList<NewProperty>()) { list, kind -> NewDatabases.add(list, kind, tags, titleName) })
    }
    var starter by rememberSaveable { mutableStateOf(DatabaseStarter.EMPTY) }
    var inline by rememberSaveable { mutableStateOf(false) }
    var choosingKind by remember { mutableStateOf(false) }
    val enabled = !state.creating
    val starterNames = mapOf(
        DatabaseStarter.EMPTY to stringResource(R.string.new_database_starter_empty),
        DatabaseStarter.TASKS to stringResource(R.string.new_database_starter_tasks),
        DatabaseStarter.PROJECTS to stringResource(R.string.new_database_starter_projects),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_database)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.new_database_name)) },
                    placeholder = if (starter != DatabaseStarter.EMPTY) ({ Text(starterNames.getValue(starter)) }) else null,
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    DatabaseStarter.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = starter == option,
                            onClick = { starter = option },
                            shape = SegmentedButtonDefaults.itemShape(index, DatabaseStarter.entries.size),
                            enabled = enabled,
                        ) { Text(starterNames.getValue(option)) }
                    }
                }
                if (starter != DatabaseStarter.EMPTY) {
                    Text(
                        stringResource(R.string.new_database_starter_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        stringResource(R.string.new_database_properties),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (starter == DatabaseStarter.EMPTY) properties.forEachIndexed { index, property ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedTextField(
                                value = property.name,
                                onValueChange = { name -> properties = properties.toMutableList().also { it[index] = property.copy(name = name) } },
                                label = { Text(names.getValue(property.kind)) },
                                singleLine = true,
                                enabled = enabled,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (property.takesOptions) {
                                OutlinedTextField(
                                    value = property.options,
                                    onValueChange = { text -> properties = properties.toMutableList().also { it[index] = property.copy(options = text) } },
                                    label = { Text(stringResource(R.string.new_database_options)) },
                                    placeholder = { Text(stringResource(R.string.new_database_options_hint)) },
                                    singleLine = true,
                                    enabled = enabled,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        IconButton(onClick = { properties = properties.filterIndexed { i, _ -> i != index } }, enabled = enabled) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.new_database_remove_property, property.name))
                        }
                    }
                }
                if (starter == DatabaseStarter.EMPTY) Box {
                    TextButton(onClick = { choosingKind = true }, enabled = enabled) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.new_database_add_property))
                    }
                    DropdownMenu(expanded = choosingKind, onDismissRequest = { choosingKind = false }) {
                        PropertyKind.entries.forEach { kind ->
                            DropdownMenuItem(
                                text = { Text(names.getValue(kind)) },
                                onClick = {
                                    choosingKind = false
                                    properties = NewDatabases.add(properties, kind, names.getValue(kind), titleName)
                                },
                            )
                        }
                    }
                }
                if (state.parentPageId != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.new_database_inline),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = inline, onCheckedChange = { inline = it }, enabled = enabled)
                    }
                }
                state.error?.let {
                    Text(
                        stringResource(R.string.new_database_failed, errorText(it)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(title, titleName, properties, names, starter, inline) },
                enabled = (title.isNotBlank() || starter != DatabaseStarter.EMPTY) && enabled,
            ) {
                if (state.creating) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.new_database_create))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = enabled) { Text(stringResource(R.string.cancel)) } },
    )
}

/** After a new database opens: its properties and views are changed in Notion. */
fun showDatabaseCreatedToast(context: Context) {
    Toast.makeText(context, R.string.new_database_created, Toast.LENGTH_LONG).show()
}
