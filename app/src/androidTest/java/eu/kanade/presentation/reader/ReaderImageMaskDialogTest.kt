package eu.kanade.presentation.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.reader.ReaderUiTestActivity
import eu.kanade.tachiyomi.ui.reader.filter.ReaderImageRules
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class ReaderImageMaskDialogTest {
    @get:Rule
    val compose = createAndroidComposeRule<ReaderUiTestActivity>()

    private val bytes = Bitmap.createBitmap(600, 1800, Bitmap.Config.ARGB_8888).let { bitmap ->
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            textSize = 44f
            isAntiAlias = true
        }
        canvas.drawColor(Color.rgb(245, 242, 230))
        paint.color = Color.rgb(30, 100, 160)
        canvas.drawRect(0f, 0f, 600f, 500f, paint)
        paint.color = Color.rgb(210, 80, 65)
        canvas.drawRect(0f, 650f, 600f, 1200f, paint)
        paint.color = Color.rgb(30, 130, 90)
        canvas.drawRect(0f, 1350f, 600f, 1800f, paint)
        paint.color = Color.WHITE
        canvas.drawText("Story above", 60f, 250f, paint)
        canvas.drawText("Spam band", 60f, 920f, paint)
        canvas.drawText("Story below", 60f, 1560f, paint)
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            .also { bitmap.recycle() }
    }

    @After
    fun cleanup() { ReaderImageRules.remove(ReaderImageRules.hash(bytes)) }

    @Test
    fun wholeHideCommitsAndCanBeRepeatedWhileOptionalContrastScanningIsDelayed() {
        val scanStarted = CompletableDeferred<Unit>()
        val releaseScan = CompletableDeferred<Unit>()
        val page = ReaderPage(0, stream = { bytes.inputStream() })
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        compose.setContent {
            MaterialTheme {
                ReaderImageMaskDialog(
                    page,
                    JSONObject(),
                    onDismiss = { events += "dismiss" },
                    onApplied = { events += "saved" },
                    onRuleChanged = {
                        page.applyRuleVisibility(it)
                        events += "visibility:$it"
                    },
                    contrastEdges = {
                        scanStarted.complete(Unit)
                        releaseScan.await()
                        emptyList()
                    },
                )
            }
        }
        try {
            compose.waitUntil(15_000) { scanStarted.isCompleted }
            compose.onNodeWithTag("reader_image_mask").assertExists()
            repeat(2) { index ->
                compose.onNodeWithText("Apply").performClick()
                compose.waitUntil(15_000) { events.size == (index + 1) * 3 }
                compose.runOnIdle { assertTrue(page.isHidden) }
                val hash = ReaderImageRules.hash(bytes)
                assertTrue(ReaderImageRules.find(hash)!!.getBoolean("hide"))
                org.junit.Assert.assertArrayEquals(bytes, ReaderImageRules.original(hash).readBytes())
                // Simulate a fresh index load; no RAM-only visibility assumption is sufficient.
                ReaderImageRules::class.java.getDeclaredField("loaded").apply { isAccessible = true }.set(null, null)
                assertTrue(ReaderImageRules.find(hash)!!.getBoolean("hide"))
            }
        } finally {
            releaseScan.complete(Unit)
        }
    }

    @Test
    fun optionalScanFailureLeavesApplyUsable() {
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        compose.setContent {
            MaterialTheme {
                ReaderImageMaskDialog(
                    ReaderPage(0, stream = { bytes.inputStream() }),
                    JSONObject(),
                    onDismiss = {},
                    onApplied = { events += "saved" },
                    contrastEdges = { throw java.io.IOException("Synthetic optional failure") },
                )
            }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("reader_image_mask").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Apply").performClick()
        compose.waitUntil(15_000) { events.size == 1 }
    }

    @Test
    fun changingPagesCancelsOldPreparationAndSavesOnlyTheCurrentPage() {
        val first = ReaderPage(0, stream = { bytes.inputStream() })
        val secondBytes = requireNotNull(
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply {
                    inMutable = true
                },
            ),
        ).let { bitmap ->
            bitmap.setPixel(0, 0, Color.CYAN)
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                .also { bitmap.recycle() }
        }
        val second = ReaderPage(1, stream = { secondBytes.inputStream() })
        val selected = mutableStateOf(first)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val saved = java.util.concurrent.CopyOnWriteArrayList<String>()
        compose.setContent {
            MaterialTheme {
                val current = selected.value
                ReaderImageMaskDialog(
                    current,
                    JSONObject(),
                    onDismiss = {},
                    onApplied = { saved += it },
                    onRuleChanged = { current.applyRuleVisibility(it) },
                    contrastEdges = { raw ->
                        if (raw.contentEquals(bytes)) {
                            started.complete(Unit)
                            release.await()
                        }
                        emptyList()
                    },
                )
            }
        }
        try {
            compose.waitUntil(15_000) { started.isCompleted }
            compose.runOnIdle { selected.value = second }
            compose.waitUntil(
                15_000,
            ) { compose.onAllNodesWithTag("reader_image_mask").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Apply").performClick()
            compose.waitUntil(15_000) { saved.size == 1 }
            val secondHash = ReaderImageRules.hash(secondBytes)
            assertEquals(listOf(secondHash), saved)
            assertEquals(null, ReaderImageRules.find(ReaderImageRules.hash(bytes)))
            assertTrue(ReaderImageRules.find(secondHash)!!.getBoolean("hide"))
        } finally {
            release.complete(Unit)
            ReaderImageRules.remove(ReaderImageRules.hash(secondBytes))
        }
    }

    @Test
    fun downloadedFileErrorStopsTheSpinnerAndLeavesCancelUsable() {
        var dismissed = false
        val page = ReaderPage(0, stream = { throw java.io.IOException("Synthetic downloaded file failure") })
        compose.setContent {
            MaterialTheme {
                ReaderImageMaskDialog(
                    page,
                    JSONObject(),
                    onDismiss = { dismissed = true },
                    onApplied = {},
                )
            }
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Synthetic downloaded file failure").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Apply").assertIsNotEnabled()
        assertEquals(
            0,
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
                .fetchSemanticsNodes().size,
        )
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test
    fun failedRuleSaveClearsBusyAndAllowsRetryOrCancelWithoutPublishingAHide() {
        val invalid = object : JSONObject() { override fun toString() = "invalid" }
        val page = ReaderPage(0, stream = { bytes.inputStream() })
        val saved = java.util.concurrent.CopyOnWriteArrayList<String>()
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                ReaderImageMaskDialog(
                    page,
                    invalid,
                    onDismiss = { dismissed = true },
                    onApplied = { saved += it },
                    contrastEdges = { emptyList() },
                )
            }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("reader_image_mask").fetchSemanticsNodes().isNotEmpty() }
        repeat(2) {
            compose.onNodeWithText("Apply").performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodes(hasText("JSONObject", substring = true))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Apply").assertIsEnabled()
            assertEquals(
                0,
                compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
                    .fetchSemanticsNodes().size,
            )
            assertEquals(0, saved.size)
            assertEquals(null, ReaderImageRules.find(ReaderImageRules.hash(bytes)))
        }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test
    fun applyingWholePagePublishesVisibilityOnUiBeforeDismissingEditor() {
        val page = ReaderPage(0, stream = { bytes.inputStream() })
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        compose.setContent {
            MaterialTheme {
                ReaderImageMaskDialog(
                    page,
                    JSONObject(),
                    onDismiss = { events += "dismiss" },
                    onApplied = { events += "saved" },
                    onRuleChanged = {
                        page.applyRuleVisibility(it)
                        events += "visibility:$it"
                    },
                )
            }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("reader_image_mask").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Apply").performClick()
        compose.waitUntil(15_000) { events.size == 3 }
        compose.runOnIdle {
            assertEquals(listOf("visibility:true", "dismiss", "saved"), events)
            assertTrue(page.isHidden)
        }
    }

    @Test
    fun defaultMaskCoversWholeImageAndBothHandlesCanSelectTheMiddle() {
        val page = ReaderPage(0, stream = { bytes.inputStream() })
        compose.setContent {
            MaterialTheme {
                ReaderImageMaskDialog(page, JSONObject().put("seriesName", "Mask UI test"), {}, {})
            }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("reader_image_mask").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Entire image will be hidden").assertExists()
        val mask = compose.onNodeWithTag("reader_image_mask")
        mask.performTouchInput {
            down(0, center + Offset(-50f, 0f))
            down(1, center + Offset(50f, 0f))
            moveTo(0, center + Offset(-150f, 0f))
            moveTo(1, center + Offset(150f, 0f))
            up(0)
            up(1)
        }
        mask.performTouchInput {
            down(0, center + Offset(-150f, 0f))
            down(1, center + Offset(150f, 0f))
            moveTo(0, center + Offset(-50f, 0f))
            moveTo(1, center + Offset(50f, 0f))
            up(0)
            up(1)
        }
        val margin = with(compose.density) { 24.dp.toPx() }
        mask.performTouchInput { swipe(Offset(center.x, margin), Offset(center.x, height * 0.36f), 600) }
        mask.performTouchInput { swipe(Offset(center.x, height - margin), Offset(center.x, height * 0.67f), 600) }
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "mask-editor-test.png")
        output.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
        compose.onNodeWithText("Apply").performClick()
        val hash = ReaderImageRules.hash(bytes)
        compose.waitUntil(15_000) { ReaderImageRules.find(hash) != null }
        val rule = ReaderImageRules.find(hash)!!
        assertTrue(rule.getInt("removeTop") in 500..800)
        assertTrue(rule.getInt("removeBottom") in 1000..1400)
        assertTrue(rule.getInt("removeTop") < rule.getInt("removeBottom"))
        mask.performTouchInput {
            val y = margin + (height - 2 * margin) * rule.getInt("removeTop") / 1800f
            swipe(Offset(center.x, y), Offset(center.x, 0f), 600)
        }
        mask.performTouchInput {
            val y = margin + (height - 2 * margin) * rule.getInt("removeBottom") / 1800f
            swipe(Offset(center.x, y), Offset(center.x, height.toFloat()), 600)
        }
        compose.onNodeWithText("Entire image will be hidden").assertExists()
    }
}
