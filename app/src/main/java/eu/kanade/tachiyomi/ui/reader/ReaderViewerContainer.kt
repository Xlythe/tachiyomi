package eu.kanade.tachiyomi.ui.reader

import android.content.Context
import android.os.Parcelable
import android.util.AttributeSet
import android.util.SparseArray
import android.view.View
import android.widget.FrameLayout
import eu.kanade.tachiyomi.R

/** Chapter initialization may attach the viewer after Android restores the activity's hierarchy. */
class ReaderViewerContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    // Retain only the two reader IDs, never an activity's entire saved hierarchy or page images.
    private var pendingViewerState: SparseArray<Parcelable>? = null

    override fun dispatchRestoreInstanceState(container: SparseArray<Parcelable>) {
        pendingViewerState = SparseArray<Parcelable>(VIEWER_IDS.size).apply {
            VIEWER_IDS.forEach { id -> container.get(id)?.let { put(id, it) } }
        }
        super.dispatchRestoreInstanceState(container)
        discardRestoredEntries(this)
    }

    override fun dispatchSaveInstanceState(container: SparseArray<Parcelable>) {
        super.dispatchSaveInstanceState(container)
        // Another rotation/process save while data is loading must not replace this with empty state.
        pendingViewerState?.let { pending ->
            for (index in 0 until pending.size()) {
                val id = pending.keyAt(index)
                if (container.indexOfKey(id) < 0) container.put(id, pending.valueAt(index))
            }
        }
    }

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        val pending = pendingViewerState ?: return
        val readerIds = VIEWER_IDS.filter { child.findViewById<View>(it) != null }
        if (readerIds.isEmpty()) return // A loading/error overlay is not the reader.
        child.restoreHierarchyState(pending)
        // A different reader mode must not replay this state on a later deliberate mode switch.
        pendingViewerState = null
    }

    private fun discardRestoredEntries(view: View) {
        val pending = pendingViewerState ?: return
        for (index in pending.size() - 1 downTo 0) {
            if (view.findViewById<View>(pending.keyAt(index)) != null) pending.removeAt(index)
        }
        if (pending.size() == 0) pendingViewerState = null
    }

    private companion object {
        val VIEWER_IDS = intArrayOf(R.id.reader_webtoon, R.id.reader_pager)
    }
}
