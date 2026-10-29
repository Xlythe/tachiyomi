package eu.kanade.tachiyomi.ui.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderChapterCompletionTest {
    private fun page(index: Int, hidden: Boolean = false, ready: Boolean = true) =
        ReaderChapterCompletion.Page(index, hidden, ready)

    @Test fun croppedFinalPageMustCompleteEvenWhenTheTransitionIsTheLastEndVisibleRow() {
        assertTrue(ReaderChapterCompletion.reachesTerminal(1, 2))
        assertFalse(ReaderChapterCompletion.reachesTerminal(2, 1))
        assertFalse(ReaderChapterCompletion.reachesTerminal(-1, 2))
    }

    @Test fun partialFinalPageKeepsItsOriginalIdentityAndMustReachItsVisibleEnd() {
        val pages = listOf(page(0), page(1), page(2))
        assertEquals(2, ReaderChapterCompletion.terminalIndex(pages))
        assertFalse(ReaderChapterCompletion.completes(pages, 1))
        assertTrue(ReaderChapterCompletion.completes(pages, 2))
    }

    @Test fun hiddenTrailingPagesCompleteAtTheLastVisibleOriginalPage() {
        val pages = listOf(page(4), page(5), page(6, hidden = true), page(7, hidden = true))
        assertEquals(5, ReaderChapterCompletion.terminalIndex(pages))
        assertFalse(ReaderChapterCompletion.completes(pages, 4))
        assertTrue(ReaderChapterCompletion.completes(pages, 5))
    }

    @Test fun pendingOrFailedSuffixIsNeverTreatedAsHiddenOrComplete() {
        assertNull(ReaderChapterCompletion.terminalIndex(listOf(page(0), page(1, ready = false))))
        assertNull(ReaderChapterCompletion.terminalIndex(listOf(page(0), page(1, hidden = true, ready = false))))
    }

    @Test fun allConfirmedHiddenUsesTheLastOriginalIdentityAndEmptyDoesNotComplete() {
        assertEquals(2, ReaderChapterCompletion.terminalIndex(listOf(page(0, true), page(1, true), page(2, true))))
        assertFalse(ReaderChapterCompletion.completes(emptyList(), 0))
    }

    @Test fun laterVisibleContentPreventsPrematureCompletionEvenAfterHiddenPages() {
        val pages = listOf(page(0), page(1, true), page(2))
        assertFalse(ReaderChapterCompletion.completes(pages, 0))
        assertTrue(ReaderChapterCompletion.completes(pages, 2))
    }
}
