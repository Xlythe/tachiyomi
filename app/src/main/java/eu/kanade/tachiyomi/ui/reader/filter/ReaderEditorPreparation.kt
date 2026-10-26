package eu.kanade.tachiyomi.ui.reader.filter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Keep editor preparation ordering explicit so optional scanning can be delayed in regressions. */
internal suspend fun <T> prepareReaderEditor(
    load: suspend () -> T,
    onReady: (T) -> Unit,
    enrich: suspend (T) -> Unit,
) {
    val source = load()
    currentCoroutineContext().ensureActive()
    onReady(source)
    try {
        enrich(source)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        // Optional snapping aids must not disable an already usable hide/crop editor.
    }
}
