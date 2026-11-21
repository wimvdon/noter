package be.wimvandonck.noter.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import be.wimvandonck.noter.R
import be.wimvandonck.noter.data.model.SelectOption
import be.wimvandonck.noter.logic.NotionColors
import be.wimvandonck.noter.logic.SchemaEdits
import be.wimvandonck.noter.ui.common.OptionChip
import be.wimvandonck.noter.ui.common.optionColors

/**
 * The options of a select, multi-select or status property in Edit database: each with its color
 * (tap the dot), for a status its group, and × to remove it; Add option at the end. A new option's
 * name is typed here; an existing one's can't be changed through Notion's API (see the hint).
 */
@Composable
fun OptionsEditor(
    property: String,
    options: List<SelectOption>,
    isStatus: Boolean,
    enabled: Boolean,
    onChange: (List<SelectOption>) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, option ->
            fun set(changed: SelectOption) = onChange(options.toMutableList().also { it[index] = changed })
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColorDot(option, enabled) { set(option.copy(color = it)) }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (option.id == null) {
                        OutlinedTextField(
                            value = option.name,
                            onValueChange = { set(option.copy(name = it)) },
                            label = { Text(stringResource(R.string.option_name)) },
                            singleLine = true,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        OptionChip(option)
                    }
                }
                if (isStatus) GroupPicker(option, enabled) { set(option.copy(group = it)) }
                IconButton(onClick = { onChange(options.filterIndexed { i, _ -> i != index }) }, enabled = enabled) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.option_remove, option.name.ifBlank { stringResource(R.string.option_name) }))
                }
            }
        }
        TextButton(
            onClick = { onChange(options + SelectOption("", "default", group = if (isStatus) SchemaEdits.STATUS_GROUPS.first() else null)) },
            enabled = enabled,
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.option_add))
        }
        Text(
            stringResource(R.string.option_rename_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ColorDot(option: SelectOption, enabled: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = option.name.ifBlank { stringResource(R.string.option_name) }
    Box {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = stringResource(R.string.option_color, label)) { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(optionColors(option.color).container),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            NotionColors.ALL.forEach { color ->
                DropdownMenuItem(
                    leadingIcon = {
                        Box(
                            Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(optionColors(color).container),
                        )
                    },
                    text = { Text(stringResource(colorName(color))) },
                    onClick = {
                        open = false
                        onPick(color)
                    },
                )
            }
        }
    }
}

@Composable
private fun GroupPicker(option: SelectOption, enabled: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled) {
            Text(
                option.group?.let { stringResource(groupName(it)) } ?: stringResource(R.string.option_group),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SchemaEdits.STATUS_GROUPS.forEach { group ->
                DropdownMenuItem(
                    text = { Text(stringResource(groupName(group))) },
                    onClick = {
                        open = false
                        onPick(group)
                    },
                )
            }
        }
    }
}

private fun groupName(group: String): Int = when (group) {
    "To-do" -> R.string.option_group_todo
    "In progress" -> R.string.option_group_progress
    else -> R.string.option_group_complete
}

private fun colorName(color: String): Int = when (color) {
    "gray" -> R.string.notion_color_gray
    "brown" -> R.string.notion_color_brown
    "orange" -> R.string.notion_color_orange
    "yellow" -> R.string.notion_color_yellow
    "green" -> R.string.notion_color_green
    "blue" -> R.string.notion_color_blue
    "purple" -> R.string.notion_color_purple
    "pink" -> R.string.notion_color_pink
    "red" -> R.string.notion_color_red
    else -> R.string.notion_color_default
}
