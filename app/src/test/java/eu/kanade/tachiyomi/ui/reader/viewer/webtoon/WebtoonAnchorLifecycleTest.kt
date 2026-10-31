package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.app.Application
import android.os.Bundle
import android.os.Parcel
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.WebtoonLayoutManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.filter.ReaderChapterReviewStore
import eu.kanade.tachiyomi.ui.reader.filter.ReaderImageRules
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import io.mockk.every
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class WebtoonAnchorLifecycleTest {
    private val controllers = mutableListOf<ActivityController<WebtoonRotationPositionTest.Host>>()

    @After fun cleanup() {
        controllers.forEach { it.pause().stop().destroy() }
        unmockkAll()
        WebtoonRotationPositionTest.Host.width = 720
        WebtoonRotationPositionTest.Host.height = 1280
    }

    @Test fun repeatedRotationsPreserveTheSamePointInBothDirections() {
        var controller = create(720, 1280)
        controller.get().manager.scrollToPositionWithOffset(1, -7440)
        controller.get().layoutReader()
        repeat(4) { index ->
            val saved = save(controller)
            controller = if (index % 2 == 0) create(1280, 720, saved) else create(720, 1280, saved)
            assertEquals(0.62, controller.get().fraction(), 0.001)
            assertFalse(controller.get().manager.isRestoringReaderAnchor)
        }
    }

    @Test fun exactlyAlignedChapterEndRemainsAtTheEndAfterRotation() {
        val original = create(720, 1280)
        original.get().manager.scrollToPositionWithOffset(1, 1280 - 12000)
        original.get().layoutReader()
        val restored = create(1280, 720, save(original)).get()
        val image = requireNotNull(restored.recycler.findViewHolderForAdapterPosition(1)).itemView
        assertEquals(720, image.bottom)
    }

    @Test fun parcelledProcessStateRetainsTheOriginalPagePoint() {
        val original = create(720, 1280)
        original.get().manager.scrollToPositionWithOffset(1, -3000)
        original.get().layoutReader()
        val parcel = Parcel.obtain()
        val saved = try {
            parcel.writeBundle(save(original))
            parcel.setDataPosition(0)
            requireNotNull(parcel.readBundle(javaClass.classLoader))
        } finally { parcel.recycle() }
        assertEquals(0.25, create(1280, 720, saved).get().fraction(), 0.001)
    }

    @Test fun pendingDecodeDoesNotConsumeTheSavedPointOrReplaceItWithPlaceholderPixels() {
        val original = create(720, 1280)
        original.get().manager.scrollToPositionWithOffset(1, -6000)
        original.get().layoutReader()
        val saved = save(original)
        val loading = create(1280, 720, saved, loading = true)
        val page = loading.get().page
        assertTrue(loading.get().manager.isRestoringReaderAnchor)
        val pending = save(loading)
        val resumed = create(720, 1280, pending, loading = true).get()
        assertTrue(resumed.manager.isRestoringReaderAnchor)
        assertEquals(0, page.index)
        resumed.page.status = Page.State.READY
        resumed.page.markWebtoonImageReady(ReaderImageRules.revision.value)
        resumed.adapter.notifyItemChanged(1)
        resumed.layoutReader()
        assertFalse(resumed.manager.isRestoringReaderAnchor)
        assertEquals(0.5, resumed.fraction(), 0.001)
    }

    @Test fun semanticAnchorSurvivesAHiddenPrecedingPageAndAnAdjacentPageBoundary() {
        val original = create(720, 1280, twoPages = true)
        original.get().manager.scrollToPositionWithOffset(2, -720)
        original.get().layoutReader()
        val restored = create(1280, 720, save(original), twoPages = true, hideFirst = true).get()
        val image = requireNotNull(restored.recycler.findViewHolderForAdapterPosition(2)).itemView
        assertEquals(0.06, -image.top.toDouble() / image.height, 0.001)
        assertTrue(restored.page.isHidden)
    }

    @Test fun aNewlyHiddenTargetMovesToVisibleContentWithoutRestoringItsHiddenPixels() {
        val original = create(720, 1280, twoPages = true)
        original.get().manager.scrollToPositionWithOffset(1, -6000)
        original.get().layoutReader()
        val restored = create(1280, 720, save(original), twoPages = true, hideFirst = true).get()
        val next = requireNotNull(restored.recycler.findViewHolderForAdapterPosition(2)).itemView
        assertEquals(0, next.top)
        assertFalse(restored.manager.isRestoringReaderAnchor)
    }

    @Test fun aSplitSecondHalfKeepsItsOwnIdentity() {
        val original = create(720, 1280, split = true)
        original.get().manager.scrollToPositionWithOffset(2, -3600)
        original.get().layoutReader()
        val restored = create(1280, 720, save(original), split = true).get()
        val second = requireNotNull(restored.recycler.findViewHolderForAdapterPosition(2)).itemView
        assertEquals(0.3, -second.top.toDouble() / second.height, 0.001)
    }

    @Test fun userDraggingCancelsAPendingRestoreInsteadOfFightingTheReader() {
        val original = create(720, 1280)
        original.get().manager.scrollToPositionWithOffset(1, -6000)
        original.get().layoutReader()
        val restored = create(1280, 720, save(original), loading = true).get()
        assertTrue(restored.manager.isRestoringReaderAnchor)
        restored.recycler.onScrollStateChanged(RecyclerView.SCROLL_STATE_DRAGGING)
        assertFalse(restored.manager.isRestoringReaderAnchor)
    }

    private fun create(
        width: Int,
        height: Int,
        saved: Bundle? = null,
        loading: Boolean = false,
        twoPages: Boolean = false,
        hideFirst: Boolean = false,
        split: Boolean = false,
    ): ActivityController<WebtoonRotationPositionTest.Host> {
        WebtoonRotationPositionTest.Host.width = width
        WebtoonRotationPositionTest.Host.height = height
        val controller = Robolectric.buildActivity(WebtoonRotationPositionTest.Host::class.java).create(saved).start()
        controllers += controller
        val host = controller.get()
        val first = host.page
        val chapter = first.chapter
        val pages = mutableListOf(first)
        if (twoPages) {
            pages += ReaderPage(1).apply {
                this.chapter = chapter
                status = Page.State.READY
                markWebtoonImageReady(ReaderImageRules.revision.value)
            }
        }
        if (split) pages += InsertPage(first)
        if (hideFirst) first.applyChapterVisibility(ReaderChapterReviewStore.Choice.HIDE, null)
        if (loading) {
            first.status = Page.State.LOAD_PAGE
            first.invalidateWebtoonImage()
        }
        every { host.adapter.onBindViewHolder(any(), any()) } answers {
            val page = host.adapter.items[secondArg<Int>()] as? ReaderPage
            val imageHeight = when {
                page == null || page.isHidden -> 0
                page.status != Page.State.READY -> height
                else -> (12000L * width / 720).toInt()
            }
            firstArg<RecyclerView.ViewHolder>().itemView.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                imageHeight,
            )
        }
        chapter.state = ReaderChapter.State.Loaded(pages)
        host.adapter.setChapters(ViewerChapters(chapter, null, null), false)
        host.adapter.notifyDataSetChanged()
        if (saved != null) controller.restoreInstanceState(saved)
        controller.resume().visible()
        host.layoutReader()
        return controller
    }

    private fun save(controller: ActivityController<WebtoonRotationPositionTest.Host>): Bundle {
        val saved = Bundle()
        controller.pause().saveInstanceState(saved).stop().destroy()
        controllers.remove(controller)
        return saved
    }

    private val WebtoonRotationPositionTest.Host.manager: WebtoonLayoutManager
        get() = recycler.layoutManager as WebtoonLayoutManager
    private val WebtoonRotationPositionTest.Host.adapter: WebtoonAdapter get() = recycler.adapter as WebtoonAdapter
    private val WebtoonRotationPositionTest.Host.page: ReaderPage
        get() = adapter.items.filterIsInstance<ReaderPage>().first()
}
