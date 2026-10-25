package eu.kanade.presentation.reader

import android.graphics.BitmapFactory
import android.graphics.Rect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.tachiyomi.ui.reader.filter.ReaderImageMask
import eu.kanade.tachiyomi.ui.reader.filter.ReaderImageRules
import eu.kanade.tachiyomi.ui.reader.filter.ReaderImageSnap
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt

/** The shaded, full-width band is removed; content above and below it is joined. */
@Composable
fun ReaderImageMaskDialog(
    page: ReaderPage,
    metadata: JSONObject,
    onDismiss: () -> Unit,
    onApplied: (String) -> Unit,
    onRuleChanged: (Boolean) -> Unit = {},
) {
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
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var watermarkMode by remember(page) { mutableStateOf(false) }
    var circleStart by remember(page) { mutableStateOf<Offset?>(null) }
    var circleEnd by remember(page) { mutableStateOf<Offset?>(null) }
    var guessedPreview by remember(page) { mutableStateOf<ImageBitmap?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val preparing by ReaderImageRules.preparing.collectAsState()
    val preparationFailures by ReaderImageRules.preparationFailures.collectAsState()

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

    fun watermarkArea(): Rect? {
        val a = circleStart ?: return null
        val b = circleEnd ?: return null
        return Rect(
            (minOf(a.x, b.x) * sourceWidth).roundToInt().coerceIn(1, (sourceWidth - 2).coerceAtLeast(1)),
            (minOf(a.y, b.y) * height).roundToInt().coerceIn(0, height - 1),
            (maxOf(a.x, b.x) * sourceWidth).roundToInt().coerceIn(1, (sourceWidth - 1).coerceAtLeast(1)),
            (maxOf(a.y, b.y) * height).roundToInt().coerceIn(0, height),
        )
    }

    LaunchedEffect(page) {
        try {
            val loaded = withContext(Dispatchers.IO) {
                val raw = requireNotNull(page.originalStream) { "Image is not ready yet" }().use { it.readBytes() }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
                require(bounds.outHeight > 0 && bounds.outWidth > 0) { "Unsupported image" }
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 2048) inSampleSize *= 2
                }
                val bitmap = requireNotNull(BitmapFactory.decodeByteArray(raw, 0, raw.size, options))
                Triple(raw, bitmap.asImageBitmap(), bounds.outHeight)
            }
            val rule = withContext(Dispatchers.IO) { ReaderImageRules.selection(loaded.first) }
            edges = withContext(Dispatchers.IO) { ReaderImageMask(loaded.first).use { it.contrastEdges() } }
            bytes = loaded.first
            preview = loaded.second
            height = loaded.third
            sourceWidth = withContext(Dispatchers.IO) { ReaderImageMask(loaded.first).use { it.width } }
            existingHash = rule?.getString("sha256")
            top = (rule?.getInt("removeTop") ?: 0).toFloat() / height
            bottom = (rule?.getInt("removeBottom") ?: height).toFloat() / height
            rule?.optJSONArray("watermark")?.let {
                watermarkMode = true
                circleStart = Offset(it.getInt(0).toFloat() / sourceWidth, it.getInt(1).toFloat() / height)
                circleEnd = Offset(it.getInt(2).toFloat() / sourceWidth, it.getInt(3).toFloat() / height)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            error = e.message ?: "Could not load image"
        }
    }

    fun commit(restore: Boolean) {
        val raw = bytes ?: return
        busy = true
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (restore) {
                        ReaderImageRules.remove(existingHash ?: ReaderImageRules.hash(raw))
                    } else {
                        val start = (top * height).roundToInt().coerceIn(0, height - 1)
                        val end = (bottom * height).roundToInt().coerceIn(start + 1, height)
                        ReaderImageRules.save(
                            raw,
                            start,
                            end,
                            metadata,
                            replacingHash = existingHash,
                            watermark = if (watermarkMode) requireNotNull(watermarkArea()) else null,
                        )
                    }
                }
                onRuleChanged(
                    !restore && !watermarkMode && (top * height).roundToInt() == 0 &&
                        (bottom * height).roundToInt() == height,
                )
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

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(MR.strings.action_cancel)) }
                    TextButton(onClick = {
                        commit(false)
                    }, enabled = bytes != null && !busy && (!watermarkMode || guessedPreview != null)) {
                        Text(stringResource(MR.strings.reader_mask_apply))
                    }
                }
                Text(stringResource(MR.strings.reader_hide_image), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(MR.strings.reader_mask_instructions))
                Text(
                    if (preparing) {
                        "Preparing local matching…"
                    } else if (preparationFailures > 0) {
                        "Local matching limited"
                    } else {
                        "Local matching ready"
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
                Row {
                    TextButton(onClick = {
                        watermarkMode = !watermarkMode
                        guessedPreview = null
                    }, enabled = !busy) {
                        Text(if (watermarkMode) "Crop spam instead" else "Circle watermark (experimental)")
                    }
                    if (watermarkMode) {
                        TextButton(onClick = {
                            val raw = bytes ?: return@TextButton
                            val area = watermarkArea() ?: return@TextButton
                            busy = true
                            scope.launch {
                                try {
                                    guessedPreview = withContext(Dispatchers.IO) {
                                        val rendered = ReaderImageMask(raw).use { it.renderWatermark(area) }
                                        val options = BitmapFactory.Options().apply {
                                            inSampleSize = 1
                                            while (maxOf(sourceWidth, height) / inSampleSize > 2048) inSampleSize *= 2
                                        }
                                        requireNotNull(
                                            BitmapFactory.decodeByteArray(rendered, 0, rendered.size, options),
                                        ).asImageBitmap()
                                    }
                                    error = null
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    error = e.message ?: "Could not preview watermark"
                                } finally { busy = false }
                            }
                        }, enabled = !busy && circleEnd != null) { Text("Preview guess") }
                    }
                }
                if (watermarkMode) {
                    Text(
                        "Drag an oval around a small watermark, then preview. " +
                            "Interpolation guesses hidden pixels and may blur artwork. " +
                            "Applies only to this exact image. More circles do not train automatic removal.",
                    )
                }
                val image = preview
                if (image != null) {
                    Canvas(
                        Modifier.weight(
                            1f,
                        ).fillMaxWidth().testTag("reader_image_mask").clipToBounds().semantics {
                            contentDescription = if (watermarkMode) "Watermark selection" else "Crop selection"
                            stateDescription =
                                "Rows ${(top * height).roundToInt()} to ${(bottom * height).roundToInt()}"
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
                            fun geometry(): Pair<Float, Float> {
                                val scale = minOf(
                                    (size.width - 48.dp.toPx()) / image.width,
                                    (size.height - 48.dp.toPx()) / image.height,
                                ).coerceAtLeast(0.01f) * zoom
                                val drawnHeight = image.height * scale
                                return ((size.height - drawnHeight) / 2f + pan.y) to drawnHeight
                            }
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val (y, drawnHeight) = geometry()
                                fun point(position: Offset): Offset {
                                    val (_, drawn) = geometry()
                                    val drawnWidth = drawn * image.width / image.height
                                    val x = (size.width - drawnWidth) / 2f + pan.x
                                    return Offset(
                                        ((position.x - x) / drawnWidth).coerceIn(0f, 1f),
                                        ((position.y - geometry().first) / drawn).coerceIn(0f, 1f),
                                    )
                                }
                                if (watermarkMode) {
                                    circleStart = point(down.position)
                                    circleEnd = circleStart
                                    guessedPreview = null
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
                                do {
                                    val event = awaitPointerEvent()
                                    if (event.changes.count { it.pressed } > 1) {
                                        handle = -1
                                        zoom = (zoom * event.calculateZoom()).coerceIn(1f, 12f)
                                        pan += event.calculatePan()
                                    } else if (event.changes.any { it.pressed }) {
                                        val drag = event.changes.first().positionChange()
                                        val delta = drag.y / geometry().second.coerceAtLeast(1f)
                                        if (watermarkMode) {
                                            circleEnd = point(event.changes.first().position)
                                        } else {
                                            when (handle) {
                                                0 -> top = snap.moveBy(delta)
                                                1 -> bottom = snap.moveBy(delta)
                                                else -> if (zoom > 1f) pan += drag
                                            }
                                        }
                                    }
                                    val baseScale = minOf(
                                        (size.width - 48.dp.toPx()) / image.width,
                                        (size.height - 48.dp.toPx()) / image.height,
                                    ).coerceAtLeast(0.01f)
                                    val maxX = ((image.width * baseScale * zoom - size.width) / 2f + 24.dp.toPx())
                                        .coerceAtLeast(0f)
                                    val maxY = ((image.height * baseScale * zoom - size.height) / 2f + 24.dp.toPx())
                                        .coerceAtLeast(0f)
                                    pan = Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                            }
                        },
                    ) {
                        val scale = minOf(
                            (size.width - 48.dp.toPx()) / image.width,
                            (size.height - 48.dp.toPx()) / image.height,
                        ).coerceAtLeast(0.01f) * zoom
                        val width = image.width * scale
                        val drawnHeight = image.height * scale
                        val x = (size.width - width) / 2f + pan.x
                        val y = (size.height - drawnHeight) / 2f + pan.y
                        drawImage(
                            guessedPreview ?: image,
                            dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
                            dstSize = IntSize(
                                width.roundToInt().coerceAtLeast(1),
                                drawnHeight.roundToInt().coerceAtLeast(1),
                            ),
                        )
                        if (!watermarkMode) {
                            drawRect(
                                Color.Black.copy(alpha = 0.6f),
                                Offset(x, y + drawnHeight * top),
                                Size(width, drawnHeight * (bottom - top)),
                            )
                        }
                        if (watermarkMode) {
                            val a = circleStart
                            val b = circleEnd
                            if (a != null && b != null) {
                                drawOval(
                                    Color.White,
                                    Offset(x + minOf(a.x, b.x) * width, y + minOf(a.y, b.y) * drawnHeight),
                                    Size(abs(a.x - b.x) * width, abs(a.y - b.y) * drawnHeight),
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
                } else if (error == null) {
                    CircularProgressIndicator()
                }
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
        }
    }
}
