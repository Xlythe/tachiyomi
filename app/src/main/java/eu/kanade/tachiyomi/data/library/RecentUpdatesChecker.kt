package eu.kanade.tachiyomi.data.library

import eu.kanade.tachiyomi.source.model.MangasPage
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.domain.library.service.LibraryPreferences
import java.time.Duration
import java.time.Instant

/**
 * Finds the part of a source's latest feed that changed since the previous library update.
 *
 * Two distinct entries from the previous scan are required before the result can be used to
 * filter the library update. Entries are compared by identity rather than position because an
 * updated manga can move to the front of the feed.
 */
internal class RecentUpdatesChecker(
    private val maxCheckpointAge: Duration = MAX_CHECKPOINT_AGE,
    private val maxPages: Int = MAX_PAGES,
    private val overlapThreshold: Int = OVERLAP_THRESHOLD,
    private val maxCheckpointEntries: Int = MAX_CHECKPOINT_ENTRIES,
) {

    suspend fun scan(
        previous: RecentUpdatesCheckpoint?,
        libraryManga: Collection<RecentUpdatesLibraryManga>,
        now: Instant = Instant.now(),
        fetchPage: suspend (Int) -> MangasPage,
    ): RecentUpdatesScanResult {
        val normalizedLibraryManga = libraryManga.mapNotNull { manga ->
            val normalizedUrl = normalizeMangaUrl(manga.mangaUrl)
            manga.copy(mangaUrl = normalizedUrl).takeIf { normalizedUrl.isNotEmpty() }
        }
        val hasUnidentifiableLibraryManga = normalizedLibraryManga.size != libraryManga.size
        val previousUrls = previous?.mangaUrls
            .orEmpty()
            .asSequence()
            .map(::normalizeMangaUrl)
            .filter(String::isNotEmpty)
            .toSet()
        val checkpointAge = previous?.let { Duration.between(it.checkedAt, now) }
        val canFilter = previousUrls.size >= overlapThreshold &&
            !hasUnidentifiableLibraryManga &&
            checkpointAge != null &&
            !checkpointAge.isNegative &&
            checkpointAge <= maxCheckpointAge

        if (!canFilter) {
            val firstPage = fetchPageOrNull(1, fetchPage)
                ?: return RecentUpdatesScanResult.fullUpdate()
            val observedUrls = firstPage.normalizedUrls()
            return RecentUpdatesScanResult.fullUpdate(
                checkpoint = observedUrls.toCheckpointOrNull(now, normalizedLibraryManga),
                pagesScanned = 1,
            )
        }

        val observedUrls = linkedSetOf<String>()
        val overlappingUrls = mutableSetOf<String>()
        val newlyEligibleUrls = normalizedLibraryManga
            .filterNot { it in previous.orEmptyLibraryManga() }
            .mapTo(mutableSetOf(), RecentUpdatesLibraryManga::mangaUrl)
        var pagesScanned = 0

        for (pageNumber in 1..maxPages) {
            val page = fetchPageOrNull(pageNumber, fetchPage)
                ?: return RecentUpdatesScanResult.fullUpdate(pagesScanned = pagesScanned)
            pagesScanned = pageNumber

            page.normalizedUrls().forEach { url ->
                observedUrls += url
                if (url in previousUrls) {
                    overlappingUrls += url
                }
            }

            if (overlappingUrls.size >= overlapThreshold) {
                return RecentUpdatesScanResult(
                    mangaUrls = observedUrls + newlyEligibleUrls,
                    checkpoint = observedUrls.toCheckpointOrNull(now, normalizedLibraryManga),
                    pagesScanned = pageNumber,
                )
            }

            if (!page.hasNextPage) break
        }

        // The feed may have rolled past the old markers or may not be a complete latest feed.
        // In either case, filtering would risk missing updates.
        return RecentUpdatesScanResult.fullUpdate(
            checkpoint = observedUrls.toCheckpointOrNull(now, normalizedLibraryManga),
            pagesScanned = pagesScanned,
        )
    }

    private suspend fun fetchPageOrNull(
        page: Int,
        fetchPage: suspend (Int) -> MangasPage,
    ): MangasPage? {
        return try {
            fetchPage(page)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun MangasPage.normalizedUrls(): Set<String> {
        return mangas.mapNotNullTo(linkedSetOf()) { manga ->
            normalizeMangaUrl(manga.url).takeIf(String::isNotEmpty)
        }
    }

    private fun Collection<String>.toCheckpointOrNull(
        now: Instant,
        libraryManga: Collection<RecentUpdatesLibraryManga>,
    ): RecentUpdatesCheckpoint? {
        if (isEmpty()) return null
        return RecentUpdatesCheckpoint(
            checkedAt = now,
            mangaUrls = take(maxCheckpointEntries),
            libraryManga = libraryManga.toList(),
        )
    }

    companion object {
        internal val MAX_CHECKPOINT_AGE: Duration = Duration.ofHours(24)
        internal const val MAX_PAGES = 10
        internal const val OVERLAP_THRESHOLD = 2
        internal const val MAX_CHECKPOINT_ENTRIES = 200

        internal fun normalizeMangaUrl(url: String): String = url.trim().trimEnd('/')
    }
}

internal data class RecentUpdatesScanResult(
    /** Null means the caller must perform a full update for this source. */
    val mangaUrls: Set<String>?,
    /** Commit only after the selected manga for the source update successfully. */
    val checkpoint: RecentUpdatesCheckpoint?,
    val pagesScanned: Int,
) {
    val requiresFullUpdate: Boolean
        get() = mangaUrls == null

    companion object {
        fun fullUpdate(
            checkpoint: RecentUpdatesCheckpoint? = null,
            pagesScanned: Int = 0,
        ) = RecentUpdatesScanResult(
            mangaUrls = null,
            checkpoint = checkpoint,
            pagesScanned = pagesScanned,
        )
    }
}

@Serializable
internal data class RecentUpdatesCheckpoint(
    @Serializable(with = InstantAsEpochMilliSerializer::class)
    val checkedAt: Instant,
    val mangaUrls: List<String>,
    val libraryManga: List<RecentUpdatesLibraryManga> = emptyList(),
)

@Serializable
internal data class RecentUpdatesLibraryManga(
    val id: Long,
    val mangaUrl: String,
)

private fun RecentUpdatesCheckpoint?.orEmptyLibraryManga(): Set<RecentUpdatesLibraryManga> {
    return this?.libraryManga.orEmpty().toSet()
}

internal class RecentUpdatesCheckpointStore(
    private val libraryPreferences: LibraryPreferences,
    private val json: Json,
) {
    fun get(sourceId: Long): RecentUpdatesCheckpoint? {
        val serialized = libraryPreferences.recentUpdatesCheckpoint(sourceId).get()
        if (serialized.isEmpty()) return null
        return try {
            json.decodeFromString<RecentUpdatesCheckpoint>(serialized)
        } catch (_: Exception) {
            null
        }
    }

    fun set(sourceId: Long, checkpoint: RecentUpdatesCheckpoint) {
        libraryPreferences.recentUpdatesCheckpoint(sourceId).set(json.encodeToString(checkpoint))
    }
}

internal object InstantAsEpochMilliSerializer : kotlinx.serialization.KSerializer<Instant> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
        "InstantAsEpochMilli",
        kotlinx.serialization.descriptors.PrimitiveKind.LONG,
    )

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Instant) {
        encoder.encodeLong(value.toEpochMilli())
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Instant {
        return Instant.ofEpochMilli(decoder.decodeLong())
    }
}
