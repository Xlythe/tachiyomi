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
    private fun awaitPreparation() = runBlocking {
        withTimeout(120_000) { ReaderImageRules.preparing.first { !it } }
        assertEquals("All local samples prepared", 0, ReaderImageRules.preparationFailures.value)
    }

    @Test
    fun boundedFullDecodePreservesRegionalPixelHashesAndSignatures() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with readerCorpusParity=true", args.getString("readerCorpusParity") == "true")
        val path = requireNotNull(args.getString("readerCorpus"))
        var checked = 0
        val start = SystemClock.elapsedRealtime()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cacheDirectory = File(context.cacheDir, "corpus-parity-${UUID.randomUUID()}")
        val cache = ReaderRuleFingerprintCache(cacheDirectory)
        try {
            ZipFile(File(path)).use { zip ->
                val rules =
                    JSONArray(zip.getInputStream(zip.getEntry("rules.json")).bufferedReader().use { it.readText() })
                require(rules.length() <= 1000)
                for (i in 0 until rules.length()) {
                    val rule = rules.getJSONObject(i)
                    val hash = rule.getString("sha256")
                    require(hash.matches(Regex("[0-9a-f]{64}")))
                    val entry = requireNotNull(zip.getEntry("$hash.original"))
                    require(entry.size in 1..32L * 1024 * 1024)
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    require(ReaderImageRules.hash(bytes) == hash)
                    ReaderImageMask(bytes, 0).use { regional ->
                        ReaderImageMask(bytes).use { bounded ->
                            val top = rule.getInt("removeTop")
                            val bottom = rule.getInt("removeBottom")
                            assertEquals(
                                "Full pixel hash at $i",
                                regional.hashBand(0, regional.height),
                                bounded.hashBand(0, bounded.height),
                            )
                            assertEquals(
                                "Band pixel hash at $i",
                                regional.hashBand(top, bottom),
                                bounded.hashBand(top, bottom),
                            )
                            assertArrayEquals(
                                "Signature at $i",
                                regional.signature(top, bottom).pixels,
                                bounded.signature(top, bottom).pixels,
                            )
                            val cached = cache.prepare(rule, bytes)
                            assertArrayEquals(
                                "Persisted signature at $i",
                                regional.signature(top, bottom).pixels,
                                cached.signature.pixels,
                            )
                            if (rule.optBoolean("hide")) {
                                val probe = regional.probeOffset(0, regional.height)
                                assertEquals("Persisted probe at $i", probe, cached.probe)
                                assertEquals(
                                    "Persisted row at $i",
                                    regional.hashBand(probe, probe + 1),
                                    cached.probeHash,
                                )
                                assertEquals(
                                    "Persisted full hash at $i",
                                    regional.hashBand(0, regional.height),
                                    cached.pixelsHash,
                                )
                            }
                            assertArrayEquals(
                                "Read-back signature at $i",
                                cached.signature.pixels,
                                requireNotNull(cache.read(rule)).signature.pixels,
                            )
                        }
                    }
                    checked++
                    cache.prune(emptySet())
                }
            }
        } finally {
            cache.prune(emptySet())
            cacheDirectory.delete()
        }
        File(context.getExternalFilesDir(null), "reader-corpus-parity-report.json").writeText(
            JSONObject().put(
                "imagesChecked",
                checked,
            ).put("elapsedMs", SystemClock.elapsedRealtime() - start).toString(2),
        )
        assertEquals(249, checked)
    }

    @Test
    fun combinedCorpusReaderLoadsStayBoundedAndPreserveRetainedRegions() {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("readerCorpus")
        assumeTrue("Opt in with readerCorpusCombined=true", args.getString("readerCorpusCombined") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val baselineMode = args.getString("readerMatcherBaseline") == "true"
        val reportName =
            if (baselineMode) "reader-corpus-baseline-report.json" else "reader-corpus-combined-report.json"
        val reportFile = File(context.getExternalFilesDir(null), reportName)
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
            val preparationStart = SystemClock.elapsedRealtime()
            File(requireNotNull(path)).inputStream().use { ReaderImageRules.importArchive(it) }
            awaitPreparation()
            report.put("importAndPreparationMs", SystemClock.elapsedRealtime() - preparationStart)
            // Cold reader access must reload persisted evidence, not reuse the preparing job's LRU.
            synchronized(ReaderImageRules) {
                val field = ReaderImageRules::class.java.getDeclaredField("prepared").apply { isAccessible = true }
                (field.get(null) as MutableMap<*, *>).clear()
            }
            val rules = JSONArray(ReaderImageRules.export())
            // Optional local test-only copy of the prior matcher; never bundled in production.
            val baselineClass = if (baselineMode) {
                Class.forName("eu.kanade.tachiyomi.ui.reader.filter.ReaderImageRulesBaseline")
            } else {
                null
            }
            val baseline = baselineClass?.getField("INSTANCE")?.get(null)
            baselineClass?.getMethod("initialize", android.content.Context::class.java)?.invoke(baseline, context)
            val baselineOpen = baselineClass?.getMethod("open", Function1::class.java, Function0::class.java)
            report.put("matcher", if (baselineMode) "f89e08388" else "current")
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
                        val profileCold = checked == 0 && args.getString("readerProfileCold") == "true"
                        if (profileCold) {
                            val trace =
                                File(context.getExternalFilesDir(null), "matcher-${report.getString("matcher")}.trace")
                            Debug.startMethodTracingSampling(trace.path, 8 * 1024 * 1024, 10_000)
                        }
                        val start = SystemClock.elapsedRealtime()
                        val stream = if (baselineOpen != null) {
                            baselineOpen.invoke(
                                baseline,
                                { _: Boolean -> },
                                { encoded.inputStream() },
                            ) as java.io.InputStream
                        } else {
                            ReaderImageRules.open { encoded.inputStream() }
                        }
                        val result = stream.use { it.readBytes() }
                        timings += SystemClock.elapsedRealtime() - start
                        if (profileCold) Debug.stopMethodTracing()
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
                    awaitPreparation()
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
