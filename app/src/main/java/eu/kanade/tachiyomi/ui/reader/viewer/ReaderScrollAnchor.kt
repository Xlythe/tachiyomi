package eu.kanade.tachiyomi.ui.reader.viewer

import kotlin.math.abs
import kotlin.math.roundToInt

/** A point in one displayed image, independent of preceding rows, decoding order and viewport width. */
internal data class ReaderScrollAnchor(val key: Key, val fraction: Double, val viewportFraction: Double) {
    data class Key(val chapterId: Long, val pageIndex: Int, val part: Int = 0) {
        init {
            require(chapterId >= 0 && pageIndex >= 0 && part in 0..1)
        }
    }

    init {
        require(fraction.isFinite() && fraction in 0.0..1.0)
        require(viewportFraction.isFinite() && viewportFraction in 0.0..1.0)
    }

    fun imageTop(imageHeight: Int, viewportTop: Int, viewportHeight: Int): Int {
        require(imageHeight > 0 && viewportHeight > 0)
        return (viewportTop + viewportFraction * viewportHeight - fraction * imageHeight).roundToInt()
    }

    companion object {
        fun capture(key: Key, imageTop: Int, imageHeight: Int, viewportTop: Int, viewportHeight: Int): ReaderScrollAnchor? {
            if (imageHeight <= 0 || viewportHeight <= 0) return null
            val bottom = imageTop.toDouble() + imageHeight
            val viewportBottom = viewportTop.toDouble() + viewportHeight
            if (bottom <= viewportTop || imageTop >= viewportBottom) return null
            // Keep the reading edge normally; keep an exactly aligned page end at the viewport end.
            if (imageTop < viewportTop && abs(bottom - viewportBottom) <= 1.0) {
                return ReaderScrollAnchor(key, 1.0, 1.0)
            }
            val point = maxOf(viewportTop, imageTop).toDouble()
            return ReaderScrollAnchor(key, (point - imageTop) / imageHeight, (point - viewportTop) / viewportHeight)
        }
    }
}
