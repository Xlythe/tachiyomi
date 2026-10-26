package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.view.View
import android.view.ViewGroup
import android.graphics.Bitmap
import com.davemorrissey.labs.subscaleview.ImageSource
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebtoonPageGeometryTest {
    @Test
    fun reservedPageMatchesTheActualImageLibraryAtFractionalFitWidths() = withPage { recycler, frame, geometry ->
        val bitmap = Bitmap.createBitmap(599, 1000, Bitmap.Config.ARGB_8888)
        val image = WebtoonSubsamplingImageView(frame.context)
        try {
            image.setImage(ImageSource.bitmap(bitmap))
            image.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST))
            geometry.bind(599 to 1000)
            layout(recycler, 901)
            assertEquals("Wrapper must not add a row beyond the image view's natural fit-width extent",
                image.measuredHeight, frame.height)
        } finally {
            image.recycle()
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    @Test
    fun legacyUnreservedPendingViewMeasuresZeroButBindingReservesViewport() = withPage(
        false,
    ) { recycler, frame, geometry ->
        assertEquals("Legacy WRAP_CONTENT with no decoded child has zero height", 0, frame.height)
        geometry.bind(null)
        layout(recycler, 901)
        assertTrue(frame.height >= 900)
    }

    @Test
    fun delayedDownloadedAndNetworkPagesKeepVisibleAnchorThroughMetadataDecodeAndRetry() {
        for (cached in listOf(false, true)) withPage { recycler, frame, geometry ->
            val owned = frame.layoutParams
            geometry.bind(if (cached) 600 to 1200 else null)
            val token = geometry.begin()
            val manager = recycler.layoutManager as LinearLayoutManager
            manager.scrollToPositionWithOffset(1, -80)
            layout(recycler, 901)
            assertTrue("Pending page must reserve space", frame.height > 1)
            assertAnchor(manager)
            geometry.metadata(token, 600, 1800)
            layout(recycler, 902)
            assertEquals(1800, frame.height)
            assertAnchor(manager)
            geometry.loaded(token, 600, 1800)
            layout(recycler, 903)
            assertAnchor(manager)
            // Error/retry leaves the last known aspect ratio in place while bytes are delayed.
            geometry.pending()
            val retry = geometry.begin()
            layout(recycler, 904)
            assertEquals(1800, frame.height)
            assertAnchor(manager)
            geometry.loaded(retry, 600, 1800)
            layout(recycler, 905)
            assertAnchor(manager)
            assertSame(owned, frame.layoutParams)
        }
    }

    @Test
    fun recycledAndOutOfOrderResultsCannotResizeAnotherPageAndOnlyConfirmedHidesCollapse() =
        withPage { recycler, frame, geometry ->
            val owned = frame.layoutParams
            val old = geometry.bind(600 to 1200)
            geometry.invalidate()
            val current = geometry.bind(600 to 800)
            geometry.metadata(old, 600, 9000)
            geometry.loaded(old, 600, 9000)
            geometry.hide(old)
            layout(recycler, 901)
            assertEquals(800, frame.height)
            geometry.hide(current)
            layout(recycler, 902)
            assertEquals(1, frame.height)
            geometry.bind(null)
            layout(recycler, 903)
            assertTrue(frame.height > 1)
            assertSame(owned, frame.layoutParams)
        }

    private fun assertAnchor(manager: LinearLayoutManager) {
        assertEquals(1, manager.findFirstVisibleItemPosition())
        assertEquals(-80, manager.getDecoratedTop(manager.findViewByPosition(1)!!))
    }

    private fun withPage(
        reserve: Boolean = true,
        action: (RecyclerView, ReaderPageImageView, WebtoonPageGeometry) -> Unit,
    ) {
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val recycler = RecyclerView(context)
            recycler.layoutManager = LinearLayoutManager(context)
            lateinit var frame: ReaderPageImageView
            lateinit var geometry: WebtoonPageGeometry
            recycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = 3
                override fun getItemViewType(position: Int) = position
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val view = if (viewType == 1) {
                        ReaderPageImageView(context, isWebtoon = true).also {
                            frame = it
                            it.updateWebtoonPageLayout(0, 0)
                            geometry = WebtoonPageGeometry(it, recycler)
                        }
                    } else {
                        View(context).apply { layoutParams = RecyclerView.LayoutParams(600, 400) }
                    }
                    return object : RecyclerView.ViewHolder(view) {}
                }
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    if (position == 1 && reserve) geometry.bind(null)
                }
            }
            try {
                layout(recycler, 900)
                action(recycler, frame, geometry)
            } catch (error: Throwable) {
                failure = error
            } finally {
                recycler.adapter = null
            }
        }
        failure?.let { throw AssertionError("Pending webtoon geometry failed", it) }
    }

    private fun layout(recycler: RecyclerView, height: Int) {
        recycler.measure(
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        recycler.layout(0, 0, 600, height)
    }
}
