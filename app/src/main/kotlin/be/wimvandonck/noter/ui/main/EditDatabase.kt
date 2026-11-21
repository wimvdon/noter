package be.wimvandonck.noter.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddReaction
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import be.wimvandonck.noter.R
import be.wimvandonck.noter.data.model.CustomEmoji
import be.wimvandonck.noter.data.model.PageIcon
import be.wimvandonck.noter.data.notion.IconCodec
import be.wimvandonck.noter.logic.EditedProperty
import be.wimvandonck.noter.logic.PropertyKind
import be.wimvandonck.noter.logic.SchemaEdits
import be.wimvandonck.noter.ui.common.PageIconView
import be.wimvandonck.noter.ui.common.PropertySettingsForm
import be.wimvandonck.noter.ui.common.errorText
import be.wimvandonck.noter.ui.common.kindNames
import be.wimvandonck.noter.ui.note.PageIconSheet
import be.wimvandonck.noter.ui.theme.AppShapes
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** The properties being edited, kept across rotation as JSON. */
private val EditedSaver = Saver<List<EditedProperty>, String>(
    save = { Json.encodeToString(ListSerializer(EditedProperty.serializer()), it) },
    restore = { runCatching { Json.decodeFromString(ListSerializer(EditedProperty.serializer()), it) }.getOrNull() },
)

/**
 * Edit database (⋮, or a tap on the database's name or icon): its icon, name and description,
 * then the data source's properties in one screen. A property is renamed in its field; its ⋮ has
 * Rename, Change type and Remove. Add property adds one of every kind Notion's API makes, with a
 * small form for the kinds that need settings (a formula, a relation, a rollup, …). "Advanced"
 * holds the lock in Notion and, inside a page, whether it shows in the page. Save sends
 * everything to Notion at once, after asking when properties or options are removed or types
 * change, since values may go with them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditDatabaseScreen(
    state: DatabaseEditState,
    title: String,
    icon: PageIcon?,
    customEmojis: List<CustomEmoji>,
    onShowIcons: () -> Unit,
    onSave: (DatabaseEditInput) -> Unit,
    onLoadRelated: (String) -> Unit,
    onOpenInNotion: () -> Unit,
    onDismiss: () -> Unit,
) {
    val names = kindNames()
    val properties = state.properties
    val info = state.info
    var name by rememberSaveable { mutableStateOf(title) }
    var emoji by rememberSaveable { mutableStateOf(icon?.emoji) }
    var custom by rememberSaveable(stateSaver = CustomEmojiSaver) { mutableStateOf<CustomEmoji?>(null) }
    var iconChanged by rememberSaveable { mutableStateOf(false) }
    // Filled in once Notion said what the database has (kept across rotation after that).
    var description by rememberSaveable(info != null) { mutableStateOf(info?.description.orEmpty()) }
    var locked by rememberSaveable(info != null) { mutableStateOf(info?.locked == true) }
    var inline by rememberSaveable(info != null) { mutableStateOf(info?.inline == true) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var edited by rememberSaveable(stateSaver = EditedSaver) { mutableStateOf(SchemaEdits.start(properties)) }
    var choosingIcon by remember { mutableStateOf(false) }
    var choosingKind by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var opened by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val enabled = !state.saving
    val removed = properties.filter { p -> edited.none { it.id == p.id } }.map { it.name }
    val lostOptions = SchemaEdits.removedOptions(properties, edited)
    val changedTypes = SchemaEdits.changedTypes(properties, edited)
    val complete = SchemaEdits.incomplete(edited).isEmpty()
    val relations = SchemaEdits.rollupRelations(properties, edited)
    val save = { onSave(DatabaseEditInput(name, emoji, custom, iconChanged, description, locked, inline, edited, names)) }
    fun update(index: Int, property: EditedProperty) {
        edited = edited.toMutableList().also { it[index] = property }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text(stringResource(R.string.edit_database)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss, enabled = enabled) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close))
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = { if (removed.isEmpty() && lostOptions.isEmpty() && changedTypes.isEmpty()) save() else confirming = true },
                            enabled = enabled && name.isNotBlank() && complete,
                        ) {
                            if (state.saving) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(stringResource(R.string.action_save))
                        }
                    },
                    windowInsets = WindowInsets(0, 0, 0, 0),
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val shown = if (iconChanged) IconCodec.choice(emoji, custom?.id, custom?.url) else icon
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(AppShapes.Item)
                                .clickable(enabled = enabled, role = Role.Button, onClickLabel = stringResource(if (shown != null) R.string.change_icon else R.string.add_icon)) {
                                    choosingIcon = true
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (shown != null) {
                                PageIconView(shown, size = 32.dp)
                            } else {
                                Icon(Icons.Rounded.AddReaction, contentDescription = stringResource(R.string.add_icon), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(stringResource(R.string.new_database_name)) },
                            singleLine = true,
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (info != null) {
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            label = { Text(stringResource(R.string.database_description)) },
                            minLines = 1,
                            maxLines = 4,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        stringResource(R.string.edit_database_properties),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    edited.forEachIndexed { index, property ->
                        PropertyRow(
                            property = property,
                            label = typeLabel(property.currentType, names),
                            names = names,
                            typeChanges = SchemaEdits.typeChanges(property, properties, edited),
                            open = property.id != null && property.id in opened,
                            enabled = enabled,
                            onToggle = { id -> opened = if (id in opened) opened - id else opened + id },
                            onChange = { update(index, it) },
                            onRemove = { edited = edited.filterIndexed { i, _ -> i != index } },
                        )
                        val id = property.id
                        val kind = property.newKind
                        val options = property.options
                        when {
                            kind != null && property.hasSettings -> PropertySettingsForm(
                                kind = kind,
                                settings = property.settings,
                                onChange = { update(index, property.copy(settings = it)) },
                                enabled = enabled,
                                relationTargets = state.relationTargets,
                                rollupRelations = relations,
                                related = state.related,
                                onLoadRelated = onLoadRelated,
                            )
                            id == null || id !in opened -> Unit
                            property.editsOptions && options != null -> OptionsEditor(
                                property = property.name,
                                options = options,
                                isStatus = property.type == "status",
                                enabled = enabled,
                            ) { changed -> update(index, property.copy(options = changed)) }
                            property.hasSettings -> PropertySettingsForm(
                                kind = PropertyKind.NUMBER,
                                settings = property.settings,
                                onChange = { update(index, property.copy(settings = it)) },
                                enabled = enabled,
                            )
                        }
                    }
                    Box {
                        TextButton(onClick = { choosingKind = true }, enabled = enabled) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.new_database_add_property))
                        }
                        DropdownMenu(expanded = choosingKind, onDismissRequest = { choosingKind = false }) {
                            SchemaEdits.addableKinds(properties, edited).forEach { kind ->
                                DropdownMenuItem(
                                    text = { Text(names.getValue(kind)) },
                                    onClick = {
                                        choosingKind = false
                                        edited = SchemaEdits.add(edited, kind, names.getValue(kind))
                                    },
                                )
                            }
                        }
                    }
                    if (info != null) {
                        HorizontalDivider()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(role = Role.Button) { advanced = !advanced },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.advanced), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Icon(if (advanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
                        }
                        if (advanced) {
                            SwitchRow(stringResource(R.string.database_locked), stringResource(R.string.database_locked_hint), locked, enabled) { locked = it }
                            if (info.parentId != null) {
                                SwitchRow(stringResource(R.string.new_database_inline), null, inline, enabled) { inline = it }
                            }
                        }
                    }
                    // Views' filters and sorts, buttons and status groups: in Notion.
                    TextButton(onClick = onOpenInNotion) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_open_in_notion))
                    }
                    state.error?.let {
                        Text(
                            stringResource(R.string.database_edit_failed, errorText(it)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    if (choosingIcon) {
        PageIconSheet(
            hasIcon = if (iconChanged) emoji != null || custom != null else icon != null,
            customEmojis = customEmojis,
            onShown = onShowIcons,
            onPick = { picked ->
                choosingIcon = false
                emoji = picked
                custom = null
                iconChanged = true
            },
            onPickCustom = { picked ->
                choosingIcon = false
                emoji = null
                custom = picked
                iconChanged = true
            },
            onRemove = {
                choosingIcon = false
                emoji = null
                custom = null
                iconChanged = true
            },
            onDismiss = { choosingIcon = false },
        )
    }
    if (confirming) {
        val loses = removed.isNotEmpty() || lostOptions.isNotEmpty()
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = {
                Text(
                    stringResource(
                        when {
                            removed.isNotEmpty() -> R.string.remove_properties_title
                            lostOptions.isNotEmpty() -> R.string.remove_options_title
                            else -> R.string.change_types_title
                        },
                    ),
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (removed.isNotEmpty()) Text(stringResource(R.string.remove_properties_body, removed.joinToString(", ")))
                    if (lostOptions.isNotEmpty()) Text(stringResource(R.string.remove_options_body, lostOptions.joinToString(", ")))
                    if (changedTypes.isNotEmpty()) Text(stringResource(R.string.change_types_body, changedTypes.joinToString(", ")))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    save()
                }) { Text(stringResource(if (loses) R.string.remove_and_save else R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * One property: its name field (labelled with its kind), a chevron for an existing property's
 * options or number format, and ⋮ with Rename, Change type (a list of the kinds it can become)
 * and Remove.
 */
@Composable
private fun PropertyRow(
    property: EditedProperty,
    label: String,
    names: Map<PropertyKind, String>,
    typeChanges: List<PropertyKind>,
    open: Boolean,
    enabled: Boolean,
    onToggle: (String) -> Unit,
    onChange: (EditedProperty) -> Unit,
    onRemove: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    var menu by remember { mutableStateOf(false) }
    var kinds by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = property.name,
            onValueChange = { onChange(property.copy(name = it)) },
            label = { Text(label) },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus),
        )
        val id = property.id
        if (id != null && property.newKind == null && (property.editsOptions || property.hasSettings)) {
            IconButton(onClick = { onToggle(id) }, enabled = enabled) {
                Icon(
                    if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = stringResource(R.string.options_of, property.name),
                )
            }
        } else {
            // The same room, so the fields line up.
            Spacer(Modifier.size(48.dp))
        }
        Box {
            IconButton(onClick = { menu = true }, enabled = enabled) {
                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.property_menu, property.name))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_rename)) },
                    onClick = {
                        menu = false
                        runCatching { focus.requestFocus() }
                    },
                )
                if (typeChanges.isNotEmpty() || property.changedTo != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.change_type)) },
                        onClick = {
                            menu = false
                            kinds = true
                        },
                    )
                }
                if (property.removable) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.new_database_remove_property, property.name)) },
                        onClick = {
                            menu = false
                            onRemove()
                        },
                    )
                }
            }
            DropdownMenu(expanded = kinds, onDismissRequest = { kinds = false }) {
                // Its own type first, to go back to it.
                val own = PropertyKind.of(property.type)
                (listOfNotNull(own.takeIf { property.changedTo != null }) + typeChanges.filter { it != own }).forEach { kind ->
                    DropdownMenuItem(
                        text = { Text(names.getValue(kind)) },
                        onClick = {
                            kinds = false
                            onChange(SchemaEdits.changeType(property, kind))
                        },
                    )
                }
            }
        }
    }
}

/** A switch with its label (and a line under it), the whole row a 48dp-high tap target. */
@Composable
private fun SwitchRow(label: String, supporting: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** What a property is, as the label of its field: its kind, or Notion's name for a type the app can't add. */
@Composable
private fun typeLabel(type: String, names: Map<PropertyKind, String>): String =
    PropertyKind.of(type)?.let(names::getValue) ?: stringResource(
        when (type) {
            "title" -> R.string.property_kind_title
            else -> R.string.property_kind_other
        },
    )

/** Keeps the custom emoji chosen in Edit database across a screen rotation. */
private val CustomEmojiSaver = listSaver<CustomEmoji?, String>(
    save = { emoji -> emoji?.let { listOf(it.id, it.name, it.url) } ?: emptyList() },
    restore = { if (it.size == 3) CustomEmoji(it[0], it[1], it[2]) else null },
)
