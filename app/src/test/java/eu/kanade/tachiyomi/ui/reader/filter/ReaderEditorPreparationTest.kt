package eu.kanade.tachiyomi.ui.reader.filter

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderEditorPreparationTest {
    @Test
    fun slowOptionalScanningCannotKeepWholePageHideWaiting() = runBlocking {
        val scanning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var ready = false
        val job = launch {
            prepareReaderEditor(load = { "downloaded page" }, onReady = { ready = true }, enrich = {
                scanning.complete(Unit)
                release.await()
            })
        }
        try {
            scanning.await()
            assertTrue(ready, "Preview/whole-page hide must be available before optional matching/contrast work")
        } finally {
            release.complete(Unit)
            job.join()
        }
    }

    @Test
    fun optionalFailureDoesNotTurnAReadyImageIntoAnErrorOrInfiniteSpinner() = runBlocking {
        val events = mutableListOf<String>()
        prepareReaderEditor(load = { "page" }, onReady = { events += "ready" }, enrich = {
            throw IllegalStateException("Optional scan failed")
        })
        assertEquals(listOf("ready"), events)
    }

    @Test
    fun navigationCancelsDelayedSourceAndNeverPublishesIntoAnotherPage() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var ready = false
        val job = launch {
            prepareReaderEditor(load = { started.complete(Unit); release.await(); "old page" },
                onReady = { ready = true }, enrich = {})
        }
        started.await()
        job.cancelAndJoin()
        release.complete(Unit)
        assertEquals(false, ready)
    }
}
