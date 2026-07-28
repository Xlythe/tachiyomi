package eu.kanade.tachiyomi.util

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class ChapterDownloadContinuationTest {

    @Test
    fun `continues only when every existing unread chapter is available offline`() {
        val downloaded = chapter(id = 1)
        val queued = chapter(id = 2)

        shouldContinueDownloadingUnreadChapters(listOf(downloaded, queued)) {
            it.id in setOf(1L, 2L)
        } shouldBe true

        shouldContinueDownloadingUnreadChapters(listOf(downloaded, queued)) {
            it.id == 1L
        } shouldBe false
    }

    @Test
    fun `read chapters do not block continuation while unread chapters exist`() {
        val downloaded = chapter(id = 1)
        val missingButRead = chapter(id = 2, read = true)

        shouldContinueDownloadingUnreadChapters(listOf(downloaded, missingButRead)) {
            it.id == 1L
        } shouldBe true
    }

    @Test
    fun `an entirely read series continues only when every chapter remains offline`() {
        val first = chapter(id = 1, read = true)
        val second = chapter(id = 2, read = true)

        shouldContinueDownloadingUnreadChapters(listOf(first, second)) { true } shouldBe true
        shouldContinueDownloadingUnreadChapters(listOf(first, second)) { it.id == 1L } shouldBe false
        shouldContinueDownloadingUnreadChapters(emptyList()) { true } shouldBe false
    }

    private fun chapter(id: Long, read: Boolean = false) = Chapter.create().copy(id = id, read = read)
}
