package eu.kanade.presentation.manga

import android.app.Application
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import eu.kanade.presentation.manga.components.MangaChapterListItem
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.reader.ReaderUiTestActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tachiyomi.domain.library.service.LibraryPreferences

/** Real pointer input and recomposition, without an emulator or an application database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF) // UI-only sandbox; this Windows host has no Conscrypt JNI library.
class ChapterPressSyncTest {
    @get:Rule val compose = createAndroidComposeRule<ReaderUiTestActivity>()

    @Test fun aHeldPressWithoutSyncUpdatesFiresOnce() {
        var longPresses = 0
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                MangaChapterListItem(
                    chapterId = 1,
                    title = "Chapter 1",
                    date = null,
                    readProgress = "Progress 0",
                    scanlator = null,
                    read = false,
                    bookmark = false,
                    selected = false,
                    downloadIndicatorEnabled = true,
                    downloadStateProvider = { Download.State.DOWNLOADING },
                    downloadProgressProvider = { 0 },
                    chapterSwipeStartAction = LibraryPreferences.ChapterSwipeAction.Disabled,
                    chapterSwipeEndAction = LibraryPreferences.ChapterSwipeAction.Disabled,
                    onLongClick = { longPresses++ },
                    onClick = { clicks++ },
                    onDownloadClick = {},
                    onChapterSwipe = {},
                    modifier = Modifier.testTag("chapter-1"),
                )
            }
        }
        compose.mainClock.autoAdvance = false
        val chapter = compose.onNodeWithTag("chapter-1")
        chapter.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle { assertEquals("Control: the host must execute a real held-press timeout", 1, longPresses) }
        chapter.performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(1, longPresses)
            assertEquals(0, clicks)
        }
    }

    @Test fun syncingRowDoesNotCancelLongPressOrClearItsSelection() {
        val rows = mutableStateOf(listOf(Row(1), Row(2)))
        val selected = mutableStateOf(emptySet<Long>())
        var longPresses = 0
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                LazyColumn {
                    items(rows.value, key = { it.id }) { row ->
                        MangaChapterListItem(
                            chapterId = row.id,
                            title = "Chapter ${row.id}",
                            date = null,
                            readProgress = "Progress ${row.progress}",
                            scanlator = null,
                            read = row.read,
                            bookmark = false,
                            selected = row.id in selected.value,
                            downloadIndicatorEnabled = true,
                            downloadStateProvider = { Download.State.DOWNLOADING },
                            downloadProgressProvider = { row.progress },
                            chapterSwipeStartAction = LibraryPreferences.ChapterSwipeAction.Disabled,
                            chapterSwipeEndAction = LibraryPreferences.ChapterSwipeAction.Disabled,
                            onLongClick = {
                                longPresses++
                                selected.value += row.id
                            },
                            onClick = { clicks++ },
                            onDownloadClick = {},
                            onChapterSwipe = {},
                            modifier = Modifier.testTag("chapter-${row.id}"),
                        )
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        val chapter = compose.onNodeWithTag("chapter-1")
        chapter.performTouchInput { down(center) }
        repeat(3) { revision ->
            compose.mainClock.advanceTimeBy(100)
            compose.runOnUiThread {
                // Replace chapter objects as sync/progress emissions do, preserving their IDs.
                rows.value = rows.value.map { it.copy(progress = revision + 1) }
            }
            compose.mainClock.advanceTimeByFrame()
        }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle {
            assertEquals("Sync recomposition must not restart the long-press timeout", 1, longPresses)
            assertTrue(1L in selected.value)
        }
        compose.runOnUiThread { rows.value = rows.value.map { it.copy(read = true, progress = 50) } }
        compose.mainClock.advanceTimeBy(200)
        chapter.performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(1, longPresses)
            assertEquals(0, clicks)
            assertTrue(1L in selected.value)
        }
    }

    private data class Row(val id: Long, val progress: Int = 0, val read: Boolean = false)
}
