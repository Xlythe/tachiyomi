package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.content.Context
import android.graphics.PointF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.core.app.ActivityCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.WebtoonLayoutManager
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderChapterCompletion
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderScrollPositionStore
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import logcat.LogPriority
import tachiyomi.core.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.math.max
import kotlin.math.min

/**
 * Implementation of a [Viewer] to display pages with a [RecyclerView].
 */
class WebtoonViewer(val activity: ReaderActivity, val isContinuous: Boolean = true) : Viewer {

    override fun onImageRuleApplied(page: ReaderPage, hidden: Boolean) {
        page.applyRuleVisibility(hidden)
        val position = adapter.items.indexOfFirst { it === page }
        (recycler.findViewHolderForAdapterPosition(position) as? WebtoonPageHolder)?.onImageRuleApplied()
    }

    val downloadManager: DownloadManager by injectLazy()

    private val scope = MainScope()

    /**
     * Recycler view used by this viewer.
     */
    val recycler = WebtoonRecyclerView(activity)

    /**
     * Frame containing the recycler view.
     */
    private val frame = WebtoonFrame(activity)

    /**
     * Layout manager of the recycler view.
     */
    private val layoutManager = WebtoonLayoutManager(activity)

    /**
     * Configuration used by this viewer, like allow taps, or crop image borders.
     */
    val config = WebtoonConfig(scope)

    /**
     * Adapter of the recycler view.
     */
    private val adapter = WebtoonAdapter(this)

    private val positionIncognito = Injekt.get<BasePreferences>().incognitoMode()
    private var privatePositionSession = positionIncognito.get()
    private val positions by lazy {
        ReaderScrollPositionStore(
            activity.applicationContext.getSharedPreferences(
                ReaderScrollPositionStore.PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            ),
        )
    }
    private var navigationGeneration = 0L
    private var pendingManualLayout = false
    private val positionCheckpoint = Runnable { saveReadingPosition() }

    private fun mayWritePosition(): Boolean {
        // Disabling incognito finishes the activity; never save that private session during its final pause.
        if (positionIncognito.get()) privatePositionSession = true
        return !privatePositionSession
    }

    private fun positionChapter(chapter: ReaderChapter): ReaderScrollPositionStore.Chapter? {
        val id = chapter.chapter.id ?: return null
        val manga = chapter.chapter.manga_id ?: return null
        val url = chapter.chapter.url
        if (id < 0 || manga < 0 || url.isBlank()) return null
        return ReaderScrollPositionStore.Chapter(manga, id, url)
    }

    private fun checkpointPositionLater() {
        recycler.removeCallbacks(positionCheckpoint)
        if (mayWritePosition()) recycler.postDelayed(positionCheckpoint, 750)
    }

    private fun forgetSavedPosition(chapter: ReaderChapter) {
        if (!mayWritePosition()) return
        runCatching { positionChapter(chapter)?.let { positions.remove(it) } }
            .onFailure { logcat(LogPriority.ERROR, it) { "Could not discard old reader position" } }
    }

    override fun saveReadingPosition() {
        if (!mayWritePosition() || pendingManualLayout) return
        try {
            val chapter = adapter.currentChapter ?: return
            val key = positionChapter(chapter) ?: return
            val pages = chapter.pages?.filterNot { it is InsertPage }?.takeIf { it.isNotEmpty() } ?: return
            if (!layoutManager.isRestoringReaderAnchor) {
                val terminal = ReaderChapterCompletion.terminalIndex(
                    pages.map {
                        ReaderChapterCompletion.Page(
                            it.index,
                            it.isHidden,
                            it.status == Page.State.READY && it.isWebtoonImageReady,
                        )
                    },
                )
                val lastPart = chapter.pages?.lastOrNull { it.originalPage.index == terminal && !it.isHidden }
                val terminalPosition = lastPart?.let { adapter.items.indexOf(it) } ?: -1
                val endVisible = layoutManager.findLastEndVisibleItemPosition()
                if (ReaderChapterCompletion.reachesTerminal(terminalPosition, endVisible)) {
                    positions.remove(key)
                    return
                }
            }
            val anchor = layoutManager.readingPosition()?.takeIf { it.key.chapterId == key.chapterId } ?: return
            val original = pages.firstOrNull { it.index == anchor.key.pageIndex } ?: return
            val progress = chapter.chapter.last_page_read
            if (progress !in pages.indices) return
            positions.write(
                key,
                ReaderScrollPositionStore.Position(
                    anchor,
                    progress,
                    pages.size,
                    chapter.chapter.read,
                    ReaderScrollPositionStore.hashPageUrl(original.url),
                ),
            )
        } catch (exception: Exception) {
            logcat(LogPriority.ERROR, exception) { "Could not checkpoint reader position" }
        }
    }

    private fun restoreSavedPosition(chapter: ReaderChapter): Boolean {
        if (layoutManager.isRestoringReaderAnchor) return true // Recreation state is newer than a disk checkpoint.
        return try {
            val key = positionChapter(chapter) ?: return false
            val saved = positions.read(key) ?: return false
            val pages = chapter.pages?.filterNot { it is InsertPage } ?: return false
            val original = pages.firstOrNull { it.index == saved.anchor.key.pageIndex }
            val compatible = original != null && saved.matches(
                chapter.chapter.last_page_read,
                pages.size,
                chapter.chapter.read,
                original.url,
            )
            if (compatible) {
                layoutManager.restoreReadingPosition(saved.anchor)
                true
            } else {
                if (mayWritePosition()) positions.remove(key)
                false
            }
        } catch (exception: Exception) {
            logcat(LogPriority.ERROR, exception) { "Could not restore reader position" }
            false
        }
    }

    /**
     * Distance to scroll when the user taps on one side of the recycler view.
     */
    private var scrollDistance = activity.resources.displayMetrics.heightPixels * 3 / 4

    /**
     * Currently active item. It can be a chapter page or a chapter transition.
     */
    private var currentPage: Any? = null

    private val threshold: Int =
        Injekt.get<ReaderPreferences>()
            .readerHideThreshold()
            .get()
            .threshold

    init {
        recycler.isVisible = false // Don't let the recycler layout yet
        recycler.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        recycler.isFocusable = false
        recycler.itemAnimator = null
        recycler.layoutManager = layoutManager
        recycler.adapter = adapter
        layoutManager.onReaderAnchorRestored = {
            recycler.post { if (scope.isActive) onScrolled() }
        }
        recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    onScrolled()

                    if ((dy > threshold || dy < -threshold) && activity.viewModel.state.value.menuVisible) {
                        activity.hideMenu()
                    }

                    if (dy < 0) {
                        val firstIndex = layoutManager.findFirstVisibleItemPosition()
                        val firstItem = adapter.items.getOrNull(firstIndex)
                        if (firstItem is ChapterTransition.Prev && firstItem.to != null) {
                            activity.requestPreloadChapter(firstItem.to)
                        }
                    }

                    val lastIndex = layoutManager.findLastEndVisibleItemPosition()
                    val lastItem = adapter.items.getOrNull(lastIndex)
                    if (lastItem is ChapterTransition.Next && lastItem.to == null) {
                        activity.showMenu()
                    }
                }
            },
        )
        recycler.tapListener = { event ->
            val viewPosition = IntArray(2)
            recycler.getLocationOnScreen(viewPosition)
            val viewPositionRelativeToWindow = IntArray(2)
            recycler.getLocationInWindow(viewPositionRelativeToWindow)
            val pos = PointF(
                (event.rawX - viewPosition[0] + viewPositionRelativeToWindow[0]) / recycler.width,
                (event.rawY - viewPosition[1] + viewPositionRelativeToWindow[1]) / recycler.height,
            )
            when (config.navigator.getAction(pos)) {
                NavigationRegion.MENU -> activity.toggleMenu()
                NavigationRegion.NEXT, NavigationRegion.RIGHT -> scrollDown()
                NavigationRegion.PREV, NavigationRegion.LEFT -> scrollUp()
            }
        }
        recycler.longTapListener = f@{ event ->
            if (activity.viewModel.state.value.menuVisible || config.longTapEnabled) {
                val child = recycler.findChildViewUnder(event.x, event.y)
                if (child != null) {
                    val position = recycler.getChildAdapterPosition(child)
                    val item = adapter.items.getOrNull(position)
                    if (item is ReaderPage) {
                        activity.onPageLongTap(item)
                        return@f true
                    }
                }
            }
            false
        }

        config.imagePropertyChangedListener = {
            refreshAdapter()
        }

        config.themeChangedListener = {
            ActivityCompat.recreate(activity)
        }

        config.doubleTapZoomChangedListener = {
            frame.doubleTapZoom = it
        }

        config.navigationModeChangedListener = {
            val showOnStart = config.navigationOverlayOnStart || config.forceNavigationOverlay
            activity.binding.navigationOverlay.setNavigation(config.navigator, showOnStart)
        }

        frame.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        frame.addView(recycler)
    }

    private fun checkAllowPreload(page: ReaderPage?): Boolean {
        // Page is transition page - preload allowed
        page ?: return true

        // Initial opening - preload allowed
        currentPage ?: return true

        val nextItem = adapter.items.getOrNull(adapter.items.size - 1)
        val nextChapter = (nextItem as? ChapterTransition.Next)?.to ?: (nextItem as? ReaderPage)?.chapter

        // Allow preload for
        // 1. Going between pages of same chapter
        // 2. Next chapter page
        return when (page.chapter) {
            (currentPage as? ReaderPage)?.chapter -> true
            nextChapter -> true
            else -> false
        }
    }

    /**
     * Returns the view this viewer uses.
     */
    override fun getView(): View {
        return frame
    }

    /**
     * Destroys this viewer. Called when leaving the reader or swapping viewers.
     */
    override fun destroy() {
        recycler.removeCallbacks(positionCheckpoint)
        saveReadingPosition()
        super.destroy()
        scope.cancel()
    }

    /**
     * Called from the RecyclerView listener when a [page] is marked as active. It notifies the
     * activity of the change and requests the preload of the next chapter if this is the last page.
     */
    private fun onPageSelected(page: ReaderPage, allowPreload: Boolean) {
        val pages = page.chapter.pages ?: return
        logcat { "onPageSelected: ${page.number}/${pages.size}" }
        activity.onPageSelected(page, allowCompletion = false)

        // Preload next chapter once we're within the last 5 pages of the current chapter
        val inPreloadRange = pages.size - page.number < 5
        if (inPreloadRange && allowPreload && page.chapter == adapter.currentChapter) {
            logcat { "Request preload next chapter because we're at page ${page.number} of ${pages.size}" }
            val nextItem = adapter.items.getOrNull(adapter.items.size - 1)
            val transitionChapter = (nextItem as? ChapterTransition.Next)?.to ?: (nextItem as?ReaderPage)?.chapter
            if (transitionChapter != null) {
                logcat { "Requesting to preload chapter ${transitionChapter.chapter.chapter_number}" }
                activity.requestPreloadChapter(transitionChapter)
            }
        }
    }

    /**
     * Called from the RecyclerView listener when a [transition] is marked as active. It request the
     * preload of the destination chapter of the transition.
     */
    private fun onTransitionSelected(transition: ChapterTransition) {
        logcat { "onTransitionSelected: $transition" }
        val toChapter = transition.to
        if (toChapter != null) {
            logcat { "Request preload destination chapter because we're on the transition" }
            activity.requestPreloadChapter(toChapter)
        }
    }

    /**
     * Tells this viewer to set the given [chapters] as active.
     */
    override fun setChapters(chapters: ViewerChapters) {
        if (adapter.currentChapter?.chapter?.id != chapters.currChapter.chapter.id) saveReadingPosition()
        val forceTransition = config.alwaysShowChapterTransition || currentPage is ChapterTransition
        adapter.setChapters(chapters, forceTransition)

        if (recycler.isGone) {
            logcat { "Recycler first layout" }
            val pages = chapters.currChapter.pages ?: return
            if (pages.isEmpty()) return
            if (!restoreSavedPosition(chapters.currChapter)) {
                positionAtPage(pages[chapters.currChapter.requestedPage.coerceIn(0, pages.lastIndex)], explicit = false)
            }
            recycler.isVisible = true
        }
    }

    /**
     * Tells this viewer to move to the given [page].
     */
    override fun moveToPage(page: ReaderPage) {
        positionAtPage(page, explicit = true)
    }

    private fun positionAtPage(page: ReaderPage, explicit: Boolean) {
        val position = adapter.items.indexOf(page)
        if (position != -1) {
            if (explicit) {
                layoutManager.discardPendingHierarchyPosition()
                forgetSavedPosition(page.chapter)
                val generation = ++navigationGeneration
                pendingManualLayout = true
                recycler.doOnNextLayout {
                    if (scope.isActive && generation == navigationGeneration) {
                        pendingManualLayout = false
                        checkpointPositionLater()
                    }
                }
            } else {
                layoutManager.cancelReaderAnchorRestoration()
            }
            layoutManager.scrollToPositionWithOffset(position, 0)
            if (layoutManager.findLastEndVisibleItemPosition() == -1) {
                onScrolled(pos = position)
            }
        } else {
            logcat { "Page $page not found in adapter" }
        }
    }

    fun onScrolled(pos: Int? = null) {
        if (layoutManager.isRestoringReaderAnchor) return
        val position = pos ?: layoutManager.findLastEndVisibleItemPosition()
        val item = adapter.items.getOrNull(position)
        // End-visible selection can land on the transition after a short cropped final page.
        // Report content completion independently of which row becomes the selected UI item.
        if (pos == null) {
            adapter.currentChapter?.let { chapter ->
                val pages = chapter.pages?.filterNot { it is InsertPage }.orEmpty()
                val terminal = ReaderChapterCompletion.terminalIndex(
                    pages.map {
                        ReaderChapterCompletion.Page(
                            it.index,
                            it.isHidden,
                            it.status == Page.State.READY && it.isWebtoonImageReady,
                        )
                    },
                )
                val terminalPage = pages.firstOrNull { it.index == terminal }
                val terminalPosition = terminalPage?.let { adapter.items.indexOf(it) } ?: -1
                if (ReaderChapterCompletion.reachesTerminal(terminalPosition, position)) {
                    activity.viewModel.onVisibleChapterEnd(chapter, requireNotNull(terminalPage).index)
                }
            }
        }
        val allowPreload = checkAllowPreload(item as? ReaderPage)
        if (item != null && currentPage != item) {
            currentPage = item
            when (item) {
                is ReaderPage -> onPageSelected(item, allowPreload)
                is ChapterTransition -> onTransitionSelected(item)
            }
        }
        checkpointPositionLater()
    }

    /**
     * Scrolls up by [scrollDistance].
     */
    private fun scrollUp() {
        if (config.usePageTransitions) {
            recycler.smoothScrollBy(0, -scrollDistance)
        } else {
            recycler.scrollBy(0, -scrollDistance)
        }
    }

    /**
     * Scrolls down by [scrollDistance].
     */
    private fun scrollDown() {
        if (config.usePageTransitions) {
            recycler.smoothScrollBy(0, scrollDistance)
        } else {
            recycler.scrollBy(0, scrollDistance)
        }
    }

    /**
     * Called from the containing activity when a key [event] is received. It should return true
     * if the event was handled, false otherwise.
     */
    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) scrollDown() else scrollUp()
                }
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) scrollUp() else scrollDown()
                }
            }
            KeyEvent.KEYCODE_MENU -> if (isUp) activity.toggleMenu()

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_PAGE_UP,
            -> if (isUp) scrollUp()

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN,
            -> if (isUp) scrollDown()
            else -> return false
        }
        return true
    }

    /**
     * Called from the containing activity when a generic motion [event] is received. It should
     * return true if the event was handled, false otherwise.
     */
    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        return false
    }

    /**
     * Notifies adapter of changes around the current page to trigger a relayout in the recycler.
     * Used when an image configuration is changed.
     */
    private fun refreshAdapter() {
        val position = layoutManager.findLastEndVisibleItemPosition()
        adapter.refresh()
        adapter.notifyItemRangeChanged(
            max(0, position - 3),
            min(position + 3, adapter.itemCount - 1),
        )
    }
}
