package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.SharedPreferences
import java.security.MessageDigest

/** Additive private bookmarks. The chapter database remains authoritative for page/read progress. */
internal class ReaderScrollPositionStore(
    private val preferences: SharedPreferences,
    private val limit: Int = 512,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init { require(limit in 1..4096) }

    data class Chapter(val mangaId: Long, val chapterId: Long, val url: String) {
        init { require(mangaId >= 0 && chapterId >= 0 && url.isNotBlank()) }
    }

    data class Position(
        val anchor: ReaderScrollAnchor,
        val expectedPage: Int,
        val pageCount: Int,
        val wasRead: Boolean,
        val pageUrlHash: String? = null,
        val updatedAt: Long = 0,
    ) {
        init {
            require(pageCount > 0 && expectedPage in 0 until pageCount)
            require(anchor.key.pageIndex in 0 until pageCount && updatedAt >= 0)
            require(pageUrlHash == null || pageUrlHash.matches(Regex("[0-9a-f]{64}")))
        }

        fun matches(progress: Int, count: Int, read: Boolean, pageUrl: String): Boolean =
            expectedPage == progress && pageCount == count && wasRead == read &&
                (pageUrlHash == null || pageUrl.isBlank() || pageUrlHash == hashPageUrl(pageUrl))
    }

    fun read(chapter: Chapter): Position? = synchronized(LOCK) {
        runCatching {
            val value = preferences.getString(key(chapter), null) ?: return@synchronized null
            require(value.length <= 512)
            val fields = value.split('|')
            require(fields.size == 10 && fields[0] == "1" && fields[7] in listOf("0", "1"))
            Position(
                ReaderScrollAnchor(
                    ReaderScrollAnchor.Key(chapter.chapterId, fields[1].toInt(), fields[2].toInt()),
                    fields[3].toDouble(),
                    fields[4].toDouble(),
                ),
                fields[5].toInt(),
                fields[6].toInt(),
                fields[7] == "1",
                fields[9].ifEmpty { null },
                fields[8].toLong(),
            )
        }.getOrNull()
    }

    fun write(chapter: Chapter, position: Position) = synchronized(LOCK) {
        require(position.anchor.key.chapterId == chapter.chapterId)
        val key = key(chapter)
        val current = preferences.all.filterKeys { it.startsWith(PREFIX) }
        val newest = current.values.maxOfOrNull(::timestamp) ?: 0L
        val next = if (newest == Long.MAX_VALUE) newest else newest + 1
        val saved = position.copy(updatedAt = maxOf(clock().coerceAtLeast(1), next))
        val value = listOf(
            "1",
            saved.anchor.key.pageIndex,
            saved.anchor.key.part,
            saved.anchor.fraction,
            saved.anchor.viewportFraction,
            saved.expectedPage,
            saved.pageCount,
            if (saved.wasRead) "1" else "0",
            saved.updatedAt,
            saved.pageUrlHash.orEmpty(),
        ).joinToString("|")
        val overflow = (current.size + if (key in current) 0 else 1) - limit
        val editor = preferences.edit().putString(key, value)
        current.entries.filterNot { it.key == key }.sortedBy { timestamp(it.value) }
            .take(overflow.coerceAtLeast(0)).forEach { editor.remove(it.key) }
        // Android flushes queued apply() work at normal activity stop; no synchronous disk write on scroll.
        editor.apply()
    }

    fun remove(chapter: Chapter) = synchronized(LOCK) {
        val key = key(chapter)
        if (preferences.contains(key)) preferences.edit().remove(key).apply()
    }

    private fun key(chapter: Chapter): String =
        PREFIX + hash("${chapter.mangaId}\n${chapter.chapterId}\n${chapter.url}")

    private fun timestamp(value: Any?): Long = (value as? String)?.split('|')?.getOrNull(8)
        ?.toLongOrNull()?.coerceAtLeast(0) ?: 0

    companion object {
        const val PREFERENCES_NAME = "reader-scroll-positions-v1"
        private const val PREFIX = "chapter:"
        private val LOCK = Any()

        fun hashPageUrl(url: String): String? = url.takeIf { it.isNotBlank() }?.let(::hash)

        private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
