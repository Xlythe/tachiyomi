package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.WebtoonLayoutManager
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.reader.ReaderChapterCompletion
import eu.kanade.tachiyomi.ui.reader.filter.ReaderImageMask
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Real crop output + RecyclerView end selection. Original page indexes are never remapped. */
class WebtoonChapterCompletionTest {
    private fun page(index: Int, hidden: Boolean = false, ready: Boolean = true) =
        ReaderChapterCompletion.Page(index, hidden, ready)

    @Test fun downloadedFinalPageCroppedToAShortImageCompletesBehindTheTransition() {
        val original = Bitmap.createBitmap(600, 1800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val bytes = ByteArrayOutputStream().also { original.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        original.recycle()
        // Same non-destructive processing used for a downloaded page's bottom-spam selection.
        val cropped = ReaderImageMask(bytes).use { it.render(100, 1800) }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(cropped, 0, cropped.size))
        assertEquals(100, bitmap.height)
        withRows(listOf(2000, bitmap.height, 250), 1) { end ->
            assertEquals("Transition is the last end-visible row", 2, end)
            assertTrue(ReaderChapterCompletion.reachesTerminal(1, end))
            assertTrue(ReaderChapterCompletion.completes(listOf(page(0), page(1)), 1))
        }
        bitmap.recycle()
    }

    @Test fun fullyHiddenTrailingPagesKeepTheLastVisibleOriginalProgressIndex() {
        val pages = listOf(page(0), page(1), page(2, true), page(3, true))
        withRows(listOf(2000, 100, 0, 0, 250), 1) { end ->
            assertEquals(1, ReaderChapterCompletion.terminalIndex(pages))
            assertTrue(ReaderChapterCompletion.reachesTerminal(1, end))
            assertTrue(ReaderChapterCompletion.completes(pages, 1))
        }
    }

    @Test fun ordinaryTallFinalPageIsNotCompleteAtItsTopAndPendingContentIsNotComplete() {
        withRows(listOf(2000, 2000, 250), 1) { end ->
            assertFalse(ReaderChapterCompletion.reachesTerminal(1, end))
        }
        assertNull(ReaderChapterCompletion.terminalIndex(listOf(page(0), page(1, ready = false))))
    }

    @Test fun allConfirmedHiddenCanReachTheEndButUnknownHiddenCannot() {
        withRows(listOf(0, 0, 250), 0) { end ->
            assertEquals(1, ReaderChapterCompletion.terminalIndex(listOf(page(0, true), page(1, true))))
            assertTrue(ReaderChapterCompletion.reachesTerminal(1, end))
        }
        assertNull(ReaderChapterCompletion.terminalIndex(listOf(page(0, true, false), page(1, true, false))))
    }

    private fun withRows(heights: List<Int>, position: Int, action: (Int) -> Unit) {
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val manager = WebtoonLayoutManager(context)
            val recycler = RecyclerView(context).apply {
                layoutManager = manager
                itemAnimator = null
                adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun getItemCount() = heights.size
                    override fun onCreateViewHolder(parent: ViewGroup, type: Int) = object : RecyclerView.ViewHolder(View(context)) {}
                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, index: Int) {
                        holder.itemView.layoutParams = RecyclerView.LayoutParams(600, heights[index])
                    }
                }
            }
            try {
                fun layout() {
                    recycler.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
                    recycler.layout(0, 0, 600, 600)
                }
                layout(); manager.scrollToPositionWithOffset(position, 0); layout()
                action(manager.findLastEndVisibleItemPosition())
            } catch (error: Throwable) { failure = error }
            finally { recycler.adapter = null }
        }
        failure?.let { throw AssertionError("Visible chapter completion regression", it) }
    }
}
