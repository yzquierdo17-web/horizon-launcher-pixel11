package com.horizon.launcher

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Holds what is being dragged, where the finger is, and where things can be dropped. */
class DragController {
    var payload by mutableStateOf<DragPayload?>(null)
        private set
    var position by mutableStateOf(Offset.Zero)
    var preview by mutableStateOf<(@Composable () -> Unit)?>(null)
        private set
    var previewSize by mutableStateOf(DpSize(56.dp, 56.dp))
        private set
    var spanX = 1
        private set
    var spanY = 1
        private set

    /** True while a finger is down on any icon or widget (blocks the home long-press menu). */
    var itemPressed = false

    var gridBounds = Rect.Zero
    var dockBounds = Rect.Zero
    var removeBounds by mutableStateOf(Rect.Zero)

    val active: Boolean get() = payload != null

    fun start(
        payload: DragPayload,
        rootPosition: Offset,
        size: DpSize,
        spanX: Int = 1,
        spanY: Int = 1,
        preview: @Composable () -> Unit,
    ) {
        this.spanX = spanX
        this.spanY = spanY
        this.previewSize = size
        this.preview = preview
        this.position = rootPosition
        this.payload = payload
    }

    fun move(delta: Offset) {
        position += delta
    }

    fun end(): Pair<DragPayload, Offset>? {
        val p = payload ?: return null
        payload = null
        preview = null
        return p to position
    }

    fun overRemove(): Boolean = active && payload !is DragPayload.FromDrawer && removeBounds.contains(position)

    fun overGrid(): Boolean = active && !overRemove() && gridBounds.contains(position)

    /** Top-left cell for the dragged item if dropped at the finger position. */
    fun cellAt(pos: Offset = position): IntOffset {
        val b = gridBounds
        val cw = b.width / GRID_COLS
        val ch = b.height / GRID_ROWS
        val fx = (pos.x - b.left) / cw - spanX / 2f
        val fy = (pos.y - b.top) / ch - spanY / 2f
        return IntOffset(
            fx.roundToInt().coerceIn(0, GRID_COLS - spanX),
            fy.roundToInt().coerceIn(0, GRID_ROWS - spanY),
        )
    }
}

private enum class PressOutcome { Tap, Cancel }

/**
 * Tap, long-press and long-press-then-drag for launcher items.
 * Runs in the Initial pass so it also works on top of widgets; it only consumes events
 * after a long press, so scrolling and swiping still work normally.
 */
fun Modifier.launcherGestures(
    drag: DragController,
    onTap: (() -> Unit)?,
    onLongPress: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
): Modifier = pointerInput(drag) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        drag.itemPressed = true
        var dragging = false
        var ended = false
        try {
            val slop = viewConfiguration.touchSlop
            var outcome = PressOutcome.Cancel
            val finishedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null) {
                        outcome = PressOutcome.Cancel
                        break
                    }
                    if (!change.pressed) {
                        outcome = PressOutcome.Tap
                        break
                    }
                    if (change.isConsumed || (change.position - down.position).getDistance() > slop) {
                        outcome = PressOutcome.Cancel
                        break
                    }
                }
                true
            }
            if (finishedEarly != null) {
                if (outcome == PressOutcome.Tap) onTap?.invoke()
                return@awaitEachGesture
            }

            onLongPress()
            val anchor = down.position
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    change.consume()
                    break
                }
                if (!dragging && (change.position - anchor).getDistance() > slop) {
                    dragging = true
                    onDragStart(change.position)
                } else if (dragging) {
                    onDrag(change.position - change.previousPosition)
                }
                change.consume()
            }
            if (dragging) {
                ended = true
                onDragEnd()
            }
        } finally {
            drag.itemPressed = false
            if (dragging && !ended) onDragEnd()
        }
    }
}

/** Stops taps from falling through an overlay to the home screen underneath. */
fun Modifier.swallowTouches(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
        } while (event.changes.any { it.pressed })
    }
}
