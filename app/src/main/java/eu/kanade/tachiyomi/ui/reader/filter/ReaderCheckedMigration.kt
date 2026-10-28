package eu.kanade.tachiyomi.ui.reader.filter

/** Only for a matcher that preserves legacy positives; the store must verify current rule proofs. */
internal fun retainPreviousPositive(hit: ReaderCheckedStore.Hit?): ReaderCheckedStore.Hit? =
    hit?.takeIf { it.decision.matched }?.copy(needsRecheck = true)
