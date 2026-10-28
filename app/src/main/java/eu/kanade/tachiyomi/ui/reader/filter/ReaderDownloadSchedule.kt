package eu.kanade.tachiyomi.ui.reader.filter

/** Bounded metadata only: never retains image bytes or downloads anything. Caller owns synchronization. */
internal class ReaderDownloadSchedule(private val limit: Int = 128) {
    data class Entry(
        val uri: String,
        val directory: Boolean,
        val generation: String,
        var cursor: Int = 0,
        var rulesVersion: String = "",
        var checkedVersion: String = "",
        var failures: Int = 0,
        var retryAt: Long = 0,
    )
    private val entries = LinkedHashMap<String, Entry>()
    init { require(limit in 1..128) }

    fun snapshot(): List<Entry> = entries.values.map { it.copy() }
    fun restore(rows: List<Entry>) {
        require(rows.size <= limit && rows.map { it.uri }.distinct().size == rows.size)
        entries.clear()
        rows.forEach { entries[it.uri] = it.copy() }
    }

    /** A final download commit replaces its old content generation, even at the same URI. */
    fun committed(uri: String, directory: Boolean, generation: String) {
        entries.remove(uri)
        entries[uri] = Entry(uri, directory, generation)
        while (entries.size > limit) entries.remove(entries.keys.first())
    }

    /** Existing metadata discovery fills free slots; repeated inventory passes never churn the bounded history. */
    fun discovered(uri: String, directory: Boolean, generation: String): Boolean {
        if (uri in entries || entries.size == limit) return false
        entries[uri] = Entry(uri, directory, generation)
        return true
    }

    fun next(version: String, now: Long): Entry? {
        val next = entries.values.firstOrNull {
            it.checkedVersion != version && (it.rulesVersion != version || it.retryAt <= now)
        } ?: return null
        if (next.rulesVersion != version) {
            next.cursor = 0
            next.rulesVersion = version
            next.failures = 0
            next.retryAt = 0
        }
        return next.copy()
    }
    fun hasPending(version: String) = entries.values.any { it.checkedVersion != version }
    fun current(item: Entry): Boolean = entries[item.uri]?.let {
        it.generation == item.generation && it.rulesVersion == item.rulesVersion
    } == true
    fun advance(item: Entry, nextCursor: Int): Boolean {
        if (!current(item)) return false
        entries.getValue(item.uri).cursor = nextCursor
        return true
    }
    fun complete(item: Entry, latestVersion: String): Boolean {
        if (!current(item) || item.rulesVersion != latestVersion) return false
        entries.getValue(item.uri).apply { checkedVersion = latestVersion; cursor = 0; failures = 0; retryAt = 0 }
        return true
    }
    fun failed(item: Entry, now: Long) {
        if (!current(item)) return
        entries.getValue(item.uri).apply {
            failures = (failures + 1).coerceAtMost(10)
            retryAt = now + minOf(600_000L, 5_000L * (1L shl failures))
        }
    }
}
