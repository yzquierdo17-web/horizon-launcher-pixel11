package com.slate.launcher.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Finger-tracked app drawer. progress: 0 = closed, 1 = fully open.
 * Drags move it 1:1 with the finger; release settles with a critically damped spring.
 */
@Stable
class AppDrawerState internal constructor(private val scope: CoroutineScope) {
    var progress by mutableFloatStateOf(0f)
        private set
    var target by mutableFloatStateOf(0f)
        private set

    /** Pixels of finger travel for a full open; set from the screen height. */
    var distancePx = 1f

    private var job: Job? = null

    val isVisible: Boolean get() = progress > 0f || target > 0f

    fun dragBy(deltaY: Float) {
        job?.cancel()
        progress = (progress - deltaY / distancePx).coerceIn(0f, 1f)
    }

    fun settle(velocityY: Float) {
        val t = when {
            velocityY < -FLING_PX -> 1f
            velocityY > FLING_PX -> 0f
            progress > 0.5f -> 1f
            else -> 0f
        }
        animateTo(t, -velocityY / distancePx)
    }

    fun open() = animateTo(1f)
    fun close() = animateTo(0f)

    fun snapClosed() {
        job?.cancel()
        target = 0f
        progress = 0f
    }

    private fun animateTo(t: Float, velocity: Float = 0f) {
        target = t
        job?.cancel()
        job = scope.launch {
            animate(
                initialValue = progress,
                targetValue = t,
                initialVelocity = velocity,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 550f),
            ) { value, _ -> progress = value.coerceIn(0f, 1f) }
        }
    }

    private companion object { const val FLING_PX = 1200f }
}

@Composable
fun rememberAppDrawerState(): AppDrawerState {
    val scope = rememberCoroutineScope()
    return remember { AppDrawerState(scope) }
}

/**
 * Vertical drag on Home / drawer chrome.
 * Swipe up opens the drawer. If [onPullDown] is set, a downward swipe calls it instead
 * (used for the notification shade on Home).
 */
fun Modifier.drawerDragGesture(
    state: AppDrawerState,
    enabled: Boolean,
    onPullDown: (() -> Unit)?,
): Modifier = if (!enabled) this else pointerInput(state, onPullDown) {
    val tracker = VelocityTracker()
    val pullThreshold = 56.dp.toPx()
    var total = 0f
    var driving = false
    detectVerticalDragGestures(
        onDragStart = {
            tracker.resetTracking()
            total = 0f
            driving = false
        },
        onDragEnd = {
            val velocity = tracker.calculateVelocity().y
            when {
                driving -> state.settle(velocity)
                onPullDown != null && total > pullThreshold -> onPullDown()
            }
        },
        onDragCancel = { if (driving) state.settle(0f) },
        onVerticalDrag = { change, dy ->
            change.consume()
            total += dy
            // Track accumulated travel, not local position: the drawer moves under the finger.
            tracker.addPosition(change.uptimeMillis, Offset(0f, total))
            if (!driving && (onPullDown == null || total < 0f)) driving = true
            if (driving) state.dragBy(dy)
        },
    )
}

/** Lets the app grid close the drawer when pulled down from the top, like Pixel. */
class DrawerScrollConnection(
    private val drawer: AppDrawerState,
    private val grid: LazyGridState,
) : NestedScrollConnection {

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        val dy = available.y
        val pullingFromTop = dy > 0f && !grid.canScrollBackward
        val partiallyOpen = drawer.progress < 1f
        return if (pullingFromTop || partiallyOpen) {
            drawer.dragBy(dy)
            Offset(0f, dy)
        } else Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity =
        if (drawer.progress < 1f) {
            drawer.settle(available.y)
            available
        } else Velocity.Zero
}
