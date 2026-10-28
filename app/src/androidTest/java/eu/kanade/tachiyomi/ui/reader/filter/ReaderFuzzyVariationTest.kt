package eu.kanade.tachiyomi.ui.reader.filter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Synthetic thin-footer probes. No private corpus is embedded and failures must remain visible. */
class ReaderFuzzyVariationTest {
    private val owned = mutableSetOf<String>()
    @After fun cleanup() { owned.forEach { ReaderImageRules.remove(it) } }
    private fun page(story: Int, changedFooter: Boolean = false): Bitmap {
        val result = Bitmap.createBitmap(600, 1596, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(if (story == 1) Color.rgb(170, 195, 210) else Color.rgb(205, 180, 145))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; strokeWidth = 3f }
        for (y in 40 until 1450 step 90) canvas.drawLine(35f, y.toFloat(), 565f, (y + 23 * story).toFloat(), paint)
        paint.color = Color.rgb(20, 5, 45); canvas.drawRect(0f, 1500f, 600f, 1596f, paint)
        paint.color = Color.rgb(80, 230, 130); paint.strokeWidth = 5f
        canvas.drawLine(0f, 1503f, 600f, 1503f, paint)
        paint.color = Color.WHITE; paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); paint.textSize = 29f
        canvas.drawText(if (changedFooter) "A STORY PANEL / DIFFERENT CONTENT" else "FUZZY FIXTURE / READ THE ORIGINAL", 12f, 1541f, paint)
        paint.textSize = 23f; canvas.drawText(if (changedFooter) "Keep these pixels visible" else "A saved footer repeated in another chapter", 12f, 1579f, paint)
        return result
    }
    private fun encode(bitmap: Bitmap, jpeg: Boolean = false): ByteArray = ByteArrayOutputStream().also {
        check(bitmap.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, if (jpeg) 90 else 100, it))
    }.toByteArray()
    private fun saveFooter() {
        val bitmap = page(1)
        val raw = encode(bitmap) + UUID.randomUUID().toString().toByteArray(); bitmap.recycle()
        owned += ReaderImageRules.hash(raw)
        ReaderImageRules.save(raw, 1500, 1596, JSONObject())
    }
    private suspend fun renderedHeight(raw: ByteArray): Int {
        assertTrue("A completed supported image must finish its background check", ReaderImageRules.precheckDownloaded(raw))
        val page = ReaderPage(0, stream = { raw.inputStream() })
        val output = page.stream!!().use { it.readBytes() }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(output, 0, output.size))
        return bitmap.height.also { bitmap.recycle() }
    }
    @Test fun unchangedThinFooterMatchesOnDifferentStoryPixels() = runBlocking {
        saveFooter(); val bitmap = page(2); val raw = encode(bitmap); bitmap.recycle()
        assertEquals(1500, renderedHeight(raw))
    }
    @Test fun scaledThinFooterIsRecognizedDuringDownloadPrecheck() = runBlocking {
        saveFooter(); val original = page(2)
        val scaled = Bitmap.createScaledBitmap(original, 720, 1915, true); original.recycle()
        val raw = encode(scaled); scaled.recycle()
        assertEquals("Resizing must not expose an explicitly saved footer", 1800, renderedHeight(raw))
    }
    @Test fun jpegReencodedThinFooterIsRecognizedDuringDownloadPrecheck() = runBlocking {
        saveFooter(); val bitmap = page(2); val raw = encode(bitmap, jpeg = true); bitmap.recycle()
        assertEquals("JPEG re-encoding must not expose an explicitly saved footer", 1500, renderedHeight(raw))
    }
    @Test fun addingARuleInvalidatesAPreviouslyCleanDownloadedResult() = runBlocking {
        val bitmap = page(2); val raw = encode(bitmap); bitmap.recycle()
        assertEquals(1596, renderedHeight(raw)); saveFooter()
        assertEquals(1500, renderedHeight(raw))
    }
    @Test fun resizedJpegWholePageRemainsRecognizableWithoutAnyCrop() = runBlocking {
        val source = page(1); val sample = encode(source) + UUID.randomUUID().toString().toByteArray()
        owned += ReaderImageRules.hash(sample); ReaderImageRules.save(sample, 0, 1596, JSONObject())
        val scaled = Bitmap.createScaledBitmap(source, 720, 1915, true); source.recycle()
        val target = encode(scaled, jpeg = true); scaled.recycle()
        assertEquals(1, renderedHeight(target))
    }
    @Test fun samePaletteDoesNotMakeDifferentWholePageArtworkSpam() = runBlocking {
        val source = page(1); val sample = encode(source) + UUID.randomUUID().toString().toByteArray(); source.recycle()
        owned += ReaderImageRules.hash(sample); ReaderImageRules.save(sample, 0, 1596, JSONObject())
        val other = page(2, changedFooter = true); val raw = encode(other); other.recycle()
        assertTrue(ReaderImageRules.precheckDownloaded(raw))
        val page = ReaderPage(0, stream = { raw.inputStream() })
        assertArrayEquals(raw, page.stream!!().use { it.readBytes() })
    }
    @Test fun similarColorsWithDifferentContentStayVisible() = runBlocking {
        saveFooter(); val bitmap = page(2, changedFooter = true); val raw = encode(bitmap); bitmap.recycle()
        assertTrue(ReaderImageRules.precheckDownloaded(raw))
        val page = ReaderPage(0, stream = { raw.inputStream() })
        assertArrayEquals(raw, page.stream!!().use { it.readBytes() })
    }
}
