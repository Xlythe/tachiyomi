package eu.kanade.tachiyomi.data.library

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Duration
import java.time.Instant

internal class RecentUpdatesCheckerTest {

    private val now = Instant.parse("2026-08-20T12:00:00Z")

    @Test
    fun `first scan seeds one page and requires a full update`(): Unit = runBlocking {
        val requestedPages = mutableListOf<Int>()

        val result = RecentUpdatesChecker().scan(
            previous = null,
            libraryManga = libraryManga(1L to "/library/1"),
            now = now,
        ) { page ->
            requestedPages += page
            page(hasNextPage = true, " /series/1/ ", "/series/2")
        }

        result.requiresFullUpdate shouldBe true
        result.pagesScanned shouldBe 1
        requestedPages shouldContainExactly listOf(1)
        result.checkpoint?.mangaUrls shouldContainExactly listOf("/series/1", "/series/2")
        result.checkpoint?.libraryManga shouldContainExactly libraryManga(1L to "/library/1")
    }

    @Test
    fun `overlap uses identities across pages when an updated marker moves`(): Unit = runBlocking {
        val previous = checkpoint(
            age = Duration.ofHours(1),
            mangaUrls = listOf("/series/a", "/series/b", "/series/c"),
            libraryManga = libraryManga(1L to "/series/b"),
        )
        val pages = listOf(
            page(hasNextPage = true, "/series/b", "/series/new", "/series/x"),
            page(hasNextPage = true, "/series/c", "/series/older"),
            page(hasNextPage = false, "/series/a"),
        )
        val requestedPages = mutableListOf<Int>()

        val result = RecentUpdatesChecker().scan(
            previous = previous,
            libraryManga = libraryManga(
                1L to "/series/b",
                2L to " /series/newly-eligible/ ",
            ),
            now = now,
        ) { page ->
            requestedPages += page
            pages[page - 1]
        }

        result.requiresFullUpdate shouldBe false
        requestedPages shouldContainExactly listOf(1, 2)
        result.mangaUrls.orEmpty() shouldContain "/series/b"
        result.mangaUrls.orEmpty() shouldContain "/series/new"
        result.mangaUrls.orEmpty() shouldContain "/series/c"
        result.mangaUrls.orEmpty() shouldContain "/series/newly-eligible"
    }

    @Test
    fun `duplicate forms of one marker do not satisfy two-marker overlap`(): Unit = runBlocking {
        val previous = checkpoint(
            age = Duration.ofHours(1),
            mangaUrls = listOf("/series/a", "/series/b"),
        )
        val requestedPages = mutableListOf<Int>()

        val result = RecentUpdatesChecker().scan(
            previous = previous,
            libraryManga = emptyList(),
            now = now,
        ) { page ->
            requestedPages += page
            when (page) {
                1 -> page(hasNextPage = true, "/series/a", " /series/a/ ")
                else -> page(hasNextPage = false, "/series/b")
            }
        }

        result.requiresFullUpdate shouldBe false
        requestedPages shouldContainExactly listOf(1, 2)
    }

    @Test
    fun `stale checkpoint seeds one page and requires a full update`(): Unit = runBlocking {
        val previous = checkpoint(
            age = RecentUpdatesChecker.MAX_CHECKPOINT_AGE.plusMinutes(1),
            mangaUrls = listOf("/series/a", "/series/b"),
        )
        val requestedPages = mutableListOf<Int>()

        val result = RecentUpdatesChecker().scan(
            previous = previous,
            libraryManga = emptyList(),
            now = now,
        ) { page ->
            requestedPages += page
            page(hasNextPage = true, "/series/a", "/series/b")
        }

        result.requiresFullUpdate shouldBe true
        requestedPages shouldContainExactly listOf(1)
    }

    @Test
    fun `missing overlap within page limit requires a full update`(): Unit = runBlocking {
        val previous = checkpoint(
            age = Duration.ofHours(1),
            mangaUrls = listOf("/series/a", "/series/b"),
        )

        val result = RecentUpdatesChecker(maxPages = 2).scan(
            previous = previous,
            libraryManga = emptyList(),
            now = now,
        ) { page ->
            page(hasNextPage = true, "/new/$page")
        }

        result.requiresFullUpdate shouldBe true
        result.pagesScanned shouldBe 2
        result.checkpoint?.mangaUrls shouldContainExactly listOf("/new/1", "/new/2")
    }

    @Test
    fun `library manga without a stable URL prevents filtering`(): Unit = runBlocking {
        val previous = checkpoint(
            age = Duration.ofHours(1),
            mangaUrls = listOf("/series/a", "/series/b"),
        )

        val result = RecentUpdatesChecker().scan(
            previous = previous,
            libraryManga = libraryManga(1L to " / "),
            now = now,
        ) {
            page(hasNextPage = true, "/series/a", "/series/b")
        }

        result.requiresFullUpdate shouldBe true
        result.pagesScanned shouldBe 1
    }

    @Test
    fun `feed failure requires a full update without advancing checkpoint`(): Unit = runBlocking {
        val previous = checkpoint(
            age = Duration.ofHours(1),
            mangaUrls = listOf("/series/a", "/series/b"),
        )

        val result = RecentUpdatesChecker().scan(
            previous = previous,
            libraryManga = emptyList(),
            now = now,
        ) { page ->
            if (page == 1) {
                page(hasNextPage = true, "/series/a", "/series/new")
            } else {
                throw IOException("Latest feed unavailable")
            }
        }

        result.requiresFullUpdate shouldBe true
        result.pagesScanned shouldBe 1
        result.checkpoint shouldBe null
    }

    private fun checkpoint(
        age: Duration,
        mangaUrls: List<String>,
        libraryManga: List<RecentUpdatesLibraryManga> = emptyList(),
    ) = RecentUpdatesCheckpoint(
        checkedAt = now.minus(age),
        mangaUrls = mangaUrls,
        libraryManga = libraryManga,
    )

    private fun libraryManga(
        vararg manga: Pair<Long, String>,
    ): List<RecentUpdatesLibraryManga> {
        return manga.map { (id, url) -> RecentUpdatesLibraryManga(id, url) }
    }

    private fun page(
        hasNextPage: Boolean,
        vararg urls: String,
    ) = MangasPage(
        mangas = urls.map { url ->
            SManga.create().apply {
                this.url = url
                title = url
            }
        },
        hasNextPage = hasNextPage,
    )
}
