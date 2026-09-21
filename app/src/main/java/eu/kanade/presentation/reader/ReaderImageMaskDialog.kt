package eu.kanade.presentation.reader

import android.graphics.BitmapFactory
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.tachiyomi.ui.reader.filter.ReaderImageRules
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
fun ReaderImageMaskDialog(page: ReaderPage, metadata: JSONObject, onDismiss: () -> Unit, onApplied: (String) -> Unit) {
    var bytes by remember(page) { mutableStateOf<ByteArray?>(null) }
    var preview by remember(page) { mutableStateOf<ImageBitmap?>(null) }
    var height by remember(page) { mutableStateOf(1) }
    var top by remember(page) { mutableStateOf(0f) }
    var bottom by remember(page) { mutableStateOf(1f) }
    var existingHash by remember(page) { mutableStateOf<String?>(null) }
    var zoom by remember(page) { mutableStateOf(1f) }
    var pan by remember(page) { mutableStateOf(Offset.Zero) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

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
            bytes = loaded.first
            preview = loaded.second
            height = loaded.third
            existingHash = rule?.getString("sha256")
            top = (rule?.getInt("removeTop") ?: 0).toFloat() / height
            bottom = (rule?.getInt("removeBottom") ?: height).toFloat() / height
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
                        ReaderImageRules.save(raw, start, end, metadata, replacingHash = existingHash)
                    }
                }
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
                    }, enabled = bytes != null && !busy) { Text(stringResource(MR.strings.reader_mask_apply)) }
                }
                Text(stringResource(MR.strings.reader_hide_image), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(MR.strings.reader_mask_instructions))
                val image = preview
                if (image != null) {
                    Canvas(
                        Modifier.weight(
                            1f,
                        ).fillMaxWidth().testTag("reader_image_mask").clipToBounds().pointerInput(image, busy) {
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
                                val topDistance = abs(down.position.y - (y + drawnHeight * top))
                                val bottomDistance = abs(down.position.y - (y + drawnHeight * bottom))
                                var handle = if (minOf(topDistance, bottomDistance) <= 32.dp.toPx()) {
                                    if (topDistance < bottomDistance) 0 else 1
                                } else {
                                    -1
                                }
                                do {
                                    val event = awaitPointerEvent()
                                    if (event.changes.count { it.pressed } > 1) {
                                        handle = -1
                                        zoom = (zoom * event.calculateZoom()).coerceIn(1f, 12f)
                                        pan += event.calculatePan()
                                    } else {
                                        val drag = event.changes.first().positionChange()
                                        val delta = drag.y / geometry().second.coerceAtLeast(1f)
                                        when (handle) {
                                            0 -> top = (top + delta).coerceIn(
                                                0f,
                                                (bottom - 1f / height).coerceAtLeast(0f),
                                            )
                                            1 -> bottom = (bottom + delta).coerceIn(
                                                (top + 1f / height).coerceAtMost(1f),
                                                1f,
                                            )
                                            else -> if (zoom > 1f) pan += drag
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
                            image,
                            dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
                            dstSize = IntSize(
                                width.roundToInt().coerceAtLeast(1),
                                drawnHeight.roundToInt().coerceAtLeast(1),
                            ),
                        )
                        drawRect(
                            Color.Black.copy(alpha = 0.6f),
                            Offset(x, y + drawnHeight * top),
                            Size(width, drawnHeight * (bottom - top)),
                        )
                        listOf(top, bottom).forEach { edge ->
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
                    Text(
                        if (top == 0f && bottom == 1f) {
                            stringResource(
                                MR.strings.reader_mask_all,
                            )
                        } else {
                            stringResource(MR.strings.reader_mask_partial)
                        },
                    )
                } else if (error == null) {
                    CircularProgressIndicator()
                }
                if (busy) CircularProgressIndicator()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (existingHash != null) {
                    TextButton(onClick = {
                        commit(true)
                    }, enabled = !busy) { Text(stringResource(MR.strings.reader_restore_image)) }
                }
            }
        }
    }
}
