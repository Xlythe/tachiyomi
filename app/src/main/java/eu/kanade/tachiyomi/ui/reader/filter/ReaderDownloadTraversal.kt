package eu.kanade.tachiyomi.ui.reader.filter

/** Durable ordered metadata bookmark; batches contain no page bytes and never exceed eight chapters. */
internal class ReaderDownloadTraversal {
    data class Key(val source: Long, val manga: String, val chapter: String) : Comparable<Key> {
        override fun compareTo(other: Key): Int = compareValuesBy(this, other, Key::source, Key::manga, Key::chapter)
    }
    data class Chapter(val key: Key, val mangaUri: String)
    data class Token(val version: String, val root: String)
    data class State(
        val token: Token = Token("", ""),
        val after: Key? = null,
        val pending: List<Chapter> = emptyList(),
        val done: Boolean = false,
        val checkedChapters: Long = 0,
        val unknownChapters: Long = 0,
    )
    private var state = State()
    fun snapshot(): State = state.copy(pending = state.pending.toList())
    fun restore(saved: State) {
        require(saved.checkedChapters >= 0 && saved.unknownChapters >= 0)
        require(saved.pending.size <= 8 && (!saved.done || saved.pending.isEmpty()))
        require(saved.pending.zipWithNext().all { (a, b) -> a.key < b.key })
        require(saved.pending.all { saved.after == null || it.key > saved.after })
        state = saved.copy(pending = saved.pending.toList())
    }
    fun begin(token: Token): State {
        if (state.token != token) state = State(token)
        return snapshot()
    }
    fun current(token: Token) = state.token == token
    fun offer(token: Token, chapters: List<Chapter>): Boolean {
        if (!current(token) || state.done || state.pending.isNotEmpty()) return false
        require(chapters.size <= 8)
        require(chapters.zipWithNext().all { (a, b) -> a.key < b.key })
        require(chapters.all { state.after == null || it.key > requireNotNull(state.after) })
        state = state.copy(pending = chapters.toList(), done = chapters.isEmpty())
        return true
    }

    /** A removed/failed chapter may be passed without ever recording a clean page decision. */
    fun finish(token: Token, chapter: Chapter, checked: Boolean = true): Boolean {
        if (!current(token) || state.pending.firstOrNull() != chapter) return false
        state = state.copy(
            after = chapter.key, pending = state.pending.drop(1),
            checkedChapters = state.checkedChapters + if (checked) 1 else 0,
            unknownChapters = state.unknownChapters + if (checked) 0 else 1,
        )
        return true
    }
}
