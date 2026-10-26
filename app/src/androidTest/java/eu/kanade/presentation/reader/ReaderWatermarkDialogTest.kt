package eu.kanade.presentation.reader

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.reader.ReaderUiTestActivity
import eu.kanade.tachiyomi.ui.reader.filter.ReaderImageRules
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.roundToInt

/** Synthetic interaction fixtures only: no detection/training or reconstruction-quality claim. */
@OptIn(ExperimentalTestApi::class)
class ReaderWatermarkDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ReaderUiTestActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val bytes = Bitmap.createBitmap(601, 4501, Bitmap.Config.ARGB_8888).let { bitmap ->
        Canvas(bitmap).apply {
            drawColor(Color.rgb(30, 80, 130))
            drawRect(200f, 1700f, 400f, 2300f, Paint().apply { color = Color.rgb(70, 40, 100) })
        }
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            .also { bitmap.recycle() } + UUID.randomUUID().toString().toByteArray()
    }
    private val selected = mutableStateOf<ReaderPage?>(null)
    private val applied = CopyOnWriteArrayList<String>()
    private val releases = mutableListOf<CompletableDeferred<Unit>>()
    private val ownedHashes = mutableSetOf<String>()
    private val area = Rect(220, 1800, 380, 2000)
    private fun mask() = compose.onNodeWithTag("reader_image_mask")
    private fun description() = mask().fetchSemanticsNode().config[SemanticsProperties.StateDescription]
    private fun rectangle(): Rect {
        val match = requireNotNull(Regex("Oval ([-0-9]+), ([-0-9]+) to ([-0-9]+), ([-0-9]+) source pixels").find(description()))
        return match.groupValues.drop(1).map { it.toInt() }.let { Rect(it[0], it[1], it[2], it[3]) }
    }
    private fun start(renderer: (suspend (ByteArray, Rect) -> ByteArray)? = null) {
        val page = ReaderPage(0, stream = { bytes.inputStream() })
        ownedHashes += ReaderImageRules.hash(bytes); selected.value = page
        compose.setContent {
            MaterialTheme {
                selected.value?.let { current ->
                    if (renderer == null) ReaderImageMaskDialog(current, JSONObject(),
                        onDismiss = { selected.value = null }, onApplied = { applied += it }, contrastEdges = { emptyList() })
                    else ReaderImageMaskDialog(current, JSONObject(),
                        onDismiss = { selected.value = null }, onApplied = { applied += it }, contrastEdges = { emptyList() },
                        watermarkPreviewRenderer = renderer)
                }
            }
        }
        awaitMask()
        compose.onNodeWithText("Select watermark").performScrollTo().performClick()
    }
    private fun awaitMask() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("reader_image_mask").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    private fun zoom3() {
        mask().performTouchInput {
            val a = center - Offset(40f, 0f); val b = center + Offset(40f, 0f)
            down(0, a); down(1, b)
            updatePointerTo(0, center - Offset(120f, 0f)); updatePointerTo(1, center + Offset(120f, 0f)); move(100)
            up(1); up(0)
        }
        compose.waitForIdle()
    }
    private fun point(x: Int, y: Int, zoom: Float = 3f): Offset {
        val bounds = mask().fetchSemanticsNode().boundsInRoot
        val pad = 24f * compose.activity.resources.displayMetrics.density
        val scale = min((bounds.width - 2 * pad) / 601f, (bounds.height - 2 * pad) / 4501f) * zoom
        return Offset((bounds.width - 601 * scale) / 2 + x * scale,
            (bounds.height - 4501 * scale) / 2 + y * scale)
    }
    private fun draw(rect: Rect = area) {
        val start = point(rect.left, rect.top); val end = point(rect.right, rect.bottom)
        mask().performTouchInput { down(start); moveTo(end, 200); up() }
        compose.waitForIdle()
    }
    private fun preview() {
        compose.onNodeWithTag("reader_watermark_preview").performScrollTo().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Show original").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Apply").assertIsEnabled()
    }
    private fun assertArea(expected: Rect = area) {
        val actual = rectangle()
        assertEquals(expected.left, actual.left); assertEquals(expected.top, actual.top)
        assertEquals(expected.right, actual.right); assertEquals(expected.bottom, actual.bottom)
    }
    private fun assertOvalDrawnOnItsSourceBoundary() {
        val expected = point((area.left + area.right) / 2, area.top)
        val pixels = mask().captureToImage().toPixelMap()
        val x = expected.x.roundToInt(); val y = expected.y.roundToInt()
        assertTrue("Expected visible top of source-coordinate oval", (x - 5..x + 5).any { px ->
            (y - 5..y + 5).any { py ->
                px in 0 until pixels.width && py in 0 until pixels.height && pixels[px, py].let {
                    it.red > 0.9f && it.green > 0.9f && it.blue > 0.9f
                }
            }
        })
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag("reader_image_editor_layout").captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @After fun cleanup() {
        releases.forEach { it.complete(Unit) }
        ownedHashes.forEach { ReaderImageRules.remove(it) }
        instrumentation.runOnMainSync { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    @Test fun sampledOddImageUsesExactSourcePixelsAndSavedSelectionReopensWithoutAutoApply() {
        start(); zoom3(); draw(); assertArea()
        mask().performCustomAccessibilityActionWithLabel("Move oval right one pixel")
        assertArea(Rect(221, 1800, 381, 2000))
        compose.onNodeWithText("Undo selection").performScrollTo().performClick(); assertArea()
        preview(); assertOvalDrawnOnItsSourceBoundary()
        screenshot("watermark-editor-portrait.png")
        compose.onNodeWithText("Show original").performScrollTo().performClick()
        assertArea(); compose.onNodeWithText("Apply").assertIsEnabled()
        compose.onNodeWithText("Show estimate").performScrollTo().performClick()
        compose.onNodeWithText("Apply").performClick()
        compose.waitUntil(15_000) { applied.size == 1 }
        val hash = ReaderImageRules.hash(bytes); val rule = requireNotNull(ReaderImageRules.find(hash))
        assertEquals(listOf(220, 1800, 380, 2000), rule.getJSONArray("watermark").let { a -> (0..3).map { a.getInt(it) } })
        assertEquals("ellipse-horizontal-interpolation-v1", rule.getString("watermarkMethod"))
        assertArrayEquals(bytes, ReaderImageRules.original(hash).readBytes())
        compose.runOnIdle { selected.value = ReaderPage(1, stream = { bytes.inputStream() }) }
        awaitMask(); assertArea(); compose.onNodeWithText("Apply").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        assertNotNull(ReaderImageRules.find(hash)); assertArrayEquals(bytes, ReaderImageRules.original(hash).readBytes())
    }

    @Test fun pinchWithEitherPointerLiftAndCancelledDraftPreservesTheReadySelection() {
        start(); zoom3(); draw(); preview(); val before = description()
        for (lift in listOf(0, 1)) {
            mask().performTouchInput {
                down(0, center - Offset(60f, 0f)); down(1, center + Offset(60f, 0f))
                updatePointerTo(0, center - Offset(75f, 0f)); updatePointerTo(1, center + Offset(75f, 0f)); move(100)
                up(lift); moveBy(1 - lift, Offset(0f, 20f), 50); up(1 - lift)
            }
            compose.waitForIdle(); assertEquals(before, description()); compose.onNodeWithText("Apply").assertIsEnabled()
        }
        mask().performTouchInput { down(center - Offset(30f, 20f)); moveTo(center + Offset(40f, 40f), 100); cancel() }
        compose.waitForIdle(); assertEquals(before, description()); compose.onNodeWithText("Apply").assertIsEnabled()
        assertNull(ReaderImageRules.find(ReaderImageRules.hash(bytes)))
    }

    @Test fun cancelledPreviewUndoAndReopenNeverPublishOrPersistAnObsoleteEstimate() {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>().also { releases += it }
        val calls = AtomicInteger()
        start { raw, _ ->
            if (calls.incrementAndGet() == 1) withContext(NonCancellable) {
                started.complete(Unit); withTimeout(15_000) { release.await() }
            }
            raw
        }
        zoom3(); draw(); assertArea()
        compose.onNodeWithTag("reader_watermark_preview").performScrollTo().performClick()
        compose.waitUntil(15_000) { started.isCompleted }
        compose.onNodeWithText("Apply").assertIsNotEnabled()
        compose.onNodeWithText("Stop preview").performScrollTo().performClick()
        draw(Rect(240, 1850, 400, 2050))
        compose.onNodeWithText("Undo selection").performScrollTo().performClick(); assertArea()
        release.complete(Unit); compose.waitForIdle(); compose.onNodeWithText("Apply").assertIsNotEnabled()
        preview(); assertArea()
        compose.onNodeWithText("Cancel").performClick()
        assertNull(ReaderImageRules.find(ReaderImageRules.hash(bytes))); assertTrue(applied.isEmpty())
        compose.runOnIdle { selected.value = ReaderPage(2, stream = { bytes.inputStream() }) }
        awaitMask(); compose.onNodeWithText("Select watermark").performScrollTo().performClick()
        assertEquals("No watermark selected", description()); compose.onNodeWithText("Apply").assertIsNotEnabled()
    }

    @Test fun orientationKeepsAUsableCanvasAndReprojectsTheSameSourceOval() {
        start(); zoom3(); draw(); preview(); assertArea(); assertOvalDrawnOnItsSourceBoundary()
        instrumentation.runOnMainSync { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(15_000) {
            val frame = compose.onNodeWithTag("reader_image_editor_layout").fetchSemanticsNode().boundsInRoot
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && frame.width > frame.height
        }
        compose.waitForIdle(); assertArea(); compose.onNodeWithText("Apply").assertIsEnabled()
        val bounds = mask().fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width > 100 * compose.activity.resources.displayMetrics.density)
        assertTrue(bounds.height > 100 * compose.activity.resources.displayMetrics.density)
        assertOvalDrawnOnItsSourceBoundary(); screenshot("watermark-editor-landscape.png")
        instrumentation.runOnMainSync { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(15_000) {
            val frame = compose.onNodeWithTag("reader_image_editor_layout").fetchSemanticsNode().boundsInRoot
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT && frame.height > frame.width
        }
        compose.waitForIdle(); assertArea(); assertOvalDrawnOnItsSourceBoundary()
    }

    @Test fun previewAllocationFailureLeavesRetryAndCancelResponsive() {
        val calls = AtomicInteger()
        start { raw, _ -> if (calls.incrementAndGet() == 1) throw OutOfMemoryError("Synthetic preview failure") else raw }
        zoom3(); draw()
        compose.onNodeWithTag("reader_watermark_preview").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Not enough memory to preview this image.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reader_watermark_preview").assertIsEnabled(); compose.onNodeWithText("Apply").assertIsNotEnabled()
        preview(); compose.onNodeWithText("Cancel").performClick()
        assertNull(ReaderImageRules.find(ReaderImageRules.hash(bytes)))
    }

    @Test fun wholeHideDoesNotWaitForCancelledNativePreviewWork() {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>().also { releases += it }
        val nativeFinished = CompletableDeferred<Unit>()
        start { raw, _ ->
            try { withContext(NonCancellable) { started.complete(Unit); withTimeout(15_000) { release.await() } } }
            finally { nativeFinished.complete(Unit) }
            raw
        }
        zoom3(); draw(); compose.onNodeWithTag("reader_watermark_preview").performScrollTo().performClick()
        compose.waitUntil(15_000) { started.isCompleted }
        compose.onNodeWithText("Crop spam instead").performScrollTo().performClick()
        compose.onNodeWithText("Apply").performClick()
        compose.waitUntil(15_000) { applied.size == 1 }
        assertFalse("Whole hide must complete before blocked native preview work finishes", nativeFinished.isCompleted)
        assertTrue(requireNotNull(ReaderImageRules.find(ReaderImageRules.hash(bytes))).getBoolean("hide"))
        assertArrayEquals(bytes, ReaderImageRules.original(ReaderImageRules.hash(bytes)).readBytes())
        release.complete(Unit)
    }
    @Test fun switchingPagesRejectsAnOldPreviewWithoutChangingTheNewSelection() {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>().also { releases += it }
        start { raw, _ -> withContext(NonCancellable) { started.complete(Unit); withTimeout(15_000) { release.await() } }; raw }
        zoom3(); draw(); compose.onNodeWithTag("reader_watermark_preview").performScrollTo().performClick()
        compose.waitUntil(15_000) { started.isCompleted }
        val second = bytes + "different-page".toByteArray(); ownedHashes += ReaderImageRules.hash(second)
        compose.runOnIdle { selected.value = ReaderPage(8, stream = { second.inputStream() }) }
        awaitMask(); compose.onNodeWithText("Select watermark").performScrollTo().performClick()
        assertEquals("No watermark selected", description()); compose.onNodeWithText("Apply").assertIsNotEnabled()
        release.complete(Unit); compose.waitForIdle()
        assertEquals("No watermark selected", description()); compose.onNodeWithText("Apply").assertIsNotEnabled()
        assertTrue(applied.isEmpty()); ownedHashes.forEach { assertNull(ReaderImageRules.find(it)) }
    }

}
