package eu.kanade.tachiyomi.ui.reader.filter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ReaderSpamOutboxTest {
    @TempDir lateinit var directory: File
    private fun report(title: String = "Series") = ReaderSpamReport("a".repeat(64), title, 2.5)

    @Test fun payloadHasExactlyAuthorizedFieldsAndEscapesControlCharacters() {
        assertEquals(
            "{\"image_hash\":\"${"a".repeat(64)}\",\"series_name\":\"A\\\"\\\\\\u000a\",\"chapter_number\":2.5}",
            report("A\"\\\n").json(),
        )
        assertThrows(IllegalArgumentException::class.java) { ReaderSpamReport("bad", "Series", 1.0) }
        assertThrows(IllegalArgumentException::class.java) { ReaderSpamReport("a".repeat(64), "Series", Double.NaN) }
        assertEquals(report("é").json(), report("e\u0301").json())
    }

    @Test fun duplicateReportSurvivesRestartAndCancellationWithoutDroppingTheRecord() {
        val first = ReaderSpamOutbox(directory)
        assertTrue(first.enqueue(report()))
        assertTrue(first.enqueue(report()))
        val second = ReaderSpamOutbox(directory)
        val pending = requireNotNull(second.ready())
        assertEquals(report().json(), String(pending.payload))
        // Cancellation does not acknowledge: a fresh worker can retry the same record.
        assertEquals(pending.id, ReaderSpamOutbox(directory).ready()?.id)
        assertEquals(1, directory.listFiles()!!.count { it.name.endsWith(".report") })
        assertTrue(second.acknowledge(pending))
        assertNull(second.ready())
    }

    @Test fun failedSendBackoffPersistsAndDoesNotBlockANewerReport() {
        var now = 1_000L
        val outbox = ReaderSpamOutbox(directory, clock = { now })
        assertTrue(outbox.enqueue(report()))
        val pending = requireNotNull(outbox.ready())
        assertTrue(outbox.retry(pending))
        assertNull(ReaderSpamOutbox(directory, clock = { now }).ready())
        assertTrue(outbox.enqueue(report("Other")))
        assertNotEquals(pending.id, outbox.ready()?.id)
        assertTrue(outbox.acknowledge(requireNotNull(outbox.ready())))
        now = 11_000
        assertEquals(1, ReaderSpamOutbox(directory, clock = { now }).ready()?.attempts)
    }

    @Test fun fullQueueRefusesNewReportsWithoutEvictionAndCorruptionIsHeld() {
        val outbox = ReaderSpamOutbox(directory, entryLimit = 1)
        assertTrue(outbox.enqueue(report()))
        assertFalse(outbox.enqueue(report("Other")))
        val pending = requireNotNull(outbox.ready())
        assertEquals(report().json(), String(pending.payload))
        File(directory, "${pending.id}.report").appendText("corrupt")
        assertNull(outbox.ready())
        assertFalse(outbox.enqueue(report("Other")))
        assertEquals(1, directory.listFiles()!!.size)
    }
}
