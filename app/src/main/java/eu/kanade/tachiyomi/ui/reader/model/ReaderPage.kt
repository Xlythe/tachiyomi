package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.filter.ReaderImageRules
import java.io.InputStream

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    stream: (() -> InputStream)? = null,
) : Page(index, url, imageUrl, null) {

    var originalStream: (() -> InputStream)? = stream
        private set

    var stream: (() -> InputStream)?
        get() = originalStream?.let { original ->
            {
                ReaderImageRules.open(
                    onMatch = {
                        hidden = it
                        matchedRevision = ReaderImageRules.revision.value
                    },
                    original = original,
                )
            }
        }
        set(value) { originalStream = value }

    @Volatile
    private var matchedRevision = -1L

    @Volatile
    private var hidden = false
    val isHidden: Boolean get() = hidden && matchedRevision == ReaderImageRules.revision.value

    open lateinit var chapter: ReaderChapter
}
