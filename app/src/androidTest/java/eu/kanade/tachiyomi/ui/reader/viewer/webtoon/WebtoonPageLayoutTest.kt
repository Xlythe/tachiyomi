package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.view.View
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
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

/** Exercise the same layout update as WebtoonPageHolder after RecyclerView owns its child. */
@RunWith(AndroidJUnit4::class)
class WebtoonPageLayoutTest {
    @Test
    fun readyPageRefreshKeepsRecyclerViewOwnedParamsAndCompletesLayout() = withPage { recycler, page ->
        val owned = page.layoutParams
        assertTrue(owned is RecyclerView.LayoutParams)
        page.updateWebtoonPageLayout(24, 15)
        layout(recycler, 1921)
        assertSame(owned, page.layoutParams)
        assertSame(recycler.getChildViewHolder(page), recycler.findViewHolderForAdapterPosition(0))
        val params = page.layoutParams as RecyclerView.LayoutParams
        assertEquals(24, params.marginStart)
        assertEquals(24, params.marginEnd)
        assertEquals(15, params.bottomMargin)
    }

    @Test
    fun repeatedHideAndRestoreKeepsHolderAndRestoresImageHeight() = withPage { recycler, page ->
        val owned = page.layoutParams
        repeat(3) { pass ->
            page.updateLayoutParams { height = 1 }
            layout(recycler, 2000 + pass * 2)
            assertEquals(1, page.height)
            page.updateWebtoonPageLayout(16, 0)
            layout(recycler, 2001 + pass * 2)
            assertSame(owned, page.layoutParams)
            assertTrue(page.height > 1)
        }
    }

    @Test
    fun repeatedBindingAndContinuousModeResetMarginsWithoutReplacingParams() = withPage { recycler, page ->
        val owned = page.layoutParams
        page.updateWebtoonPageLayout(32, 15)
        layout(recycler, 1921)
        repeat(3) { pass ->
            page.updateWebtoonPageLayout(0, 0)
            layout(recycler, 1922 + pass)
            assertSame(owned, page.layoutParams)
            val params = page.layoutParams as RecyclerView.LayoutParams
            assertEquals(0, params.marginStart)
            assertEquals(0, params.marginEnd)
            assertEquals(0, params.bottomMargin)
        }
    }

    private fun withPage(action: (RecyclerView, ReaderPageImageView) -> Unit) {
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val recycler = RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun getItemCount() = 1
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                        val page = ReaderPageImageView(parent.context, isWebtoon = true).apply {
                            minimumHeight = 400
                            updateWebtoonPageLayout(0, 0)
                        }
                        return object : RecyclerView.ViewHolder(page) {}
                    }
                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
                }
            }
            var page: ReaderPageImageView? = null
            var owned: ViewGroup.LayoutParams? = null
            try {
                layout(recycler, 1920)
                page = recycler.findViewHolderForAdapterPosition(0)!!.itemView as ReaderPageImageView
                owned = page.layoutParams
                action(recycler, page)
            } catch (error: Throwable) {
                failure = error
            } finally {
                // Keep a red regression from leaking bad params into framework cleanup.
                if (page != null && owned != null) page.layoutParams = owned
                recycler.adapter = null
            }
        }
        failure?.let { throw AssertionError("Webtoon layout refresh failed", it) }
    }

    private fun layout(recycler: RecyclerView, height: Int) {
        recycler.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        recycler.layout(0, 0, 1080, height)
    }
}
