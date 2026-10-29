package eu.kanade.tachiyomi.ui.reader.filter

import kotlin.math.abs

/** Chapter geometry is evidence, not an image label. Never use the tallest page as the reference. */
internal object ReaderChapterSizePolicy {
    data class Size(val width: Int, val height: Int)
    enum class Label { UNKNOWN, STORY, SPAM }
    data class Page(val index: Int, val size: Size?, val label: Label = Label.UNKNOWN)
    enum class Reason { DIFFERENT_WIDTH, SHORTER_HEIGHT }
    data class Assessment(val reference: Size?, val exclusions: Map<Int, Reason>, val inspected: Int, val total: Int)

    fun evaluate(pages: List<Page>): Assessment {
        val valid = pages.filter { it.size?.let { size -> size.width > 0 && size.height > 0 } == true }
        val eligible = valid.filter { it.label != Label.SPAM }
        val remaining = pages.count { it.label != Label.SPAM }
        fun unknown() = Assessment(null, emptyMap(), valid.size, pages.size)
        // Partial/error loads must not turn a handful of early pages into the chapter's normal size.
        if (eligible.size < 5 || eligible.size * 4 < remaining * 3) return unknown()
        val widths = cluster(eligible.sortedBy { requireNotNull(it.size).width }) { requireNotNull(it.size).width }
        val normal = widths.maxByOrNull { it.size } ?: return unknown()
        if (normal.size < 5 || normal.size * 4 < eligible.size * 3) return unknown()
        val width = normal[normal.size / 2].size!!.width
        val heights = cluster(normal.sortedBy { requireNotNull(it.size).height }, width = false) { it.size!!.height }
        val heightGroup = heights.maxByOrNull { it.size }
        val height = heightGroup?.takeIf { it.size >= 4 && it.size * 10 >= normal.size * 7 }
            ?.let { it[it.size / 2].size!!.height } ?: 0
        val reference = Size(width, height)
        val first = pages.minOfOrNull { it.index }
        val last = pages.maxOfOrNull { it.index }
        val excluded = linkedMapOf<Int, Reason>()
        for (page in valid) {
            if (page.label != Label.UNKNOWN) continue
            val size = requireNotNull(page.size)
            val differentWidth = abs(size.width - width) > maxOf(2, width / 100)
            val ratio = size.width.toDouble() / size.height
            // Recorded story pages include a shorter cover and three-page tail fragments.
            if (page.index == first && ratio in 0.55..0.85 && (height == 0 || size.height >= height * 0.5)) continue
            if (height == 0 && size.width.toDouble() / width in 1.9..2.1) continue
            if (height > 0) {
                // Geometry alone cannot distinguish a cover or a double spread from an advert.
                if (size.width.toDouble() / width in 1.7..2.3 && size.height.toDouble() / height in 0.85..1.15) continue
                val normalRatio = width.toDouble() / height
                if (differentWidth && abs(ratio / normalRatio - 1.0) <= 0.025) continue
            }
            if (differentWidth) {
                excluded[page.index] = Reason.DIFFERENT_WIDTH
            } else if (height > 0 && last != null && page.index < last - 2 && size.height < height * 0.85 - 4) {
                // Truncated final strips are normal in both print chapters and sliced webtoons.
                excluded[page.index] = Reason.SHORTER_HEIGHT
            }
        }
        return Assessment(reference, excluded, valid.size, pages.size)
    }

    private fun cluster(pages: List<Page>, width: Boolean = true, dimension: (Page) -> Int): List<List<Page>> {
        val groups = mutableListOf<MutableList<Page>>()
        for (page in pages) {
            val group = groups.lastOrNull()
            val centre = group?.let { dimension(it[it.size / 2]) }
            val tolerance = centre?.let { maxOf(if (width) 2 else 4, it / if (width) 100 else 50) }
            if (group != null && abs(dimension(page) - requireNotNull(centre)) <= requireNotNull(tolerance)) {
                group += page
            } else {
                groups += mutableListOf(page)
            }
        }
        return groups
    }
}
