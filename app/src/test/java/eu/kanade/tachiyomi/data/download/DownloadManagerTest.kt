package eu.kanade.tachiyomi.data.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DownloadManagerTest {
    @Test
    fun `entire manga directory can be deleted when every entry is selected`() {
        shouldDeleteEntireMangaDownload(
            mangaEntryNames = listOf("Chapter 1.cbz", "Chapter 2.cbz"),
            selectedEntryNames = setOf("Chapter 1.cbz", "Chapter 2.cbz"),
        ) shouldBe true
    }

    @Test
    fun `manga directory is retained when an unselected download exists`() {
        shouldDeleteEntireMangaDownload(
            mangaEntryNames = listOf("Chapter 1.cbz", "Chapter 2.cbz"),
            selectedEntryNames = setOf("Chapter 1.cbz"),
        ) shouldBe false
    }

    @Test
    fun `empty manga directory is handled by normal cleanup`() {
        shouldDeleteEntireMangaDownload(
            mangaEntryNames = emptyList(),
            selectedEntryNames = setOf("Chapter 1.cbz"),
        ) shouldBe false
    }
}
