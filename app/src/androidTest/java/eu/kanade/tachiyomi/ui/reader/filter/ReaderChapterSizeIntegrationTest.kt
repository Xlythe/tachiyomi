package eu.kanade.tachiyomi.ui.reader.filter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.forPageVisibility
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ReaderChapterSizeIntegrationTest {
    @Test fun splitPagesObserveTheirOriginalVisibilityWithoutRespondingToOtherPages() = runBlocking {
        val chapter = ReaderChapter(ChapterImpl())
        val original = ReaderPage(0).apply { this.chapter = chapter }
        val split = InsertPage(original)
        val other = ReaderPage(1).apply { this.chapter = chapter }
        for (page in listOf(original, split)) {
            original.applyChapterVisibility(null, null)
            val initial = page.chapterVisibilityRevision
            val values = flow {
                emit(0L)
                other.applyChapterVisibility(ReaderChapterReviewStore.Choice.HIDE, null)
                emit(1L)
                original.applyChapterVisibility(ReaderChapterReviewStore.Choice.HIDE, null)
                emit(2L)
                emit(3L)
                original.applyChapterVisibility(ReaderChapterReviewStore.Choice.KEEP, null)
                emit(4L)
            }.forPageVisibility { page.chapterVisibilityRevision }.toList()
            assertEquals(listOf(initial, initial + 1, initial + 2), values)
        }
    }

    @Test fun reevaluationClearsObsoleteDimensionFlagsWithoutLosingManualChoicesOrIndependentRules() {
        val fixture = Fixture(unusualWidth = 720, unusualHeight = 700)
        try {
            fixture.ready()
            val page = fixture.pages[3]
            // The old heuristic's derived state was memory-only, never a persisted image rule.
            page.applyChapterVisibility(null, ReaderChapterSizePolicy.Reason.WIDER_AND_SHORTER)
            assertTrue(page.isHidden)
            page.applyRuleVisibility(true)
            fixture.review.original(page, fixture.bytes[3], ReaderImageRules.hash(fixture.bytes[3]))
            assertNull(page.chapterSizeReason)
            assertTrue(page.isHidden) // Independent image-rule state remains authoritative.
            page.applyRuleVisibility(false)
            assertFalse(page.isHidden)
            fixture.review.choose(page, ReaderChapterReviewStore.Choice.HIDE)
            assertTrue(page.isHidden)
            fixture.review.choose(page, ReaderChapterReviewStore.Choice.KEEP)
            assertFalse(page.isHidden)
            fixture.review.close()
            val reopened = fixture.newReview(ReaderChapterReviewStore(fixture.file), "chapter1")
            fixture.chapter.review = reopened
            reopened.start()
            runBlocking { withTimeout(2_000) { reopened.revision.first { reopened.assessment.reference != null } } }
            reopened.original(page, fixture.bytes[3], ReaderImageRules.hash(fixture.bytes[3]))
            assertEquals(ReaderChapterReviewStore.Choice.KEEP, reopened.choice(page))
            assertFalse(page.isHidden)
        } finally { fixture.close() }
    }

    @Test fun downloadedHeaderEvidenceExcludesWithoutChangingOriginalPagesOrIndices() {
        val fixture = Fixture()
        try {
            fixture.ready()
            val page = fixture.pages[3]
            assertArrayEquals(fixture.bytes[3], page.stream!!.invoke().use { it.readBytes() })
            assertTrue(page.isHidden)
            assertEquals((0..9).toList(), fixture.pages.map { it.index })
            assertEquals(ReaderChapterSizePolicy.Reason.WIDER_AND_SHORTER, page.chapterSizeReason)
        } finally { fixture.close() }
    }

    @Test fun restoringSurvivesReevaluationRestartAndIdenticalContentWithinTheChapter() {
        val fixture = Fixture()
        try {
            fixture.ready()
            val page = fixture.pages[3]
            page.stream!!.invoke().close()
            fixture.review.choose(page, ReaderChapterReviewStore.Choice.KEEP)
            repeat(3) {
                assertArrayEquals(fixture.bytes[3], page.stream!!.invoke().use { it.readBytes() })
                assertFalse(page.isHidden)
            }
            fixture.review.close()
            val reopened = fixture.newReview(ReaderChapterReviewStore(fixture.file), "chapter1")
            fixture.chapter.review = reopened
            reopened.start()
            runBlocking { withTimeout(2_000) { reopened.revision.first { reopened.assessment.reference != null } } }
            assertArrayEquals(fixture.bytes[3], page.stream!!.invoke().use { it.readBytes() })
            assertFalse(page.isHidden)
            reopened.choose(page, null)
            page.stream!!.invoke().close()
            assertTrue(page.isHidden)
            assertNotNull(page.originalStream)
        } finally { fixture.close() }
    }

    @Test fun chapterRestoreDoesNotLeakIntoAnotherChapterAndSplitPagesShareTheOriginalDecision() {
        val fixture = Fixture()
        try {
            fixture.ready()
            val page = fixture.pages[3]
            page.stream!!.invoke().close()
            val split = InsertPage(page)
            assertTrue(split.isHidden)
            fixture.review.choose(page, ReaderChapterReviewStore.Choice.KEEP)
            assertArrayEquals(fixture.bytes[3], split.stream!!.invoke().use { it.readBytes() })
            assertFalse(split.isHidden)
            fixture.review.close()
            val other = fixture.newReview(ReaderChapterReviewStore(fixture.file), "chapter2")
            fixture.chapter.review = other
            other.start()
            runBlocking { withTimeout(2_000) { other.revision.first { other.assessment.reference != null } } }
            page.stream!!.invoke().close()
            assertTrue(page.isHidden)
            assertTrue(split.isHidden)
        } finally { fixture.close() }
    }

    @Test fun aCallbackForAReplacedStreamCannotPublishOldVisibility() {
        val fixture = Fixture()
        try {
            fixture.ready()
            val page = fixture.pages[3]
            val oldSource = page.originalStream
            page.stream = { fixture.bytes[0].inputStream() }
            assertFalse(
                fixture.review.original(page, fixture.bytes[3], ReaderImageRules.hash(fixture.bytes[3]), oldSource),
            )
            assertFalse(page.isHidden)
            assertArrayEquals(fixture.bytes[0], page.stream!!.invoke().use { it.readBytes() })
            assertFalse(page.isHidden)
        } finally { fixture.close() }
    }

    @Test fun sourceReplacementRejectsOldReadinessEvenWhenRuleVersionsAreUnchanged() {
        val page = ReaderPage(0, stream = { byteArrayOf(1).inputStream() })
        val old = page.originalStream
        val epoch = page.chapterVisibilityRevision
        page.stream = { byteArrayOf(2).inputStream() }
        page.markWebtoonImageReady(ReaderImageRules.revision.value, epoch, old)
        assertFalse(page.isWebtoonImageReady)
        page.markWebtoonImageReady(ReaderImageRules.revision.value)
        assertTrue(page.isWebtoonImageReady)
    }

    @Test fun knownPartialRulesKeepStoryAndANewExplicitEditSupersedesAChapterRestore() {
        val fixture = Fixture()
        val bytes = fixture.bytes[3]
        val hash = ReaderImageRules.hash(bytes)
        val before = ReaderImageRules.hash(ReaderImageRules.export().toByteArray())
        assertNull(ReaderImageRules.find(hash))
        var owned = false
        try {
            fixture.ready()
            val page = fixture.pages[3]
            ReaderImageRules.save(bytes, 0, 100, JSONObject())
            owned = true
            val cropped = page.stream!!.invoke().use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(cropped, 0, cropped.size, bounds)
            assertEquals(924, bounds.outHeight)
            assertFalse(page.isHidden)
            fixture.review.choose(page, ReaderChapterReviewStore.Choice.KEEP)
            assertArrayEquals(bytes, page.stream!!.invoke().use { it.readBytes() })
            ReaderImageRules.save(bytes, 0, 120, JSONObject())
            fixture.review.savedRule(page, bytes)
            assertNull(fixture.review.choice(page))
            val edited = page.stream!!.invoke().use { it.readBytes() }
            BitmapFactory.decodeByteArray(edited, 0, edited.size, bounds)
            assertEquals(904, bounds.outHeight)
            assertFalse(page.isHidden)
        } finally {
            fixture.close()
            if (owned) ReaderImageRules.remove(hash)
            assertEquals(before, ReaderImageRules.hash(ReaderImageRules.export().toByteArray()))
        }
    }

    @Test fun staleDecodeCannotMarkARestoredPageReadyForChapterCompletion() {
        val page = ReaderPage(0)
        val revision = ReaderImageRules.revision.value
        page.applyChapterVisibility(ReaderChapterReviewStore.Choice.HIDE, null)
        val old = page.chapterVisibilityRevision
        page.markWebtoonImageReady(revision, old)
        assertTrue(page.isWebtoonImageReady)
        page.applyChapterVisibility(ReaderChapterReviewStore.Choice.KEEP, null)
        page.markWebtoonImageReady(revision, old)
        assertFalse(page.isWebtoonImageReady)
        page.markWebtoonImageReady(revision, page.chapterVisibilityRevision)
        assertTrue(page.isWebtoonImageReady)
    }

    @Test fun cancelledHeaderReadsCannotPublishNewChapterDecisions() {
        val fixture = Fixture(start = false)
        val entered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.pages[0].stream = {
            entered.countDown()
            try {
                release.await()
                fixture.bytes[0].inputStream()
            } finally { exited.countDown() }
        }
        try {
            fixture.review.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            fixture.review.close()
            release.countDown()
            assertTrue(exited.await(2, TimeUnit.SECONDS))
            assertEquals(0, fixture.review.assessment.inspected)
            assertTrue(fixture.pages.none { it.isHidden })
        } finally {
            release.countDown()
            fixture.close()
        }
    }

    internal class Fixture(start: Boolean = true, unusualWidth: Int = 1532, unusualHeight: Int = 1024) {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "reader-chapter-size-" + UUID.randomUUID()).apply { check(mkdirs()) }
        val file = File(root, "overrides")
        val bytes = (0..9).map { index ->
            val width = if (index == 3) unusualWidth else 720
            val height = if (index == 3) unusualHeight else 1500
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(31 + index, 77 + index, 103 + index))
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                .toByteArray().also { bitmap.recycle() }
        }
        val chapter = ReaderChapter(
            ChapterImpl().apply {
                id = 471L
                manga_id = 72L
                name = "Size review fixture"
            },
        )
        val pages = bytes.mapIndexed { index, data ->
            ReaderPage(index, stream = { data.inputStream() }).apply {
                this.chapter = this@Fixture.chapter
                status = Page.State.READY
            }
        }
        val review = newReview(ReaderChapterReviewStore(file), "chapter1")
        init {
            chapter.state = ReaderChapter.State.Loaded(pages)
            chapter.review = review
            if (start) review.start()
        }
        fun newReview(store: ReaderChapterReviewStore, key: String) = ReaderChapterReview(pages, key, store)
        fun ready() = runBlocking {
            withTimeout(
                2_000,
            ) { review.revision.first { review.assessment.reference != null } }
        }
        fun close() {
            chapter.review?.close()
            review.close()
            check(
                root.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator + "reader-chapter-size-"),
            )
            root.deleteRecursively()
        }
    }
}
