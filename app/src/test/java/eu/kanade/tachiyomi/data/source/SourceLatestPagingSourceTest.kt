package eu.kanade.tachiyomi.data.source

import androidx.paging.PagingSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.data.source.SourceLatestPagingSource

class SourceLatestPagingSourceTest {

    @Test
    fun `latest pages discard manga repeated by shifting offsets`() = runBlocking {
        val source = FakeLatestSource(
            page(
                hasNextPage = true,
                "/series/1",
                "/series/2",
                "/series/3",
            ),
            page(
                hasNextPage = true,
                "/series/2",
                "/series/3/",
                "/series/4",
            ),
            page(
                hasNextPage = false,
                "/series/3",
                "/series/4",
                "/series/5",
            ),
        )
        val pagingSource = SourceLatestPagingSource(source)

        pagingSource.load(refresh(1)).urls() shouldContainExactly listOf("/series/1", "/series/2", "/series/3")
        pagingSource.load(append(2)).urls() shouldContainExactly listOf("/series/4")
        pagingSource.load(append(3)).urls() shouldContainExactly listOf("/series/5")
    }

    @Test
    fun `deduplication is scoped to each latest paging session`() = runBlocking {
        val source = FakeLatestSource(page(hasNextPage = false, "/series/1"))

        SourceLatestPagingSource(source).load(refresh(1)).urls() shouldContainExactly listOf("/series/1")
        SourceLatestPagingSource(source).load(refresh(1)).urls() shouldContainExactly listOf("/series/1")
    }

    private fun refresh(page: Long) = PagingSource.LoadParams.Refresh(
        key = page,
        loadSize = 25,
        placeholdersEnabled = false,
    )

    private fun append(page: Long) = PagingSource.LoadParams.Append(
        key = page,
        loadSize = 25,
        placeholdersEnabled = false,
    )

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

    private fun PagingSource.LoadResult<Long, SManga>.urls(): List<String> =
        (this as PagingSource.LoadResult.Page).data.map(SManga::url)

    private class FakeLatestSource(
        vararg pages: MangasPage,
    ) : CatalogueSource {
        private val pages = pages.toList()

        override val id = 1L
        override val name = "Fake"
        override val lang = "en"
        override val supportsLatest = true

        override suspend fun getLatestUpdates(page: Int): MangasPage = pages[page - 1]

        override fun getFilterList() = FilterList()
    }
}
