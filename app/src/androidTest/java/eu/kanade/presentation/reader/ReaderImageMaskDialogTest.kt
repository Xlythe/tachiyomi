package eu.kanade.presentation.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.filter.ReaderImageRules
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class ReaderImageMaskDialogTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

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
    fun defaultMaskCoversWholeImageAndBothHandlesCanSelectTheMiddle() {
        val page = ReaderPage(0, stream = { bytes.inputStream() })
        compose.activity.setContent {
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
