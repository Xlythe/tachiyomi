package eu.kanade.tachiyomi.ui.reader.filter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderDownloadTraversalTest {
    private val token = ReaderDownloadTraversal.Token("v1", "root")
    private fun chapter(i: Int) = ReaderDownloadTraversal.Chapter(
        ReaderDownloadTraversal.Key(1, "series", i.toString().padStart(5, '0')),
        "file:///manga",
    )
    private fun page(
        rows: List<ReaderDownloadTraversal.Chapter>,
        after: ReaderDownloadTraversal.Key?,
    ): List<ReaderDownloadTraversal.Chapter> {
        val sources = rows.groupBy { it.key.source }.mapValues { (_, rows) -> rows.groupBy { it.key.manga } }
        return ReaderDownloadIndexPage.page(
            sources.keys,
            mangas = { sources.getValue(it).keys },
            uri = { source, manga -> sources.getValue(source).getValue(manga).first().mangaUri },
            chapters = { source, manga ->
                java.util.TreeSet(
                    sources.getValue(source).getValue(manga).map { it.key.chapter },
                )
            },
            after,
            8,
        )
    }

    @Test fun moreThan128ChaptersFinishExactlyOnceWithoutAnUnboundedHistory() {
        val q = ReaderDownloadTraversal()
        val rows = (1..521).map(::chapter)
        val seen = mutableListOf<Int>()
        while (true) {
            val state = q.begin(token)
            if (state.done) break
            if (state.pending.isEmpty()) {
                q.offer(token, page(rows, state.after))
            } else {
                assertTrue(state.pending.size <= 8)
                val next = state.pending.first()
                seen += next.key.chapter.toInt()
                q.finish(token, next)
            }
        }
        assertEquals((1..521).toList(), seen)
        assertEquals(521L, q.snapshot().checkedChapters)
        assertEquals(0L, q.snapshot().unknownChapters)
        repeat(10) {
            assertTrue(q.begin(token).done)
            assertFalse(q.offer(token, rows.take(8)))
        }
    }

    @Test fun restartKeepsPendingBatchAndOnlyAcknowledgedBookmarkProgress() {
        val q = ReaderDownloadTraversal()
        q.begin(token)
        q.offer(token, (1..8).map(::chapter))
        q.finish(token, chapter(1))
        q.finish(token, chapter(2))
        val restarted = ReaderDownloadTraversal().apply { restore(q.snapshot()) }
        assertEquals(chapter(2).key, restarted.begin(token).after)
        assertEquals(chapter(3), restarted.begin(token).pending.first())
    }

    @Test fun newRulesResetThePassAndStaleBatchOrCompletionCannotAdvanceIt() {
        val q = ReaderDownloadTraversal()
        q.begin(token)
        q.offer(token, listOf(chapter(1)))
        q.finish(token, chapter(1))
        val updated = token.copy(version = "v2")
        assertEquals(null, q.begin(updated).after)
        assertFalse(q.offer(token, listOf(chapter(2))))
        assertFalse(q.finish(token, chapter(2)))
        q.offer(updated, listOf(chapter(1)))
        assertTrue(q.finish(updated, chapter(1)))
    }

    @Test fun deletionAndIndexReorderingDoNotShiftTheStableBookmarkOrRestartACompletedPass() {
        val q = ReaderDownloadTraversal()
        val rows = (1..40).map(::chapter)
        q.begin(token)
        q.offer(token, page(rows, null))
        (1..8).forEach { q.finish(token, chapter(it)) }
        val changed = rows.filter { it != chapter(8) && it != chapter(9) }.reversed()
        assertTrue(q.offer(token, page(changed, q.snapshot().after)))
        assertEquals(chapter(10), q.snapshot().pending.first())
    }

    @Test fun removedOrFailedPendingChaptersCanYieldWithoutClaimingCleanAndLaterChaptersStillAdvance() {
        val q = ReaderDownloadTraversal()
        q.begin(token)
        q.offer(token, (1..8).map(::chapter))
        assertTrue(
            q.finish(token, chapter(1), checked = false),
        ) // Removed or delegated to bounded retry history, no clean verdict.
        assertEquals(1L, q.snapshot().unknownChapters)
        assertEquals(0L, q.snapshot().checkedChapters)
        assertEquals(chapter(2), q.snapshot().pending.first())
        assertFalse(q.finish(token, chapter(8)))
    }

    @Test fun storageRootChangesStartANewPassAndRejectOldRootCompletions() {
        val q = ReaderDownloadTraversal()
        q.begin(token)
        q.offer(token, listOf(chapter(1)))
        q.begin(token.copy(root = "other"))
        assertFalse(q.finish(token, chapter(1)))
    }

    @Test fun aProtectedTraversalChapterCannotBeEvictedByMoreThan128NewDownloads() {
        val q = ReaderDownloadSchedule()
        val ticket = q.traversalTicket("old", true, "old")
        repeat(300) { q.committed("new$it", true, "new$it") }
        assertEquals(128, q.snapshot().size)
        assertEquals(ReaderDownloadSchedule.Progress.WAITING, q.progress(ticket, "v1"))
        q.releaseTraversalTicket("old")
        q.committed("last", true, "last")
        assertEquals(ReaderDownloadSchedule.Progress.SUPERSEDED, q.progress(ticket, "v1"))
    }

    @Test fun expiredBackoffCannotStarveLaterWorkWhenFailedChecksThemselvesAreSlow() {
        val q = ReaderDownloadSchedule()
        q.committed("bad", true, "bad")
        q.committed("good", true, "good")
        q.failed(q.next("v1", 0)!!, 0)
        assertEquals("good", q.next("v1", 999_999)!!.uri)
    }

    @Test fun completedBookmarkSurvivesRestartWithoutRequestingAnotherIndexPage() {
        val q = ReaderDownloadTraversal()
        q.begin(token)
        q.offer(token, emptyList())
        val restarted = ReaderDownloadTraversal().apply { restore(q.snapshot()) }
        assertTrue(restarted.begin(token).done)
        assertFalse(restarted.offer(token, listOf(chapter(1))))
    }

    @Test fun metadataPagerCrossesSourcesAndSeriesInStableOrderRegardlessOfInsertionOrder() {
        val a = ReaderDownloadTraversal.Chapter(ReaderDownloadTraversal.Key(-2, "z", "a"), "file:///one")
        val b = ReaderDownloadTraversal.Chapter(ReaderDownloadTraversal.Key(4, "a", "x"), "file:///two")
        val c = ReaderDownloadTraversal.Chapter(ReaderDownloadTraversal.Key(4, "b", "a"), "file:///three")
        assertEquals(listOf(a, b, c), page(listOf(c, a, b), null))
        assertEquals(listOf(c), page(listOf(b, c), b.key))
    }
}
