package eu.kanade.tachiyomi.ui.reader.filter

import java.util.NavigableSet

/** Pages existing metadata hierarchically; never clones a flattened library or opens chapter files. */
internal object ReaderDownloadIndexPage {
    fun page(
        sources: Collection<Long>,
        mangas: (Long) -> Collection<String>,
        uri: (Long, String) -> String?,
        chapters: (Long, String) -> NavigableSet<String>,
        after: ReaderDownloadTraversal.Key?,
        limit: Int,
    ): List<ReaderDownloadTraversal.Chapter> {
        require(limit in 1..8)
        val result = mutableListOf<ReaderDownloadTraversal.Chapter>()
        for (source in sources.sorted()) {
            if (after != null && source < after.source) continue
            for (manga in mangas(source).sorted()) {
                if (after != null && source == after.source && manga < after.manga) continue
                val mangaUri = uri(source, manga) ?: continue
                val names = chapters(source, manga)
                val remaining = if (after != null && source == after.source && manga == after.manga) {
                    names.tailSet(after.chapter, false)
                } else {
                    names
                }
                for (chapter in remaining) {
                    result += ReaderDownloadTraversal.Chapter(
                        ReaderDownloadTraversal.Key(source, manga, chapter),
                        mangaUri,
                    )
                    if (result.size == limit) return result
                }
            }
        }
        return result
    }
}
