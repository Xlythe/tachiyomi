package eu.kanade.tachiyomi.ui.reader.filter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Native codec + real committed directory/CBZ + separate-instrumentation restart acceptance. */
class ReaderCheckedIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val ownedRules = mutableListOf<String>()
    private val ownedDirectories = mutableListOf<File>()
    private fun fixture(seed: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(120, 360, Bitmap.Config.ARGB_8888)
        val random = java.util.Random(seed.toLong())
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val grey = random.nextInt(256)
            bitmap.setPixel(x, y, android.graphics.Color.rgb(grey, (grey + y * seed) % 256, (grey + x * 7) % 256))
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            .toByteArray().also { bitmap.recycle() }
    }
    private fun variant(bytes: ByteArray, label: String) = bytes + label.toByteArray()
    private fun root(): File = File(context.cacheDir, "reader-checked-test-${UUID.randomUUID()}")
        .apply { check(mkdirs()); ownedDirectories += this }
    private fun saveWhole(bytes: ByteArray) {
        ReaderImageRules.save(bytes, 0, 360, JSONObject())
        ownedRules += ReaderImageRules.hash(bytes)
    }
    private fun stored(hash: String): Boolean = File(context.filesDir, "reader-checked-v1").listFiles().orEmpty().any {
        it.name.endsWith(".checked") && runCatching {
            DataInputStream(it.inputStream()).use { input -> input.readInt() == 0x52434431 && input.readUTF() == hash }
        }.getOrDefault(false)
    }
    private fun queued(uri: Uri): Boolean = runCatching {
        val list = JSONObject(File(context.filesDir, "reader-download-prechecks-v1.json").readText()).getJSONArray("jobs")
        (0 until list.length()).any { list.getJSONObject(it).getString("uri") == uri.toString() }
    }.getOrDefault(false)
    private fun await(label: String, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15_000
        while (!predicate() && SystemClock.elapsedRealtime() < end) Thread.sleep(20)
        assertTrue(label, predicate())
    }
    private fun firstDisplayHides(bytes: ByteArray) {
        assertNull("Inferred content is not an exact user rule", ReaderImageRules.find(ReaderImageRules.hash(bytes)))
        repeat(2) { chapter ->
            val page = ReaderPage(chapter, stream = { bytes.inputStream() })
            val start = SystemClock.elapsedRealtime()
            val rendered = page.stream!!().use { it.readBytes() }
            assertTrue("Prechecked hide must be known before first render in another chapter", page.isHidden)
            assertTrue("Cached decision must not wait for native comparisons", SystemClock.elapsedRealtime() - start <= 750)
            assertEquals(1, requireNotNull(BitmapFactory.decodeByteArray(rendered, 0, rendered.size)).let {
                it.height.also { _ -> it.recycle() }
            })
        }
    }
    @After fun cleanup() {
        ownedRules.forEach { ReaderImageRules.remove(it) }
        ownedDirectories.forEach { dir ->
            check(dir.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator + "reader-checked-test-"))
            dir.deleteRecursively()
        }
    }

    @Test fun completedDirectoriesAndCbzPersistBeforeRenderingAndIgnorePartialFiles() = runBlocking {
        val original = fixture(17); saveWhole(original)
        val folder = root()
        val completed = variant(original, "completed-folder")
        val partial = variant(original, "partial-folder")
        File(folder, "001.png").writeBytes(completed)
        File(folder, "002.png.tmp").writeBytes(partial)
        ReaderDownloadPrechecks.enqueue(Uri.fromFile(folder), true)
        await("Directory precheck must finish") { !queued(Uri.fromFile(folder)) }
        assertTrue(stored(ReaderImageRules.hash(completed)))
        assertFalse(stored(ReaderImageRules.hash(partial)))
        firstDisplayHides(completed)
        assertArrayEquals(completed, File(folder, "001.png").readBytes())
        assertArrayEquals(partial, File(folder, "002.png.tmp").readBytes())
        // A later finalized page is a new completed job, not a false clean decision for the .tmp.
        assertTrue(File(folder, "002.png.tmp").renameTo(File(folder, "002.png")))
        ReaderDownloadPrechecks.enqueue(Uri.fromFile(folder), true)
        await("Renamed completed page must be checked") { !queued(Uri.fromFile(folder)) }
        firstDisplayHides(partial)
        val archive = File(root(), "chapter.cbz")
        val zipped = variant(original, "completed-cbz")
        val zippedPartial = variant(original, "partial-cbz")
        ZipOutputStream(archive.outputStream()).use { zip ->
            for ((name, bytes) in listOf("001.png" to zipped, "002.png.tmp" to zippedPartial)) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        val before = archive.readBytes()
        ReaderDownloadPrechecks.enqueue(Uri.fromFile(archive), false)
        await("CBZ precheck must finish") { !queued(Uri.fromFile(archive)) }
        assertTrue(stored(ReaderImageRules.hash(zipped)))
        assertFalse(stored(ReaderImageRules.hash(zippedPartial)))
        firstDisplayHides(zipped)
        assertArrayEquals(before, archive.readBytes())
    }

    @Test fun ruleRemovalInvalidatesAnInferredHideAndExactUserCropTakesPrecedence() = runBlocking {
        val original = fixture(23); saveWhole(original)
        val inferred = variant(original, "rules-and-manual-precedence")
        assertTrue(ReaderImageRules.precheckDownloaded(inferred))
        firstDisplayHides(inferred)
        assertTrue(ReaderImageRules.remove(ReaderImageRules.hash(original)))
        val restored = ReaderPage(1, stream = { inferred.inputStream() })
        assertArrayEquals(inferred, restored.stream!!().use { it.readBytes() })
        assertFalse(restored.isHidden)
        ReaderImageRules.save(inferred, 100, 200, JSONObject())
        ownedRules += ReaderImageRules.hash(inferred)
        val manual = ReaderPage(2, stream = { inferred.inputStream() })
        val rendered = manual.stream!!().use { it.readBytes() }
        assertFalse(manual.isHidden)
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(rendered, 0, rendered.size))
        assertEquals(260, decoded.height); decoded.recycle()
        assertArrayEquals(inferred, ReaderImageRules.original(ReaderImageRules.hash(inferred)).readBytes())
    }

    @Test fun unsupportedOrTruncatedCompletedInputsNeverBecomeCleanDecisions() = runBlocking {
        val original = fixture(31); saveWhole(original)
        for (bytes in listOf(byteArrayOf(1, 2, 3, 4), original.copyOf(32))) {
            assertFalse(ReaderImageRules.precheckDownloaded(bytes))
            assertFalse(stored(ReaderImageRules.hash(bytes)))
            val page = ReaderPage(0, stream = { bytes.inputStream() })
            assertArrayEquals(bytes, page.stream!!().use { it.readBytes() })
            assertFalse(page.isHidden)
        }
    }

    @Test fun preparePersistentDecisionsAndInterruptedDownloadForSeparateProcess() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("readerCheckedRestart") == "prepare")
        assertEquals("Restart fixture needs isolated empty rules", 0, JSONArray(ReaderImageRules.export()).length())
        val dir = File(context.cacheDir, "reader-checked-restart-fixtures")
        check(!dir.exists()); check(dir.mkdirs())
        val original = fixture(41)
        ReaderImageRules.save(original, 0, 360, JSONObject())
        val positive = variant(original, "restart-positive")
        val clean = fixture(53)
        File(dir, "positive.png").writeBytes(positive); File(dir, "clean.png").writeBytes(clean)
        assertTrue(ReaderImageRules.precheckDownloaded(positive))
        assertTrue(ReaderImageRules.precheckDownloaded(clean))
        assertTrue(stored(ReaderImageRules.hash(positive))); assertTrue(stored(ReaderImageRules.hash(clean)))
        firstDisplayHides(positive)
        val pending = File(dir, "pending").apply { check(mkdirs()) }
        File(pending, "001.png").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "pending-ready.png").writeBytes(variant(original, "restart-pending"))
        ReaderDownloadPrechecks.enqueue(Uri.fromFile(pending), true)
        await("Failed completed page keeps a resumable cursor") { queued(Uri.fromFile(pending)) }
        Thread.sleep(150)
        assertTrue(queued(Uri.fromFile(pending)))
        assertFalse(stored(ReaderImageRules.hash(byteArrayOf(1, 2, 3))))
        File(dir, "receipt.json").writeText(JSONObject().put("pid", Process.myPid())
            .put("rule", ReaderImageRules.hash(original)).toString())
    }

    @Test fun verifyRestartUsesDiskBeforeDisplayAndResumesCompletedDownload() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("readerCheckedRestart") == "verify")
        val dir = File(context.cacheDir, "reader-checked-restart-fixtures")
        val receipt = JSONObject(File(dir, "receipt.json").readText())
        assertNotEquals("Must be another Android process", receipt.getInt("pid"), Process.myPid())
        ownedRules += receipt.getString("rule")
        val positive = File(dir, "positive.png").readBytes(); val clean = File(dir, "clean.png").readBytes()
        assertTrue(stored(ReaderImageRules.hash(positive))); assertTrue(stored(ReaderImageRules.hash(clean)))
        firstDisplayHides(positive)
        val page = ReaderPage(400, stream = { clean.inputStream() })
        val start = SystemClock.elapsedRealtime()
        assertArrayEquals(clean, page.stream!!().use { it.readBytes() }); assertFalse(page.isHidden)
        assertTrue(SystemClock.elapsedRealtime() - start <= 750)
        val pending = File(dir, "pending")
        await("Restarted worker must finish its persisted download cursor") { !queued(Uri.fromFile(pending)) }
        val resumed = File(dir, "pending-ready.png").readBytes()
        assertTrue(stored(ReaderImageRules.hash(resumed))); firstDisplayHides(resumed)
        File(dir, "verified.json").writeText(JSONObject().put("pid", Process.myPid()).put("verified", true).toString())
        // Keep the generated restart fixture/receipt as test evidence; no original corpus is involved.
    }
}
