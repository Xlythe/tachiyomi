package eu.kanade.tachiyomi.ui.reader.filter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.math.roundToInt

class ReaderSignatureBudgetTest {
    @Test
    fun ordinaryAndFractionalSamplingKeepsLegacyRowCounts() {
        for ((width, height) in listOf(600 to 14000, 137 to 259, 1920 to 18001, 1080 to 34001)) {
            assertEquals(
                (height * 48f / width).roundToInt().coerceAtLeast(1),
                ReaderImageSignature.rowsFor(width, height),
            )
        }
    }

    @Test
    fun hugeAndInvalidGeometryIsRejectedBeforeAnyBitmapOrPixelAllocation() {
        for ((width, height) in listOf(1 to Int.MAX_VALUE, 1 to 100000, 0 to 50, 50 to -1)) {
            assertThrows(IllegalArgumentException::class.java) { ReaderImageSignature.rowsFor(width, height) }
        }
    }

    @Test
    fun exactBudgetBoundaryIsAllowedButOneMoreRowIsRejected() {
        assertEquals(4096, ReaderImageSignature.rowsFor(48, 4096))
        assertThrows(IllegalArgumentException::class.java) { ReaderImageSignature.rowsFor(48, 4097) }
    }
}
