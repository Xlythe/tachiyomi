package eu.kanade.presentation.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.filter.ReaderChapterReview
import eu.kanade.tachiyomi.ui.reader.filter.ReaderChapterReviewStore
import eu.kanade.tachiyomi.ui.reader.filter.ReaderChapterSizePolicy
import eu.kanade.tachiyomi.ui.reader.filter.ReaderImageRules
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import java.security.MessageDigest

@Composable
internal fun ReaderChapterSpamReviewDialog(
    chapter: ReaderChapter,
    onDismiss: () -> Unit,
    onEdit: (ReaderPage) -> Unit,
) {
    val review = chapter.review
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.82f).dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(MR.strings.reader_chapter_spam_review), style = MaterialTheme.typography.titleLarge)
                Text(chapter.chapter.name, style = MaterialTheme.typography.bodyMedium)
                if (review == null) {
                    Text(stringResource(MR.strings.reader_review_not_ready))
                } else {
                    ReviewPages(review, onEdit, Modifier.weight(1f, fill = false))
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(MR.strings.action_close))
                }
            }
        }
    }
}

@Composable
private fun ReviewPages(review: ReaderChapterReview, onEdit: (ReaderPage) -> Unit, modifier: Modifier) {
    val revision by review.revision.collectAsState()
    val rulesRevision by ReaderImageRules.revision.collectAsState()
    val automaticRevision by ReaderImageRules.automaticRevision.collectAsState()
    var showAll by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val pages = remember(revision, rulesRevision, automaticRevision, showAll) {
        review.pages.filter { showAll || it.isHidden || review.candidate(it) != null || review.choice(it) != null }
    }
    val assessment = review.assessment
    val reference = assessment.reference
    Text(
        if (reference == null) {
            stringResource(MR.strings.reader_review_collecting_sizes)
        } else {
            if (reference.height > 0) {
                stringResource(MR.strings.reader_review_normal_size, reference.width, reference.height)
            } else {
                stringResource(MR.strings.reader_review_normal_width, reference.width)
            }
        },
        style = MaterialTheme.typography.bodySmall,
    )
    Text(stringResource(MR.strings.reader_review_scope), style = MaterialTheme.typography.bodySmall)
    if (error || review.error != null) {
        Text(stringResource(MR.strings.reader_review_save_error), color = MaterialTheme.colorScheme.error)
    }
    Row(
        Modifier.toggleable(value = showAll, role = Role.Checkbox, onValueChange = { showAll = it }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = showAll, onCheckedChange = null)
        Text(stringResource(MR.strings.reader_review_show_all))
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (pages.isEmpty()) item { Text(stringResource(MR.strings.reader_review_none)) }
        items(pages, key = { it.index }) { page ->
            ReviewPage(review, page, onEdit, onError = { error = true })
            HorizontalDivider()
        }
    }
}

@Composable
private fun ReviewPage(
    review: ReaderChapterReview,
    page: ReaderPage,
    onEdit: (ReaderPage) -> Unit,
    onError: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val status by page.statusFlow.collectAsState()
    val source = page.originalStream
    var busy by remember(page) { mutableStateOf(false) }
    val preview by produceState<ReviewPreview?>(null, page, source, status) {
        if (source == null || status != Page.State.READY) return@produceState
        value = withContext(Dispatchers.IO) {
            try {
                previewPermits.withPermit {
                    runInterruptible {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        source().use { BitmapFactory.decodeStream(it, null, bounds) }
                        check(bounds.outWidth > 0 && bounds.outHeight > 0)
                        val digest = MessageDigest.getInstance("SHA-256")
                        source().use { input ->
                            val buffer = ByteArray(16 * 1024)
                            var total = 0L
                            while (true) {
                                if (Thread.currentThread().isInterrupted) throw CancellationException()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                check(total <= 16 * 1024 * 1024)
                                digest.update(buffer, 0, count)
                            }
                        }
                        if (page.originalStream !== source) return@runInterruptible ReviewPreview(null)
                        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                        review.observe(
                            page,
                            ReaderChapterSizePolicy.Size(bounds.outWidth, bounds.outHeight),
                            hash,
                            source,
                        )
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = 1
                            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 512) inSampleSize *= 2
                        }
                        ReviewPreview(source().use { BitmapFactory.decodeStream(it, null, options) })
                    }
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                ReviewPreview(null)
            }
        }
    }
    val choice = review.choice(page)
    val size = review.size(page)
    val reason = page.chapterSizeReason ?: review.candidate(page)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val bitmap = preview?.bitmap
        if (bitmap != null) {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = stringResource(MR.strings.reader_review_original_page, page.index + 1),
                modifier = Modifier.size(90.dp, 130.dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            Column(Modifier.size(90.dp, 130.dp), verticalArrangement = Arrangement.Center) {
                if (preview == null && source != null && status == Page.State.READY) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                } else {
                    Text(
                        stringResource(MR.strings.reader_review_preview_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(MR.strings.reader_review_page_number, page.index + 1, review.pages.size),
                style = MaterialTheme.typography.titleSmall,
            )
            if (size != null) Text("${size.width} × ${size.height}", style = MaterialTheme.typography.bodySmall)
            Text(
                when {
                    choice == ReaderChapterReviewStore.Choice.KEEP -> stringResource(MR.strings.reader_review_restored)
                    choice == ReaderChapterReviewStore.Choice.HIDE -> stringResource(
                        MR.strings.reader_review_explicit_hide,
                    )
                    reason == ReaderChapterSizePolicy.Reason.WIDER_AND_SHORTER -> stringResource(
                        MR.strings.reader_review_wider_and_shorter,
                    )
                    page.isHidden -> stringResource(MR.strings.reader_review_saved_rule)
                    else -> stringResource(MR.strings.reader_review_included)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            fun choose(value: ReaderChapterReviewStore.Choice?) {
                busy = true
                scope.launch {
                    try { withContext(Dispatchers.IO) { review.choose(page, value) } } catch (
                        e: Exception,
                    ) {
                        if (e is CancellationException) throw e
                        onError()
                    } finally { busy = false }
                }
            }
            FlowRow {
                val restored = choice == ReaderChapterReviewStore.Choice.KEEP ||
                    (choice == null && reason == null && !page.isHidden)
                TextButton(
                    enabled = !busy && review.hash(page) != null,
                    onClick = {
                        choose(
                            if (restored) {
                                ReaderChapterReviewStore.Choice.HIDE
                            } else {
                                ReaderChapterReviewStore.Choice.KEEP
                            },
                        )
                    },
                ) {
                    Text(
                        stringResource(
                            if (restored) MR.strings.reader_review_exclude else MR.strings.reader_review_restore,
                        ),
                    )
                }
                if (choice != null) {
                    TextButton(enabled = !busy, onClick = { choose(null) }) {
                        Text(stringResource(MR.strings.reader_review_automatic))
                    }
                }
            }
            TextButton(enabled = !busy && status == Page.State.READY && source != null, onClick = { onEdit(page) }) {
                Text(stringResource(MR.strings.reader_review_edit_rule))
            }
        }
    }
}

private val previewPermits = Semaphore(2)

private data class ReviewPreview(val bitmap: Bitmap?)
