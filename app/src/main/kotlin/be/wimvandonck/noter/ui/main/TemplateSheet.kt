package be.wimvandonck.noter.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import be.wimvandonck.noter.R
import be.wimvandonck.noter.data.model.PageTemplate
import be.wimvandonck.noter.logic.TemplatePick
import be.wimvandonck.noter.ui.common.NoterSheet

/**
 * "New from template": an empty note, then the data source's page templates (the default one
 * says so). Tapping one makes the note at once.
 */
@Composable
internal fun TemplateSheet(
    templates: List<PageTemplate>,
    onPick: (TemplatePick) -> Unit,
    onDismiss: () -> Unit,
) {
    NoterSheet(onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SheetHeader(stringResource(R.string.template_new_from))
            ListItem(
                headlineContent = { Text(stringResource(R.string.template_empty)) },
                leadingContent = { Icon(Icons.Rounded.NoteAdd, contentDescription = null) },
                modifier = Modifier.clickable { onPick(TemplatePick.Empty) },
            )
            templates.forEach { template ->
                ListItem(
                    headlineContent = { Text(template.name.ifBlank { stringResource(R.string.untitled) }) },
                    supportingContent = if (template.isDefault) {
                        { Text(stringResource(R.string.template_default)) }
                    } else {
                        null
                    },
                    leadingContent = { Icon(Icons.Rounded.Description, contentDescription = null) },
                    modifier = Modifier.clickable { onPick(TemplatePick.Specific(template.id)) },
                )
            }
            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }
}
