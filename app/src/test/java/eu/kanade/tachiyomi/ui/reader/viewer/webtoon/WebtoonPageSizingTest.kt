package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.roundToInt

class WebtoonPageSizingTest {
    @Test
    fun fractionalFitWidthDoesNotReserveAnUnpaintedRowAtAdjacentPageEdges() {
        // 601 * 1000 / 600 = 1001.666..., so nearest rounding leaves a blank fraction.
        val height = WebtoonPageSizing.imageHeight(601, 600, 1000)
        assertTrue("A viewport must not extend beyond its fit-width image".let { height <= 601.0 * 1000 / 600 })
    }

    @Test
    fun densityPaddingMarginsDecorationsAndZoomLeaveNoUnpaintedInteriorSpace() {
        for (density in listOf(1.0, 1.5, 2.625, 3.5)) {
            val recyclerWidth = (393 * density).roundToInt()
            val padding = (7 * density).roundToInt()
            val margin = (11 * density).roundToInt()
            val decoration = (3 * density).roundToInt()
            val content = recyclerWidth - 2 * (padding + margin + decoration)
            for ((width, height) in listOf(600 to 1000, 1081 to 2347, 799 to 1600, 320 to 640)) {
                val measured = WebtoonPageSizing.imageHeight(content, width, height)
                val painted = content.toDouble() * height / width
                for (zoom in listOf(1.0, 1.25, 1.75, 3.0, 12.0)) {
                    assertTrue(measured * zoom <= painted * zoom + 0.00001,
                        "density=$density source=${width}x$height zoom=$zoom reserved=$measured painted=$painted")
                }
            }
        }
    }

    @Test
    fun intentionallyHiddenPagesHaveNoPhysicalSeparatorAndTinyVisiblePagesRemainMeasurable() {
        assertEquals(0, WebtoonPageSizing.hiddenHeight)
        assertEquals(1, WebtoonPageSizing.imageHeight(1, 10000, 1))
        assertEquals(1800, WebtoonPageSizing.imageHeight(600, 600, 1800))
    }
}
