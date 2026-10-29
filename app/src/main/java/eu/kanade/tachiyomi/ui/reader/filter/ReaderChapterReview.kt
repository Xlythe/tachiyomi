package eu.kanade.tachiyomi.ui.reader.filter

import android.content.Context
import android.graphics.BitmapFactory
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File

/** Separate from rule revisions: review choices never invalidate the image matcher corpus/cache. */
internal object ReaderChapterReviews {
    val revision = MutableStateFlow(0L)
    private var store: ReaderChapterReviewStore? = null

    @Synchronized fun store(context: Context): ReaderChapterReviewStore = store ?: ReaderChapterReviewStore(
        File(context.filesDir, "reader-chapter-review-v1/overrides.bin"),
    ).also { store = it }
    fun changed() { revision.update { it + 1 } }
}

/** Header-only, cancellable chapter evidence; opening a visible page never waits for this job. */
internal class ReaderChapterReview(
    val pages: List<ReaderPage>,
    private val chapterKey: String,
    private val store: ReaderChapterReviewStore,
) {
    private data class Fact(
        val size: ReaderChapterSizePolicy.Size,
        val hash: String? = null,
        val label: ReaderChapterSizePolicy.Label = ReaderChapterSizePolicy.Label.UNKNOWN,
        val choice: ReaderChapterReviewStore.Choice? = null,
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val facts = mutableMapOf<Int, Fact>()
    private val sampled = if (pages.size <= 512) {
        pages.map { it.index }.toSet()
    } else {
        (0..511).map { pages[(it * pages.lastIndex.toLong() / 511L).toInt()].index }.toSet()
    }
    val revision = MutableStateFlow(0L)

    @Volatile var assessment = ReaderChapterSizePolicy.Assessment(null, emptyMap(), 0, pages.size)
        private set

    @Volatile var error: String? = null
        private set

    @Volatile var visibilityRevision = 0L
        private set

    @Volatile private var closed = false

    fun start() {
        scope.launch {
            for (page in pages) {
                ensureActive()
                if (page.index !in sampled || page.status != Page.State.READY) continue
                val source = page.originalStream ?: continue
                val bounds = try {
                    runInterruptible {
                        source().use { input ->
                            BitmapFactory.Options().apply {
                                inJustDecodeBounds = true
                                BitmapFactory.decodeStream(input, null, this)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    null
                }
                ensureActive()
                if (bounds != null && bounds.outWidth > 0 && bounds.outHeight > 0) {
                    synchronized(this@ReaderChapterReview) {
                        if (!closed && page.originalStream === source) {
                            facts.putIfAbsent(
                                page.index,
                                Fact(ReaderChapterSizePolicy.Size(bounds.outWidth, bounds.outHeight)),
                            )
                            if (facts.size % 8 == 0) recompute()
                        }
                    }
                }
            }
            synchronized(this@ReaderChapterReview) { if (!closed) recompute() }
        }
    }

    fun available(page: ReaderPage, source: () -> java.io.InputStream) {
        if (closed || page.index !in sampled) return
        scope.launch {
            val bounds = try {
                runInterruptible {
                    source().use { input ->
                        BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                            BitmapFactory.decodeStream(input, null, this)
                        }
                    }
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                null
            }
            ensureActive()
            if (bounds != null && bounds.outWidth > 0 && bounds.outHeight > 0) {
                synchronized(this@ReaderChapterReview) {
                    if (!closed && page.originalStream === source) {
                        facts.putIfAbsent(
                            page.index,
                            Fact(ReaderChapterSizePolicy.Size(bounds.outWidth, bounds.outHeight)),
                        )
                        recompute()
                    }
                }
            }
        }
    }

    /** Called with the same digest already computed by the display path, before any replacement renders. */
    fun original(
        page: ReaderPage,
        bytes: ByteArray,
        hash: String,
        source: (() -> java.io.InputStream)? = page.originalStream,
    ): Boolean {
        if (closed || page.originalStream !== source) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        return observe(page, ReaderChapterSizePolicy.Size(bounds.outWidth, bounds.outHeight), hash, source)
    }

    fun observe(
        page: ReaderPage,
        size: ReaderChapterSizePolicy.Size,
        hash: String,
        source: (() -> java.io.InputStream)?,
    ): Boolean {
        if (closed || page.originalStream !== source) return false
        val choice = try {
            store.get(chapterKey, hash)
        } catch (e: Exception) {
            error = "Stored chapter choices are unavailable: " + e.message
            // Never re-hide a possible explicit restore because its storage is unreadable.
            synchronized(this) {
                if (closed || page.originalStream !== source) return false
                page.applyChapterVisibility(ReaderChapterReviewStore.Choice.KEEP, null)
                revision.update { it + 1 }
            }
            return true
        }
        error = null
        val saved = ReaderImageRules.find(hash)
        val label = when {
            choice == ReaderChapterReviewStore.Choice.KEEP -> ReaderChapterSizePolicy.Label.STORY
            choice == ReaderChapterReviewStore.Choice.HIDE || saved?.optBoolean("hide") == true ->
                ReaderChapterSizePolicy.Label.SPAM
            saved != null -> ReaderChapterSizePolicy.Label.STORY
            else -> ReaderChapterSizePolicy.Label.UNKNOWN
        }
        synchronized(this) {
            if (closed || page.originalStream !== source) return false
            val value = Fact(size, hash, label, choice)
            if (facts[page.index] != value) {
                facts[page.index] = value
                recompute()
            }
        }
        return choice != null || page.chapterSizeReason != null
    }

    @Synchronized fun hash(page: ReaderPage): String? = facts[page.index]?.hash

    @Synchronized fun choice(page: ReaderPage): ReaderChapterReviewStore.Choice? = facts[page.index]?.choice

    @Synchronized fun size(page: ReaderPage): ReaderChapterSizePolicy.Size? = facts[page.index]?.size

    @Synchronized fun candidate(page: ReaderPage) = assessment.exclusions[page.index]

    fun choose(page: ReaderPage, choice: ReaderChapterReviewStore.Choice?) {
        val hash = requireNotNull(hash(page)) { "Wait for the original page preview before changing its choice" }
        store.set(chapterKey, hash, choice)
        synchronized(this) {
            if (closed) return
            for ((index, fact) in facts.toMap()) if (fact.hash == hash) {
                val saved = ReaderImageRules.find(hash)
                val label = when {
                    choice == ReaderChapterReviewStore.Choice.KEEP -> ReaderChapterSizePolicy.Label.STORY
                    choice == ReaderChapterReviewStore.Choice.HIDE || saved?.optBoolean("hide") == true ->
                        ReaderChapterSizePolicy.Label.SPAM
                    saved != null -> ReaderChapterSizePolicy.Label.STORY
                    else -> ReaderChapterSizePolicy.Label.UNKNOWN
                }
                facts[index] = fact.copy(choice = choice, label = label)
            }
            error = null
            recompute()
        }
    }

    fun restoreOriginal(page: ReaderPage, bytes: ByteArray) {
        val hash = ReaderImageRules.hash(bytes)
        store.set(chapterKey, hash, ReaderChapterReviewStore.Choice.KEEP)
        original(page, bytes, hash)
    }

    fun rulesVisibilityChanged() { revision.update { it + 1 } }

    fun savedRule(page: ReaderPage, bytes: ByteArray) {
        val hash = ReaderImageRules.hash(bytes)
        store.set(chapterKey, hash, null)
        original(page, bytes, hash)
    }

    @Synchronized fun invalidate(page: ReaderPage) {
        if (facts.remove(page.index) != null && !closed) recompute()
    }

    private fun recompute() {
        val evidence = pages.filter { it.index in sampled || it.index in facts }.map { page ->
            val fact = facts[page.index]
            ReaderChapterSizePolicy.Page(page.index, fact?.size, fact?.label ?: ReaderChapterSizePolicy.Label.UNKNOWN)
        }
        assessment = ReaderChapterSizePolicy.evaluate(evidence)
        var refresh = false
        for (page in pages) {
            val fact = facts[page.index]
            // Metadata-only probes cannot override a saved KEEP until content identity is known.
            val reason = assessment.exclusions[page.index].takeIf { fact?.hash != null }
            if (page.applyChapterVisibility(fact?.choice, reason)) refresh = true
        }
        revision.update { it + 1 }
        if (refresh) {
            visibilityRevision++
            ReaderChapterReviews.changed()
        }
    }

    fun close() {
        closed = true
        scope.cancel()
    }
}
