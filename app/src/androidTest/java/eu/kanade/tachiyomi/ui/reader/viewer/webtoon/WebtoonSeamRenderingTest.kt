package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/** Draw real adjacent decoded page views; contrasting background exposes even a one-row seam. */
@RunWith(AndroidJUnit4::class)
class WebtoonSeamRenderingTest {
    @Test
    fun consecutivePagesCoverTheirBoundaryAcrossFractionalWidthsAndParentZoom() {
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                for (width in listOf(393, 601, 1032)) {
                    for (zoom in listOf(1f, 1.25f, 1.75f, 3f)) renderBoundary(width, zoom)
                }
            } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw AssertionError("A decoded continuous-page boundary exposed background", it) }
    }

    private fun renderBoundary(width: Int, zoom: Float) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val parent = FrameLayout(context).apply { setBackgroundColor(Color.MAGENTA) }
        val recycler = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            itemAnimator = null
            setBackgroundColor(Color.MAGENTA)
        }
        parent.addView(recycler, FrameLayout.LayoutParams(width, 2600))
        val source = Bitmap.createBitmap(599, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val frames = mutableListOf<ReaderPageImageView>()
        recycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = 8
            override fun onCreateViewHolder(group: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val frame = ReaderPageImageView(context, isWebtoon = true)
                frame.updateWebtoonPageLayout(0, 0)
                frames += frame
                return object : RecyclerView.ViewHolder(frame) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val frame = holder.itemView as ReaderPageImageView
                WebtoonPageGeometry(frame, recycler).bind(599 to 1000)
                frame.setImage(
                    BitmapDrawable(context.resources, source),
                    ReaderPageImageView.Config(
                        zoomDuration = 0, minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_FIT_WIDTH,
                    ),
                )
            }
        }
        val output = Bitmap.createBitmap(width, 2600, Bitmap.Config.ARGB_8888)
        try {
            fun layout() {
                parent.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2600, View.MeasureSpec.EXACTLY),
                )
                parent.layout(0, 0, width, 2600)
            }
            layout()
            (recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(1, 100)
            layout()
            recycler.pivotX = width / 2f
            recycler.pivotY = 0f
            recycler.scaleX = zoom
            recycler.scaleY = zoom
            repeat(2) {
                parent.draw(Canvas(output))
                layout()
            }
            val boundary = (100 * zoom).roundToInt()
            for (y in boundary - 2..boundary + 2) {
                assertEquals(
                    "width=$width zoom=$zoom boundary=$boundary row=$y",
                    Color.BLUE,
                    output.getPixel(width / 2, y),
                )
            }
        } finally {
            frames.forEach { it.recycle() }
            recycler.adapter = null
            output.recycle()
            if (!source.isRecycled) source.recycle()
        }
    }
}
