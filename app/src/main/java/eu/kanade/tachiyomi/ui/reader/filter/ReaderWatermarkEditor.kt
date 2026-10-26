package eu.kanade.tachiyomi.ui.reader.filter

import kotlin.math.roundToInt

internal data class ImagePoint(val x: Double, val y: Double) {
    init { require(x.isFinite() && y.isFinite()) }
}

internal data class ImagePixels(val left: Int, val top: Int, val right: Int, val bottom: Int)

internal data class ImageBounds(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() })
        require(left <= right && top <= bottom)
    }

    fun pixels(width: Int, height: Int): ImagePixels? {
        if (width < 4 || height < 2 || width.toLong() * height > 40_000_000) return null
        val l = left.roundToInt().coerceIn(1, width - 1)
        val r = right.roundToInt().coerceIn(1, width - 1)
        val t = top.roundToInt().coerceIn(0, height)
        val b = bottom.roundToInt().coerceIn(0, height)
        if (r - l < 2 || b - t < 2 || (r - l).toLong() * (b - t) > 1_000_000) return null
        return ImagePixels(l, t, r, b)
    }

    fun shifted(dx: Double, dy: Double, width: Int, height: Int): ImageBounds {
        val x = dx.coerceIn(1 - left, width - 1 - right)
        val y = dy.coerceIn(-top, height - bottom)
        return ImageBounds(left + x, top + y, right + x, bottom + y)
    }

    companion object {
        fun between(a: ImagePoint, b: ImagePoint) = ImageBounds(
            minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y),
        )
    }
}

/** Drawing and hit testing share the source dimensions, never the sampled bitmap's aspect ratio. */
internal class ReaderImageTransform(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val viewportWidth: Double,
    val viewportHeight: Double,
    padding: Double,
    zoom: Double,
    pan: ImagePoint,
) {
    init {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(viewportWidth.isFinite() && viewportHeight.isFinite() && viewportWidth > 0 && viewportHeight > 0)
        require(padding.isFinite() && padding >= 0 && zoom.isFinite() && zoom > 0)
    }

    val scale = minOf(
        (viewportWidth - 2 * padding).coerceAtLeast(1.0) / sourceWidth,
        (viewportHeight - 2 * padding).coerceAtLeast(1.0) / sourceHeight,
    ) * zoom
    val drawnWidth = sourceWidth * scale
    val drawnHeight = sourceHeight * scale
    val origin = ImagePoint((viewportWidth - drawnWidth) / 2 + pan.x, (viewportHeight - drawnHeight) / 2 + pan.y)
    val maxPanX = ((drawnWidth - viewportWidth) / 2 + padding).coerceAtLeast(0.0)
    val maxPanY = ((drawnHeight - viewportHeight) / 2 + padding).coerceAtLeast(0.0)

    fun toViewport(point: ImagePoint) = ImagePoint(point.x * scale + origin.x, point.y * scale + origin.y)
    fun toSource(point: ImagePoint) = ImagePoint((point.x - origin.x) / scale, (point.y - origin.y) / scale)

    fun watermarkPoint(point: ImagePoint): ImagePoint = toSource(point).let {
        ImagePoint(it.x.coerceIn(1.0, (sourceWidth - 1).coerceAtLeast(1).toDouble()),
            it.y.coerceIn(0.0, sourceHeight.toDouble()))
    }
}

/** Session-only state. No renderer, rule persistence or learned detection lives here. */
internal data class ReaderWatermarkEditor(
    val imageKey: String,
    val selection: ImageBounds? = null,
    val revision: Long = 0,
    val phase: Phase = Phase.IDLE,
    val anchor: ImagePoint? = null,
    val draft: ImageBounds? = null,
    val previous: ImageBounds? = null,
    val canUndo: Boolean = false,
    val serial: Long = 0,
    val pending: Preview? = null,
    val ready: Preview? = null,
    val closed: Boolean = false,
) {
    enum class Phase { IDLE, PENDING, DRAWING, NAVIGATING }
    data class Preview(val imageKey: String, val revision: Long, val serial: Long, val area: ImagePixels)
    val gesturing get() = phase != Phase.IDLE
    val displayedSelection get() = draft ?: selection

    fun begin(point: ImagePoint) = if (closed) this else copy(phase = Phase.PENDING, anchor = point, draft = null)

    fun move(point: ImagePoint, pastSlop: Boolean): ReaderWatermarkEditor = when {
        closed || phase == Phase.NAVIGATING || phase == Phase.IDLE -> this
        phase == Phase.PENDING && !pastSlop -> this
        else -> copy(phase = Phase.DRAWING, draft = ImageBounds.between(requireNotNull(anchor), point))
    }

    fun navigate() = if (closed) this else copy(phase = Phase.NAVIGATING, anchor = null, draft = null)

    fun finish(): ReaderWatermarkEditor {
        val next = draft
        val idle = copy(phase = Phase.IDLE, anchor = null, draft = null)
        return if (phase == Phase.DRAWING && next != null && next.right > next.left && next.bottom > next.top) {
            idle.select(next)
        } else idle
    }

    fun cancelGesture() = copy(phase = Phase.IDLE, anchor = null, draft = null)

    fun select(area: ImageBounds?): ReaderWatermarkEditor = if (closed || area == selection) this else copy(
        selection = area, previous = selection, canUndo = true, revision = revision + 1,
        serial = serial + 1, pending = null, ready = null,
    )

    fun undo() = if (closed || gesturing || !canUndo) this else copy(
        selection = previous, previous = null, canUndo = false, revision = revision + 1,
        serial = serial + 1, pending = null, ready = null,
    )

    fun requestPreview(width: Int, height: Int): ReaderWatermarkEditor {
        val area = selection?.pixels(width, height) ?: return this
        if (closed || gesturing || imageKey.isEmpty()) return this
        val next = serial + 1
        return copy(serial = next, pending = Preview(imageKey, revision, next, area), ready = null)
    }

    fun completePreview(token: Preview) = if (accepts(token)) copy(pending = null, ready = token) else this
    fun failPreview(token: Preview) = if (accepts(token)) copy(pending = null, ready = null) else this
    fun cancelPreview() = copy(serial = serial + 1, pending = null, ready = null)
    fun close() = cancelGesture().cancelPreview().copy(closed = true)

    fun canApply(width: Int, height: Int): Boolean = !closed && !gesturing && pending == null &&
        (ready?.let { it.imageKey == imageKey && it.revision == revision && it.area == selection?.pixels(width, height) }
            == true)

    private fun accepts(token: Preview) = !closed && pending == token && token.imageKey == imageKey &&
        token.revision == revision && token.serial == serial
}
