package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.filter.ReaderChapterReview
import eu.kanade.tachiyomi.ui.reader.filter.ReaderChapterReviewStore
import eu.kanade.tachiyomi.ui.reader.filter.ReaderChapterSizePolicy
import eu.kanade.tachiyomi.ui.reader.filter.ReaderImageRules
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    stream: (() -> InputStream)? = null,
) : Page(index, url, imageUrl, null) {

    @Volatile var originalStream: (() -> InputStream)? = stream
        private set

    var stream: (() -> InputStream)?
        get() = originalPage.originalStream?.let { original ->
            {
                ReaderImageRules.openForDisplayWithReview(
                    onMatch = {
                        val target = originalPage
                        if (target.originalStream === original) {
                            val changed = target.hidden != it
                            target.hidden = it
                            target.matchedRevision = ReaderImageRules.revision.value
                            if (changed) target.chapterReview?.rulesVisibilityChanged()
                        }
                    },
                    original = original,
                    onOriginal = { bytes, hash ->
                        val target = originalPage
                        if (target.originalStream !== original) return@openForDisplayWithReview bytes.inputStream()
                        val review = target.chapterReview
                        if (review?.original(target, bytes, hash, original) == true) {
                            applyRuleVisibility(false)
                            bytes.inputStream()
                        } else {
                            null
                        }
                    },
                )
            }
        }
        set(value) {
            originalStream = value
            chapterVisibility.updateAndGet { ChapterVisibility(revision = it.revision + 1) }
            displayDimensions = null
            hidden = false
            matchedRevision = -1L
            webtoonReady.set(null)
            if (this::chapter.isInitialized) {
                chapter.review?.invalidate(this)
                if (value != null) chapter.review?.available(this, value)
            }
        }

    /** Last displayed aspect ratio, not an image copy. Headers fill this before slow filtering. */
    @Volatile
    internal var displayDimensions: Pair<Int, Int>? = null

    /** The editor already knows this exact page's committed choice; no matcher lock is needed. */
    internal fun applyRuleVisibility(isHidden: Boolean) {
        originalPage.hidden = isHidden
        originalPage.matchedRevision = ReaderImageRules.revision.value
    }

    @Volatile
    private var matchedRevision = -1L

    @Volatile
    private var hidden = false

    private data class ChapterVisibility(
        val choice: ReaderChapterReviewStore.Choice? = null,
        val reason: ReaderChapterSizePolicy.Reason? = null,
        val revision: Long = 0L,
    )
    private val chapterVisibility = AtomicReference(ChapterVisibility())
    internal val chapterSizeReason: ReaderChapterSizePolicy.Reason? get() = originalPage.chapterVisibility.get().reason
    internal val chapterVisibilityRevision: Long get() = originalPage.chapterVisibility.get().revision
    val isHidden: Boolean get() = if (originalPage !== this) {
        originalPage.isHidden
    } else {
        val visibility = chapterVisibility.get()
        when (visibility.choice) {
            ReaderChapterReviewStore.Choice.KEEP -> false
            ReaderChapterReviewStore.Choice.HIDE -> true
            null -> visibility.reason != null || (hidden && matchedRevision == ReaderImageRules.revision.value)
        }
    }
    internal fun applyChapterVisibility(
        choice: ReaderChapterReviewStore.Choice?,
        reason: ReaderChapterSizePolicy.Reason?,
    ): Boolean {
        if (originalPage !== this) return originalPage.applyChapterVisibility(choice, reason)
        while (true) {
            val previous = chapterVisibility.get()
            if (previous.choice == choice && previous.reason == reason) return false
            val next = ChapterVisibility(choice, reason, previous.revision + 1)
            if (chapterVisibility.compareAndSet(previous, next)) return true
        }
    }

    private data class ReadyImage(val rules: Long, val chapter: Long, val source: (() -> InputStream)?)
    private val webtoonReady = AtomicReference<ReadyImage?>(null)
    internal val isWebtoonImageReady: Boolean get() {
        val target = originalPage
        val ready = target.webtoonReady.get() ?: return false
        return ready.rules == ReaderImageRules.revision.value && ready.chapter == target.chapterVisibilityRevision &&
            ready.source === target.originalStream
    }
    internal fun invalidateWebtoonImage() { originalPage.webtoonReady.set(null) }
    internal fun markWebtoonImageReady(
        revision: Long,
        chapterRevision: Long = chapterVisibilityRevision,
        source: (() -> InputStream)? = originalPage.originalStream,
    ) {
        val target = originalPage
        if (revision == ReaderImageRules.revision.value && chapterRevision == target.chapterVisibilityRevision &&
            source === target.originalStream
        ) {
            target.webtoonReady.set(ReadyImage(revision, chapterRevision, source))
        }
    }

    internal val originalPage: ReaderPage get() = if (this is InsertPage) parent.originalPage else this
    internal val chapterReview: ReaderChapterReview? get() = if (originalPage !== this) {
        originalPage.chapterReview
    } else {
        if (this::chapter.isInitialized) chapter.review else null
    }

    open lateinit var chapter: ReaderChapter
}
