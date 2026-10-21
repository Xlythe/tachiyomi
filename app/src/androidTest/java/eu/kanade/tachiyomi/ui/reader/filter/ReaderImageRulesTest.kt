package eu.kanade.tachiyomi.ui.reader.filter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ReaderImageRulesTest {
    private val original = Bitmap.createBitmap(12, 10, Bitmap.Config.ARGB_8888).let { bitmap ->
        for (y in 0 until 10) for (x in 0 until 12) bitmap.setPixel(x, y, pixel(x, y))
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            .toByteArray().also { bitmap.recycle() }
    }

    private fun pixel(x: Int, y: Int) = android.graphics.Color.rgb(x * 17, y * 23, 71)

    @After
    fun cleanup() { ReaderImageRules.remove(ReaderImageRules.hash(original)) }

    @Test
    fun removesTopBottomAndMiddleWithoutLosingSurroundingPixels() {
        for ((top, bottom) in listOf(0 to 3, 7 to 10, 3 to 7, 0 to 1, 9 to 10)) {
            ReaderImageRules.save(original, top, bottom, JSONObject())
            val bitmap = ReaderImageRules.open { original.inputStream() }.use { BitmapFactory.decodeStream(it) }!!
            try {
                val rows = (0 until top).toList() + (bottom until 10).toList()
                assertEquals(rows.size, bitmap.height)
                assertEquals(12, bitmap.width)
                rows.forEachIndexed { outputY, inputY ->
                    for (x in 0 until 12) assertEquals(pixel(x, inputY), bitmap.getPixel(x, outputY))
                }
            } finally { bitmap.recycle() }
        }
    }

    @Test
    fun entireMaskHidesImageAndRestoreReturnsExactOriginal() {
        ReaderImageRules.save(original, 0, 10, JSONObject().put("seriesName", "Test series"))
        val hash = ReaderImageRules.hash(original)
        assertTrue(ReaderImageRules.find(hash)!!.getBoolean("hide"))
        assertArrayEquals(original, ReaderImageRules.original(hash).readBytes())
        val bitmap = ReaderImageRules.open { original.inputStream() }.use { BitmapFactory.decodeStream(it) }!!
        try {
            assertEquals(1, bitmap.height)
            assertEquals(0, bitmap.getPixel(0, 0))
        } finally { bitmap.recycle() }
        assertTrue(ReaderImageRules.remove(hash))
        assertArrayEquals(original, ReaderImageRules.open { original.inputStream() }.use { it.readBytes() })
        assertFalse(ReaderImageRules.original(hash).exists())
    }

    @Test
    fun persistsCoordinatesMetadataAndDoesNotMatchUnrelatedBytes() {
        ReaderImageRules.save(original, 2, 6, JSONObject().put("chapterNumber", 42).put("pageNumber", 3))
        ReaderImageRules::class.java.getDeclaredField("loaded").apply { isAccessible = true }.set(null, null)
        val rule = ReaderImageRules.find(ReaderImageRules.hash(original))!!
        assertEquals(2, rule.getInt("removeTop"))
        assertEquals(6, rule.getInt("removeBottom"))
        assertEquals(42, rule.getInt("chapterNumber"))
        assertEquals(3, rule.getInt("pageNumber"))
        val unrelated = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(unrelated, ReaderImageRules.open { unrelated.inputStream() }.use { it.readBytes() })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvertedMask() { ReaderImageRules.save(original, 8, 2, JSONObject()) }

    @Test
    fun providerExportsTheCapturedImageAndMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val base = "content://${context.packageName}.reader_image_rules"
        val hash = ReaderImageRules.hash(original)
        ReaderImageRules.save(original, 0, 10, JSONObject().put("seriesName", "ADB export test"))
        val json = context.contentResolver.openInputStream(Uri.parse("$base/rules"))!!.use {
            it.reader().readText()
        }
        val records = JSONArray(json)
        val rule = (0 until records.length()).map { records.getJSONObject(it) }.first {
            it.getString("sha256") == hash
        }
        assertEquals("ADB export test", rule.getString("seriesName"))
        val image = context.contentResolver.openInputStream(Uri.parse("$base/original/$hash"))!!.use { it.readBytes() }
        assertArrayEquals(original, image)
        assertEquals(1, context.contentResolver.delete(Uri.parse("$base/rules/$hash"), null, null))
        assertFalse(ReaderImageRules.original(hash).exists())
    }

    @Test
    fun sameSpamBandMatchesAroundDifferentStoryContentButChangedSpamDoesNot() {
        for ((top, bottom) in listOf(0 to 3, 7 to 10, 3 to 7)) {
            ReaderImageRules.save(original, top, bottom, JSONObject())
            val changedStory = BitmapFactory.decodeByteArray(original, 0, original.size)
                .copy(Bitmap.Config.ARGB_8888, true)
            val retainedRow = if (top == 0) 9 else 0
            changedStory.setPixel(5, retainedRow, android.graphics.Color.MAGENTA)
            val bytes = encode(changedStory)
            changedStory.recycle()
            val filtered = ReaderImageRules.open { bytes.inputStream() }.use { BitmapFactory.decodeStream(it) }!!
            try {
                assertEquals(10 - bottom + top, filtered.height)
                val outputRow = if (top == 0) retainedRow - bottom else retainedRow
                assertEquals(android.graphics.Color.MAGENTA, filtered.getPixel(5, outputRow))
            } finally { filtered.recycle() }

            val changedSpam = BitmapFactory.decodeByteArray(original, 0, original.size)
                .copy(Bitmap.Config.ARGB_8888, true)
            changedSpam.setPixel(5, top + 1, android.graphics.Color.MAGENTA)
            val unrelated = encode(changedSpam)
            changedSpam.recycle()
            assertArrayEquals(unrelated, ReaderImageRules.open { unrelated.inputStream() }.use { it.readBytes() })
        }
    }

    @Test
    fun footerMatchesOnATallerPageAtTheNewBottom() {
        ReaderImageRules.save(original, 7, 10, JSONObject())
        val taller = Bitmap.createBitmap(12, 20, Bitmap.Config.ARGB_8888)
        taller.eraseColor(android.graphics.Color.YELLOW)
        for (y in 17 until 20) for (x in 0 until 12) taller.setPixel(x, y, pixel(x, y - 10))
        val bytes = encode(taller)
        taller.recycle()
        val filtered = ReaderImageRules.open { bytes.inputStream() }.use { BitmapFactory.decodeStream(it) }!!
        try {
            assertEquals(17, filtered.height)
            assertEquals(android.graphics.Color.YELLOW, filtered.getPixel(5, 16))
        } finally { filtered.recycle() }
    }

    private fun encode(bitmap: Bitmap) = ByteArrayOutputStream().also {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    @Test
    fun repeatedDownloadedStreamsCloseAndHiddenStateTracksRestore() {
        ReaderImageRules.save(original, 0, 10, JSONObject())
        var closes = 0
        val page = ReaderPage(0, stream = {
            object : java.io.ByteArrayInputStream(original) {
                override fun close() {
                    closes++
                    super.close()
                }
            }
        })
        repeat(5) {
            page.stream!!().use { it.readBytes() }
            assertTrue(page.isHidden)
        }
        assertEquals(5, closes)
        ReaderImageRules.remove(ReaderImageRules.hash(original))
        assertFalse(page.isHidden)
        assertArrayEquals(original, page.stream!!().use { it.readBytes() })
    }

    @Test
    fun portableArchiveRoundTripsAndDuplicateImportKeepsLocalChoice() {
        ReaderImageRules.save(original, 2, 6, JSONObject().put("seriesName", "Portable test"))
        val archive = ByteArrayOutputStream().also { ReaderImageRules.exportArchive(it) }.toByteArray()
        ReaderImageRules.remove(ReaderImageRules.hash(original))
        // Simulate an orphan left by an interrupted import before the index was committed.
        ReaderImageRules.original(ReaderImageRules.hash(original)).writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(1, ReaderImageRules.importArchive(archive.inputStream()))
        assertArrayEquals(original, ReaderImageRules.original(ReaderImageRules.hash(original)).readBytes())
        assertEquals(2, ReaderImageRules.find(ReaderImageRules.hash(original))!!.getInt("removeTop"))
        ReaderImageRules.save(original, 0, 3, JSONObject())
        assertEquals(0, ReaderImageRules.importArchive(archive.inputStream()))
        assertEquals(0, ReaderImageRules.find(ReaderImageRules.hash(original))!!.getInt("removeTop"))
    }

    @Test
    fun malformedAndInterruptedArchivesLeaveExistingRulesUntouched() {
        ReaderImageRules.save(original, 2, 6, JSONObject())
        val before = ReaderImageRules.export()
        val malicious = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use {
                it.putNextEntry(ZipEntry("../rules.json"))
                it.write("[]".toByteArray())
            }
        }.toByteArray()
        assertTrue(runCatching { ReaderImageRules.importArchive(malicious.inputStream()) }.isFailure)
        assertTrue(runCatching { ReaderImageRules.importArchive(byteArrayOf(1, 2, 3).inputStream()) }.isFailure)
        assertEquals(before, ReaderImageRules.export())
    }

    @Test
    fun watermarkPreservesOutsideEllipseAndRestoresOriginalBytes() {
        ReaderImageRules.save(original, 0, 10, JSONObject(), watermark = Rect(2, 2, 8, 8))
        val bitmap = ReaderImageRules.open { original.inputStream() }.use { BitmapFactory.decodeStream(it) }!!
        assertEquals(10, bitmap.height)
        assertEquals(pixel(0, 4), bitmap.getPixel(0, 4))
        assertEquals(pixel(11, 4), bitmap.getPixel(11, 4))
        assertFalse(ReaderImageRules.find(ReaderImageRules.hash(original))!!.getBoolean("hide"))
        bitmap.recycle()
        ReaderImageRules.remove(ReaderImageRules.hash(original))
        assertArrayEquals(original, ReaderImageRules.open { original.inputStream() }.use { it.readBytes() })
    }

    @Test
    fun oddHeightWatermarkNeverChangesPixelsOutsideItsBoundingBox() {
        val bitmap = BitmapFactory.decodeByteArray(original, 0, original.size)
            .copy(Bitmap.Config.ARGB_8888, true)
        bitmap.setPixel(8, 5, android.graphics.Color.MAGENTA)
        val bytes = encode(bitmap)
        bitmap.recycle()
        val rendered = ReaderImageMask(bytes).use { it.renderWatermark(Rect(2, 2, 8, 9)) }
        val filtered = BitmapFactory.decodeByteArray(rendered, 0, rendered.size)!!
        assertEquals(android.graphics.Color.MAGENTA, filtered.getPixel(8, 5))
        filtered.recycle()
    }

    @Test
    fun adjacentAndOverlappingMasksRenderFromOriginalCoordinates() {
        ReaderImageMask(original).use { image ->
            val rendered = image.render(listOf(1..3, 3..5, 6..6))
            val bitmap = BitmapFactory.decodeByteArray(rendered, 0, rendered.size)!!
            assertEquals(4, bitmap.height)
            assertEquals(pixel(4, 0), bitmap.getPixel(4, 0))
            assertEquals(pixel(4, 7), bitmap.getPixel(4, 1))
            bitmap.recycle()
        }
    }

    @Test
    fun learnedWholeSpamMatchesReencodedResizedAndSplitPagesButNotBlankArt() {
        val spam = Bitmap.createBitmap(480, 600, Bitmap.Config.ARGB_8888)
        for (y in 0 until 600) for (x in 0 until 480) {
            val seed = (x / 10 * 73856093) xor (y / 10 * 19349663)
            spam.setPixel(x, y, 0xff000000.toInt() or (seed and 0x00ffffff))
        }
        val raw = encode(spam)
        val hash = ReaderImageRules.hash(raw)
        try {
            ReaderImageRules.save(raw, 0, 600, JSONObject())
            val jpeg = ByteArrayOutputStream().also { spam.compress(Bitmap.CompressFormat.JPEG, 100, it) }.toByteArray()
            var hidden = false
            ReaderImageRules.open(onMatch = { hidden = it }) { jpeg.inputStream() }.close()
            assertTrue(hidden)
            val resized = Bitmap.createScaledBitmap(spam, 960, 1200, false)
            ReaderImageRules.open(onMatch = { hidden = it }) { encode(resized).inputStream() }.close()
            resized.recycle()
            assertTrue(hidden)
            for (atTop in listOf(true, false)) {
                val page = Bitmap.createBitmap(480, 900, Bitmap.Config.ARGB_8888)
                page.eraseColor(android.graphics.Color.YELLOW)
                for (y in 0 until 300) for (x in 0 until 480) {
                    page.setPixel(x, if (atTop) y else 600 + y, spam.getPixel(x, if (atTop) 300 + y else y))
                }
                val bytes = encode(page)
                val filtered = ReaderImageRules.open { bytes.inputStream() }.use { BitmapFactory.decodeStream(it) }!!
                assertEquals(600, filtered.height)
                assertEquals(android.graphics.Color.YELLOW, filtered.getPixel(20, 200))
                filtered.recycle()
                page.recycle()
            }
            val blank = Bitmap.createBitmap(
                480,
                600,
                Bitmap.Config.ARGB_8888,
            ).apply { eraseColor(android.graphics.Color.WHITE) }
            val bytes = encode(blank)
            blank.recycle()
            assertArrayEquals(bytes, ReaderImageRules.open { bytes.inputStream() }.use { it.readBytes() })
        } finally {
            spam.recycle()
            ReaderImageRules.remove(hash)
        }
    }
}
