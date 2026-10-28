package eu.kanade.tachiyomi.ui.reader.filter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ReaderDetailedSignatureTest {
    private fun textured(width: Int, rows: Int) = IntArray(width * rows) { i ->
        if ((i % width) % 2 == 0) 0xff101010.toInt() else 0xffefefef.toInt()
    }
    @Test fun aHigherResolutionSliceKeepsItsWidth() {
        val source = ReaderImageSignature(textured(192, 24), 192)
        val slice = source.slice(3, 17)
        assertEquals(192, slice.width); assertEquals(14, slice.height)
    }
    @Test fun differentSignatureWidthsCannotAgreeThroughAnAccidentalPrefix() {
        val coarse = ReaderImageSignature(textured(48, 16))
        val detailed = ReaderImageSignature(textured(192, 16), 192)
        assertFalse(coarse.agrees(detailed, 0, 0, 16))
    }
    @Test fun moreResolutionCanMeetTheSameMinimumWithoutWeakeningThresholds() {
        val coarse = ReaderImageSignature(textured(48, 8))
        val detailed = ReaderImageSignature(textured(192, 32), 192)
        assertFalse(coarse.agrees(coarse, 0, 0, 8))
        assertTrue(detailed.agrees(detailed, 0, 0, 32))
        val different = detailed.pixels.copyOf()
        for (i in 0 until 192 * 3) different[i] = 0xffff0000.toInt()
        assertFalse(detailed.agrees(ReaderImageSignature(different, 192), 0, 0, 32))
    }
}
