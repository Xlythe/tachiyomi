package eu.kanade.tachiyomi.ui.reader.filter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

/** Opt-in local corpus check; user images are never bundled in the app or test APK. */
@RunWith(AndroidJUnit4::class)
class ReaderImageCorpusTest {
    @Test
    fun combinedCorpusReaderLoadsStayBoundedAndPreserveRetainedRegions() {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("readerCorpus")
        assumeTrue("Opt in with readerCorpusCombined=true", args.getString("readerCorpusCombined") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reportFile = File(context.getExternalFilesDir(null), "reader-corpus-combined-report.json")
        val report = JSONObject()
        val timings = mutableListOf<Long>()
        val changes = JSONArray()
        val owned = mutableListOf<String>()
        val runtime = Runtime.getRuntime()
        fun heap() = runtime.totalMemory() - runtime.freeMemory()
        System.gc()
        val heapBefore = heap()
        var maxPssKb = Debug.getPss()
        val pssBefore = maxPssKb
        var totalStart = 0L
        var checked = 0
        assertEquals("Combined audit requires isolated rules", 0, JSONArray(ReaderImageRules.export()).length())
        try {
            File(requireNotNull(path)).inputStream().use { ReaderImageRules.importArchive(it) }
            val rules = JSONArray(ReaderImageRules.export())
            for (i in 0 until rules.length()) owned += rules.getJSONObject(i).getString("sha256")
            report.put("activeRules", rules.length())
            require(rules.length() in 1..1000)
            totalStart = SystemClock.elapsedRealtime()
            for (i in 0 until rules.length()) {
                val rule = rules.getJSONObject(i)
                if (rule.optBoolean("hide") || rule.has("watermark")) continue
                val raw = ReaderImageRules.original(rule.getString("sha256")).readBytes()
                val image = requireNotNull(BitmapFactory.decodeByteArray(raw, 0, raw.size))
                try {
                    val top = rule.getInt("removeTop")
                    val bottom = rule.getInt("removeBottom")
                    for (band in listOf(0 until top, bottom until image.height).filter { !it.isEmpty() }) {
                        if (SystemClock.elapsedRealtime() - totalStart > 180_000) break
                        val retained = Bitmap.createBitmap(image, 0, band.first, image.width, band.count())
                        val encoded = ByteArrayOutputStream().also {
                            retained.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }.toByteArray()
                        retained.recycle()
                        val start = SystemClock.elapsedRealtime()
                        val result = ReaderImageRules.open { encoded.inputStream() }.use { it.readBytes() }
                        timings += SystemClock.elapsedRealtime() - start
                        maxPssKb = maxOf(maxPssKb, Debug.getPss())
                        if (!encoded.contentEquals(result)) {
                            val match = ReaderImageRules.selection(encoded)
                            val template = (0 until rules.length()).firstOrNull {
                                rules.getJSONObject(it).getString("sha256") == match?.optString("sha256")
                            }
                            changes.put(
                                JSONObject().put("ruleIndex", i).put("retainedStart", band.first)
                                    .put("templateIndex", template).put("top", match?.optInt("removeTop"))
                                    .put("bottom", match?.optInt("removeBottom")),
                            )
                        }
                        checked++
                    }
                } finally {
                    image.recycle()
                }
                if (SystemClock.elapsedRealtime() - totalStart > 180_000) break
            }
            val warm = timings.drop(1).sorted()
            val p95 = warm.getOrNull(((warm.size - 1) * 0.95).toInt()) ?: 0
            System.gc()
            report.put("retainedRegionsChecked", checked).put("changedRegions", changes)
                .put("coldReadMs", timings.firstOrNull()).put("warmP95Ms", p95)
                .put("warmMaxMs", warm.maxOrNull()).put("allReadMs", JSONArray(timings))
                .put("readerAuditMs", SystemClock.elapsedRealtime() - totalStart)
                .put("heapGrowthBytesAfterGc", heap() - heapBefore)
                .put("sampledPssGrowthKb", maxPssKb - pssBefore)
            reportFile.writeText(report.toString(2))
            assertEquals(report.toString(), 78, checked)
            assertEquals(report.toString(), 0, changes.length())
            assertTrue(report.toString(), timings.first() <= 5_000)
            assertTrue(report.toString(), p95 <= 750 && (warm.maxOrNull() ?: 0) <= 2_000)
            assertTrue(report.toString(), report.getLong("heapGrowthBytesAfterGc") <= 64L * 1024 * 1024)
            assertTrue(report.toString(), maxPssKb - pssBefore <= 128 * 1024)
        } finally {
            owned.forEach { ReaderImageRules.remove(it) }
        }
    }

    @Test
    fun recordedWholePageRulesRecognizeIdenticalPixelsWithDifferentEncodedBytes() {
        val path = InstrumentationRegistry.getArguments().getString("readerCorpus")
        assumeTrue("Supply readerCorpus to evaluate a private local export", path != null)
        val report = JSONObject()
        val failures = JSONArray()
        val falsePositives = JSONArray()
        var checked = 0
        var retainedChecked = 0
        ZipFile(File(requireNotNull(path))).use { zip ->
            val entries = zip.entries().toList()
            require(entries.size <= 1002 && entries.sumOf { it.size } <= 512L * 1024 * 1024)
            require(entries.all { it.size in 0..32L * 1024 * 1024 })
            require(entries.map { it.name }.distinct().size == entries.size)
            require(
                entries.all {
                    it.name in listOf("format.json", "rules.json") || it.name.matches(Regex("[0-9a-f]{64}\\.original"))
                },
            )
            val format =
                JSONObject(zip.getInputStream(zip.getEntry("format.json")).bufferedReader().use { it.readText() })
            require(format.getString("format") == "tachiyomi-reader-image-rules" && format.getInt("version") == 1)
            val rules = JSONArray(zip.getInputStream(zip.getEntry("rules.json")).bufferedReader().use { it.readText() })
            require(rules.length() <= 1000)
            for (i in 0 until rules.length()) {
                val rule = rules.getJSONObject(i)
                if (rule.has("watermark")) continue
                val hash = rule.getString("sha256")
                require(hash.matches(Regex("[0-9a-f]{64}")))
                val bytes = zip.getInputStream(zip.getEntry("$hash.original")).use { it.readBytes() }
                require(ReaderImageRules.hash(bytes) == hash)
                assertEquals("Corpus check requires isolated rules", 0, JSONArray(ReaderImageRules.export()).length())
                try {
                    val top = rule.getInt("removeTop")
                    val bottom = rule.getInt("removeBottom")
                    ReaderImageRules.save(bytes, top, bottom, JSONObject())
                    if (rule.optBoolean("hide")) {
                        var hidden = false
                        ReaderImageRules.open(
                            onMatch = { hidden = it },
                        ) { (bytes + byteArrayOf(0)).inputStream() }.close()
                        if (!hidden) failures.put(i)
                        checked++
                    } else {
                        val image = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                        try {
                            for (band in listOf(0 until top, bottom until image.height).filter { !it.isEmpty() }) {
                                val retained = Bitmap.createBitmap(image, 0, band.first, image.width, band.count())
                                val encoded = ByteArrayOutputStream().also {
                                    retained.compress(
                                        Bitmap.CompressFormat.PNG,
                                        100,
                                        it,
                                    )
                                }.toByteArray()
                                val result = ReaderImageRules.open { encoded.inputStream() }.use { it.readBytes() }
                                if (!encoded.contentEquals(result)) falsePositives.put("$i:${band.first}")
                                retainedChecked++
                                retained.recycle()
                            }
                        } finally {
                            image.recycle()
                        }
                    }
                } finally {
                    ReaderImageRules.remove(hash)
                }
            }
        }
        report.put("wholePagesChecked", checked).put("missedRuleIndices", failures)
        report.put("retainedRegionsChecked", retainedChecked).put("falsePositiveRegions", falsePositives)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "reader-corpus-report.json").writeText(report.toString(2))
        assertTrue("No complete page examples", checked > 0)
        assertEquals(report.toString(), 0, failures.length())
        assertEquals(report.toString(), 0, falsePositives.length())
    }
}
