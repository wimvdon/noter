package be.wimvandonck.noter.ui.main

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import be.wimvandonck.noter.R
import be.wimvandonck.noter.logic.NoteSection
import be.wimvandonck.noter.logic.SectionTitle
import be.wimvandonck.noter.logic.ViewSections
import be.wimvandonck.noter.ui.common.optionColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * A board's or calendar's notes as Notion's view groups them, one section below the other: a
 * header per section (its colour, name and number of notes; a tap folds it away), then its notes
 * as cards. A calendar opens at today (or the first day after it). Long press: the note's menu.
 */
@Composable
internal fun SectionedNoteGrid(
    state: NotesUiState,
    sections: List<NoteSection>,
    previews: Map<String, String>,
    onOpen: (String) -> Unit,
    onTogglePin: (String) -> Unit,
) {
    var collapsed by remember(state.databaseId) { mutableStateOf(emptySet<String>()) }
    var pressedKey by remember { mutableStateOf<String?>(null) }
    var menuKey by remember { mutableStateOf<String?>(null) }
    val gridState = rememberLazyStaggeredGridState()
    val haptics = LocalHapticFeedback.current

    // A calendar opens at today: the header of the first section from today on.
    val upcoming = ViewSections.firstUpcoming(sections)
    val upcomingKey = upcoming?.let { sections[it].key }
    LaunchedEffect(upcomingKey) {
        val index = upcoming ?: return@LaunchedEffect
        val item = sections.take(index).sumOf { 1 + if (it.key in collapsed) 0 else it.notes.size }
        if (item > 0) gridState.scrollToItem(item)
    }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(160.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
        verticalItemSpacing = 8.dp,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sections.forEach { section ->
            val folded = section.key in collapsed
            item(key = "h:" + section.key, span = StaggeredGridItemSpan.FullLine) {
                SectionHeader(
                    section = section,
                    collapsed = folded,
                    onToggle = { collapsed = if (folded) collapsed - section.key else collapsed + section.key },
                    modifier = Modifier.animateItem(),
                )
            }
            if (!folded) {
                // A note can be in several sections (several options): the key holds the section.
                items(section.notes, key = { section.key + "/" + it.id }, span = { StaggeredGridItemSpan.SingleLane }) { note ->
                    val key = section.key + "/" + note.id
                    val pinned = note.id in state.pinnedIds
                    NoteCard(
                        note = note,
                        preview = previews[note.id],
                        showDatabaseIcon = false,
                        databaseIcon = null,
                        chipProperty = state.cardProperty,
                        layout = state.propertyLayouts[note.databaseId],
                        statusDefaults = state.statusDefaults[note.databaseId].orEmpty(),
                        lifted = false,
                        onOpen = { if (pressedKey != key) onOpen(note.id) },
                        menu = {
                            DropdownMenu(expanded = menuKey == key, onDismissRequest = { menuKey = null }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (pinned) R.string.action_unpin else R.string.action_pin)) },
                                    leadingIcon = { Icon(Icons.Rounded.PushPin, null) },
                                    onClick = { menuKey = null; onTogglePin(note.id) },
                                )
                            }
                        },
                        modifier = Modifier
                            .animateItem()
                            // As in the one-list grid: a long press opens the menu when the finger lifts.
                            .pointerInput(key) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        pressedKey = key
                                    },
                                    onDragEnd = {
                                        if (pressedKey == key) menuKey = key
                                        pressedKey = null
                                    },
                                    onDragCancel = { pressedKey = null },
                                    onDrag = { _, _ -> },
                                )
                            },
                    )
                }
            }
        }
    }
}

/** A section's header: a dot in the option's colour, its name, the number of notes and a chevron. */
@Composable
private fun SectionHeader(section: NoteSection, collapsed: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val rotation by animateFloatAsState(if (collapsed) -90f else 0f, label = "chevron")
    val stateText = stringResource(if (collapsed) R.string.section_collapsed else R.string.section_expanded)
    val count = pluralStringResource(R.plurals.section_notes, section.notes.size, section.notes.size)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(horizontal = 4.dp)
            .semantics {
                heading()
                stateDescription = stateText
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val color = (section.title as? SectionTitle.Option)?.color?.takeIf { it != "default" }
            if (color != null) {
                val colors = optionColors(color)
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(colors.container)
                        .border(1.dp, colors.content.copy(alpha = 0.4f), CircleShape),
                )
            }
            Text(
                sectionName(section.title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                section.notes.size.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = count },
            )
        }
        Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.rotate(rotation))
    }
}

@Composable
private fun sectionName(title: SectionTitle): String = when (title) {
    is SectionTitle.Option -> title.name
    is SectionTitle.Empty -> stringResource(R.string.section_none, title.property)
    is SectionTitle.Checkbox -> stringResource(if (title.checked) R.string.quick_checked else R.string.quick_unchecked)
    is SectionTitle.NoDate -> stringResource(R.string.section_no_date)
    is SectionTitle.Day -> when (title.daysFromToday) {
        0L -> stringResource(R.string.quick_date_today)
        1L -> stringResource(R.string.day_tomorrow)
        -1L -> stringResource(R.string.day_yesterday)
        else -> dayName(title.date)
    }
}

/** A day in full ("Saturday, November 28, 2026"), in the phone's language. */
@Composable
private fun dayName(date: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    return date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
}
