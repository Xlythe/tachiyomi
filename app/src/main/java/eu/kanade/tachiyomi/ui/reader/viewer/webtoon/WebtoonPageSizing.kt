package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

/** Physical pixel sizing shared by pending metadata and decoded continuous-reader pages. */
internal object WebtoonPageSizing {
    const val hiddenHeight = 0

    fun imageHeight(contentWidth: Int, sourceWidth: Int, sourceHeight: Int): Int {
        require(sourceWidth > 0 && sourceHeight > 0)
        // The image library truncates its fit-width natural height. Rounding up reserves
        // background pixels beyond the painted image, magnified by continuous-reader zoom.
        return (sourceHeight.toDouble() / sourceWidth * contentWidth.coerceAtLeast(1)).toInt().coerceAtLeast(1)
    }
}
