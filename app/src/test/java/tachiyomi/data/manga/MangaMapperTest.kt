package tachiyomi.data.manga

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MangaMapperTest {

    @Test
    fun `custom title overrides source title`() {
        mapManga(customTitle = "My series name").title shouldBe "My series name"
    }

    @Test
    fun `cleared custom title restores source title`() {
        mapManga(customTitle = null).title shouldBe "Source series name"
    }

    private fun mapManga(customTitle: String?) = MangaMapper.mapManga(
        id = 1,
        source = 2,
        url = "/series",
        artist = null,
        author = null,
        description = null,
        genre = null,
        title = "Source series name",
        status = 0,
        thumbnailUrl = null,
        favorite = true,
        lastUpdate = null,
        nextUpdate = null,
        initialized = true,
        viewerFlags = 0,
        chapterFlags = 0,
        coverLastModified = 0,
        dateAdded = 0,
        updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
        calculateInterval = 0,
        lastModifiedAt = 0,
        favoriteModifiedAt = null,
        customTitle = customTitle,
    )
}
