package eu.kanade.tachiyomi.ui.reader.filter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class ReaderImageAnalysisTest {
    private fun sample(rows: Int, seed: Int = 7): ReaderImageSignature {
        val random = Random(seed)
        return ReaderImageSignature(IntArray(48 * rows) { random.nextInt(0x101010, 0xe0e0e0) or (255 shl 24) })
    }

    @Test
    fun toleratesSmallColourChangesButRejectsUnrelatedArtAndBoundaryContamination() {
        val a = sample(60)
        val b = ReaderImageSignature(a.pixels.map { it + 0x010101 }.toIntArray())
        assertTrue(a.agrees(b, 0, 0, 60))
        assertFalse(a.agrees(sample(60, 123), 0, 0, 60))
        val translucent = ReaderImageSignature(a.pixels.map { it and 0xfeffffff.toInt() }.toIntArray())
        assertFalse(a.agrees(translucent, 0, 0, 60))
        val changed = a.pixels.copyOf().apply { for (x in 0 until 48) this[x] = -1 }
        assertFalse(a.agrees(ReaderImageSignature(changed), 0, 0, 60))
    }

    @Test
    fun blankPanelsAndTinyFragmentsDoNotLearnSpam() {
        val blank = ReaderImageSignature(IntArray(48 * 60) { -1 })
        assertFalse(blank.agrees(blank, 0, 0, 60))
        assertTrue(blank.edgeMatches(blank).isEmpty())
        val small = sample(11)
        assertTrue(small.edgeMatches(small).isEmpty())
    }

    @Test
    fun matchesStandaloneAndBothSidesOfSplitSpam() {
        val spam = sample(60)
        assertEquals(listOf(0..59, 0..59), spam.edgeMatches(spam))
        val topFragment = ReaderImageSignature(spam.slice(30, 60).pixels + sample(90, 5).pixels)
        val bottomFragment = ReaderImageSignature(sample(90, 5).pixels + spam.slice(0, 30).pixels)
        assertEquals(listOf(0..29), topFragment.edgeMatches(spam))
        assertEquals(listOf(90..119), bottomFragment.edgeMatches(spam))
        val short = ReaderImageSignature(spam.slice(50, 60).pixels + sample(90, 5).pixels)
        assertTrue(short.edgeMatches(spam).isEmpty())
    }

    @Test
    fun interiorFragmentsAreNotSearchedAndRepeatedReadsAreDeterministic() {
        val spam = sample(60)
        val page = ReaderImageSignature(sample(40, 4).pixels + spam.pixels + sample(40, 9).pixels)
        repeat(5) { assertTrue(page.edgeMatches(spam).isEmpty()) }
    }

    @Test
    fun unionsAdjacentAndOverlappingCropsWithoutRemovingTwice() {
        assertEquals(listOf(0..39, 80..99), ReaderImageSignature.merge(listOf(20..39, 0..19, 10..25, 80..99)))
    }

    @Test
    fun snapsOnlyAtSharpWideContrastBoundaries() {
        val rows = IntArray(48 * 80) { if (it / 48 < 40) -1 else 0xff202020.toInt() }
        assertEquals(listOf(40), ReaderImageSignature.contrastEdges(rows, 48))
        val lettering = IntArray(48 * 80) { if (it % 48 < 12 && it / 48 == 40) 0xff000000.toInt() else -1 }
        assertTrue(ReaderImageSignature.contrastEdges(lettering, 48).isEmpty())
        val gradient = IntArray(48 * 80) {
            val c = it / 48
            0xff000000.toInt() or (c * 0x010101)
        }
        assertTrue(ReaderImageSignature.contrastEdges(gradient, 48).isEmpty())
    }

    @Test
    fun dragCapturesAndHoldsThroughJitterThenAllowsDeliberatePullAway() {
        val snap = ReaderImageSnap(0.4f, listOf(0.5f), 0.01f, 0.03f)
        assertEquals(0.45f, snap.moveBy(0.05f), 0.00001f)
        assertEquals(0.5f, snap.moveBy(0.045f), 0.00001f)
        repeat(10) {
            assertEquals(0.5f, snap.moveBy(0.007f), 0.00001f)
            assertEquals(0.5f, snap.moveBy(-0.007f), 0.00001f)
        }
        assertEquals(0.535f, snap.moveBy(0.04f), 0.00001f)
        assertEquals(0.537f, snap.moveBy(0.002f), 0.00001f)
    }

    @Test
    fun reversingDirectionRecapturesAndCanReleaseOnTheOtherSide() {
        val snap = ReaderImageSnap(0.6f, listOf(0.5f), 0.01f, 0.03f)
        assertEquals(0.5f, snap.moveBy(-0.095f), 0.00001f)
        assertEquals(0.465f, snap.moveBy(-0.04f), 0.00001f)
        assertEquals(0.5f, snap.moveBy(0.028f), 0.00001f)
        assertEquals(0.533f, snap.moveBy(0.04f), 0.00001f)
    }

    @Test
    fun fingerUpPreservesLastDisplayedPositionWithoutCaptureOrRounding() {
        val stationary = ReaderImageSnap(0.495123f, listOf(0.5f), 0.01f, 0.03f)
        assertEquals(0.495123f, stationary.finish())
        val snapped = ReaderImageSnap(0.4f, listOf(0.5f), 0.01f, 0.03f)
        val displayed = snapped.moveBy(0.095f)
        repeat(5) { assertEquals(displayed, snapped.finish()) }
        val free = snapped.moveBy(0.042123f)
        assertEquals(free, snapped.finish())
    }

    @Test
    fun snapCannotCrossTheOtherHandleAndNoBoundaryAllowsFinePlacement() {
        val snap = ReaderImageSnap(0.4f, listOf(0.5f, 0.8f), 0.01f, 0.03f, 0f, 0.49f)
        assertEquals(0.49f, snap.moveBy(0.095f))
        assertEquals(0.487654f, snap.moveBy(-0.002346f), 0.00001f)
        assertEquals(0f, snap.moveBy(-1f))
    }

    @Test
    fun approximateCutsRequireOneUnambiguousSourceResolutionBoundary() {
        assertEquals(302, ReaderImageSignature.refineBoundary(300, 900, 90, listOf(302, 600)))
        assertNull(ReaderImageSignature.refineBoundary(300, 900, 90, listOf(290, 600)))
        assertNull(ReaderImageSignature.refineBoundary(300, 900, 90, listOf(298, 302)))
        assertEquals(0, ReaderImageSignature.refineBoundary(0, 900, 90, emptyList()))
        assertEquals(900, ReaderImageSignature.refineBoundary(900, 900, 90, emptyList()))
    }
}
