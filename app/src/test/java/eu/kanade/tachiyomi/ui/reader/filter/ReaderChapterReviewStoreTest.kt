package eu.kanade.tachiyomi.ui.reader.filter

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ReaderChapterReviewStoreTest {
    @TempDir lateinit var directory: File
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    @Test fun restoresSurviveRestartAndReevaluationButStayChapterAndContentScoped() {
        val file = File(directory, "choices")
        ReaderChapterReviewStore(file).set("chapter1", a, ReaderChapterReviewStore.Choice.KEEP)
        val reopened = ReaderChapterReviewStore(file)
        repeat(3) { assertEquals(ReaderChapterReviewStore.Choice.KEEP, reopened.get("chapter1", a)) }
        assertNull(reopened.get("chapter2", a))
        assertNull(reopened.get("chapter1", b))
        reopened.set("chapter1", a, ReaderChapterReviewStore.Choice.HIDE)
        assertEquals(ReaderChapterReviewStore.Choice.HIDE, ReaderChapterReviewStore(file).get("chapter1", a))
        reopened.set("chapter1", a, null)
        assertNull(ReaderChapterReviewStore(file).get("chapter1", a))
    }

    @Test fun fullStorageRefusesNewChoicesWithoutEvictingAnExistingRestore() {
        val file = File(directory, "choices")
        val store = ReaderChapterReviewStore(file, 1)
        store.set("chapter", a, ReaderChapterReviewStore.Choice.KEEP)
        assertThrows(
            IllegalStateException::class.java,
        ) { store.set("chapter", b, ReaderChapterReviewStore.Choice.HIDE) }
        assertEquals(ReaderChapterReviewStore.Choice.KEEP, ReaderChapterReviewStore(file, 1).get("chapter", a))
    }

    @Test fun damagedStorageIsHeldInsteadOfBeingOverwrittenWithAnEmptyMap() {
        val file = File(directory, "choices").apply { writeText("broken") }
        val before = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) {
            ReaderChapterReviewStore(file).set("chapter", a, ReaderChapterReviewStore.Choice.KEEP)
        }
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun concurrentChoicesAndInterruptedStagingDoNotLoseExistingRestores() {
        val file = File(directory, "choices")
        val store = ReaderChapterReviewStore(file)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val futures = (0..15).map { index ->
                pool.submit {
                    store.set("chapter$index", a, ReaderChapterReviewStore.Choice.KEEP)
                }
            }
            futures.forEach { it.get() }
        } finally { pool.shutdown() }
        val interrupted = File(directory, "chapter-review-interrupted.pending").apply { writeText("partial") }
        val reopened = ReaderChapterReviewStore(file)
        repeat(16) { assertEquals(ReaderChapterReviewStore.Choice.KEEP, reopened.get("chapter$it", a)) }
        reopened.set("chapter0", a, ReaderChapterReviewStore.Choice.HIDE)
        assertEquals(ReaderChapterReviewStore.Choice.HIDE, ReaderChapterReviewStore(file).get("chapter0", a))
        assertEquals("partial", interrupted.readText())
    }
}
