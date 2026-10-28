package eu.kanade.tachiyomi.ui.reader

/** Original page identities are never renumbered when image rules remove visible content. */
internal object ReaderChapterCompletion {
    data class Page(val index: Int, val hidden: Boolean, val ready: Boolean)

    fun terminalIndex(pages: List<Page>): Int? {
        val last = pages.lastOrNull() ?: return null
        val visible = pages.lastOrNull { !it.hidden }
        if (visible == null) return last.index.takeIf { pages.all { it.ready } }
        return visible.index.takeIf {
            visible.ready && pages.dropWhile { it.index <= visible.index }.all { it.hidden && it.ready }
        }
    }

    fun reachesTerminal(terminalAdapterPosition: Int, endVisibleAdapterPosition: Int): Boolean =
        terminalAdapterPosition >= 0 && endVisibleAdapterPosition >= terminalAdapterPosition

    fun completes(pages: List<Page>, endVisibleIndex: Int): Boolean =
        terminalIndex(pages)?.let { endVisibleIndex >= it } == true
}
