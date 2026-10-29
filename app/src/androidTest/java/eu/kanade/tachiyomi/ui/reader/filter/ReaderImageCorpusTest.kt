package eu.kanade.tachiyomi.ui.reader.filter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/** Opt-in local corpus check; user images are never bundled in the app or test APK. */
@RunWith(AndroidJUnit4::class)
class ReaderImageCorpusTest {
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
