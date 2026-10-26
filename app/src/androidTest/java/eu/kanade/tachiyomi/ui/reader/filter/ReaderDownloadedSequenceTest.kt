package eu.kanade.tachiyomi.ui.reader.filter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

class ReaderDownloadedSequenceTest {
    @Test
    fun downloadedWebpSequenceNeverWaitsForAutomaticComparisons() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with readerSequence=true", args.getString("readerSequence") == "true")
        val baseline = args.getString("readerSequenceBaseline") == "true"
        val reuse = args.getString("readerReuseCorpus") == "true"
        val keep = args.getString("readerKeepCorpus") == "true"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtures = File(context.cacheDir, "download-sequence-${UUID.randomUUID()}").apply { mkdirs() }
        val owned = mutableListOf<String>()
        val report = JSONObject().put("baseline", baseline)
        val timings = JSONArray()
        if (!reuse) assertEquals(0, JSONArray(ReaderImageRules.export()).length())
        try {
            val importStart = SystemClock.elapsedRealtime()
            val archive = File(requireNotNull(args.getString("readerCorpus")))
            if (!reuse) archive.inputStream().use { ReaderImageRules.importArchive(it) }
            val rules = JSONArray(ReaderImageRules.export())
            val expected = ZipFile(archive).use { zip ->
                val text = zip.getInputStream(zip.getEntry("rules.json")).bufferedReader().use { it.readText() }
                val list = JSONArray(text)
                (0 until list.length()).map { list.getJSONObject(it) }.associateBy { it.getString("sha256") }
            }
            assertEquals(expected.size, rules.length())
            for (i in 0 until rules.length()) {
                val rule = rules.getJSONObject(i)
                val recorded = requireNotNull(expected[rule.getString("sha256")])
                for (field in listOf("width", "height", "removeTop", "removeBottom")) {
                    assertEquals("Corpus ownership/selection $field", recorded.getInt(field), rule.getInt(field))
                }
            }
            for (i in 0 until rules.length()) owned += rules.getJSONObject(i).getString("sha256")
            assertEquals(249, owned.size)
            report.put("importMs", SystemClock.elapsedRealtime() - importStart)
            val sources = (0 until rules.length()).map { rules.getJSONObject(it) }.filter {
                !it.optBoolean("hide") && it.getInt("removeTop") >= 200
            }.sortedByDescending { it.getInt("removeTop") }.take(3)
            assertEquals(3, sources.size)
            for ((i, rule) in sources.withIndex()) {
                val raw = ReaderImageRules.original(rule.getString("sha256")).readBytes()
                val image = requireNotNull(BitmapFactory.decodeByteArray(raw, 0, raw.size))
                val retained = Bitmap.createBitmap(image, 0, 0, image.width, rule.getInt("removeTop"))
                val page = Bitmap.createBitmap(600, 12_000 + 1000 * i, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = android.graphics.Canvas(page)
                    val tileHeight = (retained.height * 600f / retained.width).toInt().coerceAtLeast(1)
                    for (y in 0 until page.height step tileHeight) {
                        canvas.drawBitmap(retained, null, android.graphics.Rect(0, y, 600, y + tileHeight), null)
                    }
                    File(fixtures, "page-$i.webp").outputStream().use {
                        @Suppress("DEPRECATION")
                        check(page.compress(Bitmap.CompressFormat.WEBP, 90, it))
                    }
                } finally {
                    page.recycle()
                    retained.recycle()
                    image.recycle()
                }
            }
            report.put("preparationWaitMs", 0)
            for (i in sources.indices) {
                val file = File(fixtures, "page-$i.webp")
                var fileStartDelay = 0L
                var fileIoMs = 0L
                var closed = 0
                val start = SystemClock.elapsedRealtime()
                val page = eu.kanade.tachiyomi.ui.reader.model.ReaderPage(i, stream = {
                    fileStartDelay = SystemClock.elapsedRealtime() - start
                    object : java.io.FilterInputStream(file.inputStream()) {
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            val tick = SystemClock.elapsedRealtime()
                            return super.read(b, off, len).also { fileIoMs += SystemClock.elapsedRealtime() - tick }
                        }
                        override fun close() {
                            closed++
                            super.close()
                        }
                    }
                })
                val result = page.stream!!().use { it.readBytes() }
                val streamMs = SystemClock.elapsedRealtime() - start
                val decodeStart = SystemClock.elapsedRealtime()
                requireNotNull(BitmapFactory.decodeByteArray(result, 0, result.size)).recycle()
                val decodeMs = SystemClock.elapsedRealtime() - decodeStart
                val lockStart = SystemClock.elapsedRealtime()
                synchronized(ReaderImageRules) { Unit }
                val lockMs = SystemClock.elapsedRealtime() - lockStart
                timings.put(
                    JSONObject().put("page", i).put("streamMs", streamMs)
                        .put("fileStartDelayMs", fileStartDelay).put("fileIoMs", fileIoMs)
                        .put("displayDecodeMs", decodeMs).put("rulesMonitorProbeMs", lockMs),
                )
                report.put("pages", timings)
                File(
                    context.getExternalFilesDir(null),
                    "reader-downloaded-sequence-${if (baseline) "baseline" else "candidate"}.json",
                )
                    .writeText(report.toString(2))
                assertEquals(1, closed)
                assertTrue(!page.isHidden)
                assertArrayEquals("Retained WebP page $i", file.readBytes(), result)
                if (!baseline) {
                    assertTrue(report.toString(), streamMs <= 750)
                    assertTrue(report.toString(), streamMs + decodeMs <= 2_000)
                    assertTrue(report.toString(), lockMs <= 250)
                }
                val hiddenRule = (0 until rules.length()).map {
                    rules.getJSONObject(
                        it,
                    )
                }.first { it.optBoolean("hide") }
                val hiddenRaw = ReaderImageRules.original(hiddenRule.getString("sha256")).readBytes()
                val hiddenPage = eu.kanade.tachiyomi.ui.reader.model.ReaderPage(
                    99,
                    stream = { hiddenRaw.inputStream() },
                )
                val hideStart = SystemClock.elapsedRealtime()
                hiddenPage.stream!!().close()
                val hideMs = SystemClock.elapsedRealtime() - hideStart
                assertTrue(hiddenPage.isHidden)
                if (!baseline) assertTrue("Exact hide while comparisons run: $hideMs", hideMs <= 250)
            }
            assertEquals(249, JSONArray(ReaderImageRules.export()).length())
        } finally {
            if (!keep) owned.forEach { ReaderImageRules.remove(it) }
            fixtures.listFiles()?.forEach { it.delete() }
            fixtures.delete()
        }
    }
}
