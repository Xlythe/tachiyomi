package eu.kanade.tachiyomi.ui.reader.filter

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ReaderAutomaticMatcherTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun cleanup() { scope.cancel() }

    @Test
    fun blockedComparisonDoesNotBlockReadsAndQueueAndResultsStayBounded() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val evaluated = CopyOnWriteArrayList<Int>()
        val matcher = ReaderAutomaticMatcher(scope, 4, { true }, {}, { bytes ->
            evaluated += bytes[0].toInt()
            if (bytes[0] == 1.toByte()) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            ReaderAutomaticMatcher.Result(bytes)
        })
        try {
            matcher.enqueue("one", ByteArray(4) { 1 })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val start = System.nanoTime()
            matcher.enqueue("two", ByteArray(4) { 2 })
            matcher.enqueue("three", ByteArray(4) { 3 })
            assertNull(matcher.ready("one"))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 250)
        } finally { release.countDown() }
        await { matcher.ready("three") != null }
        assertEquals(listOf(1, 3), evaluated)
        assertNull(matcher.ready("one"))
    }

    @Test
    fun staleCompletionCannotPublishAfterRestoreAndNewGenerationCanRun() {
        val generation = AtomicReference("old")
        val changes = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val matcher = ReaderAutomaticMatcher(scope, 8, { it == generation.get() }, { changes.incrementAndGet() }, {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            ReaderAutomaticMatcher.Result(byteArrayOf(9), true)
        })
        try {
            matcher.enqueue("old", byteArrayOf(1))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            generation.set("new")
            matcher.enqueue("new", byteArrayOf(2))
        } finally { release.countDown() }
        await { matcher.ready("new") != null }
        assertNull(matcher.ready("old"))
        await { changes.get() == 1 }
        assertTrue(matcher.ready("new")!!.hidden)
    }

    @Test
    fun errorsAreCachedAsVisibleAndOversizedInputIsNotRetained() {
        val calls = AtomicInteger()
        val matcher = ReaderAutomaticMatcher(scope, 4, { true }, { error("No changed image") }, {
            calls.incrementAndGet()
            error("Unsupported decoder")
        })
        matcher.enqueue("large", ByteArray(5))
        matcher.enqueue("bad", byteArrayOf(1))
        await { matcher.ready("bad") != null }
        repeat(5) { matcher.enqueue("bad", byteArrayOf(1)) }
        assertEquals(1, calls.get())
        assertNull(matcher.ready("large"))
        assertNull(matcher.ready("bad")!!.rendered)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(condition())
    }
}
