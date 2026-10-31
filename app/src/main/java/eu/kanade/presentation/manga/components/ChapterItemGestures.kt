package eu.kanade.presentation.manga.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed

/** Follow the finger in window coordinates: live row layout must not look like finger movement. */
internal suspend fun PointerInputScope.detectChapterItemGestures(
    interactionSource: MutableInteractionSource,
    positionInWindow: (Offset) -> Offset?,
    onLongPress: () -> Unit,
    onTap: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown()
        val origin = positionInWindow(down.position) ?: return@awaitEachGesture
        println("CHAPTER_PRESS down local=${down.position} window=$origin")
        down.consume()
        val press = PressInteraction.Press(down.position)
        interactionSource.tryEmit(press)
        var released = false
        try {
            val end = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                awaitChapterPressEnd(down.id, origin, positionInWindow)
            }
            when (end) {
                null -> {
                    println("CHAPTER_PRESS long timeout")
                    onLongPress()
                    // Match normal long-click semantics: consume the rest of this gesture, never emit a tap.
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        released = event.changes.all { !it.pressed }
                    } while (!released)
                }
                ChapterPressEnd.UP_INSIDE -> {
                    released = true
                    onTap()
                }
                ChapterPressEnd.UP_OUTSIDE, ChapterPressEnd.CANCELLED -> Unit
            }
        } finally {
            println("CHAPTER_PRESS finished released=$released")
            // tryEmit also delivers cancellation when the keyed row is removed and its coroutine is cancelled.
            interactionSource.tryEmit(
                if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press),
            )
        }
    }
}

private enum class ChapterPressEnd { UP_INSIDE, UP_OUTSIDE, CANCELLED }

private suspend fun AwaitPointerEventScope.awaitChapterPressEnd(
    pointer: PointerId,
    origin: Offset,
    positionInWindow: (Offset) -> Offset?,
): ChapterPressEnd {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Main)
        val change = event.changes.firstOrNull { it.id == pointer } ?: return ChapterPressEnd.CANCELLED
        if (event.changes.size != 1 || event.changes.any { it.isConsumed }) {
            println("CHAPTER_PRESS consumed main")
            return ChapterPressEnd.CANCELLED
        }
        val position = positionInWindow(change.position) ?: return ChapterPressEnd.CANCELLED
        println("CHAPTER_PRESS move local=${change.position} window=$position origin=$origin size=$size")
        if ((position - origin).getDistance() > viewConfiguration.touchSlop) return ChapterPressEnd.CANCELLED
        if (change.changedToUpIgnoreConsumed()) {
            val inside = change.position.x in 0f..size.width.toFloat() && change.position.y in 0f..size.height.toFloat()
            if (inside) change.consume()
            return if (inside) ChapterPressEnd.UP_INSIDE else ChapterPressEnd.UP_OUTSIDE
        }
        if (!change.pressed) return ChapterPressEnd.CANCELLED
        // A real list scroll or swipe wins even if it consumed the event after our main-pass observation.
        if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) {
            println("CHAPTER_PRESS consumed final")
            return ChapterPressEnd.CANCELLED
        }
    }
}
