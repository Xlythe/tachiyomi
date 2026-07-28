package eu.kanade.tachiyomi.util

import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

internal fun shouldContinueDownloadingUnreadChapters(
    chapters: List<Chapter>,
    isAvailableOffline: (Chapter) -> Boolean,
): Boolean {
    val unreadChapters = chapters.filterNot { it.read }
    val chaptersToCheck = unreadChapters.ifEmpty { chapters }
    return chaptersToCheck.isNotEmpty() && chaptersToCheck.all(isAvailableOffline)
}

fun List<Chapter>.shouldContinueDownloadingUnreadChapters(
    manga: Manga,
    downloadManager: DownloadManager,
): Boolean = shouldContinueDownloadingUnreadChapters(this) { chapter ->
    val activeDownload = downloadManager.getQueuedDownloadOrNull(chapter.id)
    activeDownload?.status == Download.State.QUEUE ||
        activeDownload?.status == Download.State.DOWNLOADING ||
        downloadManager.isChapterDownloaded(
            chapter.name,
            chapter.scanlator,
            manga.title,
            manga.source,
        )
}
