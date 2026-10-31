package eu.kanade.tachiyomi.ui.reader.viewer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ReaderScrollAnchorTest {
    private val key = ReaderScrollAnchor.Key(47, 12)

    @Test fun reflowScalesTheIntraImageOffsetRatherThanKeepingOldPixels() {
        val anchor = requireNotNull(ReaderScrollAnchor.capture(key, -6000, 12000, 0, 1280))
        assertEquals(0.5, anchor.fraction)
        assertEquals(-10666, anchor.imageTop(21333, 0, 720))
        assertEquals(key, anchor.key)
    }

    @Test fun viewportInsetsAndToolbarHeightDoNotChangeTheContentPoint() {
        val anchor = requireNotNull(ReaderScrollAnchor.capture(key, -6000, 12000, 24, 1200))
        val top = anchor.imageTop(21333, 80, 600)
        assertEquals(anchor.fraction, (80 - top).toDouble() / 21333, 1.0 / 21333)
    }

    @Test fun anAlignedPageEndRemainsAtTheEndAcrossBothRotationDirections() {
        val anchor = requireNotNull(ReaderScrollAnchor.capture(key, -10720, 12000, 0, 1280))
        assertEquals(1.0, anchor.fraction)
        assertEquals(1.0, anchor.viewportFraction)
        assertEquals(720 - 21333, anchor.imageTop(21333, 0, 720))
        assertEquals(-10720, anchor.imageTop(12000, 0, 1280))
    }

    @Test fun beginningAndSmallInterPageGapsRemainValid() {
        val beginning = requireNotNull(ReaderScrollAnchor.capture(key, 0, 12000, 0, 1280))
        assertEquals(0, beginning.imageTop(21333, 0, 720))
        val afterGap = requireNotNull(ReaderScrollAnchor.capture(key, 16, 12000, 0, 1280))
        assertEquals(0.0, afterGap.fraction)
        assertEquals(9, afterGap.imageTop(21333, 0, 720))
    }

    @Test fun repeatedReflowsDoNotAccumulateAnImageJump() {
        var anchor = requireNotNull(ReaderScrollAnchor.capture(key, -6000, 12000, 0, 1280))
        repeat(100) {
            val height = if (it % 2 == 0) 21333 else 12000
            val viewport = if (it % 2 == 0) 720 else 1280
            anchor = requireNotNull(
                ReaderScrollAnchor.capture(key, anchor.imageTop(height, 0, viewport), height, 0, viewport),
            )
            assertEquals(0.5, anchor.fraction, 0.0001)
        }
    }

    @Test fun loadingOrOffscreenGeometryCannotReplaceAnExistingAnchor() {
        assertNull(ReaderScrollAnchor.capture(key, 0, 0, 0, 1000))
        assertNull(ReaderScrollAnchor.capture(key, 0, 12000, 0, 0))
        assertNull(ReaderScrollAnchor.capture(key, -12000, 12000, 0, 1000))
        assertNull(ReaderScrollAnchor.capture(key, 1000, 12000, 0, 1000))
    }

    @Test fun sourceIdentityIncludesTheChapterOriginalPageAndSplitPart() {
        assertEquals(ReaderScrollAnchor.Key(47, 12, 1), key.copy(part = 1))
        assertNotEquals(key, key.copy(chapterId = 48))
        assertThrows(IllegalArgumentException::class.java) { ReaderScrollAnchor(key, Double.NaN, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { ReaderScrollAnchor(key, 0.5, -1.0) }
        assertThrows(IllegalArgumentException::class.java) { ReaderScrollAnchor.Key(47, -1) }
    }
}
