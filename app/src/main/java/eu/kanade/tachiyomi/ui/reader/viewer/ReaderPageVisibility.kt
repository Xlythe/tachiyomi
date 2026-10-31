package eu.kanade.tachiyomi.ui.reader.viewer

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Scope chapter-wide notifications before collectLatest can cancel an unchanged page's decode. */
internal fun Flow<Long>.forPageVisibility(currentRevision: () -> Long): Flow<Long> =
    map { currentRevision() }.distinctUntilChanged()
