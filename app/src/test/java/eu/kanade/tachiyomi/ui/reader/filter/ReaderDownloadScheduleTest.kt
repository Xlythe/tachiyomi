package eu.kanade.tachiyomi.ui.reader.filter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderDownloadScheduleTest {
    @Test fun completedDownloadsRequeueOnlyWhenTheApplicableVersionChanges() {
        val q = ReaderDownloadSchedule()
        q.committed("chapter", true, "first")
        val old = q.next("v1", 0)!!
        assertTrue(q.complete(old, "v1"))
        assertNull(q.next("v1", 0))
        val updated = q.next("v2", 0)!!
        assertEquals(0, updated.cursor)
        assertEquals("v2", updated.rulesVersion)
        assertTrue(q.complete(updated, "v2"))
        assertNull(q.next("v2", 0))
    }
    @Test fun interruptedWorkResumesAtItsDurableCursorAndCompletedHistorySurvivesRestart() {
        val q = ReaderDownloadSchedule()
        q.committed("done", false, "a"); q.committed("pending", true, "b")
        q.complete(q.next("v1", 0)!!, "v1")
        q.advance(q.next("v1", 0)!!, 17)
        val restarted = ReaderDownloadSchedule().apply { restore(q.snapshot()) }
        assertEquals("pending", restarted.next("v1", 0)!!.uri)
        assertEquals(17, restarted.next("v1", 0)!!.cursor)
        assertEquals("done", restarted.next("v2", 0)!!.uri)
        assertEquals(0, restarted.next("v2", 0)!!.cursor)
    }
    @Test fun rapidUpdatesCannotAcceptOldCompletionsOrOldCursorCallbacks() {
        val q = ReaderDownloadSchedule(); q.committed("same", true, "first")
        val a = q.next("v1", 0)!!; q.advance(a, 21)
        val b = q.next("v2", 0)!!
        assertFalse(q.advance(a, 99)); assertFalse(q.complete(a, "v2"))
        assertFalse(q.complete(b, "v3"))
        val c = q.next("v3", 0)!!
        assertEquals(0, c.cursor); assertTrue(q.complete(c, "v3"))
    }
    @Test fun replacingACommittedUriInvalidatesAnInFlightGeneration() {
        val q = ReaderDownloadSchedule(); q.committed("same", true, "first")
        val old = q.next("v1", 0)!!
        q.committed("same", true, "replacement")
        assertFalse(q.advance(old, 50)); assertFalse(q.complete(old, "v1"))
        assertEquals(0, q.next("v1", 0)!!.cursor)
    }
    @Test fun failuresAreUnknownBackOffAndDoNotBlockOtherChapters() {
        val q = ReaderDownloadSchedule(); q.committed("bad", true, "a"); q.committed("good", false, "b")
        val bad = q.next("v1", 0)!!; q.failed(bad, 0)
        val good = q.next("v1", 1)!!; assertEquals("good", good.uri); q.complete(good, "v1")
        assertNull(q.next("v1", 2)); assertTrue(q.hasPending("v1"))
        assertEquals("bad", q.next("v1", 10_000)!!.uri)
        // Fair rotation puts the previously successful chapter first in the new version too.
        val firstUpdated = q.next("v2", 2)!!
        assertEquals("good", firstUpdated.uri)
        q.complete(firstUpdated, "v2")
        val failedUpdated = q.next("v2", 2)!!
        assertEquals("bad", failedUpdated.uri)
        assertEquals(0, failedUpdated.failures)
        assertEquals(0, failedUpdated.retryAt)
    }
    @Test fun metadataAndDiscoveryAreBoundedWithoutRepeatedInventoryChurn() {
        val q = ReaderDownloadSchedule(2)
        assertTrue(q.discovered("a", true, "a")); assertTrue(q.discovered("b", false, "b"))
        assertFalse(q.discovered("c", true, "c"))
        q.complete(q.next("v1", 0)!!, "v1"); q.complete(q.next("v1", 0)!!, "v1")
        repeat(5) { assertFalse(q.discovered("c", true, "c")); assertNull(q.next("v1", 0)) }
        q.committed("c", true, "c")
        assertEquals(listOf("b", "c"), q.snapshot().map { it.uri })
        assertEquals("c", q.next("v1", 0)!!.uri)
    }
    @Test fun interruptionOfANewVersionNeverCertifiesTheOldCleanPassAsCurrent() {
        val q = ReaderDownloadSchedule()
        q.committed("chapter", true, "content")
        val old = q.next("v1", 0)!!
        q.complete(old, "v1")
        val updated = q.next("v2", 0)!!
        q.advance(updated, 5)
        q.failed(updated, 0)
        val restarted = ReaderDownloadSchedule().apply { restore(q.snapshot()) }
        assertTrue(restarted.hasPending("v2"))
        assertFalse(restarted.complete(old, "v2"))
        assertEquals("v1", restarted.snapshot().single().checkedVersion)
        assertEquals(5, restarted.next("v2", 10_000)!!.cursor)
    }
}
