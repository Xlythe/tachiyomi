package eu.kanade.tachiyomi.ui.reader.filter

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun errorsRemainUnknownAndCanRetryWhileOversizedInputIsNotRetained() {
        val calls = AtomicInteger()
        val matcher = ReaderAutomaticMatcher(scope, 4, { true }, { error("No changed image") }, {
            calls.incrementAndGet()
            error("Unsupported decoder")
        })
        matcher.enqueue("large", ByteArray(5))
        matcher.enqueue("bad", byteArrayOf(1))
        await { calls.get() == 1 && !matcher.isBusy("bad") }
        assertNull(matcher.ready("bad"))
        matcher.enqueue("bad", byteArrayOf(1))
        await { calls.get() == 2 && !matcher.isBusy("bad") }
        assertEquals(2, calls.get())
        assertNull(matcher.ready("large"))
        assertNull(matcher.ready("bad"))
    }

    @Test
    fun cancelledWorkerCannotPublishACompletedDecisionOrLeaveBusyStateBehind() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val changes = AtomicInteger()
        val matcher = ReaderAutomaticMatcher(scope, 8, { true }, { changes.incrementAndGet() }, {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            ReaderAutomaticMatcher.Result(byteArrayOf(7), true)
        })
        try {
            matcher.enqueue("cancelled", byteArrayOf(1))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            scope.cancel()
        } finally { release.countDown() }
        await { !matcher.isBusy("cancelled") }
        assertNull(matcher.ready("cancelled"))
        assertEquals(0, changes.get())
    }

    @Test
    fun decoderAllocationFailureStaysUnknownInsteadOfEscapingTheOptionalWorker() {
        val escaped = CountDownLatch(1)
        val attempted = CountDownLatch(1)
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> escaped.countDown() })
        val matcher = ReaderAutomaticMatcher(localScope, 8, { true }, {}, {
            attempted.countDown()
            throw OutOfMemoryError("Synthetic decoder allocation failure; no allocation performed")
        })
        try {
            matcher.enqueue("failed-decoder", byteArrayOf(1))
            assertTrue(attempted.await(2, TimeUnit.SECONDS))
            await { !matcher.isBusy("failed-decoder") }
            assertFalse(escaped.await(200, TimeUnit.MILLISECONDS))
            assertNull(matcher.ready("failed-decoder"))
        } finally { localScope.cancel() }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(condition())
    }

    @Test
    fun downloadPrechecksCannotEvictVisibleWorkAndIdenticalRequestsShareOneComparison() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val evaluated = CopyOnWriteArrayList<Int>()
        val matcher = ReaderAutomaticMatcher(scope, 8, { true }, {}, { bytes ->
            evaluated += bytes[0].toInt()
            if (bytes[0] == 1.toByte()) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            ReaderAutomaticMatcher.Result()
        })
        try {
            matcher.enqueue("active", byteArrayOf(1), foreground = false)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            matcher.enqueue("active", byteArrayOf(1))
            matcher.enqueue("visible", byteArrayOf(2))
            matcher.enqueue("download", byteArrayOf(3), foreground = false)
        } finally { release.countDown() }
        await { matcher.ready("visible") != null }
        assertEquals(listOf(1, 2), evaluated)
        assertNull(matcher.ready("download"))
    }
}
