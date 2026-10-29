package eu.kanade.presentation.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.reader.ReaderUiTestActivity
import eu.kanade.tachiyomi.ui.reader.filter.ReaderImageMask
import eu.kanade.tachiyomi.ui.reader.filter.ReaderImageRules
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Bounded fresh-activity diagnostic: every failure is retained, never retried into success. */
@RunWith(Parameterized::class)
class ReaderEditorReadinessTest(private val launch: Int) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "freshHost{0}")
        fun launches() = (1..8).map { arrayOf(it) }
    }

    @get:Rule val compose = createAndroidComposeRule<ReaderUiTestActivity>()

    @Test fun editorPublishesAndBecomesVisibleOnAFreshHost() {
        val events = CopyOnWriteArrayList<String>()
        fun event(text: String) { events += "${SystemClock.uptimeMillis()} $text" }
        val raw = Bitmap.createBitmap(600, 1800, Bitmap.Config.ARGB_8888).let { image ->
            image.eraseColor(Color.BLUE)
            ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                .also { image.recycle() }
        }
        val page = ReaderPage(0, stream = {
            event("source opened")
            object : FilterInputStream(raw.inputStream()) {
                override fun close() {
                    super.close()
                    event("source closed")
                }
            }
        })
        val secondRaw = raw + "second-page-$launch".toByteArray()
        val second = ReaderPage(1, stream = {
            event("second source opened")
            secondRaw.inputStream()
        })
        val selected = mutableStateOf(page)
        val published = AtomicInteger()
        val saved = CopyOnWriteArrayList<String>()
        compose.setContent {
            val content: @Composable () -> Unit = {
                event("test content composed")
                ReaderImageMaskDialog(selected.value, JSONObject(), {}, { saved += it }, contrastEdges = { bytes ->
                    event("preview published; optional enrichment started")
                    published.set(if (bytes.contentEquals(secondRaw)) 2 else 1)
                    withContext(Dispatchers.IO) { ReaderImageMask(bytes).use { it.contrastEdges() } }
                        .also { event("optional enrichment completed") }
                })
            }
            if (launch % 2 == 0) ReaderUiTestHost(content) else MaterialTheme(content = content)
        }
        try {
            compose.waitUntil(15_000) {
                compose.onAllNodesWithTag("reader_image_mask").fetchSemanticsNodes().isNotEmpty()
            }
            event("mask semantics visible")
            compose.runOnIdle { selected.value = second }
            compose.waitUntil(15_000) {
                published.get() == 2 &&
                    compose.onAllNodesWithTag("reader_image_mask").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Apply").performClick()
            compose.waitUntil(15_000) { saved.isNotEmpty() }
            assertEquals(listOf(ReaderImageRules.hash(secondRaw)), saved)
            assertNull(ReaderImageRules.find(ReaderImageRules.hash(raw)))
            assertArrayEquals(secondRaw, ReaderImageRules.original(ReaderImageRules.hash(secondRaw)).readBytes())
            event("second page hidden and original preserved")
        } finally {
            val text = StringBuilder().appendLine(
                "launch=$launch autoAdvance=${compose.mainClock.autoAdvance} time=${compose.mainClock.currentTime}",
            )
            events.forEach { text.appendLine(it) }
            runCatching {
                compose.activityRule.scenario.onActivity { activity ->
                    text.appendLine(
                        "host=${activity.lifecycle.currentState} focus=${activity.hasWindowFocus()} " +
                            "decor=${activity.window.decorView.width}x${activity.window.decorView.height} " +
                            "shown=${activity.window.decorView.isShown}",
                    )
                }
            }.onFailure { text.appendLine("HOST FAILURE $it") }
            runCatching {
                val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
                text.appendLine("roots=${roots.size}")
                fun dump(node: androidx.compose.ui.semantics.SemanticsNode, depth: Int) {
                    if (depth > 8) return
                    text.appendLine(
                        "${" ".repeat(depth)}id=${node.id} bounds=${node.boundsInRoot} config=${node.config}",
                    )
                    node.children.forEach { dump(it, depth + 1) }
                }
                roots.forEach { dump(it, 0) }
            }.onFailure { text.appendLine("SEMANTICS FAILURE $it") }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.getExternalFilesDir(null), "reader-readiness-launch-$launch.txt").writeText(text.toString())
            println("READER_READINESS_TRACE\n$text")
            ReaderImageRules.remove(ReaderImageRules.hash(secondRaw))
        }
    }
}
