package eu.kanade.presentation.reader

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.kanade.tachiyomi.ui.reader.ReaderUiTestActivity
import eu.kanade.tachiyomi.ui.reader.filter.ReaderChapterSizeIntegrationTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReaderChapterSpamReviewDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ReaderUiTestActivity>()

    @Test fun reviewShowsOriginalContextAndCanRestoreThenReturnToAutomatic() {
        val fixture = ReaderChapterSizeIntegrationTest.Fixture()
        try {
            fixture.ready()
            val page = fixture.pages[3]
            page.stream!!.invoke().close()
            compose.setContent {
                ReaderUiTestHost {
                    ReaderChapterSpamReviewDialog(fixture.chapter, {}, {})
                }
            }
            compose.waitUntil(5_000) { fixture.review.hash(page) != null }
            compose.onNodeWithText("Review excluded pages").assertExists()
            compose.onNodeWithText("Page 4 of 10").assertExists()
            compose.onNodeWithContentDescription("Original page 4").assertExists()
            compose.onNodeWithText("Restore").performClick()
            compose.waitUntil(5_000) { !page.isHidden }
            compose.onNodeWithText("Restored in this chapter").assertExists()
            assertArrayEquals(fixture.bytes[3], page.stream!!.invoke().use { it.readBytes() })
            compose.onNodeWithText("Automatic").performClick()
            compose.waitUntil(5_000) { page.isHidden }
            assertEquals(3, page.index)
        } finally {
            compose.runOnIdle {}
            fixture.close()
        }
    }

    @Test fun ruleEditingAndDismissAreExplicitAndDoNotChangeAChapterChoice() {
        val fixture = ReaderChapterSizeIntegrationTest.Fixture()
        try {
            fixture.ready()
            val page = fixture.pages[3]
            page.stream!!.invoke().close()
            var edited = false
            var dismissed = false
            compose.setContent {
                ReaderUiTestHost {
                    ReaderChapterSpamReviewDialog(fixture.chapter, { dismissed = true }, { edited = it === page })
                }
            }
            compose.onNodeWithText("Edit image rule").performClick()
            compose.runOnIdle {
                assertTrue(edited)
                assertFalse(dismissed)
            }
            compose.onNodeWithText("Close").performClick()
            compose.runOnIdle { assertTrue(dismissed) }
            assertTrue(page.isHidden)
        } finally {
            compose.runOnIdle {}
            fixture.close()
        }
    }
}
