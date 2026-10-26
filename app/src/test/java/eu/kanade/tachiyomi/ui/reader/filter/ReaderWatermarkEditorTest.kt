package eu.kanade.tachiyomi.ui.reader.filter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class ReaderWatermarkEditorTest {
    private val area = ImageBounds(10.0, 20.0, 40.0, 50.0)
    private fun ready(): ReaderWatermarkEditor {
        val state = ReaderWatermarkEditor("image", area).requestPreview(101, 301)
        return state.completePreview(requireNotNull(state.pending))
    }

    @Test
    fun pinchNeverChangesSelectionOrPreviewIncludingEitherPointerHandoff() {
        for (handoff in listOf(ImagePoint(1.0, 2.0), ImagePoint(90.0, 200.0))) {
            val original = ready()
            var state = original.begin(ImagePoint(12.0, 24.0)).navigate()
            repeat(5) { state = state.move(handoff, true) }
            state = state.finish()
            assertEquals(original, state)
        }
    }

    @Test
    fun secondPointerAfterDrawingRollsBackDraftWithoutDiscardingReadyPreview() {
        val original = ready()
        val drawing = original.begin(ImagePoint(50.0, 60.0)).move(ImagePoint(80.0, 100.0), true)
        assertEquals(area, drawing.selection)
        assertFalse(drawing.canApply(101, 301))
        assertEquals(original, drawing.navigate().move(ImagePoint(90.0, 200.0), true).finish())
    }

    @Test
    fun tapsJitterAndCancellationLeaveSelectionAndPreviewIntact() {
        val original = ready()
        val pending = original.begin(ImagePoint(60.0, 80.0)).move(ImagePoint(60.1, 80.1), false)
        assertNull(pending.draft)
        assertEquals(original, pending.finish())
        assertEquals(original, pending.move(ImagePoint(90.0, 120.0), true).cancelGesture())
    }

    @Test
    fun fourDragDirectionsCommitLastDisplayedBoundsWithoutFingerUpMovement() {
        for (a in listOf(ImagePoint(10.0, 20.0), ImagePoint(40.0, 50.0),
            ImagePoint(10.0, 50.0), ImagePoint(40.0, 20.0))) {
            val b = ImagePoint(50 - a.x, 70 - a.y)
            val drawing = ReaderWatermarkEditor("image").begin(a).move(b, true)
            val displayed = drawing.displayedSelection
            val finished = drawing.finish()
            assertEquals(area, displayed)
            assertEquals(displayed, finished.selection)
            assertEquals(ReaderWatermarkEditor.Phase.IDLE, finished.phase)
            assertEquals(finished, finished.finish())
            assertNull(finished.ready)
        }
    }

    @Test
    fun undoRestoresPreviousSelectionAndRequiresANewPreviewWithoutPersistingAnything() {
        val original = ready()
        val edited = original.select(ImageBounds(20.0, 30.0, 50.0, 70.0))
        assertNull(edited.ready)
        assertTrue(edited.canUndo)
        val undone = edited.undo()
        assertEquals(area, undone.selection)
        assertFalse(undone.canUndo)
        assertFalse(undone.canApply(101, 301))
        assertEquals(undone, undone.undo())
        assertNull(ReaderWatermarkEditor("image").select(area).undo().selection)
    }

    @Test
    fun delayedCancelledOrOutOfOrderPreviewCannotEnableApply() {
        val a = ReaderWatermarkEditor("image", area).requestPreview(101, 301)
        val tokenA = requireNotNull(a.pending)
        val b = a.cancelPreview().requestPreview(101, 301)
        val tokenB = requireNotNull(b.pending)
        assertEquals(b, b.completePreview(tokenA))
        assertEquals(b, b.failPreview(tokenA))
        val completed = b.completePreview(tokenB)
        assertTrue(completed.canApply(101, 301))
        assertEquals(completed, completed.completePreview(tokenA))
        val closed = b.close()
        assertEquals(closed, closed.completePreview(tokenB))
        assertFalse(closed.canApply(101, 301))
        assertEquals(ReaderWatermarkEditor("different", area),
            ReaderWatermarkEditor("different", area).completePreview(tokenA))
    }

    @Test
    fun editingDuringDecodeRejectsTheOldResultAndFailurePermitsRetry() {
        val state = ReaderWatermarkEditor("image", area).requestPreview(101, 301)
        val token = requireNotNull(state.pending)
        val changed = state.select(ImageBounds(30.0, 40.0, 60.0, 90.0))
        assertEquals(changed, changed.completePreview(token))
        val failed = state.failPreview(token)
        assertNull(failed.pending)
        assertFalse(failed.canApply(101, 301))
        assertNotNull(failed.requestPreview(101, 301).pending)
    }

    @Test
    fun viewportNavigationDuringDecodePreservesTheSelectionRequest() {
        val pending = ReaderWatermarkEditor("image", area).requestPreview(101, 301)
        val token = requireNotNull(pending.pending)
        val navigated = pending.begin(ImagePoint(20.0, 30.0)).navigate().completePreview(token).finish()
        assertEquals(area, navigated.selection)
        assertTrue(navigated.canApply(101, 301))
    }

    @Test
    fun transformRoundTripsOddDimensionsAndTallPagesAtMultipleZoomAndPanValues() {
        val random = Random(701)
        for ((w, h) in listOf(601 to 1801, 1201 to 120_003, 64 to 64)) {
            for (zoom in listOf(1.0, 3.0, 12.0)) {
                for ((vw, vh) in listOf(1080.0 to 1920.0, 1920.0 to 1080.0)) {
                    val transform = ReaderImageTransform(w, h, vw, vh, 24.0, zoom, ImagePoint(-37.0, 91.0))
                    repeat(50) {
                        val point = ImagePoint(random.nextDouble(w.toDouble()), random.nextDouble(h.toDouble()))
                        val roundTrip = transform.toSource(transform.toViewport(point))
                        assertEquals(point.x, roundTrip.x, 0.000001)
                        assertEquals(point.y, roundTrip.y, 0.000001)
                    }
                }
            }
        }
    }

    @Test
    fun sourceEdgesAndInterpolationLimitsAreExplicitAndQuantizationHappensOnce() {
        val transform = ReaderImageTransform(101, 301, 300.0, 600.0, 24.0, 1.0, ImagePoint(0.0, 0.0))
        assertEquals(ImagePoint(1.0, 0.0), transform.watermarkPoint(ImagePoint(-100.0, -100.0)))
        assertEquals(ImagePoint(100.0, 301.0), transform.watermarkPoint(ImagePoint(1000.0, 1000.0)))
        assertEquals(ImagePixels(10, 21, 40, 51), ImageBounds(10.4, 20.6, 40.4, 50.6).pixels(101, 301))
        assertNull(area.pixels(3, 301))
        assertNull(area.pixels(101, 1))
        assertNull(ImageBounds(1.0, 0.0, 2000.0, 2000.0).pixels(3000, 3000))
        assertNull(ImageBounds(10.0, 10.0, 11.0, 11.0).pixels(101, 301))
        assertNull(area.pixels(40_000, 40_000))
    }

    @Test
    fun pixelNudgesPreserveSizeAndClampToTheNeighbourColumnBoundary() {
        assertEquals(ImageBounds(1.0, 0.0, 31.0, 30.0), area.shifted(-100.0, -100.0, 101, 301))
        assertEquals(ImageBounds(70.0, 271.0, 100.0, 301.0), area.shifted(1000.0, 1000.0, 101, 301))
        val edited = ready().select(area.shifted(1.0, 0.0, 101, 301))
        assertNull(edited.ready)
        assertEquals(area, edited.undo().selection)
    }

    @Test
    fun invalidTransformInputsNeverProduceCoordinates() {
        assertThrows(IllegalArgumentException::class.java) {
            ReaderImageTransform(0, 301, 300.0, 600.0, 24.0, 1.0, ImagePoint(0.0, 0.0))
        }
        for (zoom in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                ReaderImageTransform(101, 301, 300.0, 600.0, 24.0, zoom, ImagePoint(0.0, 0.0))
            }
        }
    }
}
