package com.app.newspaperss.ui.edition

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.newspaperss.data.EditionContent
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.delivery.EditionEmail
import com.app.newspaperss.delivery.EditionIntents
import com.app.newspaperss.delivery.FolderDelivery
import com.app.newspaperss.delivery.KindleSend
import com.app.newspaperss.edition.EditionBuilder
import com.app.newspaperss.listen.Listening
import com.app.newspaperss.settings.KindleEmail
import com.app.newspaperss.ui.components.ArticleRowFrame
import com.app.newspaperss.ui.components.EpisodeLabelTag
import com.app.newspaperss.ui.components.episodeLabel
import com.app.newspaperss.ui.components.BUILDING_NOTE
import com.app.newspaperss.ui.components.LocalClock
import com.app.newspaperss.ui.components.StarToggle
import com.app.newspaperss.ui.listen.ListenButton
import com.app.newspaperss.ui.listen.PodcastLine
import com.app.newspaperss.ui.today.failureColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * @param preferOpen the reader reads on this device (a Boox), so opening an edition delivers it.
 * @param offerOpen false for a Kindle or Kobo, whose reader sends the book rather than opening it here.
 * @param kindleReader a sent edition offers to open the Kindle app, where it shows up.
 * @param kindleEmail Send emails the edition to this Kindle address rather than sharing it.
 * @param listening offers to read the edition aloud; null leaves Listen out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditionDetailScreen(
    viewModel: EditionDetailViewModel,
    onBack: () -> Unit,
    onReadArticle: (position: Int) -> Unit = {},
    preferOpen: Boolean = false,
    offerOpen: Boolean = true,
    kindleReader: Boolean = false,
    kindleEmail: KindleEmail? = null,
    listening: Listening? = null,
    onOpenPlayer: () -> Unit = {},
) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val building by viewModel.building.collectAsStateWithLifecycle()
    val sentToKindle by viewModel.sentToKindle.collectAsStateWithLifecycle()
    val context = LocalContext.current
    fun launch(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No reading app on this phone can open the edition. Try Send instead.", Toast.LENGTH_LONG).show()
        false
    }

    fun send() {
        val current = detail ?: return
        val edition = current.edition ?: return
        current.file?.let {
            val body = kindleEmail?.let { EditionEmail.body(edition.title, current.contents.map(EditionContent::entry)) }
            EditionIntents.launchSend(context, it, edition.title, edition.id, kindleEmail, body, onMailAppOpened = viewModel::markEmailed)
        }
    }

    fun open() {
        detail?.file?.let { if (launch(EditionIntents.open(context, it)) && preferOpen) viewModel.markSent() }
    }

    fun sendAnotherWay() {
        val current = detail ?: return
        val edition = current.edition ?: return
        current.file?.let { EditionIntents.launchSend(context, it, edition.title, edition.id, kindleEmail = null) {} }
    }

    val saveTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(EditionIntents.EPUB_MIME)) { uri ->
        if (uri != null) viewModel.saveCopy(context.contentResolver, uri)
    }
    val copied by viewModel.copied.collectAsStateWithLifecycle()
    LaunchedEffect(copied) {
        copied?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.copySaid()
        }
    }

    val kindleApp = rememberKindleApp()
    val emailsKindle = kindleEmail?.let { remember(it) { EditionIntents.mailAppFor(context, it) } } != null
    val choices = detail?.edition?.let {
        editionChoices(
            it.status, preferOpen, offerOpen, kindleReader, emailsKindle,
            kindleAppInstalled = kindleApp.value != null, hasFile = detail?.file != null,
        )
    }

    var markingNotSent by rememberSaveable { mutableStateOf(false) }
    detail?.edition?.takeIf { markingNotSent }?.let { edition ->
        MarkNotSentDialog(edition.title, onDismiss = { markingNotSent = false }, onConfirm = viewModel::markNotSent)
    }

    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    LaunchedEffect(deleted) { if (deleted) onBack() }
    var deleting by rememberSaveable { mutableStateOf(false) }
    detail?.edition?.takeIf { deleting }?.let { edition ->
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete ${edition.title}?") },
            text = {
                Text(
                    when (edition.status) {
                        EditionStatus.READY -> "It hasn't been sent, so its articles go into your next edition."
                        EditionStatus.DELIVERED -> "Its articles won't come back. The copy on your e-reader stays."
                        else -> "The file on this phone is deleted too."
                    },
                )
            },
            confirmButton = { TextButton(onClick = { deleting = false; viewModel.delete() }) { Text("Delete edition") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Keep") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edition") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    val status = detail?.edition?.status
                    // Rare and destructive, so in a menu rather than a button of its own, where a slow
                    // e-ink refresh makes a mis-tap easy. A sent edition's rare actions go here too,
                    // as on Today's card.
                    if (status != null && status != EditionStatus.BUILDING) {
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More options") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                // The same as Today's card's menu, then Delete.
                                choices?.more.orEmpty().forEach { action ->
                                    DropdownMenuItem(text = { Text(action.label) }, onClick = {
                                        menu = false
                                        when (action) {
                                            MoreAction.SEND, MoreAction.SEND_AGAIN -> send()
                                            MoreAction.SEND_ANOTHER_WAY -> sendAnotherWay()
                                            MoreAction.OPEN_HERE -> open()
                                            MoreAction.OPEN_KINDLE_APP -> startKindle(context, kindleApp)
                                            MoreAction.MARK_NOT_SENT -> markingNotSent = true
                                        }
                                    })
                                }
                                // Android's share sheet often offers no Files target, so Send another way can't keep the book.
                                detail?.takeIf { it.file != null }?.edition?.let { edition ->
                                    DropdownMenuItem(text = { Text("Save a copy…") }, onClick = {
                                        menu = false
                                        // Some e-readers have no save dialog at all.
                                        try {
                                            saveTo.launch(FolderDelivery.fileName(edition.title))
                                        } catch (_: ActivityNotFoundException) {
                                            Toast.makeText(context, "This device has no way to pick where to save a file. Try Send another way.", Toast.LENGTH_LONG).show()
                                        }
                                    })
                                }
                                DropdownMenuItem(text = { Text("Delete edition") }, onClick = { menu = false; deleting = true })
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        val current = detail ?: return@Scaffold
        val edition = current.edition
        if (edition == null) {
            Text("This edition has been deleted.", modifier = Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        // Any edition whose book is still here, a ready one, a sent one or one released as not sent:
        // it may have been read aloud part way before a newer one came.
        val readable = edition.status == EditionStatus.READY || edition.status == EditionStatus.DELIVERED ||
            (edition.status == EditionStatus.FAILED && edition.error == EditionBuilder.NOT_SENT)
        val listenable = listening?.takeIf { current.file != null && current.contents.isNotEmpty() && readable }
        val minutes = current.contents.sortedBy { it.entry.position }.map { it.entry.minutes }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Header(
                    edition,
                    fileMissing = current.file == null,
                    building = building,
                    sentToKindle = sentToKindle,
                    next = choices?.next,
                    preferOpen = preferOpen,
                    onSend = ::send,
                    onOpen = ::open,
                    onSent = viewModel::markSent,
                    listen = listenable?.let { l ->
                        { modifier: Modifier -> ListenButton(l, edition.id, minutes, onOpenPlayer, modifier) }
                    },
                )
            }
            listenable?.podcasts?.let { podcasts ->
                item { PodcastLine(podcasts, edition.id, minutes, Modifier.padding(horizontal = 16.dp).padding(top = 8.dp)) }
            }
            if (current.contents.isNotEmpty()) {
                item {
                    Text("Contents", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp).semantics { heading() })
                }
                if (current.contents.any(current::canStar)) {
                    item {
                        Text(
                            "Didn't get to one? Tap ☆ to bring it back.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).semantics {
                                contentDescription = "Didn't get to one? Tap the star to bring it back."
                            },
                        )
                        if (building) {
                            Text(BUILDING_NOTE, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        }
                    }
                }
            }
            val anyStar = current.contents.any(current::canStar)
            items(current.contents, key = { it.entry.id }) { content ->
                val articleId = content.entry.articleId
                ContentRow(
                    content,
                    canStar = current.canStar(content),
                    starred = current.isStarred(content),
                    onStar = { if (articleId != null) viewModel.setStarred(articleId, it) },
                    building = building,
                    reserveStar = anyStar,
                    onOpen = if (current.file != null) { { onReadArticle(content.entry.position) } } else null,
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun Header(
    edition: EditionEntity,
    fileMissing: Boolean,
    building: Boolean,
    sentToKindle: KindleSend?,
    next: NextStep?,
    preferOpen: Boolean,
    onSend: () -> Unit,
    onOpen: () -> Unit,
    onSent: () -> Unit,
    listen: (@Composable (Modifier) -> Unit)? = null,
) {
    val zone = LocalClock.current.zone
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(edition.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(dateOf(edition.createdAt, zone), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
        Text(statusOf(edition, zone), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        if (edition.status == EditionStatus.FAILED) {
            Text(edition.error ?: "This edition couldn't be made.", color = failureColor(edition.error))
        }
        if (edition.articleCount > 0) {
            Text(articlesAndMinutes(edition.articleCount, edition.minutes), style = MaterialTheme.typography.bodyMedium)
        }
        if (edition.status == EditionStatus.DELIVERED) sentToKindle?.let { KindleNote(it) }
        // The same button as Today's card; the rest is in the top bar's ⋮.
        next?.let { NextStepButton(it, enabled = !fileMissing, onSend, onOpen, Modifier.padding(top = 12.dp)) }
        if (fileMissing && (edition.status == EditionStatus.READY || edition.status == EditionStatus.DELIVERED)) {
            Text(
                "This edition's file has been deleted, so it can't be sent or opened.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        // A ready one, or one released as not sent when a newer one was made: it may have gone out
        // another way. Not while an edition is being made, which may be taking its articles.
        val notSent = edition.status == EditionStatus.FAILED && edition.error == EditionBuilder.NOT_SENT && !fileMissing && !building
        if (edition.status == EditionStatus.READY || notSent) MarkAsSentLine(preferOpen, onSent, Modifier.padding(top = 4.dp))
        listen?.invoke(Modifier.padding(top = 8.dp))
    }
}

/** @param reserveStar keeps the star's slot on a row without one, so titles line up with those that have it. */
@Composable
private fun ContentRow(
    content: EditionContent,
    canStar: Boolean,
    starred: Boolean,
    onStar: (Boolean) -> Unit,
    building: Boolean,
    reserveStar: Boolean,
    onOpen: (() -> Unit)?,
) {
    val entry = content.entry
    // Words, not the glyph, so it isn't mistaken for the toggle beside it; past tense, since that
    // toggle is about the next edition and may be off.
    val details = listOfNotNull(entry.sourceTitle, minutesLabel(entry.minutes), "You starred it".takeIf { entry.starred }).joinToString(" · ")
    ArticleRowFrame(
        modifier = if (onOpen != null) Modifier.clickable(onClickLabel = "Read", onClick = onOpen) else Modifier,
        title = { Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        details = {
            Column {
                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                episodeLabel(content.episodeKind, content.pair)?.let { EpisodeLabelTag(it) }
                if (starred) {
                    Text("Starred for your next edition", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        trailing = if (canStar) {
            { StarToggle(entry.title, starred, onStar, enabled = !(building && starred)) }
        } else null,
        reserveTrailing = canStar || reserveStar,
    )
}


private fun dateOf(instant: Instant, zone: ZoneId): String =
    instant.atZone(zone).toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))

private fun statusOf(edition: EditionEntity, zone: ZoneId): String = when (edition.status) {
    EditionStatus.READY -> "Ready to send"
    EditionStatus.DELIVERED -> edition.deliveredAt?.takeIf { dateOf(it, zone) != dateOf(edition.createdAt, zone) }?.let { "Sent on ${dateOf(it, zone)}" } ?: "Sent"
    EditionStatus.FAILED -> "Not sent"
    EditionStatus.BUILDING -> "Being made"
    EditionStatus.DELETED -> "Deleted"
}
