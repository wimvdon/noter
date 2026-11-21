package be.wimvandonck.noter.ui.note

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import be.wimvandonck.noter.R
import be.wimvandonck.noter.data.model.Block
import be.wimvandonck.noter.data.model.Comment
import be.wimvandonck.noter.data.model.Discussion
import be.wimvandonck.noter.data.notion.InlineMarkdown
import be.wimvandonck.noter.logic.CommentThreads
import be.wimvandonck.noter.ui.common.NoterSheet
import be.wimvandonck.noter.ui.common.errorText
import be.wimvandonck.noter.ui.theme.AppShapes

/**
 * The page's comments (online only): its discussions, the page's own first, each comment with its
 * author, time and text, "Reply" under a discussion, Edit and Delete on the token's own comments,
 * and a field at the bottom that adds a comment to the page (or the reply being written).
 */
@Composable
fun CommentsSheet(
    ui: CommentsUi,
    blocks: List<Block>,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onSend: (text: String, discussionId: String?, onDone: () -> Unit) -> Unit,
    onEdit: (comment: Comment, text: String, onDone: () -> Unit) -> Unit,
    onDelete: (Comment) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var replyingTo by remember { mutableStateOf<Discussion?>(null) }
    var editing by remember { mutableStateOf<Comment?>(null) }
    var deleting by remember { mutableStateOf<Comment?>(null) }
    val discussions = ui.discussions
    // A discussion or comment that is gone (deleted) can no longer be answered or edited.
    val replyTarget = replyingTo?.let { r -> discussions.firstOrNull { it.id == r.id } }
    val editTarget = editing?.let { e -> ui.comments.firstOrNull { it.id == e.id } }

    NoterSheet(onDismiss = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                stringResource(R.string.comments),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            Box(Modifier.fillMaxWidth()) {
                when {
                    ui.loadError != null -> Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                        Text(errorText(ui.loadError), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.comments_retry)) }
                    }
                    ui.loading && ui.comments.isEmpty() -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    }
                    discussions.isEmpty() -> Text(
                        stringResource(R.string.comments_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                    else -> LazyColumn(
                        Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(discussions, key = { it.id }) { discussion ->
                            DiscussionCard(
                                discussion = discussion,
                                blocks = blocks,
                                ui = ui,
                                onReply = { replyingTo = discussion; editing = null; draft = "" },
                                onEdit = { comment -> editing = comment; replyingTo = null; draft = comment.text },
                                onDelete = { deleting = it },
                            )
                        }
                    }
                }
            }
            if (ui.loadError == null) {
                Composer(
                    draft = draft,
                    onDraft = { draft = it },
                    sending = ui.sending,
                    error = ui.sendError,
                    replyTo = replyTarget?.comments?.firstOrNull()?.let { CommentThreads.authorName(it, ui.users) },
                    replying = replyTarget != null,
                    editing = editTarget != null,
                    onCancel = { replyingTo = null; editing = null; draft = "" },
                    onSend = {
                        val done = { draft = ""; replyingTo = null; editing = null }
                        if (editTarget != null) onEdit(editTarget, draft, done) else onSend(draft, replyTarget?.id, done)
                    },
                )
            }
        }
    }

    deleting?.let { comment ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.comment_delete_title)) },
            text = { Text(stringResource(R.string.comment_delete_body)) },
            confirmButton = {
                TextButton(onClick = { deleting = null; onDelete(comment) }) { Text(stringResource(R.string.comment_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun DiscussionCard(
    discussion: Discussion,
    blocks: List<Block>,
    ui: CommentsUi,
    onReply: () -> Unit,
    onEdit: (Comment) -> Unit,
    onDelete: (Comment) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = AppShapes.Item,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            CommentThreads.quote(discussion, blocks)?.let { quote ->
                Row(Modifier.padding(end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(3.dp).height(20.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    Text(
                        quote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            discussion.comments.forEach { comment ->
                CommentRow(
                    comment = comment,
                    name = CommentThreads.authorName(comment, ui.users) ?: stringResource(R.string.comment_someone),
                    own = CommentThreads.isOwn(comment, ui.mine),
                    onEdit = { onEdit(comment) },
                    onDelete = { onDelete(comment) },
                )
            }
            TextButton(onClick = onReply) { Text(stringResource(R.string.comment_reply)) }
        }
    }
}

@Composable
private fun CommentRow(comment: Comment, name: String, own: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                CommentThreads.millis(comment.createdTime)?.let { time ->
                    Text(
                        DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Text(
                commentText(comment.text, MaterialTheme.colorScheme.primary),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (own) {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more_options), modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (CommentThreads.editable(comment)) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.comment_edit)) }, onClick = { menu = false; onEdit() })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.comment_delete)) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    sending: Boolean,
    error: String?,
    replyTo: String?,
    replying: Boolean,
    editing: Boolean,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (replying || editing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (editing) stringResource(R.string.comment_editing)
                    else stringResource(R.string.comments_reply_to, replyTo ?: stringResource(R.string.comment_someone)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cancel), modifier = Modifier.size(18.dp))
                }
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = onDraft,
            placeholder = { Text(stringResource(R.string.comments_add_hint)) },
            maxLines = 4,
            enabled = !sending,
            isError = error != null,
            supportingText = error?.let { { Text(errorText(it)) } },
            trailingIcon = {
                if (sending) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onSend, enabled = CommentThreads.sendable(draft) != null) {
                        Icon(
                            if (editing) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.Send,
                            contentDescription = stringResource(if (editing) R.string.comment_save else R.string.comment_send),
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A comment's inline Markdown (bold, italic, strikethrough, code, links) as styled text. */
private fun commentText(markdown: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    InlineMarkdown.parse(markdown).forEach { run ->
        val style = SpanStyle(
            fontWeight = if (run.bold) FontWeight.Bold else null,
            fontStyle = if (run.italic) FontStyle.Italic else null,
            fontFamily = if (run.code) FontFamily.Monospace else null,
            textDecoration = when {
                run.strike && run.link != null -> TextDecoration.combine(listOf(TextDecoration.LineThrough, TextDecoration.Underline))
                run.strike -> TextDecoration.LineThrough
                run.link != null -> TextDecoration.Underline
                else -> null
            },
            color = if (run.link != null) linkColor else Color.Unspecified,
        )
        withStyle(style) { append(run.text) }
    }
}
