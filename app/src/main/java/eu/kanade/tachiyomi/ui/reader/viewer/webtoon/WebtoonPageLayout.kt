package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewGroup.MarginLayoutParams
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView

/** Shared by initial binding and the asynchronous READY/restore path. */
internal fun ReaderPageImageView.updateWebtoonPageLayout(sideMargin: Int, bottomGap: Int) {
    // RecyclerView owns this object and stores its ViewHolder in it. Refreshing an attached page
    // must preserve both its subtype and identity, including when restoring a hidden page's height.
    val params = layoutParams as? MarginLayoutParams ?: RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    params.apply {
        width = MATCH_PARENT
        height = WRAP_CONTENT
        bottomMargin = bottomGap
        marginStart = sideMargin
        marginEnd = sideMargin
    }
    layoutParams = params
}
