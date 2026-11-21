package be.wimvandonck.noter.ui.main

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import be.wimvandonck.noter.R
import be.wimvandonck.noter.logic.QuickChip
import be.wimvandonck.noter.logic.QuickChoice

/**
 * The view's quick filters, one chip each, in a row that scrolls sideways. A chip with a condition
 * toggles it; one without opens a small menu to pick a value.
 */
@Composable
fun QuickFilterRow(
    chips: List<QuickChipState>,
    onToggle: (QuickChip) -> Unit,
    onChoose: (QuickChip, QuickChoice) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEach { state ->
            var picking by remember(state.chip.key) { mutableStateOf(false) }
            Box {
                FilterChip(
                    selected = state.on,
                    enabled = state.enabled,
                    onClick = { if (state.on || state.chip.preset != null) onToggle(state.chip) else picking = true },
                    label = { Text(state.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
                DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                    state.choices.forEach { choice ->
                        DropdownMenuItem(
                            text = { Text(choiceName(choice)) },
                            onClick = {
                                picking = false
                                onChoose(state.chip, choice)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun choiceName(choice: QuickChoice): String = when (choice.kind) {
    QuickChoice.Kind.OPTION -> choice.name
    QuickChoice.Kind.CHECKED -> stringResource(R.string.quick_checked)
    QuickChoice.Kind.UNCHECKED -> stringResource(R.string.quick_unchecked)
    QuickChoice.Kind.ME -> stringResource(R.string.quick_me)
}
