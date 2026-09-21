package eu.kanade.tachiyomi.ui.reader.filter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
}
