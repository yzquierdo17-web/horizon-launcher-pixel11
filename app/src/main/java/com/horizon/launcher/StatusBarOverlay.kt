package com.horizon.launcher

import android.graphics.drawable.Icon
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    when (mode) {
        BarMode.Sticker -> {
            // Just right of the camera, centred in the status bar.
            val hole = geometry.hole
            val x = hole?.let { (it.right + with(density) { 10.dp.toPx() }).roundToInt() }
                ?: (geometry.screenW * 0.56f).roundToInt()
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.offset { IntOffset(x, 0) }.height(barH),
                    contentAlignment = Alignment.Center,
                ) {
                    EmojiBatteryGlyph(style, battery, Color.White, height = (barH.value * 0.5f).dp)
                }
            }
        }
        BarMode.Full -> FullBar(geometry, style, battery, barH)
        BarMode.Off -> Unit
    }
}

@Composable
private fun FullBar(geometry: IslandGeometry, style: EmojiBatteryStyle, battery: BatteryState, barH: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val bg = Color(style.background)
    val ink = if (bg.luminance() > 0.5f) Color.Black else Color.White
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
    val textStyle = TextStyle(color = ink, fontSize = (barH.value * 0.42f).sp, fontWeight = FontWeight.Medium)
    val iconSize = (barH.value * 0.42f).dp

    Box(Modifier.fillMaxWidth().height(barH).background(bg)) {
        Row(
            Modifier.width(leftW).height(barH).padding(start = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(time, style = textStyle)
            Spacer(Modifier.width(8.dp))
            icons.take(4).forEach { (pkg, icon) ->
                NotifIcon(pkg, icon, ink, iconSize)
                Spacer(Modifier.width(5.dp))
            }
        }
        Row(
            Modifier.align(Alignment.TopEnd).width(rightW).height(barH).padding(end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
        ) {
            if (net.wifi >= 0) WifiIcon(net.wifi, ink, iconSize)
            if (net.cell >= 0) {
                Spacer(Modifier.width(5.dp))
                CellIcon(net.cell, ink, iconSize)
            }
            Spacer(Modifier.width(7.dp))
            EmojiBatteryGlyph(style, battery, ink, height = (barH.value * 0.45f).dp)
        }
    }
}

@Composable
private fun NotifIcon(pkg: String, icon: Icon, ink: Color, size: androidx.compose.ui.unit.Dp) {
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
private fun WifiIcon(level: Int, ink: Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(size)) {
        val c = Offset(size.toPx() / 2, size.toPx() * 0.92f)
        // A fan of four arcs; the lit ones show the signal.
        for (i in 0 until 4) {
            val r = size.toPx() * (0.25f + i * 0.22f)
            drawArc(
                if (i < level) ink else ink.copy(alpha = 0.3f),
                startAngle = 225f, sweepAngle = 90f, useCenter = i == 0,
                topLeft = Offset(c.x - r, c.y - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                style = if (i == 0) androidx.compose.ui.graphics.drawscope.Fill else androidx.compose.ui.graphics.drawscope.Stroke(width = size.toPx() * 0.11f),
            )
        }
    }
}

@Composable
private fun CellIcon(level: Int, ink: Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width
        val p = Path().apply { moveTo(s, 0f); lineTo(s, s); lineTo(0f, s); close() }
        drawPath(p, ink.copy(alpha = 0.3f))
        if (level > 0) {
            val f = level / 4f
            val lit = Path().apply { moveTo(s * f, s * (1 - f)); lineTo(s * f, s); lineTo(0f, s); close() }
            drawPath(lit, ink)
        }
    }
}
