package eu.kanade.tachiyomi.ui.reader.filter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderChapterSizePolicyTest {
    private fun chapter(vararg unusual: Pair<Int, ReaderChapterSizePolicy.Size>) = (0..9).map { index ->
        ReaderChapterSizePolicy.Page(index, unusual.firstOrNull { it.first == index }?.second ?: size(720, 1500))
    }
    private fun size(width: Int, height: Int) = ReaderChapterSizePolicy.Size(width, height)

    @Test fun recordedWideCardAndThinBannerAreExcludedByDefault() {
        val result = ReaderChapterSizePolicy.evaluate(chapter(3 to size(1532, 1024), 5 to size(720, 192)))
        assertEquals(
            mapOf(
                3 to ReaderChapterSizePolicy.Reason.DIFFERENT_WIDTH,
                5 to ReaderChapterSizePolicy.Reason.SHORTER_HEIGHT,
            ),
            result.exclusions,
        )
        assertEquals(size(720, 1500), result.reference)
    }

    @Test fun coversSpreadsAndShortFinalPagesRemainVisible() {
        val result = ReaderChapterSizePolicy.evaluate(
            chapter(0 to size(720, 1080), 3 to size(1440, 1500), 9 to size(720, 300)),
        )
        assertTrue(result.exclusions.isEmpty())
    }

    @Test fun roundingAndScaledCopiesAreNotSpamEvidence() {
        assertTrue(
            ReaderChapterSizePolicy.evaluate(chapter(3 to size(724, 1495), 5 to size(360, 750))).exclusions.isEmpty(),
        )
    }

    @Test fun mixedResolutionChaptersHaveNoDominantReference() {
        val pages = chapter(1 to size(960, 2000), 2 to size(960, 2000), 3 to size(960, 2000), 4 to size(960, 2000))
        assertNull(ReaderChapterSizePolicy.evaluate(pages).reference)
    }

    @Test fun variableWebtoonHeightsDoNotUseTheMaximum() {
        val pages = (0..9).map { ReaderChapterSizePolicy.Page(it, size(720, 800 + it * 400)) }
        val result = ReaderChapterSizePolicy.evaluate(pages)
        assertEquals(0, result.reference?.height)
        assertTrue(result.exclusions.isEmpty())
    }

    @Test fun oneLongStitchedPageDoesNotMakeOrdinaryPagesShort() {
        val result = ReaderChapterSizePolicy.evaluate(chapter(4 to size(720, 12000)))
        assertEquals(size(720, 1500), result.reference)
        assertTrue(result.exclusions.isEmpty())
    }

    @Test fun insufficientAndInterruptedLoadsStayUnknown() {
        assertNull(ReaderChapterSizePolicy.evaluate(chapter().take(4)).reference)
        val partial = chapter().map { if (it.index < 5) it else it.copy(size = null) }
        assertNull(ReaderChapterSizePolicy.evaluate(partial).reference)
    }

    @Test fun userMarkedStoryIsNeverReclassifiedByGeometry() {
        val pages = chapter(4 to size(640, 900)).map {
            if (it.index == 4) it.copy(label = ReaderChapterSizePolicy.Label.STORY) else it
        }
        assertTrue(ReaderChapterSizePolicy.evaluate(pages).exclusions.isEmpty())
    }

    @Test fun orderingDoesNotChangeTheReferenceOrDecisions() {
        val pages = chapter(4 to size(720, 200), 5 to size(1532, 1024))
        assertEquals(ReaderChapterSizePolicy.evaluate(pages), ReaderChapterSizePolicy.evaluate(pages.reversed()))
    }

    @Test fun observedModestCutsAndThreePageStoryTailsAreNotExcluded() {
        val pages = (0..78).map { index ->
            val height = when (index) { 1 -> 1320
                76 -> 1336
                77 -> 985
                78 -> 783
                else -> 1500 }
            ReaderChapterSizePolicy.Page(index, size(720, height))
        }
        assertTrue(ReaderChapterSizePolicy.evaluate(pages).exclusions.isEmpty())
    }

    @Test fun aCoverStaysVisibleWhenNormalWebtoonHeightsVary() {
        val pages = (0..9).map { index ->
            ReaderChapterSizePolicy.Page(
                index,
                if (index == 0) size(800, 1200) else size(720, 800 + index * 400),
            )
        }
        assertTrue(ReaderChapterSizePolicy.evaluate(pages).exclusions.isEmpty())
    }
}
