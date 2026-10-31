package eu.kanade.presentation.manga

import android.app.Application
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import eu.kanade.presentation.manga.components.MangaChapterListItem
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.reader.ReaderUiTestActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tachiyomi.domain.library.service.LibraryPreferences

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ChapterPressCancellationTest {
    @get:Rule val compose = createAndroidComposeRule<ReaderUiTestActivity>()

    @Test fun earlyReleaseRemainsATapInsteadOfALongPress() {
        val fixture = Fixture()
        fixture.down()
        compose.mainClock.advanceTimeBy(100)
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle {
            assertEquals(listOf(2L), fixture.clicks)
            assertEquals(emptyList<Long>(), fixture.longPresses)
        }
    }

    @Test fun cancellationDoesNotSelectOrClickAChapter() {
        val fixture = Fixture()
        fixture.down()
        compose.mainClock.advanceTimeBy(100)
        compose.onRoot().performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle {
            assertEquals(emptyList<Long>(), fixture.clicks)
            assertEquals(emptyList<Long>(), fixture.longPresses)
        }
    }

    @Test fun aRealFingerDragCancelsTheLongPress() {
        val fixture = Fixture()
        val point = fixture.down()
        compose.mainClock.advanceTimeBy(100)
        compose.onRoot().performTouchInput { moveTo(point + Offset(0f, -100f)) }
        compose.mainClock.advanceTimeBy(700)
        compose.onRoot().performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(emptyList<Long>(), fixture.clicks)
            assertEquals(emptyList<Long>(), fixture.longPresses)
        }
    }

    @Test fun removingThePressedChapterCannotSelectItsReplacement() {
        val fixture = Fixture()
        fixture.down()
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread { fixture.rows.value = listOf(1L, 3L, 4L) }
        compose.mainClock.advanceTimeBy(700)
        compose.onRoot().performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(emptyList<Long>(), fixture.clicks)
            assertEquals(emptyList<Long>(), fixture.longPresses)
        }
    }

    @Test fun reorderingDuringThePressKeepsTheOriginalChapterIdentity() {
        val fixture = Fixture()
        fixture.down()
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread { fixture.rows.value = fixture.rows.value.reversed() }
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle { assertEquals(listOf(2L), fixture.longPresses) }
        compose.onRoot().performTouchInput { up() }
        compose.runOnUiThread { fixture.read.value = true }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle {
            assertEquals(setOf(2L), fixture.selected.value)
            assertEquals(listOf(2L), fixture.longPresses)
            assertEquals(emptyList<Long>(), fixture.clicks)
        }
    }

    private inner class Fixture {
        val rows = mutableStateOf(listOf(1L, 2L, 3L))
        val read = mutableStateOf(false)
        val selected = mutableStateOf(emptySet<Long>())
        val clicks = mutableListOf<Long>()
        val longPresses = mutableListOf<Long>()

        init {
            compose.setContent {
                MaterialTheme {
                    LazyColumn {
                        items(rows.value, key = { it }) { id ->
                            MangaChapterListItem(
                                chapterId = id,
                                title = "Chapter $id",
                                date = "Today",
                                readProgress = null,
                                scanlator = null,
                                read = read.value,
                                bookmark = false,
                                selected = id in selected.value,
                                downloadIndicatorEnabled = false,
                                downloadStateProvider = { Download.State.NOT_DOWNLOADED },
                                downloadProgressProvider = { 0 },
                                chapterSwipeStartAction = LibraryPreferences.ChapterSwipeAction.ToggleBookmark,
                                chapterSwipeEndAction = LibraryPreferences.ChapterSwipeAction.ToggleRead,
                                onLongClick = {
                                    longPresses += id
                                    selected.value += id
                                },
                                onClick = { clicks += id },
                                onDownloadClick = {},
                                onChapterSwipe = {},
                                modifier = Modifier.testTag("chapter-$id"),
                            )
                        }
                    }
                }
            }
            compose.mainClock.autoAdvance = false
        }

        fun down(): Offset {
            val point = compose.onNodeWithTag("chapter-2").fetchSemanticsNode().boundsInRoot.center
            compose.onRoot().performTouchInput { down(point) }
            return point
        }
    }
}
