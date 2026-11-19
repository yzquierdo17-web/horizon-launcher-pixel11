package com.horizon.launcher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Rotating dial clock (watch-bezel style): a big hour on the left, an inner minute scale and an
 * outer seconds scale that turn so the current minute and second sit inside a pill on the right,
 * with the date in small spaced capitals beside it.
 *
 * Tap the dial for Clock, tap the date for Calendar.
 */
@Composable
fun DialClock(
    is24h: Boolean,
    onOpenClock: () -> Unit,
    onOpenCalendar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Redraw ~30 times a second: smooth enough for the seconds sweep, light on battery.
    // withFrameMillis pauses automatically when Home isn't on screen.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { }
            val t = System.currentTimeMillis()
            if (t - nowMs >= 33) nowMs = t
        }
    }

    val measurer = rememberTextMeasurer()
    val shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 1f), 6f)
    val minuteStyle = TextStyle(color = Color.White, fontSize = 15.sp, shadow = shadow)
    val secondStyle = TextStyle(color = Color.White, fontSize = 12.sp, shadow = shadow)
    // Pre-measure the scale labels once; only their position and fade change per frame.
    val minuteLabels = remember(measurer) { (0 until 60 step 5).associateWith { measurer.measure(it.pad(), minuteStyle) } }
    val secondLabels = remember(measurer) { (0 until 60 step 5).associateWith { measurer.measure(it.pad(), secondStyle) } }

    val dateFmt = remember { DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()) }
    val dayFmt = remember { DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()) }

    // Where the date starts, so a tap there opens Calendar (kept outside Compose state on purpose).
    val dateLeft = remember { FloatArray(1) }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(292.dp)
            .pointerInput(Unit) {
                detectTapGestures { p -> if (dateLeft[0] > 0f && p.x >= dateLeft[0]) onOpenCalendar() else onOpenClock() }
            },
    ) {
        val zone = ZoneId.systemDefault()
        val time = Instant.ofEpochMilli(nowMs).atZone(zone)
        val sec = time.second + (nowMs % 1000) / 1000f
        val min = time.minute + sec / 60f

        val rMin = 84.dp.toPx()
        val rSec = 126.dp.toPx()
        val c = Offset(28.dp.toPx(), size.height / 2f)

        drawScale(minuteLabels, c, rMin, min, tickOffset = 14.dp.toPx(), majorTick = 6.dp.toPx(), minorTick = 3.dp.toPx(), tickAlpha = 0.35f)
        drawScale(secondLabels, c, rSec, sec, tickOffset = 13.dp.toPx(), majorTick = 9.dp.toPx(), minorTick = 5.dp.toPx(), tickAlpha = 0.6f)

        // Pill around the current minute and second.
        val pillH = 34.dp.toPx()
        val pillLeft = c.x + rMin - 19.dp.toPx()
        val pillRight = c.x + rSec + 17.dp.toPx()
        drawRoundRect(
            color = Color.White.copy(alpha = 0.92f),
            topLeft = Offset(pillLeft, c.y - pillH / 2f),
            size = Size(pillRight - pillLeft, pillH),
            cornerRadius = CornerRadius(pillH / 2f),
            style = Stroke(width = 1.5.dp.toPx()),
        )
        drawCentered(measurer.measure(time.minute.pad(), TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium, shadow = shadow)), Offset(c.x + rMin, c.y))
        drawCentered(measurer.measure(time.second.pad(), TextStyle(color = Color.White, fontSize = 13.sp, shadow = shadow)), Offset(c.x + rSec, c.y))

        // Big hour, right-aligned against the pill.
        val hour = if (is24h) time.hour else (time.hour % 12).let { if (it == 0) 12 else it }
        val hourText = measurer.measure(hour.pad(), TextStyle(color = Color.White, fontSize = 58.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp, shadow = shadow))
        drawText(hourText, topLeft = Offset(pillLeft - 10.dp.toPx() - hourText.size.width, c.y - hourText.size.height / 2f))

        // Date in small spaced capitals.
        val dateX = pillRight + 22.dp.toPx()
        dateLeft[0] = dateX
        val dateText = measurer.measure(
            time.format(dateFmt).uppercase(),
            TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.sp, shadow = shadow),
        )
        val dayText = measurer.measure(
            time.format(dayFmt).uppercase(),
            TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.5.sp, shadow = shadow),
        )
        if (dateX + maxOf(dateText.size.width, dayText.size.width) <= size.width) {
            drawText(dateText, topLeft = Offset(dateX, c.y - dateText.size.height - 2.dp.toPx()))
            drawText(dayText, topLeft = Offset(dateX, c.y + 2.dp.toPx()))
        }
    }
}

/**
 * One rotating scale. The current [value] points right (3 o'clock); larger numbers sit above it,
 * like the reference. Labels every 5, ticks every 1, fading out toward the back of the ring.
 */
private fun DrawScope.drawScale(
    labels: Map<Int, TextLayoutResult>,
    center: Offset,
    radius: Float,
    value: Float,
    tickOffset: Float,
    majorTick: Float,
    minorTick: Float,
    tickAlpha: Float,
) {
    val span = 108f // degrees visible above and below the pill
    for (v in 0 until 60) {
        var delta = (v - value) * 6f
        delta = ((delta % 360f) + 540f) % 360f - 180f
        val d = abs(delta)
        if (d > span) continue
        val fade = (1f - (d / span).pow(2.2f)).coerceIn(0f, 1f)
        val rad = Math.toRadians(-delta.toDouble())
        val cs = cos(rad).toFloat()
        val sn = sin(rad).toFloat()

        // Tick marks just outside the numbers.
        val r1 = radius + tickOffset
        val r2 = r1 + if (v % 5 == 0) majorTick else minorTick
        drawLine(
            color = Color.White.copy(alpha = tickAlpha * fade),
            start = Offset(center.x + cs * r1, center.y + sn * r1),
            end = Offset(center.x + cs * r2, center.y + sn * r2),
            strokeWidth = if (v % 5 == 0) 1.4.dp.toPx() else 1.dp.toPx(),
        )

        // Number labels every 5, hidden where the pill shows the live value.
        if (v % 5 == 0 && d > 8f) {
            val layout = labels[v] ?: continue
            drawCentered(layout, Offset(center.x + cs * radius, center.y + sn * radius), alpha = 0.9f * fade)
        }
    }
}

private fun DrawScope.drawCentered(layout: TextLayoutResult, at: Offset, alpha: Float = 1f) {
    drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f), alpha = alpha)
}

private fun Int.pad() = toString().padStart(2, '0')
