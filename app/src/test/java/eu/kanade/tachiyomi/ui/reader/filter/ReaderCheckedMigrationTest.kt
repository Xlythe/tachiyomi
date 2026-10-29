package eu.kanade.tachiyomi.ui.reader.filter

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderCheckedMigrationTest {
    @Test fun cleanOrUnverifiedPreviousResultsCannotCrossMatcherVersions() {
        assertNull(retainPreviousPositive(null))
        assertNull(retainPreviousPositive(ReaderCheckedStore.Hit(ReaderCheckedStore.Decision(), false)))
    }

    @Test fun provenWholeHidesStayHiddenBeforeTheNewMatcherFinishes() {
        val hit = requireNotNull(
            retainPreviousPositive(
                ReaderCheckedStore.Hit(
                    ReaderCheckedStore.Decision(true, byteArrayOf(1, 2, 3)),
                    false,
                ),
            ),
        )
        assertTrue(hit.decision.hidden)
        assertTrue(hit.needsRecheck)
    }

    @Test fun provenCropsStayVisibleAsCropsButMustRecheckForNewThinBands() {
        val bytes = byteArrayOf(4, 5, 6)
        val hit = requireNotNull(
            retainPreviousPositive(
                ReaderCheckedStore.Hit(
                    ReaderCheckedStore.Decision(false, bytes),
                    false,
                ),
            ),
        )
        assertFalse(hit.decision.hidden)
        assertArrayEquals(bytes, hit.decision.rendered)
        assertTrue(hit.needsRecheck)
    }
}
