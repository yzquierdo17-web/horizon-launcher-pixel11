package com.horizon.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.BatteryManager
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

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

/** Where the front camera is, in px. Falls back to a centred spot under the status bar. */
private data class Cutout(val centerX: Float, val top: Float, val width: Float, val height: Float)

/**
 * A Dynamic Island that wraps the Pixel's camera hole: calls, timers, music, charging and
 * notification peeks. Lives on Horizon's home screen only (a launcher can't draw over other apps).
 */
@Composable
fun DynamicIsland(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val config = LocalConfiguration.current

    val media by IslandHub.media.collectAsState()
    val call by IslandHub.call.collectAsState()
    val timer by IslandHub.timer.collectAsState()
    val peek by IslandHub.peek.collectAsState()
    val battery = rememberBattery()

    // A clock for timers, call length and peek expiry
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }

    // Plugging in or unplugging shows the charge for a moment
    var powerUntil by remember { mutableLongStateOf(0L) }
    var lastCharging by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(battery.charging) {
        if (lastCharging != null && lastCharging != battery.charging) {
            powerUntil = System.currentTimeMillis() + 3000
            view.performHapticFeedback(
                if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY,
            )
        }
        lastCharging = battery.charging
    }
    LaunchedEffect(peek?.key, peek?.postedAt) {
        val p = peek
        if (p != null && System.currentTimeMillis() - p.postedAt < 2000) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    var expanded by remember { mutableStateOf(false) }

    // Find the camera hole once the window has insets
    var cutout by remember { mutableStateOf<Cutout?>(null) }
    LaunchedEffect(config.orientation, config.screenWidthDp) {
        repeat(40) {
            val insets = view.rootWindowInsets
            if (insets != null) {
                val r = insets.displayCutout?.boundingRectTop
                val statusTop = with(density) { 6.dp.toPx() }
                cutout = if (r != null && !r.isEmpty && r.top < with(density) { 80.dp.toPx() }) {
                    // Position relative to our window
                    val loc = IntArray(2).also { view.getLocationOnScreen(it) }
                    Cutout(r.exactCenterX() - loc[0], (r.top - loc[1]).toFloat(), r.width().toFloat(), r.height().toFloat())
                } else {
                    Cutout(view.width / 2f, statusTop, 0f, with(density) { 22.dp.toPx() })
                }
                return@LaunchedEffect
            }
            delay(50)
        }
    }
    val cut = cutout ?: return

    val peekLive = peek?.takeIf { now - it.postedAt < 4500 }
    val incoming = call?.incoming == true
    val kind = when {
        incoming -> Kind.Incoming
        peekLive != null -> Kind.Peek
        now < powerUntil -> Kind.Power
        call != null -> if (expanded) Kind.BigCall else Kind.Call
        timer != null -> if (expanded) Kind.BigTimer else Kind.Timer
        media != null -> if (expanded) Kind.BigMedia else Kind.Media
        battery.charging -> if (expanded) Kind.BigCharging else Kind.Charging
        else -> Kind.Idle
    }
    // Collapse when there's nothing left to expand
    LaunchedEffect(kind) { if (kind == Kind.Idle) expanded = false }

    val camW = with(density) { cut.width.toDp() }
    val camH = with(density) { cut.height.toDp() }
    val pad = 5.dp
    val pillH = maxOf(camH + pad * 2, 34.dp)
    val topY = maxOf(with(density) { cut.top.toDp() } - pad, 3.dp)
    val shift = with(density) { (cut.centerX - view.width / 2f).toDp() }
    val gap = if (camW > 0.dp) camW + 14.dp else 10.dp
    val headerH = (camH + pad * 2).coerceAtLeast(24.dp)
    val radius by animateDpAsState(
        if (kind.big) 30.dp else pillH / 2,
        spring(dampingRatio = 0.8f, stiffness = 400f),
        label = "islandRadius",
    )

    val openMedia: () -> Unit = {
        media?.let { m ->
            val pi = m.controller.sessionActivity
            if (pi != null) IslandHub.send(context, pi)
            else context.packageManager.getLaunchIntentForPackage(m.packageName)?.let {
                runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        if (expanded && kind.big) {
            // Tap anywhere else to shrink the island back
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.25f))
                    .clickable(remember { MutableInteractionSource() }, indication = null) { expanded = false },
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .offset(x = shift, y = topY),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(radius))
                    .background(Color.Black)
                    .pointerInput(kind) {
                        detectTapGestures(
                            onTap = {
                                when (kind) {
                                    Kind.Peek -> {
                                        IslandHub.send(context, peekLive?.open)
                                        IslandHub.dismissPeek()
                                    }
                                    Kind.Power, Kind.Idle, Kind.Incoming -> Unit
                                    else -> {
                                        expanded = !expanded
                                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                    }
                                }
                            },
                            onLongPress = {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                when (kind) {
                                    Kind.Media, Kind.BigMedia -> openMedia()
                                    Kind.Call, Kind.BigCall -> IslandHub.send(context, call?.open)
                                    Kind.Timer, Kind.BigTimer -> IslandHub.send(context, timer?.open)
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
                            if (total < -40f) {
                                if (kind == Kind.Peek) IslandHub.dismissPeek()
                                expanded = false
                                total = 0f
                            }
                        }
                    },
            ) {
                AnimatedContent(
                    targetState = kind,
                    transitionSpec = {
                        (fadeIn(tween(220, delayMillis = 80)) + scaleIn(tween(260, delayMillis = 40), initialScale = 0.9f)) togetherWith
                            fadeOut(tween(90)) using
                            SizeTransform(clip = true) { _, _ -> spring(dampingRatio = 0.72f, stiffness = 380f) }
                    },
                    label = "island",
                ) { k ->
                    when (k) {
                        Kind.Idle -> Box(Modifier.size(width = maxOf(camW, 1.dp), height = maxOf(camH, 1.dp)))

                        Kind.Charging -> Compact(pillH, gap,
                            left = { Icon(Icons.Filled.Bolt, null, tint = CallGreen, modifier = Modifier.size(18.dp)) },
                            right = { Text("${battery.level}%", color = CallGreen, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) },
                        )

                        Kind.Media -> {
                            val m = rememberLast(media)
                            val tint = rememberArtTint(m?.art)
                            Compact(pillH, gap,
                                left = { Art(m?.art, 24.dp, 7.dp) },
                                right = { Bars(playing = m?.playing == true, color = tint) },
                            )
                        }

                        Kind.Timer -> {
                            val t = rememberLast(timer)
                            Compact(pillH, gap,
                                left = { Icon(Icons.Filled.Timer, null, tint = TimerOrange, modifier = Modifier.size(18.dp)) },
                                right = { Mono(t?.let { timerText(it, now) }.orEmpty(), TimerOrange, 15) },
                            )
                        }

                        Kind.Call -> {
                            val c = rememberLast(call)
                            Compact(pillH, gap,
                                left = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Call, null, tint = CallGreen, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Mono(c?.startedAt?.let { formatElapsed(now - it) } ?: "Call", CallGreen, 14)
                                    }
                                },
                                right = { Bars(playing = true, color = CallGreen) },
                            )
                        }

                        Kind.Peek -> {
                            val p = rememberLast(peekLive)
                            Big(headerH, gap,
                                headerLeft = p?.appName.orEmpty(),
                                headerRight = "now",
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (p?.icon != null) Art(p.icon, 40.dp, 20.dp)
                                    else IconCircle(Icons.Filled.Notifications, Color.White.copy(alpha = 0.15f), Color.White, 40.dp)
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

                        Kind.Power -> Big(headerH, gap, headerLeft = "", headerRight = "") {
                            ChargeRow(battery)
                        }

                        Kind.BigCharging -> Big(headerH, gap, headerLeft = "Battery", headerRight = "") {
                            ChargeRow(battery)
                        }

                        Kind.Incoming -> {
                            val c = rememberLast(call)
                            Big(headerH, gap, headerLeft = "Incoming call", headerRight = "") {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (c?.icon != null) Art(c.icon, 44.dp, 22.dp)
                                    else IconCircle(Icons.Filled.Call, Color.White.copy(alpha = 0.15f), Color.White, 44.dp)
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
                            val c = rememberLast(call)
                            Big(headerH, gap, headerLeft = "On a call", headerRight = c?.startedAt?.let { formatElapsed(now - it) }.orEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (c?.icon != null) Art(c.icon, 44.dp, 22.dp)
                                    else IconCircle(Icons.Filled.Call, CallGreen.copy(alpha = 0.2f), CallGreen, 44.dp)
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
                            val t = rememberLast(timer)
                            Big(headerH, gap, headerLeft = t?.label.orEmpty(), headerRight = "") {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconCircle(Icons.Filled.Timer, TimerOrange.copy(alpha = 0.2f), TimerOrange, 44.dp)
                                    Spacer(Modifier.width(14.dp))
                                    Mono(t?.let { timerText(it, now) }.orEmpty(), TimerOrange, 34, Modifier.weight(1f))
                                    Text("Open", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(Color.White.copy(alpha = 0.15f))
                                            .clickable { IslandHub.send(context, t?.open); expanded = false }
                                            .padding(horizontal = 14.dp, vertical = 8.dp))
                                }
                            }
                        }

                        Kind.BigMedia -> {
                            val m = rememberLast(media)
                            val tint = rememberArtTint(m?.art)
                            Big(headerH, gap, headerLeft = "", headerRight = "") {
                                MediaPanel(m, tint, now, onOpen = { openMedia(); expanded = false })
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- Layout pieces ----------

/** The pill: something on each side of the camera. */
@Composable
private fun Compact(height: Dp, gap: Dp, left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(
        Modifier
            .height(height)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterStart) { left() }
        Spacer(Modifier.width(gap))
        Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterEnd) { right() }
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
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, bottom = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(headerH), verticalAlignment = Alignment.CenterVertically) {
            Text(headerLeft, color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(gap))
            Text(headerRight, color = Dim, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
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
