package eu.kanade.tachiyomi.ui.reader.filter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

class ReaderCheckedCorpusTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val token = (InstrumentationRegistry.getArguments().getString("readerCheckedRun") ?: "initial")
        .also { require(it.matches(Regex("[a-zA-Z0-9-]{1,40}"))) }
    private val directory = File(context.cacheDir, "reader-checked-corpus-evidence-$token")
    private fun rules() = JSONArray(ReaderImageRules.export())
    private fun assertOwned() {
        val archive = File(requireNotNull(InstrumentationRegistry.getArguments().getString("readerCorpus")))
        val expected = ZipFile(archive).use { zip ->
            JSONArray(zip.getInputStream(zip.getEntry("rules.json")).bufferedReader().use { it.readText() })
        }
        val byHash = (0 until expected.length()).map { expected.getJSONObject(it) }.associateBy { it.getString("sha256") }
        val current = rules(); assertEquals(249, current.length())
        for (i in 0 until current.length()) {
            val row = current.getJSONObject(i); val original = requireNotNull(byHash[row.getString("sha256")])
            for (field in listOf("width", "height", "removeTop", "removeBottom")) assertEquals(original.getInt(field), row.getInt(field))
        }
    }
    private fun reportFile() = File(context.getExternalFilesDir(null), "reader-checked-corpus-candidate.json")
    private fun readCachedHide(bytes: ByteArray): Long {
        val page = ReaderPage(800, stream = { bytes.inputStream() })
        val start = SystemClock.elapsedRealtime(); val data = page.stream!!().use { it.readBytes() }
        val ms = SystemClock.elapsedRealtime() - start
        assertTrue("Persisted inferred hide must be known before display", page.isHidden)
        assertTrue("Persisted lookup must not compare templates", ms <= 750)
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(data, 0, data.size))
        assertEquals(1, bitmap.height); bitmap.recycle(); return ms
    }
    @Test fun coldCheckPersistsWhileAllRulesStayNonblockingAndWarmLookupIsImmediate() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("readerCheckedCorpus") == "prepare")
        assertEquals(0, rules().length())
        val archive = File(requireNotNull(InstrumentationRegistry.getArguments().getString("readerCorpus")))
        val report = JSONObject().put("pid", Process.myPid())
        val importStart = SystemClock.elapsedRealtime()
        archive.inputStream().use { ReaderImageRules.importArchive(it) }
        report.put("importMs", SystemClock.elapsedRealtime() - importStart); assertOwned()
        check(!directory.exists()); check(directory.mkdirs())
        val list = rules()
        val whole = (0 until list.length()).map { list.getJSONObject(it) }.filter { it.optBoolean("hide") }
        report.put("wholeRulesBelow12SignatureRows", whole.count { it.getInt("height") * 48.0 / it.getInt("width") < 11.5 })
        // This measures persisted inference, not a relaxation of the legacy minimum-height/texture guards.
        val rule = whole.sortedBy { it.getInt("width").toLong() * it.getInt("height") }.first { candidate ->
            ReaderImageMask(ReaderImageRules.original(candidate.getString("sha256")).readBytes()).use { image ->
                val signature = image.signature()
                signature.agrees(signature, 0, 0, signature.height)
            }
        }
        report.put("positiveRuleHash", rule.getString("sha256"))
        val original = ReaderImageRules.original(rule.getString("sha256")).readBytes()
        val positive = original + "checked-corpus-positive-v1-$token".toByteArray()
        File(directory, "positive.bin").writeBytes(positive)
        val hash = ReaderImageRules.hash(positive); assertEquals(null, ReaderImageRules.find(hash))
        report.put("contentHash", hash).put("pssBeforeKb", Debug.getPss())
        val checkStart = SystemClock.elapsedRealtime()
        val checking = async(Dispatchers.IO) { ReaderImageRules.precheckDownloaded(positive) }
        val timings = JSONArray()
        repeat(3) { index ->
            val bitmap = Bitmap.createBitmap(600, 1600 + index * 300, Bitmap.Config.ARGB_8888)
            val random = java.util.Random((index + 71).toLong())
            for (y in 0 until bitmap.height) {
                val colour = android.graphics.Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
                for (x in 0 until bitmap.width) bitmap.setPixel(x, y, colour)
            }
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            val page = ReaderPage(index, stream = { bytes.inputStream() })
            val start = SystemClock.elapsedRealtime(); val data = page.stream!!().use { it.readBytes() }
            val streamMs = SystemClock.elapsedRealtime() - start
            val decodeStart = SystemClock.elapsedRealtime()
            requireNotNull(BitmapFactory.decodeByteArray(data, 0, data.size)).recycle()
            val decodeMs = SystemClock.elapsedRealtime() - decodeStart
            val lockStart = SystemClock.elapsedRealtime(); synchronized(ReaderImageRules) { Unit }
            val lockMs = SystemClock.elapsedRealtime() - lockStart
            timings.put(JSONObject().put("streamMs", streamMs).put("decodeMs", decodeMs).put("lockMs", lockMs))
            report.put("pages", timings); reportFile().writeText(report.toString(2))
            assertArrayEquals(bytes, data); assertFalse(page.isHidden)
            assertTrue(report.toString(), streamMs <= 750 && streamMs + decodeMs <= 2000 && lockMs <= 250)
        }
        assertTrue("Whole inferred hide must finish and persist", withTimeout(180_000) { checking.await() })
        report.put("backgroundCheckAndConcurrentFixturesMs", SystemClock.elapsedRealtime() - checkStart)
        report.put("warmReadMs", readCachedHide(positive)).put("pssAfterKb", Debug.getPss())
        val retainedRule = (0 until list.length()).map { list.getJSONObject(it) }
            .filter { !it.optBoolean("hide") && it.getInt("removeTop") >= 200 }
            .maxBy { it.getInt("removeTop") }
        val raw = ReaderImageRules.original(retainedRule.getString("sha256")).readBytes()
        val source = requireNotNull(BitmapFactory.decodeByteArray(raw, 0, raw.size))
        val retained = Bitmap.createBitmap(source, 0, 0, source.width, retainedRule.getInt("removeTop"))
        val negative = ByteArrayOutputStream().also { retained.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        retained.recycle(); source.recycle()
        val negativeStart = SystemClock.elapsedRealtime()
        assertTrue(withTimeout(180_000) { ReaderImageRules.precheckDownloaded(negative) })
        val negativePage = ReaderPage(900, stream = { negative.inputStream() })
        assertArrayEquals("User-retained manga region must stay intact after completed inference", negative,
            negativePage.stream!!().use { it.readBytes() })
        assertFalse("Retained manga must not become a durable hide", negativePage.isHidden)
        report.put("retainedNegativeRuleHash", retainedRule.getString("sha256"))
            .put("retainedNegativeCheckMs", SystemClock.elapsedRealtime() - negativeStart)
        val files = File(context.filesDir, "reader-checked-v1").listFiles().orEmpty()
        val owned = files.filter { it.name.matches(Regex("[0-9a-f]{64}\\.checked(\\.bak)?")) }
        report.put("derivedCacheBytes", owned.sumOf { it.length() }).put("derivedCacheEntries", owned.size)
        assertTrue(owned.sumOf { it.length() } <= 64L * 1024 * 1024); assertTrue(owned.size <= 4096)
        assertOwned(); reportFile().writeText(report.toString(2))
        File(directory, "receipt.json").writeText(report.toString(2))
    }
    @Test fun anotherProcessReusesTheWholeCorpusDecisionBeforeItsFirstRender() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("readerCheckedCorpus") == "verify")
        assertOwned()
        val report = JSONObject(File(directory, "receipt.json").readText())
        assertTrue(Process.myPid() != report.getInt("pid"))
        val positive = File(directory, "positive.bin").readBytes()
        assertEquals(report.getString("contentHash"), ReaderImageRules.hash(positive))
        report.put("restartPid", Process.myPid()).put("restartReadMs", readCachedHide(positive))
        reportFile().writeText(report.toString(2))
    }
}
