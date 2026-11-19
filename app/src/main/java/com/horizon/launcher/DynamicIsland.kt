package com.horizon.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.BatteryManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

private val CallGreen = Color(0xFF34C759)
private val EndRed = Color(0xFFFF453A)
private val TimerOrange = Color(0xFFFF9F0A)
private val Dim = Color.White.copy(alpha = 0.6f)

/** What the island is showing. Big* kinds are the expanded (tapped) versions. */
private enum class Kind(val big: Boolean = false) {
    Idle, Charging, Media, Timer, Call,
    Peek(true), Power(true), Incoming(true),
    BigMedia(true), BigTimer(true), BigCall(true), BigCharging(true),
}

private data class Battery(val level: Int, val charging: Boolean)

/** Sizes that depend on the camera and the chosen size, in dp. */
private class Dims(
    val onCamera: Boolean,
    val holeW: Dp,
    val holeH: Dp,
    val pillH: Dp,
    val gap: Dp,
    val wing: Dp,
    val bigW: Dp,
) {
    fun size(k: Kind): DpSize = when {
        k == Kind.Idle -> if (onCamera) DpSize(holeW + 1.dp, holeH + 1.dp) else DpSize(0.dp, 0.dp)
        k.big -> DpSize(bigW, pillH + contentH(k) + 16.dp)
        else -> DpSize(wing * 2 + gap, pillH)
    }

    private fun contentH(k: Kind): Dp = when (k) {
        Kind.BigMedia -> 152.dp
        Kind.Power, Kind.BigCharging -> 46.dp
        else -> 56.dp
    }
}

private val Smooth = spring<Dp>(dampingRatio = 0.86f, stiffness = 420f)
/** Growing overshoots a little and settles, like iPhone. */
private val Bouncy = spring<Dp>(dampingRatio = 0.64f, stiffness = 260f)

/**
 * The Dynamic Island, drawn inside the overlay window from [IslandOverlayService].
 * [onWindow] tells the service what shape of window it needs.
 */
@Composable
fun DynamicIsland(
    geometry: IslandGeometry,
    placement: IslandPlacement,
    editing: Boolean,
    outsideTaps: Int,
    onWindow: (IslandWindow) -> Unit,
    onPlacement: ((IslandPlacement) -> IslandPlacement) -> Unit,
    onDoneEditing: () -> Unit,
) {
    val density = LocalDensity.current
    val windowCallback by rememberUpdatedState(onWindow)

    if (editing) {
        LaunchedEffect(Unit) { windowCallback(IslandWindow.Full) }
        EditIsland(geometry, placement, onPlacement, onDoneEditing)
        return
    }
    if (!placement.enabled || geometry.landscape) {
        LaunchedEffect(Unit) { windowCallback(IslandWindow.Hidden) }
        Box(Modifier.size(1.dp))
        return
    }

    val dims = rememberDims(geometry, placement)
    val (cxPx, _) = islandAnchor(geometry, placement, density.density)
    val compactOffset = with(density) { (cxPx - geometry.screenW / 2f).toDp() }
    LiveIsland(dims, compactOffset, outsideTaps, windowCallback)
}

@Composable
private fun rememberDims(geometry: IslandGeometry, placement: IslandPlacement): Dims {
    val density = LocalDensity.current
    return remember(geometry, placement, density) {
        val hole = geometry.hole?.takeIf { placement.onCamera }
        val s = placement.scale
        with(density) {
            val holeW = (hole?.width() ?: 0f).toDp()
            val holeH = (hole?.height() ?: 0f).toDp()
            val pad = 5.dp * s
            val pillH = if (hole != null) maxOf(holeH + pad * 2, 30.dp * s) else 34.dp * s
            Dims(
                onCamera = hole != null,
                holeW = holeW,
                holeH = holeH,
                pillH = pillH,
                gap = if (hole != null) holeW + 12.dp else 10.dp,
                wing = 58.dp * s,
                bigW = minOf(geometry.screenW.toDp() - 20.dp, 440.dp),
            )
        }
    }
}

@Composable
private fun LiveIsland(dims: Dims, compactOffset: Dp, outsideTaps: Int, onWindow: (IslandWindow) -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current

    val media by IslandHub.media.collectAsState()
    val call by IslandHub.call.collectAsState()
    val timer by IslandHub.timer.collectAsState()
    val peek by IslandHub.peek.collectAsState()
    val battery = rememberBattery()
    var expanded by remember { mutableStateOf(false) }

    // A clock for timers, call length and peek expiry (slower when nothing is ticking)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val fastTick = call != null || timer != null || expanded || peek != null
    LaunchedEffect(fastTick) {
        while (true) {
            now = System.currentTimeMillis()
            delay(if (fastTick) 250 else 1000)
        }
    }

    // Plugging in or unplugging shows the charge for a moment
    var powerUntil by remember { mutableLongStateOf(0L) }
    var lastCharging by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(battery.charging) {
        if (lastCharging != null && lastCharging != battery.charging) {
            powerUntil = System.currentTimeMillis() + 3000
            now = System.currentTimeMillis()
            view.haptic(confirm = true)
        }
        lastCharging = battery.charging
    }
    LaunchedEffect(peek?.key, peek?.postedAt) {
        val p = peek
        if (p != null && System.currentTimeMillis() - p.postedAt < 2000) {
            now = System.currentTimeMillis()
            view.haptic(confirm = false)
        }
    }
    LaunchedEffect(outsideTaps) { if (outsideTaps > 0) expanded = false }

    val peekLive = peek?.takeIf { now - it.postedAt < 5000 }
    val kind = when {
        call?.incoming == true -> Kind.Incoming
        peekLive != null -> Kind.Peek
        now < powerUntil -> Kind.Power
        call != null -> if (expanded) Kind.BigCall else Kind.Call
        timer != null -> if (expanded) Kind.BigTimer else Kind.Timer
        media != null -> if (expanded) Kind.BigMedia else Kind.Media
        battery.charging -> if (expanded) Kind.BigCharging else Kind.Charging
        else -> Kind.Idle
    }
    LaunchedEffect(kind) { if (kind == Kind.Idle) expanded = false }

    // ---- Shape animation: width, height, horizontal position, corner ----
    val target = dims.size(kind)
    val targetX = if (kind.big) 0.dp else compactOffset
    val idleTop = if (kind == Kind.Idle && dims.onCamera) (dims.pillH - target.height) / 2 else 0.dp
    val w = remember { Animatable(target.width, Dp.VectorConverter) }
    val h = remember { Animatable(target.height, Dp.VectorConverter) }
    val x = remember { Animatable(targetX, Dp.VectorConverter) }
    val top = remember { Animatable(idleTop, Dp.VectorConverter) }
    LaunchedEffect(target, targetX, idleTop) {
        val growing = target.width > w.value || target.height > h.value
        val spec = if (growing) Bouncy else Smooth
        launch { w.animateTo(target.width, spec) }
        launch { h.animateTo(target.height, spec) }
        launch { x.animateTo(targetX, if (growing) Bouncy else Smooth) }
        launch { top.animateTo(idleTop, Smooth) }
    }
    val corner by animateDpAsState(
        if (kind.big) 38.dp else target.height / 2,
        spring(dampingRatio = 0.8f, stiffness = 300f),
        label = "corner",
    )
    val settled = !w.isRunning && !h.isRunning && !x.isRunning

    // Wide window while big or still shrinking back; a snug window otherwise
    var wide by remember { mutableStateOf(false) }
    LaunchedEffect(kind.big, settled) {
        if (kind.big) wide = true else if (settled) wide = false
    }
    val window = when {
        wide -> IslandWindow.Wide
        kind == Kind.Idle -> IslandWindow.Hidden
        else -> IslandWindow.Compact
    }
    LaunchedEffect(window) { onWindow(window) }

    // Squish a little under your finger
    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.5f, stiffness = 700f), label = "press")

    val openMedia: () -> Unit = {
        media?.let { m ->
            val pi = m.controller.sessionActivity
            if (pi != null) IslandHub.send(context, pi)
            else context.packageManager.getLaunchIntentForPackage(m.packageName)?.let {
                runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }

    Box(if (wide) Modifier.fillMaxWidth() else Modifier, contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .padding(top = top.value.coerceAtLeast(0.dp))
                .offset(x = if (wide) x.value else 0.dp)
                .size(w.value.coerceAtLeast(0.dp), h.value.coerceAtLeast(0.dp))
                .graphicsLayer {
                    scaleX = press
                    scaleY = press
                }
                .clip(RoundedCornerShape(minOf(corner, h.value / 2).coerceAtLeast(0.dp)))
                .background(Color.Black)
                .pointerInput(kind) {
                    detectTapGestures(
                        onPress = {
                            if (kind != Kind.Idle) pressed = true
                            tryAwaitRelease()
                            pressed = false
                        },
                        onTap = {
                            when (kind) {
                                Kind.Peek -> {
                                    IslandHub.send(context, peekLive?.open)
                                    IslandHub.dismissPeek()
                                }
                                Kind.Power, Kind.Idle, Kind.Incoming -> Unit
                                else -> {
                                    expanded = !expanded
                                    view.haptic(confirm = false)
                                }
                            }
                        },
                        onLongPress = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            when (kind) {
                                Kind.Media, Kind.BigMedia -> openMedia()
                                Kind.Call, Kind.BigCall -> IslandHub.send(context, call?.open)
                                Kind.Timer, Kind.BigTimer -> IslandHub.send(context, timer?.open)
                                Kind.Peek -> {
                                    IslandHub.send(context, peekLive?.open)
                                    IslandHub.dismissPeek()
                                }
                                else -> Unit
                            }
                            expanded = false
                        },
                    )
                }
                .pointerInput(kind) {
                    var total = 0f
                    detectVerticalDragGestures(onDragStart = { total = 0f }) { _, dy ->
                        total += dy
                        when {
                            total < -40f -> {
                                if (kind == Kind.Peek) IslandHub.dismissPeek()
                                expanded = false
                                total = 0f
                            }
                            total > 40f && !kind.big && kind != Kind.Idle -> {
                                expanded = true // pull down to open, like iPhone
                                total = 0f
                            }
                        }
                    }
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            AnimatedContent(
                targetState = kind,
                transitionSpec = {
                    (fadeIn(tween(240, delayMillis = 110)) + scaleIn(tween(360, delayMillis = 60), initialScale = 0.82f)) togetherWith
                        (fadeOut(tween(110)) + scaleOut(tween(160), targetScale = 0.9f)) using null
                },
                contentAlignment = Alignment.TopCenter,
                label = "island",
            ) { k ->
                // Lay out at the final size so text doesn't reflow while the shape grows
                val full = dims.size(k)
                Box(
                    Modifier
                        .wrapContentSize(Alignment.TopCenter, unbounded = true)
                        .requiredSize(full.width, full.height),
                ) {
                    IslandContent(k, dims, media, call, timer, peekLive, battery, now, openMedia) { expanded = false }
                }
            }
        }
    }
}

@Composable
private fun IslandContent(
    k: Kind,
    dims: Dims,
    mediaNow: IslandHub.Media?,
    callNow: IslandHub.Call?,
    timerNow: IslandHub.Timer?,
    peekNow: IslandHub.Peek?,
    battery: Battery,
    now: Long,
    openMedia: () -> Unit,
    collapse: () -> Unit,
) {
    val context = LocalContext.current
    val pillH = dims.pillH
    val gap = dims.gap
    when (k) {
        Kind.Idle -> Unit

        Kind.Charging -> Compact(gap,
            left = { Icon(Icons.Filled.Bolt, null, tint = CallGreen, modifier = Modifier.size(18.dp)) },
            right = { Text("${battery.level}%", color = CallGreen, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) },
        )

        Kind.Media -> {
            val m = rememberLast(mediaNow)
            val tint = rememberArtTint(m?.art)
            Compact(gap,
                left = { Art(m?.art, pillH - 12.dp, 7.dp) },
                right = { Bars(playing = m?.playing == true, color = tint) },
            )
        }

        Kind.Timer -> {
            val t = rememberLast(timerNow)
            Compact(gap,
                left = { Icon(Icons.Filled.Timer, null, tint = TimerOrange, modifier = Modifier.size(18.dp)) },
                right = { Mono(t?.let { timerText(it, now) }.orEmpty(), TimerOrange, 14) },
            )
        }

        Kind.Call -> {
            val c = rememberLast(callNow)
            Compact(gap,
                left = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Call, null, tint = CallGreen, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Mono(c?.startedAt?.let { formatElapsed(now - it) } ?: "Call", CallGreen, 13)
                    }
                },
                right = { Bars(playing = true, color = CallGreen) },
            )
        }

        Kind.Peek -> {
            val p = rememberLast(peekNow)
            Big(pillH, gap, headerLeft = p?.appName.orEmpty(), headerRight = "now") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (p?.icon != null) Art(p.icon, 42.dp, 21.dp)
                    else IconCircle(Icons.Filled.Notifications, Color.White.copy(alpha = 0.15f), Color.White, 42.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p?.title.orEmpty(), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!p?.text.isNullOrBlank()) {
                            Text(p?.text.orEmpty(), color = Dim, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        Kind.Power -> Big(pillH, gap, headerLeft = "", headerRight = "") { ChargeRow(battery) }

        Kind.BigCharging -> Big(pillH, gap, headerLeft = "Battery", headerRight = "") { ChargeRow(battery) }

        Kind.Incoming -> {
            val c = rememberLast(callNow)
            Big(pillH, gap, headerLeft = "Incoming call", headerRight = "") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (c?.icon != null) Art(c.icon, 46.dp, 23.dp)
                    else IconCircle(Icons.Filled.Call, Color.White.copy(alpha = 0.15f), Color.White, 46.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(c?.name.orEmpty(), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    RoundButton(Icons.Filled.CallEnd, EndRed, "Decline") { IslandHub.send(context, c?.decline) }
                    Spacer(Modifier.width(12.dp))
                    RoundButton(Icons.Filled.Call, CallGreen, "Answer") { IslandHub.send(context, c?.answer) }
                }
            }
        }

        Kind.BigCall -> {
            val c = rememberLast(callNow)
            Big(pillH, gap, headerLeft = "On a call", headerRight = c?.startedAt?.let { formatElapsed(now - it) }.orEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (c?.icon != null) Art(c.icon, 46.dp, 23.dp)
                    else IconCircle(Icons.Filled.Call, CallGreen.copy(alpha = 0.2f), CallGreen, 46.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c?.name.orEmpty(), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Hold to open", color = Dim, fontSize = 13.sp)
                    }
                    if (c?.hangUp != null) {
                        RoundButton(Icons.Filled.CallEnd, EndRed, "Hang up") { IslandHub.send(context, c.hangUp) }
                    }
                }
            }
        }

        Kind.BigTimer -> {
            val t = rememberLast(timerNow)
            Big(pillH, gap, headerLeft = t?.label.orEmpty(), headerRight = "") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconCircle(Icons.Filled.Timer, TimerOrange.copy(alpha = 0.2f), TimerOrange, 46.dp)
                    Spacer(Modifier.width(14.dp))
                    Mono(t?.let { timerText(it, now) }.orEmpty(), TimerOrange, 32, Modifier.weight(1f))
                    Text("Open", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.15f))
                            .clickable { IslandHub.send(context, t?.open); collapse() }
                            .padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
        }

        Kind.BigMedia -> {
            val m = rememberLast(mediaNow)
            val tint = rememberArtTint(m?.art)
            Big(pillH, gap, headerLeft = "", headerRight = "") {
                MediaPanel(m, tint, now, onOpen = { openMedia(); collapse() })
            }
        }
    }
}

// ---------- Moving the island ----------

@Composable
private fun EditIsland(
    geometry: IslandGeometry,
    placement: IslandPlacement,
    onPlacement: ((IslandPlacement) -> IslandPlacement) -> Unit,
    onDone: () -> Unit,
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val hole = geometry.hole
    val start = remember { islandAnchor(geometry, placement, density.density) }
    var cx by remember { mutableFloatStateOf(start.first) }
    var top by remember { mutableFloatStateOf(start.second) }
    var dragging by remember { mutableStateOf(false) }

    val s = placement.scale
    val snapPx = with(density) { 44.dp.toPx() }
    val onCamDims = rememberDims(geometry, placement.copy(onCamera = true))
    val freeDims = rememberDims(geometry, placement.copy(onCamera = false))
    val pillHFree = with(density) { freeDims.pillH.toPx() }
    val near = hole != null && hypot(cx - hole.centerX(), top + pillHFree / 2 - hole.centerY()) < snapPx
    val snapped = (placement.onCamera && !dragging) || (dragging && near)
    val dims = if (snapped) onCamDims else freeDims
    val size = dims.size(Kind.Media)
    var wasNear by remember { mutableStateOf(near) }
    LaunchedEffect(near) {
        if (dragging && near != wasNear) view.haptic(confirm = near)
        wasNear = near
    }

    // Where to draw it: snapped sits on the camera, otherwise follows your finger
    val (drawCx, drawTop) = if (snapped && hole != null) {
        islandAnchor(geometry, placement.copy(onCamera = true), density.density)
    } else cx to top

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
    ) {
        if (hole != null && dragging) {
            val ring = with(density) { (maxOf(hole.width(), hole.height()) + 20.dp.toPx()) }
            val ringDp = with(density) { ring.toDp() }
            Box(
                Modifier
                    .offset { IntOffset((hole.centerX() - ring / 2).roundToInt(), (hole.centerY() - ring / 2).roundToInt()) }
                    .size(ringDp)
                    .border(2.dp, if (near) CallGreen else Color.White.copy(alpha = 0.5f), CircleShape),
            )
        }

        Box(
            Modifier
                .offset {
                    IntOffset(
                        (drawCx - size.width.toPx() / 2).roundToInt(),
                        drawTop.roundToInt(),
                    )
                }
                .size(size)
                .clip(RoundedCornerShape(size.height / 2))
                .background(Color.Black)
                .border(1.5.dp, if (snapped) CallGreen else Color.White.copy(alpha = 0.6f), RoundedCornerShape(size.height / 2))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            dragging = true
                            cx = drawCx
                            top = drawTop
                        },
                        onDragEnd = {
                            dragging = false
                            val snap = hole != null &&
                                hypot(cx - hole.centerX(), top + pillHFree / 2 - hole.centerY()) < snapPx
                            onPlacement { it.copy(onCamera = snap, centerX = cx, top = top) }
                        },
                        onDragCancel = { dragging = false },
                    ) { change, d ->
                        change.consume()
                        cx = (cx + d.x).coerceIn(0f, geometry.screenW.toFloat())
                        top = (top + d.y).coerceIn(0f, geometry.screenH - pillHFree)
                    }
                },
        ) {
            Compact(dims.gap,
                left = { Icon(Icons.Filled.MusicNote, null, tint = Color.White, modifier = Modifier.size(18.dp)) },
                right = { Bars(playing = true, color = Color(0xFF8AB4F8)) },
            )
        }

        Column(
            Modifier
                .align(Alignment.Center)
                .padding(24.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xFF1C1F26))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Move the island", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    dragging && near -> "Let go to put it on the camera"
                    snapped -> "It's on the camera. Drag it anywhere else"
                    else -> "Drag it anywhere. Bring it to the camera to snap it back"
                },
                color = Dim, fontSize = 14.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EditButton("Smaller") { onPlacement { it.copy(scale = it.scale - 0.1f) } }
                Text("${(s * 100).roundToInt()}%", color = Color.White, fontSize = 15.sp, modifier = Modifier.align(Alignment.CenterVertically))
                EditButton("Bigger") { onPlacement { it.copy(scale = it.scale + 0.1f) } }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (hole != null) EditButton("Put on camera") { onPlacement { it.copy(onCamera = true) } }
                EditButton("Done", primary = true, onClick = onDone)
            }
        }
    }
}

@Composable
private fun EditButton(label: String, primary: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        color = if (primary) Color.Black else Color.White,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (primary) Color.White else Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

private fun View.haptic(confirm: Boolean) {
    performHapticFeedback(
        when {
            confirm && android.os.Build.VERSION.SDK_INT >= 30 -> HapticFeedbackConstants.CONFIRM
            confirm -> HapticFeedbackConstants.VIRTUAL_KEY
            else -> HapticFeedbackConstants.CLOCK_TICK
        },
    )
}

// ---------- Layout pieces ----------

/** The pill: something on each side of the camera. */
@Composable
private fun Compact(gap: Dp, left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { left() }
        Spacer(Modifier.width(gap))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { right() }
    }
}

/** The expanded card: a small header beside the camera, then the content below it. */
@Composable
private fun Big(
    headerH: Dp,
    gap: Dp,
    headerLeft: String,
    headerRight: String,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(headerH), verticalAlignment = Alignment.CenterVertically) {
            Text(headerLeft, color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(gap))
            Text(headerRight, color = Dim, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun MediaPanel(m: IslandHub.Media?, tint: Color, now: Long, onOpen: () -> Unit) {
    val controls = m?.controller?.transportControls
    // `now` changes every tick, so this re-reads the position while expanded
    val pos = m?.positionNow() ?: 0L
    val dur = m?.duration ?: 0L
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clickable(onClick = onOpen)) { Art(m?.art, 60.dp, 14.dp) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(m?.title.orEmpty(), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(m?.artist.orEmpty(), color = Dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Bars(playing = m?.playing == true, color = tint)
        }
        Spacer(Modifier.height(14.dp))
        if (dur > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Mono(formatElapsed(pos), Dim, 12)
                Spacer(Modifier.width(10.dp))
                BoxWithConstraints(
                    Modifier
                        .weight(1f)
                        .height(18.dp)
                        .pointerInput(dur) {
                            detectTapGestures { o -> controls?.seekTo((o.x / size.width * dur).toLong()) }
                        },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    val frac = (pos.toFloat() / dur).coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)))
                    Box(Modifier.width(maxWidth * frac).height(5.dp).clip(CircleShape).background(Color.White))
                }
                Spacer(Modifier.width(10.dp))
                Mono("-" + formatElapsed(dur - pos), Dim, 12)
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            PlainButton(Icons.Filled.SkipPrevious, "Previous", 34.dp) { controls?.skipToPrevious() }
            PlainButton(if (m?.playing == true) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play or pause", 44.dp) {
                if (m?.playing == true) controls?.pause() else controls?.play()
            }
            PlainButton(Icons.Filled.SkipNext, "Next", 34.dp) { controls?.skipToNext() }
        }
    }
}

@Composable
private fun ChargeRow(b: Battery) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconCircle(Icons.Filled.BatteryChargingFull, (if (b.charging) CallGreen else Color.White).copy(alpha = 0.2f), if (b.charging) CallGreen else Color.White, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(if (b.charging) "Charging" else "Not charging", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            BoxWithConstraints(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(maxWidth * (b.level / 100f))
                        .clip(CircleShape)
                        .background(if (b.charging) CallGreen else if (b.level <= 20) EndRed else Color.White),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Text("${b.level}%", color = if (b.charging) CallGreen else Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Art(bitmap: Bitmap?, size: Dp, corner: Dp) {
    val img = remember(bitmap) { bitmap?.asImageBitmap() }
    if (img != null) {
        Image(img, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(RoundedCornerShape(corner)))
    } else {
        Box(Modifier.size(size).clip(RoundedCornerShape(corner)).background(Color.White.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.MusicNote, null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
        }
    }
}

@Composable
private fun IconCircle(icon: ImageVector, bg: Color, tint: Color, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
private fun RoundButton(icon: ImageVector, color: Color, label: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).background(color).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun PlainButton(icon: ImageVector, label: String, size: Dp, onClick: () -> Unit) {
    Box(Modifier.size(size + 16.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(size))
    }
}

@Composable
private fun Mono(text: String, color: Color, sizeSp: Int, modifier: Modifier = Modifier) {
    Text(text, color = color, fontSize = sizeSp.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, maxLines = 1, modifier = modifier)
}

/** Little equaliser bars: dance while playing, lie flat when paused. */
@Composable
private fun Bars(playing: Boolean, color: Color) {
    val t = rememberInfiniteTransition(label = "bars")
    Row(Modifier.height(18.dp), horizontalArrangement = Arrangement.spacedBy(2.5.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(420, 560, 360, 500).forEachIndexed { i, ms ->
            val h by t.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(ms, delayMillis = i * 70), RepeatMode.Reverse),
                label = "bar$i",
            )
            Box(
                Modifier
                    .width(3.dp)
                    .height(18.dp * (if (playing) h else 0.2f))
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

// ---------- State helpers ----------

/** Keeps the last non-null value so content can finish animating out after its data is gone. */
@Composable
private fun <T> rememberLast(value: T?): T? {
    val holder = remember { arrayOfNulls<Any>(1) }
    if (value != null) holder[0] = value
    @Suppress("UNCHECKED_CAST")
    return holder[0] as T?
}

/** A bright colour taken from the album art, for the equaliser bars. */
@Composable
private fun rememberArtTint(art: Bitmap?): Color = remember(art) {
    if (art == null) return@remember Color.White
    runCatching {
        val px = Bitmap.createScaledBitmap(art, 1, 1, true).getPixel(0, 0)
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(px, hsv)
        hsv[1] = (hsv[1] * 1.2f).coerceIn(0.35f, 0.9f)
        hsv[2] = 1f
        Color(android.graphics.Color.HSVToColor(hsv))
    }.getOrDefault(Color.White)
}

@Composable
private fun rememberBattery(): Battery {
    val context = LocalContext.current
    var battery by remember { mutableStateOf(readBattery(context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                battery = when (intent?.action) {
                    Intent.ACTION_POWER_CONNECTED -> battery.copy(charging = true)
                    Intent.ACTION_POWER_DISCONNECTED -> battery.copy(charging = false)
                    else -> readBattery(intent)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return battery
}

private fun readBattery(intent: Intent?): Battery {
    if (intent == null) return Battery(0, false)
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    val charging = plugged || status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    return Battery(if (level >= 0) level * 100 / scale else 0, charging)
}

private fun timerText(t: IslandHub.Timer, now: Long): String {
    val time = t.time ?: return t.text
    return if (t.countDown) formatElapsed((time - now).coerceAtLeast(0) + 999) else formatElapsed(now - time)
}

private fun formatElapsed(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
