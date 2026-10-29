package eu.kanade.tachiyomi.ui.reader.filter

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ReaderFingerprintCacheTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "fingerprint-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val raw = Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888).let { bitmap ->
        for (y in 0 until 64) for (x in 0 until 48) bitmap.setPixel(x, y, -16777216 or (x * 31737 + y * 7919))
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            .toByteArray().also { bitmap.recycle() }
    }
    private val rule = JSONObject().put("sha256", ReaderImageRules.hash(raw)).put("width", 48).put("height", 64)
        .put("removeTop", 0).put("removeBottom", 64).put("hide", true)

    @After
    fun cleanup() {
        ReaderImageRules.remove(ReaderImageRules.hash(raw))
        directory.listFiles()?.forEach { it.delete() }
        directory.delete()
    }

    @Test
    fun persistsOriginalStripEvidenceAndRecoversCorruptionAndInterruptedWrites() {
        val cache = ReaderRuleFingerprintCache(directory)
        val entry = cache.prepare(rule, raw)
        ReaderImageMask(raw, 0).use { original ->
            assertArrayEquals(original.signature().pixels, entry.signature.pixels)
            assertEquals(original.hashBand(0, 64), entry.pixelsHash)
            assertEquals(original.probeOffset(0, 64), entry.probe)
        }
        val file = File(directory, "${cache.key(rule)}.bin")
        assertNotNull(cache.read(rule))
        val valid = file.readBytes()
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(cache.read(rule))
        assertEquals(entry.pixelsHash, cache.prepare(rule, raw).pixelsHash)
        val interrupted = File(directory, "${cache.key(rule)}.bin.new")
        interrupted.writeBytes(byteArrayOf(9))
        assertNotNull(cache.read(rule))
        assertTrue("A concurrent read must not remove an unfinished write", interrupted.exists())
        cache.prune(setOf(cache.key(rule)))
        assertFalse(interrupted.exists())
        File(directory, "${cache.key(rule)}.bin.bak").writeBytes(valid)
        file.writeBytes(byteArrayOf(8))
        assertNotNull(cache.read(rule))
        assertEquals(ReaderImageRules.hash(raw), rule.getString("sha256"))
    }

    @Test
    fun boundsDiskUsageAndSeparatesRuleSelectionAndDecoderProvenance() {
        val cache = ReaderRuleFingerprintCache(directory, 128)
        cache.prepare(rule, raw)
        val changed = JSONObject(rule.toString()).put("removeTop", 8).put("hide", false)
        assertNotEquals(cache.key(rule), cache.key(changed))
        assertNull(cache.read(changed))
        assertNull(ReaderRuleFingerprintCache(directory, provenance = "different-decoder-build").read(rule))
        val keep = File(directory, "unrelated.keep").apply { writeText("keep") }
        cache.prune(setOf(cache.key(rule)))
        assertTrue(directory.listFiles().orEmpty().filter { it.name.endsWith(".bin") }.sumOf { it.length() } <= 128)
        assertTrue(keep.exists())
    }

    @Test
    fun importingNeverBlocksManualChoicesOrResurrectsARestoredPage() {
        for (restore in listOf(false, true)) {
            ReaderImageRules.save(raw, 0, 64, JSONObject())
            val archive = ByteArrayOutputStream().also { ReaderImageRules.exportArchive(it) }.toByteArray()
            ReaderImageRules.remove(ReaderImageRules.hash(raw))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            var imported = -1
            val input = object : FilterInputStream(archive.inputStream()) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    return super.read(buffer, offset, length)
                }
            }
            val worker = Thread {
                try { imported = ReaderImageRules.importArchive(input) } catch (error: Throwable) { failure.set(error) }
            }
            worker.start()
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val start = android.os.SystemClock.elapsedRealtime()
                ReaderImageRules.save(raw, 0, 16, JSONObject())
                if (restore) ReaderImageRules.remove(ReaderImageRules.hash(raw))
                assertTrue(android.os.SystemClock.elapsedRealtime() - start < 250)
            } finally {
                release.countDown()
                worker.join(10_000)
            }
            assertFalse(worker.isAlive)
            failure.get()?.let { throw AssertionError("Import failed", it) }
            assertEquals(0, imported)
            if (restore) {
                assertNull(ReaderImageRules.find(ReaderImageRules.hash(raw)))
                assertArrayEquals(raw, ReaderImageRules.open { raw.inputStream() }.use { it.readBytes() })
            } else {
                assertEquals(16, ReaderImageRules.find(ReaderImageRules.hash(raw))!!.getInt("removeBottom"))
                ReaderImageRules.remove(ReaderImageRules.hash(raw))
            }
        }
    }
}
