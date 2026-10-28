package eu.kanade.tachiyomi.ui.reader.filter

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real committed local chapter bytes through the single worker; metadata pages never open images. */
class ReaderDownloadTraversalIntegrationTest {
    @Test fun traverses130ExistingChaptersBeyondHistoryAndRetainsCompletedBookmark() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "reader-index-test-" + UUID.randomUUID()).apply { check(mkdirs()) }
        val bitmap = Bitmap.createBitmap(96, 192, Bitmap.Config.ARGB_8888)
        for (y in 0 until 192) for (x in 0 until 96) bitmap.setPixel(x, y,
            if ((x + y) % 2 == 0) Color.rgb(10, 35, 75) else Color.rgb(230, 210, 160))
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() + UUID.randomUUID().toString().toByteArray()
        bitmap.recycle()
        val chapters = (1..130).map { i -> File(root, i.toString().padStart(5, '0')).apply {
            check(mkdirs()); File(this, "001.png").writeBytes(bytes)
        } }
        val names = java.util.TreeSet(chapters.map { it.name })
        val pageCalls = AtomicInteger()
        val rootUri = Uri.fromFile(root).toString()
        try {
            ReaderImageRules.save(bytes, 0, 192, JSONObject())
            ReaderDownloadIndexPrechecks.installIndex(rootUri) { after, limit ->
                pageCalls.incrementAndGet()
                ReaderDownloadIndexPage.page(listOf(7L), { listOf("series") }, { _, _ -> rootUri },
                    { _, _ -> names }, after, limit)
            }
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (SystemClock.elapsedRealtime() < deadline) {
                val state = ReaderDownloadIndexPrechecks.snapshot()
                if (state.token.root == rootUri && state.done) break
                Thread.sleep(20)
            }
            val state = ReaderDownloadIndexPrechecks.snapshot()
            assertTrue("The complete existing index must finish beyond128 records", state.token.root == rootUri && state.done)
            assertEquals("Every chapter must have a completed worker decision", 130L, state.checkedChapters)
            assertEquals("Errors must not masquerade as completed checks", 0L, state.unknownChapters)
            assertEquals(18, pageCalls.get()) // Seventeen data pages and the final empty page.
            val persisted = JSONObject(File(context.filesDir, "reader-download-traversal-v1.json").readText())
            assertEquals(rootUri, persisted.getString("root")); assertTrue(persisted.getBoolean("done"))
            assertEquals(130L, persisted.getLong("checkedChapters"))
            ReaderDownloadIndexPrechecks.rulesChanged() // Same rule version must not rescan an unchanged completed pass.
            while (ReaderDownloadIndexPrechecks.isRunning() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
            assertFalse("Completed traversal must stop instead of continuously polling the index", ReaderDownloadIndexPrechecks.isRunning())
            assertEquals(18, pageCalls.get())
            chapters.forEach { assertTrue(File(it, "001.png").readBytes().contentEquals(bytes)) }
        } finally {
            ReaderDownloadIndexPrechecks.indexUnavailable()
            chapters.forEach { ReaderDownloadPrechecks.forget(Uri.fromFile(it)) }
            ReaderImageRules.remove(ReaderImageRules.hash(bytes))
            check(root.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator + "reader-index-test-"))
            root.deleteRecursively()
        }
    }
}
