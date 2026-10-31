@file:Suppress("PackageDirectoryMismatch")

package androidx.recyclerview.widget

import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import androidx.recyclerview.widget.RecyclerView.NO_POSITION
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderScrollAnchor
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonAdapter
import kotlin.math.abs

/**
 * Layout manager used by the webtoon viewer. Item prefetch is disabled because the extra layout
 * space feature is used which allows setting the image even if the holder is not visible,
 * avoiding (in most cases) black views when they are visible.
 *
 * This layout manager uses the same package name as the support library in order to use a package
 * protected method.
 */
class WebtoonLayoutManager(context: Context) : LinearLayoutManager(context) {

    private var readerAnchor: ReaderScrollAnchor? = null
    private var requestedAnchorPosition = NO_POSITION
    private var appliedAnchor: AppliedAnchor? = null
    private var hierarchyPositionDiscarded = false
    internal var onReaderAnchorRestored: (() -> Unit)? = null
    internal val isRestoringReaderAnchor: Boolean get() = readerAnchor != null

    internal fun readingPosition(): ReaderScrollAnchor? = readerAnchor ?: captureReaderAnchor()

    internal fun restoreReadingPosition(anchor: ReaderScrollAnchor) {
        readerAnchor = anchor
        requestedAnchorPosition = NO_POSITION
        appliedAnchor = null
        requestLayout()
    }

    internal fun discardPendingHierarchyPosition() {
        hierarchyPositionDiscarded = true
        cancelReaderAnchorRestoration()
    }

    private data class PositionedPage(val position: Int, val page: ReaderPage, val key: ReaderScrollAnchor.Key)
    private data class AppliedAnchor(val position: Int, val width: Int, val imageHeight: Int, val offset: Int)

    override fun onSaveInstanceState(): Parcelable? {
        val parent = super.onSaveInstanceState()
        val anchor = readerAnchor ?: captureReaderAnchor() ?: return parent
        return Bundle().apply {
            putInt("reader_anchor_version", 1)
            putParcelable("parent", parent)
            putLong("chapter", anchor.key.chapterId)
            putInt("page", anchor.key.pageIndex)
            putInt("part", anchor.key.part)
            putDouble("fraction", anchor.fraction)
            putDouble("viewport", anchor.viewportFraction)
        }
    }

    @Suppress("DEPRECATION")
    override fun onRestoreInstanceState(state: Parcelable?) {
        if (hierarchyPositionDiscarded) return // A deliberate page choice wins over a later framework callback.
        if (state !is Bundle || state.getInt("reader_anchor_version") != 1) {
            super.onRestoreInstanceState(state)
            return
        }
        state.classLoader = javaClass.classLoader
        super.onRestoreInstanceState(state.getParcelable<Parcelable>("parent"))
        readerAnchor = runCatching {
            ReaderScrollAnchor(
                ReaderScrollAnchor.Key(
                    state.getLong("chapter", -1),
                    state.getInt("page", -1),
                    state.getInt("part", -1),
                ),
                state.getDouble("fraction", Double.NaN),
                state.getDouble("viewport", Double.NaN),
            )
        }.getOrNull()
        requestedAnchorPosition = NO_POSITION
        appliedAnchor = null
        requestLayout()
    }

    internal fun cancelReaderAnchorRestoration() {
        readerAnchor = null
        requestedAnchorPosition = NO_POSITION
        appliedAnchor = null
    }

    private fun finishReaderAnchorRestoration() {
        val wasRestoring = readerAnchor != null
        cancelReaderAnchorRestoration()
        if (wasRestoring) onReaderAnchorRestored?.invoke()
    }

    private fun pageKey(page: ReaderPage): ReaderScrollAnchor.Key? {
        val original = page.originalPage
        val chapter = original.chapter.chapter.id ?: return null
        if (chapter < 0 || original.index < 0) return null
        return ReaderScrollAnchor.Key(chapter, original.index, if (page is InsertPage) 1 else 0)
    }

    private fun captureReaderAnchor(): ReaderScrollAnchor? {
        val adapter = mRecyclerView?.adapter as? WebtoonAdapter ?: return null
        for (position in findFirstVisibleItemPosition()..findLastVisibleItemPosition()) {
            val page = adapter.items.getOrNull(position) as? ReaderPage ?: continue
            if (page.isHidden || page.status != Page.State.READY || !page.isWebtoonImageReady) continue
            val key = pageKey(page) ?: continue
            val view = findViewByPosition(position) ?: continue
            ReaderScrollAnchor.capture(
                key,
                view.top + view.paddingTop,
                view.height - view.paddingTop - view.paddingBottom,
                paddingTop,
                height - paddingTop - paddingBottom,
            )?.let { return it }
        }
        return null
    }

    override fun onLayoutCompleted(state: RecyclerView.State?) {
        super.onLayoutCompleted(state)
        if (state?.isPreLayout == true) return
        val anchor = readerAnchor ?: return
        val adapter = mRecyclerView?.adapter as? WebtoonAdapter ?: return
        val chapter = adapter.items.mapIndexedNotNull { position, item ->
            val page = item as? ReaderPage ?: return@mapIndexedNotNull null
            val key = pageKey(page) ?: return@mapIndexedNotNull null
            if (key.chapterId == anchor.key.chapterId) PositionedPage(position, page, key) else null
        }
        if (chapter.isEmpty()) return // Chapter data has not arrived; keep the saved content point.
        val visible = chapter.filterNot { it.page.isHidden }
        if (visible.isEmpty()) {
            finishReaderAnchorRestoration()
            return
        }
        val exact = visible.firstOrNull { it.key == anchor.key }
            ?: visible.firstOrNull { it.key.pageIndex == anchor.key.pageIndex }
        val target = exact ?: visible.firstOrNull { it.key.pageIndex > anchor.key.pageIndex } ?: visible.last()
        val targetAnchor = if (exact != null) {
            anchor
        } else {
            // Never reveal a hidden image to satisfy a saved offset; use the next visible page or previous end.
            val end = if (target.key.pageIndex < anchor.key.pageIndex) 1.0 else 0.0
            ReaderScrollAnchor(target.key, end, end)
        }
        val view = findViewByPosition(target.position)
        if (view == null) {
            if (requestedAnchorPosition != target.position) {
                requestedAnchorPosition = target.position
                super.scrollToPositionWithOffset(target.position, 0)
            }
            return
        }
        if (target.page.status != Page.State.READY || !target.page.isWebtoonImageReady || view.isLayoutRequested) return
        val imageHeight = view.height - view.paddingTop - view.paddingBottom
        val viewportHeight = height - paddingTop - paddingBottom
        if (imageHeight <= 0 || viewportHeight <= 0) return
        val decoratedTop = getDecoratedTop(view) - (view.layoutParams as RecyclerView.LayoutParams).topMargin
        val imageInset = view.top + view.paddingTop - decoratedTop
        val offset = targetAnchor.imageTop(imageHeight, paddingTop, viewportHeight) - imageInset - paddingTop
        val applied = AppliedAnchor(target.position, view.width, imageHeight, offset)
        if (abs(decoratedTop - paddingTop - offset) <= 1 || appliedAnchor == applied) {
            // A repeated application may be clamped at a chapter edge. Do not fight the layout indefinitely.
            finishReaderAnchorRestoration()
        } else {
            appliedAnchor = applied
            super.scrollToPositionWithOffset(target.position, offset)
        }
    }

    /**
     * Extra layout space is set to half the screen height.
     */
    private val extraLayoutSpace = context.resources.displayMetrics.heightPixels / 2

    init {
        isItemPrefetchEnabled = false
    }

    /**
     * Returns the custom extra layout space.
     */
    override fun getExtraLayoutSpace(state: RecyclerView.State): Int {
        return extraLayoutSpace
    }

    /**
     * Returns the position of the last item whose end side is visible on screen.
     */
    fun findLastEndVisibleItemPosition(): Int {
        ensureLayoutState()
        @ViewBoundsCheck.ViewBounds val preferredBoundsFlag =
            (ViewBoundsCheck.FLAG_CVE_LT_PVE or ViewBoundsCheck.FLAG_CVE_EQ_PVE)

        val fromIndex = childCount - 1
        val toIndex = -1

        val child = if (mOrientation == HORIZONTAL) {
            mHorizontalBoundCheck
                .findOneViewWithinBoundFlags(fromIndex, toIndex, preferredBoundsFlag, 0)
        } else {
            mVerticalBoundCheck
                .findOneViewWithinBoundFlags(fromIndex, toIndex, preferredBoundsFlag, 0)
        }

        return if (child == null) NO_POSITION else getPosition(child)
    }
}
