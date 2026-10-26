package eu.kanade.tachiyomi.ui.reader.filter

/** Keep editor preparation ordering explicit so optional scanning can be delayed in regressions. */
internal suspend fun <T> prepareReaderEditor(
    load: suspend () -> T,
    onReady: (T) -> Unit,
    enrich: suspend (T) -> Unit,
) {
    val source = load()
    enrich(source)
    onReady(source)
}
