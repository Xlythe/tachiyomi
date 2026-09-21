package eu.kanade.tachiyomi.ui.reader.filter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Decode strips so hashing a long banner does not require a full-page bitmap. */
internal class ReaderImageMask(private val bytes: ByteArray) : Closeable {
    private val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it)
    }
    val width = bounds.outWidth
    val height = bounds.outHeight

    init { require(width > 0 && height > 0) { "Unsupported image" } }

    @Suppress("DEPRECATION")
    private val decoder = runCatching { BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false) }.getOrNull()
    private var fallback: Bitmap? = null

    private fun strips(top: Int, bottom: Int, action: (Bitmap, Int) -> Unit) {
        for (row in top until bottom step 64) {
            val count = minOf(64, bottom - row)
            val bitmap = if (decoder != null) {
                decoder.decodeRegion(Rect(0, row, width, row + count), BitmapFactory.Options())
                    ?: error("Cannot decode image region")
            } else {
                val full = fallback ?: (
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: error("Cannot decode image")
                    ).also { fallback = it }
                Bitmap.createBitmap(full, 0, row, width, count)
            }
            try { action(bitmap, row) } finally { if (bitmap !== fallback) bitmap.recycle() }
        }
    }

    fun hashBand(top: Int, bottom: Int): String {
        require(top >= 0 && top < bottom && bottom <= height)
        val digest = MessageDigest.getInstance("SHA-256")
        strips(top, bottom) { bitmap, _ ->
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val packed = ByteBuffer.allocate(pixels.size * 4)
            packed.asIntBuffer().put(pixels)
            digest.update(packed.array())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Prefer a detailed row over blank margins for the cheap exact-match prefilter. */
    fun probeOffset(top: Int, bottom: Int): Int {
        var bestOffset = 0
        var bestScore = -1L
        for (offset in (0 until 16).map { (bottom - top - 1) * it / 15 }.distinct()) {
            strips(top + offset, top + offset + 1) { bitmap, _ ->
                val pixels = IntArray(width)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, 1)
                var score = 0L
                for (x in 1 until width) score += Integer.bitCount(pixels[x] xor pixels[x - 1])
                if (score > bestScore) {
                    bestScore = score
                    bestOffset = offset
                }
            }
        }
        return bestOffset
    }

    fun render(top: Int, bottom: Int): ByteArray {
        require(top >= 0 && top < bottom && bottom <= height)
        val retained = height - (bottom - top)
        val result = Bitmap.createBitmap(width, retained.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(result)
            strips(0, top) { bitmap, row -> canvas.drawBitmap(bitmap, 0f, row.toFloat(), null) }
            strips(
                bottom,
                height,
            ) { bitmap, row -> canvas.drawBitmap(bitmap, 0f, (row - bottom + top).toFloat(), null) }
            return ByteArrayOutputStream().use {
                check(result.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            }
        } finally {
            result.recycle()
        }
    }

    override fun close() {
        decoder?.recycle()
        fallback?.recycle()
    }
}
