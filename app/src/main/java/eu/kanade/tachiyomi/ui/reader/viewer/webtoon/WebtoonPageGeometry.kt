package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView

/** Keeps a pending decode measurable and ignores dimensions belonging to an older binding. */
internal class WebtoonPageGeometry(
    private val frame: ReaderPageImageView,
    private val recycler: RecyclerView,
) {
    private var generation = 0L
    private var dimensions: Pair<Int, Int>? = null
    private var hidden = false

    fun bind(size: Pair<Int, Int>?): Long {
        dimensions = size
        hidden = false
        generation++
        resize(visibleHeight())
        return generation
    }

    fun begin(): Long = ++generation
    fun invalidate() { generation++ }
    fun isCurrent(token: Long) = token == generation

    fun metadata(token: Long, width: Int, height: Int) {
        if (!isCurrent(token) || width <= 0 || height <= 0) return
        dimensions = width to height
        if (!hidden) resize(visibleHeight())
    }

    fun loaded(token: Long, width: Int, height: Int) {
        if (!isCurrent(token)) return
        hidden = false
        metadata(token, width, height)
    }

    fun hide(token: Long) {
        if (!isCurrent(token)) return
        hidden = true
        resize(WebtoonPageSizing.hiddenHeight)
    }

    fun pending() {
        hidden = false
        resize(visibleHeight())
    }

    private fun visibleHeight(): Int {
        val fallback = (recycler.height.takeIf { it > 0 } ?: frame.resources.displayMetrics.heightPixels).coerceAtLeast(
            1,
        )
        val size = dimensions ?: return fallback
        val margins = frame.layoutParams as? android.view.ViewGroup.MarginLayoutParams
        val width = (recycler.width.takeIf { it > 0 } ?: frame.resources.displayMetrics.widthPixels) -
            (margins?.marginStart ?: 0) - (margins?.marginEnd ?: 0) - recycler.paddingLeft - recycler.paddingRight
        return WebtoonPageSizing.imageHeight(width, size.first, size.second)
    }

    private fun resize(height: Int) {
        if (frame.layoutParams?.height == height) return
        val manager = recycler.layoutManager as? LinearLayoutManager
        val position = if (!recycler.isComputingLayout) manager?.findFirstVisibleItemPosition() else null
        val anchor = position?.takeIf { it != RecyclerView.NO_POSITION }?.let { manager?.findViewByPosition(it) }
        val offset = anchor?.let { manager!!.getDecoratedTop(it) - recycler.paddingTop }
        frame.updateLayoutParams { this.height = height }
        if (position != null && offset != null) manager?.scrollToPositionWithOffset(position, offset)
    }
}
