package eu.kanade.presentation.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.tachiyomi.ui.reader.filter.ImageBounds
import eu.kanade.tachiyomi.ui.reader.filter.ImagePoint
import eu.kanade.tachiyomi.ui.reader.filter.ReaderImageMask
import eu.kanade.tachiyomi.ui.reader.filter.ReaderImageRules
import eu.kanade.tachiyomi.ui.reader.filter.ReaderImageSnap
import eu.kanade.tachiyomi.ui.reader.filter.ReaderImageTransform
import eu.kanade.tachiyomi.ui.reader.filter.ReaderSpamReports
import eu.kanade.tachiyomi.ui.reader.filter.ReaderWatermarkEditor
import eu.kanade.tachiyomi.ui.reader.filter.prepareReaderEditor
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt

// Cancelled native previews finish serially; a new dialog cannot accumulate full-page renderers.
private val watermarkPreviewMutex = Mutex()

/** The shaded, full-width band is removed; content above and below it is joined. */
@Composable
fun ReaderImageMaskDialog(
    page: ReaderPage,
    metadata: JSONObject,
    onDismiss: () -> Unit,
    onApplied: (String) -> Unit,
    onRuleChanged: (Boolean) -> Unit = {},
    contrastEdges: suspend (ByteArray) -> List<Float> = { raw ->
        withContext(Dispatchers.IO) { ReaderImageMask(raw).use { it.contrastEdges() } }
    },
    watermarkPreviewRenderer: suspend (ByteArray, Rect) -> ByteArray = { raw, area ->
        ReaderImageMask(raw).use { it.renderWatermark(area) }
    },
) {
    // Keep the modal window stable; its page-keyed content owns cancellation and preparation.
    ReaderImageMaskDialogContent(
        page,
        metadata,
        onDismiss,
        onApplied,
        onRuleChanged,
        contrastEdges,
        watermarkPreviewRenderer,
    )
}

@Composable
private fun ReaderImageMaskDialogContent(
    page: ReaderPage,
    metadata: JSONObject,
    onDismiss: () -> Unit,
    onApplied: (String) -> Unit,
    onRuleChanged: (Boolean) -> Unit,
    contrastEdges: suspend (ByteArray) -> List<Float>,
    watermarkPreviewRenderer: suspend (ByteArray, Rect) -> ByteArray,
) {
    // Keep preparation and every state consumer in the dialog's composition. Publishing from
    // the parent composition can race a dialog's initial snapshot and leave its loading UI stale.
    var busy by remember(page) { mutableStateOf(false) }
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        key(page) {
            var bytes by remember(page) { mutableStateOf<ByteArray?>(null) }
            var preview by remember(page) { mutableStateOf<ImageBitmap?>(null) }
            var height by remember(page) { mutableStateOf(1) }
            var sourceWidth by remember(page) { mutableStateOf(1) }
            var top by remember(page) { mutableStateOf(0f) }
            var bottom by remember(page) { mutableStateOf(1f) }
            var edges by remember(page) { mutableStateOf(emptyList<Float>()) }
            var existingHash by remember(page) { mutableStateOf<String?>(null) }
            var zoom by remember(page) { mutableStateOf(1f) }
            var pan by remember(page) { mutableStateOf(Offset.Zero) }
            var error by remember { mutableStateOf<String?>(null) }
            var notice by remember { mutableStateOf<String?>(null) }
            var watermarkMode by remember(page) { mutableStateOf(false) }
            var editor by remember(page) { mutableStateOf(ReaderWatermarkEditor("")) }
            var guessedPreview by remember(page) { mutableStateOf<ImageBitmap?>(null) }
            var guessedToken by remember(page) { mutableStateOf<ReaderWatermarkEditor.Preview?>(null) }
            var previewJob by remember(page) { mutableStateOf<Job?>(null) }
            var showOriginal by remember(page) { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            val context = LocalContext.current
            val imagePadding = with(LocalDensity.current) { 24.dp.toPx() }

            fun transfer(importing: Boolean, uri: android.net.Uri) {
                busy = true
                error = null
                scope.launch {
                    try {
                        notice = withContext(Dispatchers.IO) {
                            if (importing) {
                                val count = requireNotNull(context.contentResolver.openInputStream(uri)).use {
                                    ReaderImageRules.importArchive(it)
                                }
                                "Imported $count new rules. Existing choices were kept."
                            } else {
                                requireNotNull(context.contentResolver.openOutputStream(uri)).use {
                                    ReaderImageRules.exportArchive(it)
                                }
                                "Exported rules and original samples. " +
                                    "The archive includes reading metadata; share it privately."
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        error = e.message ?: "Could not transfer image rules"
                    } finally { busy = false }
                }
            }
            val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {
                if (it != null) transfer(false, it)
            }
            val importRulesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
                if (it != null) transfer(true, it)
            }

            fun updateEditor(next: ReaderWatermarkEditor) {
                if (next.revision != editor.revision || next.imageKey != editor.imageKey || next.closed) {
                    previewJob?.cancel()
                    guessedPreview = null
                    guessedToken = null
                    showOriginal = false
                }
                editor = next
            }

            fun dismissEditor() {
                updateEditor(editor.close())
                onDismiss()
            }

            fun stopPreview() {
                previewJob?.cancel()
                editor = editor.cancelPreview()
                guessedPreview = null
                guessedToken = null
            }

            fun watermarkArea(): Rect? = editor.selection?.pixels(sourceWidth, height)?.let {
                Rect(it.left, it.top, it.right, it.bottom)
            }

            fun imageTransform(viewportWidth: Float, viewportHeight: Float, padding: Float) = ReaderImageTransform(
                sourceWidth,
                height,
                viewportWidth.toDouble(),
                viewportHeight.toDouble(),
                padding.toDouble(),
                zoom.toDouble(),
                ImagePoint(pan.x.toDouble(), pan.y.toDouble()),
            )

            DisposableEffect(page) { onDispose { previewJob?.cancel() } }

            fun requestPreview() {
                val raw = bytes ?: return
                val next = editor.requestPreview(sourceWidth, height)
                val token = next.pending ?: return
                previewJob?.cancel()
                editor = next
                guessedPreview = null
                guessedToken = null
                showOriginal = false
                error = null
                previewJob = scope.launch {
                    var generated: Bitmap? = null
                    var published = false
                    try {
                        withContext(Dispatchers.IO) {
                            watermarkPreviewMutex.withLock {
                                ensureActive()
                                val area = token.area.let { Rect(it.left, it.top, it.right, it.bottom) }
                                val rendered = watermarkPreviewRenderer(raw, area)
                                ensureActive()
                                val options = BitmapFactory.Options().apply {
                                    inSampleSize = 1
                                    while (maxOf(sourceWidth, height) / inSampleSize > 2048) inSampleSize *= 2
                                }
                                generated =
                                    requireNotNull(BitmapFactory.decodeByteArray(rendered, 0, rendered.size, options))
                                ensureActive()
                            }
                        }
                        if (editor.pending == token && !editor.closed) {
                            editor = editor.completePreview(token)
                            guessedPreview = requireNotNull(generated).asImageBitmap()
                            guessedToken = token
                            published = true
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (editor.pending == token && !editor.closed) {
                            editor = editor.failPreview(token)
                            error = e.message ?: "Could not preview watermark"
                        }
                    } catch (e: OutOfMemoryError) {
                        if (editor.pending == token && !editor.closed) {
                            editor = editor.failPreview(token)
                            error = "Not enough memory to preview this image."
                        }
                    } finally {
                        if (!published) generated?.recycle()
                    }
                }
            }

            LaunchedEffect(page) {
                data class LoadedPreview(
                    val raw: ByteArray,
                    val bitmap: ImageBitmap,
                    val width: Int,
                    val height: Int,
                    val rule: JSONObject?,
                )
                try {
                    prepareReaderEditor(
                        load = {
                            withContext(Dispatchers.IO) {
                                val raw = requireNotNull(
                                    page.originalStream,
                                ) { "Image is not ready yet" }().use { it.readBytes() }
                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
                                require(bounds.outHeight > 0 && bounds.outWidth > 0) { "Unsupported image" }
                                val options = BitmapFactory.Options().apply {
                                    inSampleSize = 1
                                    while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 2048) {
                                        inSampleSize *= 2
                                    }
                                }
                                val bitmap = requireNotNull(BitmapFactory.decodeByteArray(raw, 0, raw.size, options))
                                // Exact local edits only: opening Hide must not start a full corpus comparison.
                                LoadedPreview(
                                    raw,
                                    bitmap.asImageBitmap(),
                                    bounds.outWidth,
                                    bounds.outHeight,
                                    ReaderImageRules.find(ReaderImageRules.hash(raw)),
                                )
                            }
                        },
                        onReady = { loaded ->
                            bytes = loaded.raw
                            preview = loaded.bitmap
                            height = loaded.height
                            sourceWidth = loaded.width
                            updateEditor(ReaderWatermarkEditor(ReaderImageRules.hash(loaded.raw)))
                            val rule = loaded.rule
                            existingHash = rule?.getString("sha256")
                            top = (rule?.getInt("removeTop") ?: 0).toFloat() / height
                            bottom = (rule?.getInt("removeBottom") ?: height).toFloat() / height
                            rule?.optJSONArray("watermark")?.let {
                                watermarkMode = true
                                updateEditor(
                                    ReaderWatermarkEditor(
                                        editor.imageKey,
                                        ImageBounds(
                                            it.getInt(0).toDouble(),
                                            it.getInt(1).toDouble(),
                                            it.getInt(2).toDouble(),
                                            it.getInt(3).toDouble(),
                                        ),
                                    ),
                                )
                            }
                        },
                        enrich = { loaded -> edges = contrastEdges(loaded.raw) },
                    )
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    error = e.message ?: "Could not load image"
                }
            }

            fun commit(restore: Boolean) {
                val raw = bytes ?: return
                if (!restore && watermarkMode && !editor.canApply(sourceWidth, height)) return
                val savedWatermark = if (!restore && watermarkMode) requireNotNull(watermarkArea()) else null
                val start = (top * height).roundToInt().coerceIn(0, height - 1)
                val end = (bottom * height).roundToInt().coerceIn(start + 1, height)
                busy = true
                error = null
                scope.launch {
                    try {
                        var reportQueued = true
                        withContext(Dispatchers.IO) {
                            if (restore) {
                                ReaderImageRules.remove(existingHash ?: ReaderImageRules.hash(raw))
                                page.chapterReview?.restoreOriginal(page, raw)
                            } else {
                                fun save() = ReaderImageRules.save(
                                    raw,
                                    start,
                                    end,
                                    metadata,
                                    replacingHash = existingHash,
                                    watermark = savedWatermark,
                                )
                                // Exact whole-image hides stay immediate even if cancelled native preview work remains.
                                if (savedWatermark == null && start == 0 && end == height) {
                                    save()
                                } else {
                                    watermarkPreviewMutex.withLock { save() }
                                }
                                page.chapterReview?.savedRule(page, raw)
                                if (savedWatermark == null) {
                                    reportQueued =
                                        ReaderSpamReports.enqueueExplicit(ReaderImageRules.hash(raw), metadata)
                                }
                            }
                        }
                        onRuleChanged(
                            !restore && savedWatermark == null && start == 0 && end == height,
                        )
                        if (!reportQueued) {
                            Toast.makeText(
                                context,
                                "Image rule saved; spam report could not be queued",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        updateEditor(editor.close())
                        onDismiss()
                        if (!restore) onApplied(ReaderImageRules.hash(raw))
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        error = e.message ?: "Could not save image rule"
                    } finally {
                        busy = false
                    }
                }
            }

            val toolbar: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(
                        onClick = { dismissEditor() },
                        enabled = !busy,
                    ) { Text(stringResource(MR.strings.action_cancel)) }
                    TextButton(onClick = {
                        commit(false)
                    }, enabled = bytes != null && !busy && (!watermarkMode || editor.canApply(sourceWidth, height))) {
                        Text(stringResource(MR.strings.reader_mask_apply))
                    }
                }
                Text(
                    if (watermarkMode) "Watermark preview" else stringResource(MR.strings.reader_hide_image),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            val controls: @Composable () -> Unit = {
                if (!watermarkMode) Text(stringResource(MR.strings.reader_mask_instructions))
                TextButton(onClick = {
                    stopPreview()
                    updateEditor(editor.cancelGesture())
                    watermarkMode = !watermarkMode
                }, enabled = !busy) {
                    Text(if (watermarkMode) "Crop spam instead" else "Select watermark")
                }
                if (watermarkMode) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { requestPreview() },
                            modifier = Modifier.testTag("reader_watermark_preview"),
                            enabled = !busy && !editor.gesturing && editor.pending == null && watermarkArea() != null,
                        ) {
                            Text("Preview guess")
                        }
                        TextButton(
                            onClick = {
                                updateEditor(editor.undo())
                                error = null
                            },
                            enabled = !busy && !editor.gesturing && editor.canUndo,
                        ) { Text("Undo selection") }
                    }
                    Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (editor.pending != null) {
                            TextButton(onClick = { stopPreview() }) { Text("Stop preview") }
                            CircularProgressIndicator(Modifier.size(24.dp))
                        }
                        if (editor.ready == guessedToken && guessedPreview != null) {
                            TextButton(onClick = { showOriginal = !showOriginal }) {
                                Text(if (showOriginal) "Show estimate" else "Show original")
                            }
                        }
                    }
                    Text(
                        "Draw an oval; pinch to inspect. Preview guesses pixels and may blur artwork. " +
                            "This image only; no automatic learning.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (editor.selection != null && watermarkArea() == null) {
                        Text(
                            "Adjust the selection: it must cover at least 2 by 2 source pixels, " +
                                "stay inside the image, and contain at most one million pixels.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            // Read readiness in this composition and capture its value as a slot input. Reading only
            // inside the deferred slot can leave its initial loading branch skipped after publication.
            val image = preview
            val imageContent: @Composable (Modifier) -> Unit = { imageModifier ->
                Column(imageModifier) {
                    if (image != null) {
                        Canvas(
                            Modifier.weight(
                                1f,
                            ).fillMaxWidth().testTag("reader_image_mask").clipToBounds().onSizeChanged { viewport ->
                                if (viewport.width > 0 && viewport.height > 0) {
                                    val limits =
                                        imageTransform(
                                            viewport.width.toFloat(),
                                            viewport.height.toFloat(),
                                            imagePadding,
                                        )
                                    pan = Offset(
                                        pan.x.coerceIn(-limits.maxPanX.toFloat(), limits.maxPanX.toFloat()),
                                        pan.y.coerceIn(-limits.maxPanY.toFloat(), limits.maxPanY.toFloat()),
                                    )
                                }
                            }.semantics {
                                contentDescription = if (watermarkMode) "Watermark selection" else "Crop selection"
                                stateDescription = if (watermarkMode) {
                                    editor.displayedSelection?.let {
                                        "Oval ${it.left.roundToInt()}, ${it.top.roundToInt()} to " +
                                            "${it.right.roundToInt()}, ${it.bottom.roundToInt()} source pixels"
                                    } ?: "No watermark selected"
                                } else {
                                    "Rows ${(top * height).roundToInt()} to ${(bottom * height).roundToInt()}"
                                }
                                if (!busy && watermarkMode && !editor.gesturing && editor.selection != null) {
                                    customActions = listOf(
                                        "Move oval left one pixel" to (-1.0 to 0.0),
                                        "Move oval right one pixel" to (1.0 to 0.0),
                                        "Move oval up one pixel" to (0.0 to -1.0),
                                        "Move oval down one pixel" to (0.0 to 1.0),
                                    ).map { (label, delta) ->
                                        CustomAccessibilityAction(label) {
                                            updateEditor(
                                                editor.select(
                                                    requireNotNull(editor.selection)
                                                        .shifted(delta.first, delta.second, sourceWidth, height),
                                                ),
                                            )
                                            true
                                        }
                                    }
                                }
                                if (!busy && !watermarkMode) {
                                    customActions = listOf(
                                        CustomAccessibilityAction("Move upper handle up one pixel") {
                                            top = (top - 1f / height).coerceAtLeast(0f)
                                            true
                                        },
                                        CustomAccessibilityAction("Move upper handle down one pixel") {
                                            top = (top + 1f / height).coerceAtMost(bottom - 1f / height)
                                            true
                                        },
                                        CustomAccessibilityAction("Move lower handle up one pixel") {
                                            bottom = (bottom - 1f / height).coerceAtLeast(top + 1f / height)
                                            true
                                        },
                                        CustomAccessibilityAction("Move lower handle down one pixel") {
                                            bottom = (bottom + 1f / height).coerceAtMost(1f)
                                            true
                                        },
                                    )
                                }
                            }.pointerInput(image, busy, watermarkMode) {
                                if (busy) return@pointerInput
                                fun transform() = imageTransform(
                                    size.width.toFloat(),
                                    size.height.toFloat(),
                                    24.dp.toPx(),
                                )
                                fun geometry(): Pair<Float, Float> = transform().let {
                                    it.origin.y.toFloat() to it.drawnHeight.toFloat()
                                }
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    val (y, drawnHeight) = geometry()
                                    if (watermarkMode) {
                                        updateEditor(
                                            editor.begin(
                                                transform().watermarkPoint(
                                                    ImagePoint(down.position.x.toDouble(), down.position.y.toDouble()),
                                                ),
                                            ),
                                        )
                                    }
                                    val topDistance = abs(down.position.y - (y + drawnHeight * top))
                                    val bottomDistance = abs(down.position.y - (y + drawnHeight * bottom))
                                    var handle = if (minOf(topDistance, bottomDistance) <= 32.dp.toPx()) {
                                        if (topDistance < bottomDistance) 0 else 1
                                    } else {
                                        -1
                                    }
                                    val snap = ReaderImageSnap(
                                        if (handle == 0) top else bottom,
                                        edges,
                                        8.dp.toPx() / drawnHeight,
                                        18.dp.toPx() / drawnHeight,
                                        if (handle == 0) 0f else (top + 1f / height).coerceAtMost(1f),
                                        if (handle == 0) (bottom - 1f / height).coerceAtLeast(0f) else 1f,
                                    )
                                    try {
                                        do {
                                            val event = awaitPointerEvent()
                                            // Compose represents Android ACTION_CANCEL as consumed pointer-up changes.
                                            // Another recognizer taking this gesture also cancels our uncommitted oval.
                                            if (watermarkMode && event.changes.any { it.isConsumed }) {
                                                updateEditor(editor.cancelGesture())
                                                break
                                            }
                                            val pressed = event.changes.filter { it.pressed }
                                            val multi = event.changes.any {
                                                it.id != down.id && (it.pressed || it.previousPressed)
                                            }
                                            if (watermarkMode) {
                                                if (multi || editor.phase == ReaderWatermarkEditor.Phase.NAVIGATING) {
                                                    updateEditor(editor.navigate())
                                                    if (pressed.size > 1) {
                                                        val centroid = event.calculateCentroid(useCurrent = false)
                                                        val anchored = transform().toSource(
                                                            ImagePoint(
                                                                centroid.x.toDouble(),
                                                                centroid.y.toDouble(),
                                                            ),
                                                        )
                                                        zoom = (zoom * event.calculateZoom()).coerceIn(1f, 12f)
                                                        val projected = transform().toViewport(anchored)
                                                        pan += centroid + event.calculatePan() -
                                                            Offset(projected.x.toFloat(), projected.y.toFloat())
                                                    } else if (pressed.isNotEmpty() && zoom > 1f) {
                                                        pan += event.calculatePan()
                                                    }
                                                } else {
                                                    pressed.firstOrNull { it.id == down.id }?.let { change ->
                                                        updateEditor(
                                                            editor.move(
                                                                transform().watermarkPoint(
                                                                    ImagePoint(
                                                                        change.position.x.toDouble(),
                                                                        change.position.y.toDouble(),
                                                                    ),
                                                                ),
                                                                (change.position - down.position).getDistance() >
                                                                    viewConfiguration.touchSlop,
                                                            ),
                                                        )
                                                    }
                                                }
                                            } else if (pressed.size > 1) {
                                                handle = -1
                                                zoom = (zoom * event.calculateZoom()).coerceIn(1f, 12f)
                                                pan += event.calculatePan()
                                            } else if (pressed.isNotEmpty()) {
                                                val drag = pressed.first().positionChange()
                                                val delta = drag.y / geometry().second.coerceAtLeast(1f)
                                                when (handle) {
                                                    0 -> top = snap.moveBy(delta)
                                                    1 -> bottom = snap.moveBy(delta)
                                                    else -> if (zoom > 1f) pan += drag
                                                }
                                            }
                                            val limits = transform()
                                            pan = Offset(
                                                pan.x.coerceIn(-limits.maxPanX.toFloat(), limits.maxPanX.toFloat()),
                                                pan.y.coerceIn(-limits.maxPanY.toFloat(), limits.maxPanY.toFloat()),
                                            )
                                            event.changes.forEach { it.consume() }
                                        } while (event.changes.any { it.pressed })
                                        if (watermarkMode) updateEditor(editor.finish())
                                    } catch (e: CancellationException) {
                                        if (watermarkMode) updateEditor(editor.cancelGesture())
                                        throw e
                                    }
                                }
                            },
                        ) {
                            if (size.width <= 0f || size.height <= 0f) return@Canvas
                            val transform = imageTransform(size.width, size.height, 24.dp.toPx())
                            val width = transform.drawnWidth.toFloat()
                            val drawnHeight = transform.drawnHeight.toFloat()
                            val x = transform.origin.x.toFloat()
                            val y = transform.origin.y.toFloat()
                            val displayed = if (!showOriginal && editor.draft == null && editor.ready == guessedToken) {
                                guessedPreview ?: image
                            } else {
                                image
                            }
                            // Continuous drawing and hit testing use exactly the same source-image frame.
                            withTransform({
                                translate(x, y)
                                scale(width / displayed.width, drawnHeight / displayed.height, pivot = Offset.Zero)
                            }) { drawImage(displayed) }
                            if (!watermarkMode) {
                                drawRect(
                                    Color.Black.copy(alpha = 0.6f),
                                    Offset(x, y + drawnHeight * top),
                                    Size(width, drawnHeight * (bottom - top)),
                                )
                            }
                            if (watermarkMode) {
                                editor.displayedSelection?.let { area ->
                                    val origin = transform.toViewport(ImagePoint(area.left, area.top))
                                    drawOval(
                                        Color.White,
                                        Offset(origin.x.toFloat(), origin.y.toFloat()),
                                        Size(
                                            ((area.right - area.left) * transform.scale).toFloat(),
                                            ((area.bottom - area.top) * transform.scale).toFloat(),
                                        ),
                                        style = Stroke(2.dp.toPx()),
                                    )
                                }
                            }
                            (if (watermarkMode) emptyList() else listOf(top, bottom)).forEach { edge ->
                                val lineY = y + drawnHeight * edge
                                drawLine(Color.White, Offset(x, lineY), Offset(x + width, lineY), 2.dp.toPx())
                                drawCircle(Color.White, 12.dp.toPx(), Offset(size.width / 2, lineY))
                                drawLine(
                                    Color.DarkGray,
                                    Offset(size.width / 2 - 6.dp.toPx(), lineY),
                                    Offset(size.width / 2 + 6.dp.toPx(), lineY),
                                    2.dp.toPx(),
                                )
                            }
                        }
                        if (!watermarkMode) {
                            Text(
                                if (top == 0f && bottom == 1f) {
                                    stringResource(
                                        MR.strings.reader_mask_all,
                                    )
                                } else {
                                    stringResource(MR.strings.reader_mask_partial)
                                },
                            )
                        }
                    } else {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            if (error == null) CircularProgressIndicator()
                        }
                    }
                }
            }
            val footer: @Composable () -> Unit = {
                if (busy) CircularProgressIndicator()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                notice?.let { Text(it) }
                Row {
                    TextButton(
                        onClick = { export.launch("reader-image-rules.zip") },
                        enabled = !busy,
                    ) { Text("Export rules") }
                    TextButton(onClick = {
                        importRulesPicker.launch(arrayOf("application/zip", "application/octet-stream"))
                    }, enabled = !busy) { Text("Import rules") }
                }
                if (existingHash != null) {
                    TextButton(onClick = {
                        commit(true)
                    }, enabled = !busy) { Text(stringResource(MR.strings.reader_restore_image)) }
                }
            }
            Surface(Modifier.fillMaxSize()) {
                ReaderImageEditorLayout(toolbar, controls, imageContent, footer)
            }
        }
    }
}
