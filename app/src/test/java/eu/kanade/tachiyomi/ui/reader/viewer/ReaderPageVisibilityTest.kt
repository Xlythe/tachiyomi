package eu.kanade.tachiyomi.ui.reader.viewer

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderPageVisibilityTest {
    @Test fun unrelatedPageAndNeighbourChapterUpdatesDoNotRestartAPendingDecode() = runBlocking {
        var ownRevision = 0L
        var cancellations = 0
        val started = mutableListOf<Long>()
        flow {
            emit(0L)
            yield()
            repeat(12) {
                emit(it + 1L)
                yield()
            }
            ownRevision = 1L
            emit(13L)
            yield()
            emit(14L) // Repeated notification for the same selected-page decision.
            yield()
            ownRevision = 2L
            emit(15L)
        }.forPageVisibility { ownRevision }.collectLatest { revision ->
            started += revision
            if (revision < 2L) {
                try { awaitCancellation() } finally { cancellations++ }
            }
        }
        assertEquals(listOf(0L, 1L, 2L), started)
        assertEquals(2, cancellations)
    }

    @Test fun onlyTheChangedPagesRevisionIsPublished() = runBlocking {
        var a = 0L
        val b = 0L
        fun events() = flow {
            emit(0L)
            a = 1L
            emit(1L)
            emit(2L)
            a = 2L
            emit(3L)
        }
        assertEquals(listOf(0L, 1L, 2L), events().forPageVisibility { a }.toList())
        assertEquals(listOf(0L), events().forPageVisibility { b }.toList())
    }

    @Test fun aNewSubscriptionStartsWithItsCurrentPageAndDoesNotReplayOldDecisions() = runBlocking {
        var revision = 3L
        val notifications = flow {
            emit(100L)
            emit(101L)
        }
        assertEquals(listOf(3L), notifications.forPageVisibility { revision }.toList())
        revision = 7L
        assertEquals(listOf(7L), notifications.forPageVisibility { revision }.toList())
    }

    @Test fun hideRestoreAndAutomaticTransitionsAreNotSuppressed() = runBlocking {
        var revision = 0L
        val notifications = flow {
            emit(10L)
            for (next in 1L..3L) {
                revision = next
                emit(10L + next)
                emit(20L + next)
            }
        }
        assertEquals(listOf(0L, 1L, 2L, 3L), notifications.forPageVisibility { revision }.toList())
    }
}
