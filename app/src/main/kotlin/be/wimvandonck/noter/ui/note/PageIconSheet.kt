package be.wimvandonck.noter.ui.note

import be.wimvandonck.noter.ui.common.NoterSheet
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import be.wimvandonck.noter.R
import be.wimvandonck.noter.data.model.CustomEmoji
import be.wimvandonck.noter.logic.EmojiInput
import be.wimvandonck.noter.ui.common.NotionImage
import be.wimvandonck.noter.ui.theme.AppShapes

/**
 * Choosing a page's icon: common emoji to tap, a field for any other emoji (the keyboard's emoji
 * search finds them all), the workspace's own custom emojis (a section of its own, only when it has
 * any; [onShown] asks for them), and removing the icon. Notion's own icons and pictures are set in
 * Notion; the app shows them but only sets emoji.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageIconSheet(
    hasIcon: Boolean,
    customEmojis: List<CustomEmoji>,
    onShown: () -> Unit,
    onPick: (String) -> Unit,
    onPickCustom: (CustomEmoji) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { onShown() }
    NoterSheet(onDismiss = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.page_icon), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (hasIcon) TextButton(onClick = onRemove) { Text(stringResource(R.string.remove_icon)) }
            }
            OutlinedTextField(
                value = typed,
                onValueChange = { text ->
                    typed = text
                    EmojiInput.first(text)?.let(onPick)
                },
                label = { Text(stringResource(R.string.page_icon_type)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(52.dp),
                modifier = Modifier.heightIn(max = 360.dp).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (customEmojis.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            stringResource(R.string.workspace_emojis),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                        )
                    }
                    items(customEmojis, key = { it.id }) { custom ->
                        Box(
                            Modifier
                                .size(52.dp)
                                .clip(AppShapes.Item)
                                .clickable(onClickLabel = custom.name, role = Role.Button) { onPickCustom(custom) },
                            contentAlignment = Alignment.Center,
                        ) {
                            NotionImage(url = custom.url, expiry = null, contentDescription = custom.name, modifier = Modifier.size(32.dp))
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            stringResource(R.string.common_emojis),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp, top = 8.dp),
                        )
                    }
                }
                items(EmojiInput.SUGGESTED) { emoji ->
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(AppShapes.Item)
                            .clickable(role = Role.Button) { onPick(emoji) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(emoji, style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
            Spacer(Modifier.padding(bottom = 8.dp))
        }
    }
}
