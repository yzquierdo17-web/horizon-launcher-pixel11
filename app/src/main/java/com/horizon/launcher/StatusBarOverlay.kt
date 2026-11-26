package com.horizon.launcher

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What Horizon draws at the top of the screen for the emoji battery: either just the battery
 * beside the camera, or a whole status bar of its own (time, notifications, signal, battery).
 */
@Composable
fun EmojiStatusBar(geometry: IslandGeometry, visible: Boolean) {
    val style by EmojiBatteryPrefs.style.collectAsState()
    val battery by BatteryHub.state.collectAsState()
    val mode = BarMode.of(style.bar)
    if (mode == BarMode.Off || !visible || geometry.landscape) return
    val density = LocalDensity.current
    val barH = with(density) { geometry.statusBar.toDp() }
    Box(Modifier.fillMaxSize().then(barGestures(style))) {
        when (mode) {
            BarMode.Sticker -> {
                // Just right of the camera, centred in the status bar.
                val hole = geometry.hole
                val x = hole?.let { (it.right + with(density) { 10.dp.toPx() }).roundToInt() }
                    ?: (geometry.screenW * 0.56f).roundToInt()
                Box(Modifier.offset { IntOffset(x, 0) }.height(barH), contentAlignment = Alignment.Center) {
                    EmojiBatteryGlyph(style, battery, Color.White, height = (barH.value * 0.5f).dp)
                }
                NotchDecor(geometry, style)
            }
            BarMode.Full -> FullBar(geometry, style, battery, barH)
            BarMode.Off -> Unit
        }
    }
}

/** Tap, long-press and swipes on the bar, when you turned status bar gestures on. */
@Composable
private fun barGestures(style: EmojiBatteryStyle): Modifier {
    if (!style.barGestures) return Modifier
    val context = LocalContext.current
    val s by rememberUpdatedState(style)
    return Modifier
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { runBarAction(context, s.barTap) },
                onLongPress = { runBarAction(context, s.barLongPress) },
            )
        }
        .pointerInput(Unit) {
            var dx = 0f
            var dy = 0f
            detectDragGestures(
                onDragStart = { dx = 0f; dy = 0f },
                onDrag = { change, amount -> change.consume(); dx += amount.x; dy += amount.y },
                onDragEnd = {
                    val min = 40.dp.toPx()
                    when {
                        dy > min && abs(dy) > abs(dx) -> runBarAction(context, s.barSwipeDown)
                        dx < -min && abs(dx) > abs(dy) -> runBarAction(context, s.barSwipeLeft)
                        dx > min && abs(dx) > abs(dy) -> runBarAction(context, s.barSwipeRight)
                    }
                },
            )
        }
}

/** Actions the status bar gestures can run (from the accessibility helper, no launcher needed). */
val BarActions: List<Pair<String, String>> = listOf(
    "none" to "Nothing",
    "notifications" to "Open notifications",
    "quicksettings" to "Open quick settings",
    "back" to "Back",
    "home" to "Home",
    "recents" to "Recent apps",
    "power" to "Power menu",
    "screenshot" to "Take a screenshot",
    "lock" to "Lock screen",
    "flashlight" to "Flashlight",
    "camera" to "Camera",
    "assistant" to "Assistant",
)

fun barActionLabel(id: String, apps: List<AppEntry>): String =
    if (id.startsWith("app:")) apps.firstOrNull { it.key == id.removePrefix("app:") }?.let { "Open ${it.label}" } ?: "Open an app"
    else BarActions.firstOrNull { it.first == id }?.second ?: "Nothing"

fun runBarAction(context: Context, id: String) {
    val a = android.accessibilityservice.AccessibilityService::class.java
    fun global(name: String) = runCatching { IslandOverlayService.globalAction(a.getField(name).getInt(null)) }.getOrDefault(false)
    when {
        id.startsWith("app:") -> launchAppKey(context, id.removePrefix("app:"))
        id == "notifications" -> global("GLOBAL_ACTION_NOTIFICATIONS")
        id == "quicksettings" -> global("GLOBAL_ACTION_QUICK_SETTINGS")
        id == "back" -> global("GLOBAL_ACTION_BACK")
        id == "home" -> global("GLOBAL_ACTION_HOME")
        id == "recents" -> global("GLOBAL_ACTION_RECENTS")
        id == "power" -> global("GLOBAL_ACTION_POWER_DIALOG")
        id == "screenshot" -> global("GLOBAL_ACTION_TAKE_SCREENSHOT")
        id == "lock" -> IslandOverlayService.lockScreen()
        id == "flashlight" -> Torch.toggle(context)
        id == "camera" -> context.startSafely(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        id == "assistant" -> context.startSafely(Intent(Intent.ACTION_VOICE_COMMAND))
    }
}

/** Opens an app from its Horizon key ("component#userSerial"). */
private fun launchAppKey(context: Context, key: String) {
    runCatching {
        val comp = android.content.ComponentName.unflattenFromString(key.substringBefore('#')) ?: return
        val serial = key.substringAfter('#', "0").toLongOrNull() ?: 0L
        val um = context.getSystemService(android.os.UserManager::class.java)
        val user = um.getUserForSerialNumber(serial) ?: android.os.Process.myUserHandle()
        context.getSystemService(android.content.pm.LauncherApps::class.java).startMainActivity(comp, user, null, null)
    }
}

@Composable
private fun FullBar(geometry: IslandGeometry, style: EmojiBatteryStyle, battery: BatteryState, barH: Dp) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val bg1 = Color(style.background)
    val bg2 = Color(style.background2)
    val gradient = style.bgMode == "gradient"
    val auto = if ((if (gradient) (bg1.luminance() + bg2.luminance()) / 2 else bg1.luminance()) > 0.5f) Color.Black else Color.White
    val ink = if (style.iconColor == 0L) auto else Color(style.iconColor)
    val timeInk = if (style.timeColor == 0L) auto else Color(style.timeColor)
    val net by NetHub.state.collectAsState()
    val icons by IslandHub.statusIcons.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            NetHub.pollCell(context)
            delay(5_000)
        }
    }
    val is24 = android.text.format.DateFormat.is24HourFormat(context)
    val time = remember(now / 60_000, is24) { SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.getDefault()).format(Date(now)) }
    // Keep everything clear of the camera.
    val holeL = geometry.hole?.left ?: (geometry.screenW / 2f)
    val holeR = geometry.hole?.right ?: (geometry.screenW / 2f)
    val leftW = with(density) { (holeL - 12.dp.toPx()).coerceAtLeast(0f).toDp() }
    val rightW = with(density) { (geometry.screenW - holeR - 12.dp.toPx()).coerceAtLeast(0f).toDp() }
    val timeStyle = TextStyle(
        color = timeInk,
        fontSize = (barH.value * 0.42f * style.timeScale).sp,
        fontWeight = if (style.timeBold) FontWeight.Bold else FontWeight.Medium,
    )
    val iconSize = (barH.value * 0.42f * style.iconScale).dp

    Box(
        Modifier
            .fillMaxWidth()
            .height(barH)
            .background(if (gradient) Brush.horizontalGradient(listOf(bg1, bg2)) else Brush.linearGradient(listOf(bg1, bg1))),
    ) {
        Row(
            Modifier.width(leftW).height(barH).padding(start = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (style.showTime) {
                Text(time, style = timeStyle)
                Spacer(Modifier.width(8.dp))
            }
            if (style.showNotifs) icons.take(4).forEach { (pkg, icon) ->
                NotifIcon(pkg, icon, ink, iconSize * 0.95f)
                Spacer(Modifier.width(5.dp))
            }
        }
        Row(
            Modifier.align(Alignment.TopEnd).width(rightW).height(barH).padding(end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            if (style.showAirplane && net.airplane) {
                Text("✈", color = ink, fontSize = (iconSize.value * 0.95f).sp)
                Spacer(Modifier.width(5.dp))
            }
            if (style.showWifi && net.wifi >= 0) {
                WifiIcon(net.wifi, ink, iconSize, style.wifiStyle)
                Spacer(Modifier.width(5.dp))
            }
            if (style.showNetType && net.netType != null) {
                Text(net.netType!!, color = ink, fontSize = (iconSize.value * 0.72f).sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(3.dp))
            }
            if (style.showCell && net.cell >= 0) {
                CellIcon(net.cell, ink, iconSize, style.cellStyle)
                Spacer(Modifier.width(7.dp))
            }
            EmojiBatteryGlyph(style, battery, ink, height = (barH.value * 0.45f).dp)
        }
        NotchDecor(geometry, style)
    }
}

/** Shapes around the camera: a ring, a wider pill, or a classic notch. */
@Composable
private fun NotchDecor(geometry: IslandGeometry, style: EmojiBatteryStyle) {
    val hole = geometry.hole ?: return
    if (style.notch == "none") return
    val color = Color(style.notchColor)
    Canvas(Modifier.fillMaxSize()) {
        val cx = hole.centerX()
        val cy = hole.centerY()
        val r = hole.width() / 2f * style.notchSize
        when (style.notch) {
            "ring" -> drawCircle(color, r + 3.dp.toPx(), Offset(cx, cy), style = Stroke(width = 2.dp.toPx()))
            "pill" -> {
                val w = r * 6f
                val h = r * 2.6f
                drawRoundRect(color, Offset(cx - w / 2, cy - h / 2), Size(w, h), CornerRadius(h / 2))
            }
            "notch" -> {
                val w = r * 9f
                val h = size.height
                val corner = h * 0.45f
                val p = Path().apply {
                    moveTo(cx - w / 2 - corner, 0f)
                    quadraticBezierTo(cx - w / 2, 0f, cx - w / 2, corner)
                    lineTo(cx - w / 2, h - corner)
                    quadraticBezierTo(cx - w / 2, h, cx - w / 2 + corner, h)
                    lineTo(cx + w / 2 - corner, h)
                    quadraticBezierTo(cx + w / 2, h, cx + w / 2, h - corner)
                    lineTo(cx + w / 2, corner)
                    quadraticBezierTo(cx + w / 2, 0f, cx + w / 2 + corner, 0f)
                    close()
                }
                drawPath(p, color)
            }
        }
    }
}

@Composable
private fun NotifIcon(pkg: String, icon: Icon, ink: Color, size: Dp) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bmp = remember(pkg, icon) {
        runCatching {
            val pc = if (icon.type == Icon.TYPE_RESOURCE) context.createPackageContext(pkg, 0) else context
            icon.loadDrawable(pc)?.toBitmap(px, px)?.asImageBitmap()
        }.getOrNull()
    }
    if (bmp != null) Image(bmp, null, colorFilter = ColorFilter.tint(ink), modifier = Modifier.size(size))
}

@Composable
fun WifiIcon(level: Int, ink: Color, size: Dp, style: String = "arcs") {
    Canvas(Modifier.size(size)) {
        val s = this.size.width
        when (style) {
            "dots" -> for (i in 0 until 4) {
                drawCircle(if (i < level) ink else ink.copy(alpha = 0.3f), s * 0.1f, Offset(s * (0.15f + i * 0.233f), s * 0.6f))
            }
            "bars" -> for (i in 0 until 4) {
                val h = s * (0.35f + i * 0.2f)
                drawRoundRect(
                    if (i < level) ink else ink.copy(alpha = 0.3f),
                    Offset(s * (0.06f + i * 0.24f), s - h), Size(s * 0.17f, h), CornerRadius(s * 0.05f),
                )
            }
            else -> {
                val c = Offset(s / 2, s * 0.92f)
                for (i in 0 until 4) {
                    val r = s * (0.25f + i * 0.22f)
                    drawArc(
                        if (i < level) ink else ink.copy(alpha = 0.3f),
                        startAngle = 225f, sweepAngle = 90f, useCenter = i == 0,
                        topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2),
                        style = if (i == 0) androidx.compose.ui.graphics.drawscope.Fill else Stroke(width = s * 0.11f),
                    )
                }
            }
        }
    }
}

@Composable
fun CellIcon(level: Int, ink: Color, size: Dp, style: String = "bars") {
    Canvas(Modifier.size(size)) {
        val s = this.size.width
        when (style) {
            "triangle" -> {
                val p = Path().apply { moveTo(s, 0f); lineTo(s, s); lineTo(0f, s); close() }
                drawPath(p, ink.copy(alpha = 0.3f))
                if (level > 0) {
                    val f = level / 4f
                    drawPath(Path().apply { moveTo(s * f, s * (1 - f)); lineTo(s * f, s); lineTo(0f, s); close() }, ink)
                }
            }
            "dots" -> for (i in 0 until 4) {
                drawCircle(if (i < level) ink else ink.copy(alpha = 0.3f), s * 0.1f, Offset(s * (0.15f + i * 0.233f), s * 0.6f))
            }
            else -> for (i in 0 until 4) {
                val h = s * (0.3f + i * 0.23f)
                drawRoundRect(
                    if (i < level) ink else ink.copy(alpha = 0.3f),
                    Offset(s * (0.04f + i * 0.25f), s - h), Size(s * 0.18f, h), CornerRadius(s * 0.05f),
                )
            }
        }
    }
}

// ---------- Charging animation ----------

/** Plays over everything for a few seconds when you plug in. */
@Composable
fun ChargingAnimation(style: EmojiBatteryStyle, battery: BatteryState, onDone: () -> Unit) {
    val theme = BatteryTheme.of(style.theme)
    val emoji = theme.emojiFor(battery.level, true, style.custom)
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        fade.animateTo(1f, tween(300))
        delay(2600)
        fade.animateTo(0f, tween(400))
        onDone()
    }
    val t = rememberInfiniteTransition(label = "charge")
    val pulse by t.animateFloat(0.92f, 1.08f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "pulse")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "spin")
    val rise by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "rise")
    val green = Color(0xFF4CD964)
    Box(
        Modifier.fillMaxSize().alpha(fade.value).background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        when (style.chargeAnim) {
            "ring" -> Canvas(Modifier.size(240.dp)) {
                drawCircle(green.copy(alpha = 0.18f), size.minDimension / 2, style = Stroke(10.dp.toPx()))
                drawArc(
                    Brush.sweepGradient(listOf(Color.Transparent, green)), spin, 300f * battery.level / 100f + 20f, false,
                    style = Stroke(10.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
                )
            }
            "bubbles" -> Canvas(Modifier.fillMaxSize()) {
                val n = 14
                for (i in 0 until n) {
                    val phase = (rise + i / n.toFloat()) % 1f
                    val x = size.width * ((i * 37 % 100) / 100f)
                    val y = size.height * (1f - phase)
                    drawCircle(green.copy(alpha = 0.5f * (1f - phase)), (6 + (i % 4) * 5).dp.toPx(), Offset(x, y))
                }
            }
            else -> Canvas(Modifier.size(260.dp)) {
                drawCircle(Brush.radialGradient(listOf(green.copy(alpha = 0.45f), Color.Transparent)), size.minDimension / 2 * pulse)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 96.sp, modifier = Modifier.graphicsLayer { scaleX = pulse; scaleY = pulse })
            Spacer(Modifier.height(10.dp))
            Text("${battery.level}%", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Text("⚡ Charging", color = green, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }
    }
}
