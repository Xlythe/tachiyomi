package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import kotlin.math.roundToInt

/** Physical pixel sizing shared by pending metadata and decoded continuous-reader pages. */
internal object WebtoonPageSizing {
    const val hiddenHeight = 1

    fun imageHeight(contentWidth: Int, sourceWidth: Int, sourceHeight: Int): Int {
        require(sourceWidth > 0 && sourceHeight > 0)
        return (contentWidth.coerceAtLeast(1).toDouble() * sourceHeight / sourceWidth).roundToInt().coerceAtLeast(1)
    }
}
