package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.WebtoonLayoutManager
import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.filter.ReaderImageRules
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.util.system.createReaderThemeContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Exercises Android view-state recreation with the production recycler, adapter and layout manager.
 * Image decoding and source/database IO are replaced by a deterministic tall rendered page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF) // No TLS is exercised by the view-state fixture.
class WebtoonRotationPositionTest {
    @After fun cleanup() {
        unmockkAll()
        Host.width = 720
        Host.height = 1280
    }

    @Test fun recreationKeepsTheMiddleOfALongPageAcrossWidthChanges() {
        Host.width = 720
        Host.height = 1280
        val original = Robolectric.buildActivity(Host::class.java).setup()
        val old = original.get()
        old.layoutReader()
        (old.recycler.layoutManager as WebtoonLayoutManager).scrollToPositionWithOffset(1, -6000)
        old.layoutReader()
        assertTrue("The original fixture must actually be scrolled into its long page", old.fraction() > 0.49)
        val saved = Bundle()
        original.pause().saveInstanceState(saved).stop().destroy()

        Host.width = 1280
        Host.height = 720
        val recreated = Robolectric.buildActivity(Host::class.java)
            .create(saved).start().restoreInstanceState(saved).resume().visible()
        try {
            recreated.get().layoutReader()
            assertEquals(
                "Rotation must preserve intra-page content, not restart the image",
                0.5,
                recreated.get().fraction(),
                0.001,
            )
        } finally { recreated.pause().stop().destroy() }
    }

    @Test fun recreationAtTheBeginningDoesNotInventAnOffset() {
        val original = Robolectric.buildActivity(Host::class.java).setup()
        val saved = Bundle()
        original.get().layoutReader()
        original.pause().saveInstanceState(saved).stop().destroy()
        val recreated = Robolectric.buildActivity(Host::class.java)
            .create(saved).start().restoreInstanceState(saved).resume().visible()
        try {
            recreated.get().layoutReader()
            assertEquals(0.0, recreated.get().fraction(), 0.001)
        } finally { recreated.pause().stop().destroy() }
    }

    class Host : Activity() {
        companion object {
            var width = 720
            var height = 1280
        }
        lateinit var recycler: WebtoonRecyclerView

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val viewer = mockk<WebtoonViewer>(relaxed = true)
            val readerActivity = mockk<ReaderActivity>(relaxed = true)
            every { viewer.activity } returns readerActivity
            mockkStatic("eu.kanade.tachiyomi.util.system.ContextExtensionsKt")
            every { readerActivity.createReaderThemeContext() } returns this
            val adapter = spyk(WebtoonAdapter(viewer))
            every { adapter.onCreateViewHolder(any(), any()) } answers {
                object : RecyclerView.ViewHolder(View(firstArg<ViewGroup>().context)) {}
            }
            every { adapter.onBindViewHolder(any(), any()) } answers {
                val page = adapter.items[secondArg<Int>()] as? ReaderPage
                firstArg<RecyclerView.ViewHolder>().itemView.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    if (page == null) 0 else (12000L * width / 720).toInt(),
                )
            }
            val chapter = ReaderChapter(ChapterImpl().apply { id = 47; manga_id = 72 })
            chapter.state = ReaderChapter.State.Loaded(
                listOf(
                    ReaderPage(0).apply {
                        this.chapter = chapter
                        status = Page.State.READY
                        displayDimensions = 720 to 12000
                        markWebtoonImageReady(ReaderImageRules.revision.value)
                    },
                ),
            )
            adapter.setChapters(ViewerChapters(chapter, null, null), false)
            recycler = WebtoonRecyclerView(this).apply {
                layoutManager = WebtoonLayoutManager(this@Host)
                this.adapter = adapter
            }
            setContentView(recycler)
        }

        fun layoutReader() {
            repeat(3) {
                recycler.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                )
                recycler.layout(0, 0, width, height)
            }
        }

        fun fraction(): Double {
            val page = requireNotNull(recycler.findViewHolderForAdapterPosition(1)) { "Tall page must be attached" }.itemView
            return -page.top.toDouble() / page.height
        }
    }
}
