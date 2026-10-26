package eu.kanade.tachiyomi.ui.reader.filter

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ReaderCheckedStoreTest {
    @TempDir lateinit var directory: File
    private val image = "1".repeat(64)
    private val corpus = "2".repeat(64)
    private val rule = "3".repeat(64)
    private val version = "4".repeat(64)
    private val proofs get() = mapOf(rule to version)
    private fun key(hash: String = image, corpus: String = this.corpus, matcher: String = "matcher-v1") =
        ReaderCheckedStore.Key(hash, corpus, matcher)

    @Test
    fun cleanSurvivesRestartAndAnotherChapterButNewRulesOrMatcherRequireCheckingAgain() {
        val first = ReaderCheckedStore(directory)
        assertTrue(first.write(key(), ReaderCheckedStore.Outcome.Complete(ReaderCheckedStore.Decision())))
        val restarted = ReaderCheckedStore(directory)
        val hit = requireNotNull(restarted.read(key(), proofs))
        assertFalse(hit.decision.hidden)
        assertNull(hit.decision.rendered)
        assertNull(restarted.read(key(corpus = "5".repeat(64)), proofs))
        assertNull(restarted.read(key(matcher = "matcher-v2"), proofs))
        assertNull(restarted.read(key(hash = "6".repeat(64)), proofs))
    }

    @Test
    fun inferredWholeHideSurvivesUnrelatedRulesButRestoreOrAnApplicableEditInvalidatesIt() {
        val store = ReaderCheckedStore(directory)
        assertTrue(
            store.write(
                key(),
                ReaderCheckedStore.Outcome.Complete(
                    ReaderCheckedStore.Decision(true, byteArrayOf()),
                    proofs,
                ),
            ),
        )
        val restarted = ReaderCheckedStore(directory)
        val hit = requireNotNull(restarted.read(key(corpus = "5".repeat(64)), proofs))
        assertTrue(hit.decision.hidden)
        assertFalse(hit.needsRecheck)
        assertNull(restarted.read(key(), emptyMap()))
        assertNull(restarted.read(key(), mapOf(rule to "7".repeat(64))))
    }

    @Test
    fun cachedCropIsImmediateButAdditionalRulesCanRequestARefresh() {
        val bytes = byteArrayOf(7, 8, 9)
        val store = ReaderCheckedStore(directory)
        assertTrue(
            store.write(
                key(),
                ReaderCheckedStore.Outcome.Complete(
                    ReaderCheckedStore.Decision(false, bytes),
                    proofs,
                ),
            ),
        )
        val hit = requireNotNull(ReaderCheckedStore(directory).read(key(corpus = "5".repeat(64)), proofs))
        assertTrue(hit.needsRecheck)
        assertArrayEquals(bytes, hit.decision.rendered)
    }

    @Test
    fun errorsPartialDownloadsAndCancellationAreNeverPersistedAsClean() {
        val store = ReaderCheckedStore(directory)
        for (outcome in listOf(
            ReaderCheckedStore.Outcome.Incomplete,
            ReaderCheckedStore.Outcome.Failed,
            ReaderCheckedStore.Outcome.Cancelled,
        )) {
            assertFalse(store.write(key(), outcome))
            assertNull(store.read(key(), proofs))
        }
        assertFalse(
            store.write(
                key(),
                ReaderCheckedStore.Outcome.Complete(
                    ReaderCheckedStore.Decision(true, byteArrayOf()),
                ),
            ),
        )
    }

    @Test
    fun interruptedWritesAndCorruptionCannotTurnIntoADecisionOrDestroyTheBackup() {
        val store = ReaderCheckedStore(directory)
        assertTrue(store.write(key(), ReaderCheckedStore.Outcome.Complete(ReaderCheckedStore.Decision())))
        val base = File(directory, "${key().fileToken()}.checked")
        val valid = base.readBytes()
        val pending = File(directory, "${key().fileToken()}.123.pending").apply { writeBytes(byteArrayOf(8)) }
        assertNotNull(store.read(key(), proofs))
        assertTrue(pending.exists())
        base.writeBytes(valid.copyOf().apply { this[size / 2] = (this[size / 2].toInt() xor 1).toByte() })
        assertNull(store.read(key(), proofs))
        val backup = File(directory, "${base.name}.bak").apply { writeBytes(valid) }
        assertNotNull(store.read(key(), proofs))
        assertTrue(backup.exists())
        assertTrue(pending.exists())
    }

    @Test
    fun boundsEntryCountAndBytesWithoutTouchingOriginalsOrUnknownFiles() {
        var time = System.currentTimeMillis()
        val store =
            ReaderCheckedStore(directory, byteLimit = 2048, entryLimit = 2, payloadLimit = 32, clock = { time++ })
        val keep = File(directory, "original-do-not-touch.original").apply { writeText("keep") }
        for (n in 1..3) {
            assertTrue(
                store.write(
                    key(hash = n.toString().repeat(64)),
                    ReaderCheckedStore.Outcome.Complete(ReaderCheckedStore.Decision()),
                ),
            )
        }
        assertNull(store.read(key(hash = "1".repeat(64)), proofs))
        assertNotNull(store.read(key(hash = "3".repeat(64)), proofs))
        assertTrue(keep.exists())
        assertTrue(
            directory.listFiles().orEmpty().filter { it.name.endsWith(".checked") }.sumOf { it.length() } <= 2048,
        )
        assertFalse(
            store.write(
                key(),
                ReaderCheckedStore.Outcome.Complete(
                    ReaderCheckedStore.Decision(false, ByteArray(33)),
                    proofs,
                ),
            ),
        )
    }
}
