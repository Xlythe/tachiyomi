package eu.kanade.tachiyomi.ui.reader.filter

import kotlin.math.abs
import kotlin.math.roundToInt

/** One drag's unsnapped position survives capture, so pulling away can overcome the attraction. */
internal class ReaderImageSnap(
    initial: Float,
    private val edges: List<Float>,
    private val capture: Float,
    private val release: Float,
    private val minimum: Float = 0f,
    private val maximum: Float = 1f,
) {
    private var raw = initial
    private var locked: Float? = null
    var position = initial
        private set

    init { require(capture >= 0f && release > capture && minimum <= maximum) }

    fun moveBy(delta: Float): Float {
        raw = (raw + delta).coerceIn(minimum, maximum)
        val boundary = locked
        if (boundary != null && abs(raw - boundary) > release) locked = null
        if (locked == null) {
            locked = edges.filter { it in minimum..maximum }.minByOrNull { abs(it - raw) }
                ?.takeIf { abs(it - raw) <= capture }
        }
        position = locked ?: raw
        return position
    }

    // Finger-up has no geometry calculation, snap or rounding step.
    fun finish(): Float = position
}

/** Small, colour-preserving signatures. Never classify by a perceptual hash alone. */
internal class ReaderImageSignature(val pixels: IntArray, val width: Int = 48) {
    val height = pixels.size / width

    fun slice(top: Int, bottom: Int) = ReaderImageSignature(pixels.copyOfRange(top * width, bottom * width), width)

    /** Require texture and agreement across the complete proposed removal, including its edges. */
    fun agrees(other: ReaderImageSignature, start: Int, otherStart: Int, rows: Int): Boolean {
        if (rows < 12 || start < 0 || otherStart < 0 || start + rows > height || otherStart + rows > other.height) {
            return false
        }
        var error = 0L
        var detail = 0L
        var bad = 0
        for (y in 0 until rows) {
            var rowError = 0L
            for (x in 0 until width) {
                val a = pixels[(start + y) * width + x]
                val b = other.pixels[(otherStart + y) * width + x]
                if ((a ushr 24) != (b ushr 24)) return false
                for (shift in 0..16 step 8) {
                    val value = (a shr shift) and 255
                    rowError += abs(value - ((b shr shift) and 255))
                    if (x > 0) detail += abs(value - ((pixels[(start + y) * width + x - 1] shr shift) and 255))
                }
                if (colourError(a, b) > 36) bad++
            }
            // A close average cannot excuse a strip of unrelated story at a boundary.
            if (rowError > width * 3 * 9) return false
            error += rowError
        }
        return error <= rows.toLong() * width * 3 * 5 && bad <= rows * width / 50 &&
            detail >= rows.toLong() * (width - 1) * 3 * 7
    }

    /** Only page edges may match a truncated template; interior guesses are never searched. */
    fun edgeMatches(sample: ReaderImageSignature): List<IntRange> {
        val result = mutableListOf<IntRange>()
        val minimum = maxOf(12, (sample.height * 0.35f).roundToInt())
        for (rows in minOf(height, sample.height) downTo minimum) {
            if (agrees(sample, 0, sample.height - rows, rows)) {
                result += 0 until rows
                break
            }
        }
        for (rows in minOf(height, sample.height) downTo minimum) {
            if (agrees(sample, height - rows, 0, rows)) {
                result += height - rows until height
                break
            }
        }
        return result
    }

    companion object {
        fun refineBoundary(value: Int, height: Int, normalizedRows: Int, edges: List<Int>): Int? {
            if (value == 0 || value == height) return value
            val tolerance = height.toFloat() / normalizedRows * 0.75f
            // Ambiguous boundaries and seamless transitions stay visible rather than guessing a cut.
            return edges.distinct().filter { abs(it - value) <= tolerance }.singleOrNull()
        }

        private fun colourError(a: Int, b: Int) = abs((a and 255) - (b and 255)) +
            abs(((a shr 8) and 255) - ((b shr 8) and 255)) + abs(((a shr 16) and 255) - ((b shr 16) and 255))

        /** Wide, sharp transitions only: isolated lettering and gradual shading do not attract handles. */
        fun contrastEdges(pixels: IntArray, width: Int): List<Int> {
            val height = pixels.size / width
            val scores = IntArray(height)
            for (y in 1 until height) {
                var changed = 0
                var total = 0
                for (x in 0 until width) {
                    val difference = colourError(pixels[y * width + x], pixels[(y - 1) * width + x]) / 3
                    total += difference
                    if (difference >= 24) changed++
                }
                if (changed * 100 >= width * 65 && total >= width * 24) scores[y] = total / width
            }
            return (1 until height).filter { y ->
                scores[y] > 0 && scores[y] >= scores[y - 1] && (y + 1 == height || scores[y] > scores[y + 1])
            }
        }

        fun merge(bands: List<IntRange>): List<IntRange> {
            val merged = mutableListOf<IntRange>()
            for (band in bands.sortedBy { it.first }) {
                val last = merged.lastOrNull()
                if (last != null && band.first <= last.last + 1) {
                    merged[merged.lastIndex] = last.first..maxOf(last.last, band.last)
                } else {
                    merged += band
                }
            }
            return merged
        }
    }
}
