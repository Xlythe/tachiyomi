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
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

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

    fun signature(top: Int = 0, bottom: Int = height): ReaderImageSignature {
        val rows = ((bottom - top) * 48f / width).roundToInt().coerceAtLeast(1)
        require(rows <= 4096) { "Image too long for similarity matching" }
        val small = Bitmap.createBitmap(48, rows, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(small)
            canvas.scale(48f / width, rows.toFloat() / (bottom - top))
            strips(top, bottom) { bitmap, row -> canvas.drawBitmap(bitmap, 0f, (row - top).toFloat(), null) }
            val pixels = IntArray(48 * rows)
            small.getPixels(pixels, 0, 48, 0, 0, 48, rows)
            return ReaderImageSignature(pixels)
        } finally { small.recycle() }
    }

    fun contrastEdges(): List<Float> {
        if (height > 100_000) return emptyList()
        val columns = minOf(width, 64)
        val pixels = IntArray(columns * height)
        strips(0, height) { bitmap, row ->
            for (y in 0 until bitmap.height) for (x in 0 until columns) {
                pixels[(row + y) * columns + x] = bitmap.getPixel(x * width / columns, y)
            }
        }
        return ReaderImageSignature.contrastEdges(pixels, columns).map { it.toFloat() / height }
    }

    fun render(top: Int, bottom: Int): ByteArray = render(listOf(top until bottom))

    fun render(removals: List<IntRange>): ByteArray {
        require(width.toLong() * height <= 40_000_000) { "Image too large to filter safely" }
        val bands = ReaderImageSignature.merge(removals)
        require(bands.all { it.first >= 0 && it.first <= it.last && it.last < height })
        val retained = height - bands.sumOf { it.last - it.first + 1 }
        val result = Bitmap.createBitmap(width, retained.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(result)
            var start = 0
            var removed = 0
            for (band in bands + listOf(height until height)) {
                strips(
                    start,
                    band.first,
                ) { bitmap, row -> canvas.drawBitmap(bitmap, 0f, (row - removed).toFloat(), null) }
                removed += band.last - band.first + 1
                start = band.last + 1
            }
            return ByteArrayOutputStream().use {
                check(result.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            }
        } finally {
            result.recycle()
        }
    }

    /** Optional local interpolation, not recovery of the unknown pixels under an overlay. */
    fun renderWatermark(area: Rect): ByteArray {
        require(width.toLong() * height <= 40_000_000) { "Image too large to filter safely" }
        require(area.left > 0 && area.top >= 0 && area.right < width && area.bottom <= height)
        require(area.width() > 1 && area.height() > 1 && area.width().toLong() * area.height() <= 1_000_000)
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(result)
            strips(0, height) { bitmap, row -> canvas.drawBitmap(bitmap, 0f, row.toFloat(), null) }
            val cx = (area.left + area.right - 1) / 2f
            val cy = (area.top + area.bottom - 1) / 2f
            val rx = area.width() / 2f
            val ry = area.height() / 2f
            for (y in area.top until area.bottom) {
                val dy = (y - cy) / ry
                val span = rx * sqrt((1f - dy * dy).coerceAtLeast(0f))
                val left = ceil(cx - span).toInt().coerceAtLeast(area.left)
                val right = floor(cx + span).toInt().coerceAtMost(area.right - 1)
                val a = result.getPixel(left - 1, y)
                val b = result.getPixel(right + 1, y)
                for (x in left..right) {
                    val t = (x - left + 1f) / (right - left + 2f)
                    var colour = 0
                    for (shift in 0..24 step 8) {
                        val v = ((a ushr shift) and 255) * (1 - t) + ((b ushr shift) and 255) * t
                        colour = colour or (v.roundToInt() shl shift)
                    }
                    result.setPixel(x, y, colour)
                }
            }
            return ByteArrayOutputStream().use {
                check(result.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            }
        } finally { result.recycle() }
    }

    override fun close() {
        decoder?.recycle()
        fallback?.recycle()
    }
}
