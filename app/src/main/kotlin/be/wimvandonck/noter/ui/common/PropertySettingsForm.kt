package be.wimvandonck.noter.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import be.wimvandonck.noter.R
import be.wimvandonck.noter.data.model.PageRef
import be.wimvandonck.noter.data.model.PropertySchema
import be.wimvandonck.noter.logic.PropertyConfigs
import be.wimvandonck.noter.logic.PropertyKind
import be.wimvandonck.noter.logic.PropertySettings

/**
 * The small form under a property's name for kinds with settings (Add property, Change type): a
 * number's format, a formula, a relation's database and two-way switch, a rollup's relation,
 * property and calculation, a unique ID's prefix. Pickers are drop-down fields, one below the
 * other, so it fits a small phone. [related]: properties of related data sources, by ID (a
 * rollup asks [onLoadRelated] for the one it needs).
 */
@Composable
fun PropertySettingsForm(
    kind: PropertyKind,
    settings: PropertySettings,
    onChange: (PropertySettings) -> Unit,
    enabled: Boolean,
    relationTargets: List<PageRef> = emptyList(),
    rollupRelations: List<PropertySchema> = emptyList(),
    related: Map<String, List<PropertySchema>> = emptyMap(),
    onLoadRelated: (String) -> Unit = {},
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (kind) {
            PropertyKind.NUMBER -> {
                val formats = (PropertyConfigs.NUMBER_FORMATS + settings.numberFormat).distinct()
                ChoiceField(
                    label = stringResource(R.string.number_format),
                    value = numberFormatName(settings.numberFormat),
                    choices = formats.map { it to numberFormatName(it) },
                    enabled = enabled,
                ) { onChange(settings.copy(numberFormat = it)) }
            }
            PropertyKind.FORMULA -> OutlinedTextField(
                value = settings.formula,
                onValueChange = { onChange(settings.copy(formula = it)) },
                label = { Text(stringResource(R.string.property_kind_formula)) },
                placeholder = { Text(stringResource(R.string.formula_hint)) },
                isError = settings.formula.isBlank(),
                supportingText = if (settings.formula.isBlank()) ({ Text(stringResource(R.string.required_field)) }) else null,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            PropertyKind.RELATION -> {
                ChoiceField(
                    label = stringResource(R.string.relation_database),
                    value = settings.relationTargetName,
                    choices = relationTargets.map { it.id to it.title.ifBlank { it.id } },
                    enabled = enabled,
                    isError = settings.relationTarget == null,
                    empty = stringResource(R.string.relation_no_databases),
                ) { id -> onChange(settings.copy(relationTarget = id, relationTargetName = relationTargets.firstOrNull { it.id == id }?.title.orEmpty())) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(enabled = enabled, role = Role.Switch) { onChange(settings.copy(twoWay = !settings.twoWay)) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.relation_two_way), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = settings.twoWay, onCheckedChange = null, enabled = enabled)
                }
            }
            PropertyKind.ROLLUP -> {
                val relation = rollupRelations.firstOrNull { it.id == settings.rollupRelation }
                val target = relation?.relationDatabaseId
                LaunchedEffect(target) { target?.let(onLoadRelated) }
                ChoiceField(
                    label = stringResource(R.string.property_kind_relation),
                    value = relation?.name.orEmpty(),
                    choices = rollupRelations.map { it.id to it.name },
                    enabled = enabled,
                    isError = relation == null,
                ) { id -> onChange(settings.copy(rollupRelation = id, rollupProperty = null)) }
                if (target != null) {
                    val properties = related[target]
                    val chosen = properties?.firstOrNull { it.id == settings.rollupProperty }
                    ChoiceField(
                        label = stringResource(R.string.rollup_property),
                        value = chosen?.name ?: if (properties == null) stringResource(R.string.rollup_loading) else "",
                        choices = properties.orEmpty().map { it.id to it.name },
                        enabled = enabled && properties != null,
                        isError = chosen == null,
                    ) { id ->
                        val type = properties?.firstOrNull { it.id == id }?.type
                        onChange(settings.copy(rollupProperty = id, rollupFunction = PropertyConfigs.fitFunction(settings.rollupFunction, type)))
                    }
                    if (chosen != null) {
                        ChoiceField(
                            label = stringResource(R.string.rollup_function),
                            value = rollupFunctionName(settings.rollupFunction),
                            choices = PropertyConfigs.rollupFunctions(chosen.type).map { it to rollupFunctionName(it) },
                            enabled = enabled,
                        ) { onChange(settings.copy(rollupFunction = it)) }
                    }
                }
            }
            PropertyKind.UNIQUE_ID -> OutlinedTextField(
                value = settings.idPrefix,
                onValueChange = { typed -> onChange(settings.copy(idPrefix = typed.filter { !it.isWhitespace() }.take(MAX_PREFIX))) },
                label = { Text(stringResource(R.string.unique_id_prefix)) },
                placeholder = { Text(stringResource(R.string.unique_id_prefix_hint)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            else -> Unit
        }
    }
}

private const val MAX_PREFIX = 20

/**
 * A read-only field that opens a drop-down list of [choices] (key to label); [onPick] gets the
 * key. With no choices the list says [empty].
 */
@Composable
fun ChoiceField(
    label: String,
    value: String,
    choices: List<Pair<String, String>>,
    enabled: Boolean,
    isError: Boolean = false,
    empty: String? = null,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = null) },
            singleLine = true,
            isError = isError,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        // The field itself only shows the choice: a tap anywhere on it opens the list.
        Box(
            Modifier
                .matchParentSize()
                .clickable(enabled = enabled, role = Role.DropdownList, onClickLabel = label) { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (choices.isEmpty() && empty != null) {
                DropdownMenuItem(text = { Text(empty) }, onClick = { open = false }, enabled = false)
            }
            choices.forEach { (key, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        open = false
                        onPick(key)
                    },
                )
            }
        }
    }
}

/** A number format's name in the user's language (Notion's own key for one the app doesn't name). */
@Composable
fun numberFormatName(format: String): String = when (format) {
    "number" -> stringResource(R.string.number_format_number)
    "number_with_commas" -> stringResource(R.string.number_format_number_with_commas)
    "percent" -> stringResource(R.string.number_format_percent)
    "euro" -> stringResource(R.string.number_format_euro)
    "dollar" -> stringResource(R.string.number_format_dollar)
    "pound" -> stringResource(R.string.number_format_pound)
    "yen" -> stringResource(R.string.number_format_yen)
    else -> format.replace('_', ' ')
}

/** A rollup function's name in the user's language. */
@Composable
fun rollupFunctionName(function: String): String = when (function) {
    "show_original" -> stringResource(R.string.rollup_fn_show_original)
    "count" -> stringResource(R.string.rollup_fn_count)
    "count_values" -> stringResource(R.string.rollup_fn_count_values)
    "unique" -> stringResource(R.string.rollup_fn_unique)
    "empty" -> stringResource(R.string.rollup_fn_empty)
    "not_empty" -> stringResource(R.string.rollup_fn_not_empty)
    "sum" -> stringResource(R.string.rollup_fn_sum)
    "average" -> stringResource(R.string.rollup_fn_average)
    "min" -> stringResource(R.string.rollup_fn_min)
    "max" -> stringResource(R.string.rollup_fn_max)
    "earliest_date" -> stringResource(R.string.rollup_fn_earliest_date)
    "latest_date" -> stringResource(R.string.rollup_fn_latest_date)
    "checked" -> stringResource(R.string.rollup_fn_checked)
    "percent_checked" -> stringResource(R.string.rollup_fn_percent_checked)
    else -> function.replace('_', ' ')
}
