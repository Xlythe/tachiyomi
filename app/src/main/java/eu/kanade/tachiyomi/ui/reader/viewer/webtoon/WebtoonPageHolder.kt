package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.content.res.Resources
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updateMargins
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.filter.ReaderImageRules
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.dpToPx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import logcat.LogPriority
import tachiyomi.core.util.lang.launchIO
import tachiyomi.core.util.lang.withIOContext
import tachiyomi.core.util.lang.withUIContext
import tachiyomi.core.util.system.ImageUtil
import tachiyomi.core.util.system.logcat
import java.io.BufferedInputStream
import java.io.InputStream

/**
 * Holder of the webtoon reader for a single page of a chapter.
 *
 * @param frame the root view for this holder.
 * @param viewer the webtoon viewer.
 * @constructor creates a new webtoon holder.
 */
class WebtoonPageHolder(
    private val frame: ReaderPageImageView,
    viewer: WebtoonViewer,
) : WebtoonBaseHolder(frame, viewer) {

    /**
     * Loading progress bar to indicate the current progress.
     */
    private val progressIndicator = createProgressIndicator()

    /**
     * Progress bar container. Needed to keep a minimum height size of the holder, otherwise the
     * adapter would create more views to fill the screen, which is not wanted.
     */
    private lateinit var progressContainer: ViewGroup

    /**
     * Error layout to show when the image fails to load.
     */
    private var errorLayout: ReaderErrorBinding? = null

    /**
     * Getter to retrieve the height of the recycler view.
     */
    private val parentHeight
        get() = (viewer.recycler.height.takeIf { it > 0 } ?: frame.resources.displayMetrics.heightPixels).coerceAtLeast(
            1,
        )

    /**
     * Page of a chapter.
     */
    private var page: ReaderPage? = null

    private val scope = MainScope()
    private val geometry = WebtoonPageGeometry(frame, viewer.recycler)

    /**
     * Job for loading the page.
     */
    private var loadJob: Job? = null

    init {
        refreshLayoutParams()
        geometry.bind(null)

        frame.onScaleChanged = { viewer.activity.hideMenu() }
    }

    /**
     * Binds the given [page] with this view holder, subscribing to its state.
     */
    fun bind(page: ReaderPage) {
        loadJob?.cancel()
        frame.recycle()
        this.page = page
        refreshLayoutParams()
        val token = geometry.bind(page.displayDimensions)
        if (page.isHidden) showHidden(token)
        loadJob = scope.launch { loadPageAndProcessStatus() }
    }

    fun onImageRuleApplied() {
        loadJob?.cancel()
        if (page?.isHidden == true) showHidden(geometry.begin()) else geometry.pending()
        loadJob = scope.launch { loadPageAndProcessStatus() }
    }

    private fun showHidden(token: Long) {
        if (!geometry.isCurrent(token)) return
        frame.recycle()
        progressContainer.isVisible = false
        removeErrorLayout()
        geometry.hide(token)
    }

    private fun refreshLayoutParams() {
        val margin = Resources.getSystem().displayMetrics.widthPixels * (viewer.config.sidePadding / 100f)
        frame.updateWebtoonPageLayout(margin.toInt(), if (viewer.isContinuous) 0 else 15.dpToPx)
    }

    /**
     * Called when the view is recycled and added to the view pool.
     */
    override fun recycle() {
        loadJob?.cancel()
        loadJob = null
        geometry.invalidate()
        page = null

        removeErrorLayout()
        frame.recycle()
        progressIndicator.setProgress(0)
    }

    /**
     * Loads the page and processes changes to the page's status.
     *
     * Returns immediately if there is no page or the page has no PageLoader.
     * Otherwise, this function does not return. It will continue to process status changes until
     * the Job is cancelled.
     */
    private suspend fun loadPageAndProcessStatus() {
        val page = page ?: return
        val loader = page.chapter.pageLoader ?: return
        supervisorScope {
            launchIO {
                loader.loadPage(page)
            }
            combine(
                page.statusFlow,
                ReaderImageRules.revision,
                ReaderImageRules.preparedRevision,
                ReaderImageRules.automaticRevision,
            ) { state, _, _, _ ->
                state
            }.collectLatest { state ->
                when (state) {
                    Page.State.QUEUE -> setQueued()
                    Page.State.LOAD_PAGE -> setLoading()
                    Page.State.DOWNLOAD_IMAGE -> {
                        setDownloading()
                        page.progressFlow.collectLatest { value ->
                            progressIndicator.setProgress(value)
                        }
                    }
                    Page.State.READY -> setImage()
                    Page.State.ERROR -> setError()
                }
            }
        }
    }

    /**
     * Called when the page is queued.
     */
    private fun setQueued() {
        progressContainer.isVisible = true
        progressIndicator.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is loading.
     */
    private fun setLoading() {
        progressContainer.isVisible = true
        progressIndicator.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is downloading
     */
    private fun setDownloading() {
        progressContainer.isVisible = true
        progressIndicator.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is ready.
     */
    private suspend fun setImage() {
        progressIndicator.setProgress(0)
        val page = page ?: return
        val token = geometry.begin()
        if (page.isHidden) {
            showHidden(token)
            return
        }
        progressContainer.isVisible = true
        progressIndicator.show()

        val streamFn = page.stream ?: return setError()

        try {
            if (page.displayDimensions == null) {
                val bounds = withIOContext {
                    runCatching {
                        page.originalStream?.invoke()?.use { raw ->
                            BitmapFactory.Options().apply {
                                inJustDecodeBounds = true
                                BitmapFactory.decodeStream(raw, null, this)
                            }
                        }
                    }.getOrNull()
                }
                if (bounds != null && bounds.outWidth > 0 && bounds.outHeight > 0 && geometry.isCurrent(token)) {
                    page.displayDimensions = bounds.outWidth to bounds.outHeight
                    geometry.metadata(token, bounds.outWidth, bounds.outHeight)
                }
            }
            val (openStream, isAnimated) = withIOContext {
                // Buffer the processed result so cancellation cannot leave a ZIP/file stream open.
                streamFn().buffered(16).use { stream ->
                    if (page.isHidden) return@withIOContext Pair(byteArrayOf().inputStream(), false)
                    process(stream).use { processed ->
                        val buffered = processed.readBytes().inputStream()
                        val animated = ImageUtil.isAnimatedAndSupported(buffered)
                        buffered.reset()
                        Pair(buffered, animated)
                    }
                }
            }
            if (!geometry.isCurrent(token)) {
                openStream.close()
                return
            }
            if (page.isHidden) {
                openStream.close()
                showHidden(token)
                return
            }
            withUIContext {
                if (!geometry.isCurrent(token)) return@withUIContext
                frame.onImageLoaded = {
                    if (geometry.isCurrent(token)) {
                        frame.imageDimensions()?.let { (width, height) ->
                            page.displayDimensions = width to height
                            geometry.loaded(token, width, height)
                        }
                        onImageDecoded()
                    }
                }
                frame.onImageLoadError = { if (geometry.isCurrent(token)) setError() }
                frame.setImage(
                    openStream,
                    isAnimated,
                    ReaderPageImageView.Config(
                        zoomDuration = viewer.config.doubleTapAnimDuration,
                        minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_FIT_WIDTH,
                        cropBorders = viewer.config.imageCropBorders,
                    ),
                )
                removeErrorLayout()
            }
            // Suspend the coroutine to close the input stream only when the WebtoonPageHolder is recycled
            suspendCancellableCoroutine<Nothing> { continuation ->
                continuation.invokeOnCancellation { openStream.close() }
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e)
            withUIContext {
                if (geometry.isCurrent(token)) setError()
            }
        }
    }

    private fun process(imageStream: BufferedInputStream): InputStream {
        if (page?.isHidden == true) return imageStream
        if (viewer.config.dualPageRotateToFit) {
            return rotateDualPage(imageStream)
        }

        if (viewer.config.dualPageSplit) {
            val isDoublePage = ImageUtil.isWideImage(imageStream)
            if (isDoublePage) {
                val upperSide = if (viewer.config.dualPageInvert) ImageUtil.Side.LEFT else ImageUtil.Side.RIGHT
                return ImageUtil.splitAndMerge(imageStream, upperSide)
            }
        }

        return imageStream
    }

    private fun rotateDualPage(imageStream: BufferedInputStream): InputStream {
        val isDoublePage = ImageUtil.isWideImage(imageStream)
        return if (isDoublePage) {
            val rotation = if (viewer.config.dualPageRotateToFitInvert) -90f else 90f
            ImageUtil.rotateImage(imageStream, rotation)
        } else {
            imageStream
        }
    }

    /**
     * Called when the page has an error.
     */
    private fun setError() {
        progressContainer.isVisible = false
        initErrorLayout()
    }

    /**
     * Called when the image is decoded and going to be displayed.
     */
    private fun onImageDecoded() {
        progressContainer.isVisible = false
        removeErrorLayout()
    }

    /**
     * Creates a new progress bar.
     */
    private fun createProgressIndicator(): ReaderProgressIndicator {
        progressContainer = FrameLayout(context)
        frame.addView(progressContainer, MATCH_PARENT, MATCH_PARENT)

        val progress = ReaderProgressIndicator(context).apply {
            updateLayoutParams<FrameLayout.LayoutParams> {
                updateMargins(top = parentHeight / 4)
            }
        }
        progressContainer.addView(progress)
        return progress
    }

    /**
     * Initializes a button to retry pages.
     */
    private fun initErrorLayout(): ReaderErrorBinding {
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(context), frame, true)
            errorLayout?.root?.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, (parentHeight * 0.8).toInt())
            errorLayout?.actionRetry?.setOnClickListener {
                page?.let { it.chapter.pageLoader?.retryPage(it) }
            }
        }

        val imageUrl = page?.imageUrl
        errorLayout?.actionOpenInWebView?.isVisible = imageUrl != null
        if (imageUrl != null) {
            if (imageUrl.startsWith("http", true)) {
                errorLayout?.actionOpenInWebView?.setOnClickListener {
                    val intent = WebViewActivity.newIntent(context, imageUrl)
                    context.startActivity(intent)
                }
            }
        }

        return errorLayout!!
    }

    /**
     * Removes the decode error layout from the holder, if found.
     */
    private fun removeErrorLayout() {
        errorLayout?.let {
            frame.removeView(it.root)
            errorLayout = null
        }
    }
}
