package com.horizon.launcher

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Range steps: the label you see, and the radius asked for in nautical miles. */
private data class RadarRange(val label: String, val nm: Int)

private fun ranges(imperial: Boolean): List<RadarRange> =
    if (imperial) listOf(10, 25, 50, 100).map { RadarRange("$it mi", (it * 0.869).roundToInt().coerceAtLeast(1)) }
    else listOf(15, 40, 80, 160).map { RadarRange("$it km", (it * 0.54).roundToInt().coerceAtLeast(1)) }

private const val KN_TO_MPH = 1.15078
private const val KN_TO_KMH = 1.852

private fun fmtDistance(nm: Double, imperial: Boolean): String =
    if (imperial) "%.1f mi".format(Locale.getDefault(), nm * KN_TO_MPH) else "%.1f km".format(Locale.getDefault(), nm * KN_TO_KMH)

private fun fmtSpeed(kn: Double?, imperial: Boolean): String = when {
    kn == null -> "—"
    imperial -> "${(kn * KN_TO_MPH).roundToInt()} mph"
    else -> "${(kn * KN_TO_KMH).roundToInt()} km/h"
}

private fun fmtAltitude(a: Aircraft): String = when {
    a.onGround -> "On the ground"
    a.altitude == null -> "—"
    else -> "%,d ft".format(Locale.getDefault(), a.altitude)
}

/** Ground grey, low green, middle amber, cruising blue, emergencies red. */
private fun altitudeColor(a: Aircraft): Color = when {
    a.emergency -> Color(0xFFFF5A5F)
    a.onGround -> Color(0xFF9AA0A6)
    (a.altitude ?: 0) < 10_000 -> Color(0xFF6EE7A0)
    (a.altitude ?: 0) < 25_000 -> Color(0xFFFFC857)
    else -> Color(0xFF7CC4FF)
}

/** Full-screen live radar of the planes around you. */
@Composable
fun FlightRadarScreen(vm: LauncherViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val state by vm.flights.collectAsState()
    val imperial = remember { FlightRepository.imperial() }
    val steps = remember(imperial) { ranges(imperial) }
    val range = steps[settings.flightRange.coerceIn(0, steps.lastIndex)]
    var selected by remember { mutableStateOf<String?>(null) }
    val trails = rememberTrails(state)

    LaunchedEffect(range.nm) { vm.startRadar(range.nm) }
    DisposableEffect(Unit) { onDispose { vm.stopRadar() } }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startRadar(range.nm)
    }

    val center = state.center
    val sorted = remember(state.planes, center) {
        if (center == null) emptyList()
        else state.planes.map { it to FlightRepository.distanceNm(center.first, center.second, it.lat, it.lon) }
            .sortedBy { it.second }
    }
    val chosen = sorted.firstOrNull { it.first.hex == selected }

    Box(
        Modifier
            .fillMaxSize()
            .swallowTouches()
            .background(LocalThemeSurface.current.copy(alpha = 0.98f)),
    ) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        "Back",
                        tint = LocalInk.current,
                        modifier = Modifier.clip(CircleShape).clickable(onClick = onClose).padding(8.dp).size(24.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Flight radar", color = LocalInk.current, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                        Text(
                            when {
                                state.problem == "location" -> "Needs your location"
                                state.problem == "network" && state.updatedAt == 0L -> "Can't reach the flight network"
                                state.updatedAt == 0L -> "Looking for planes…"
                                else -> "${state.planes.size} " + (if (state.planes.size == 1) "plane" else "planes") +
                                    " within ${range.label} · live"
                            },
                            color = LocalInk.current.copy(alpha = 0.6f),
                            fontSize = 13.sp,
                        )
                    }
                    if (state.loading && state.updatedAt == 0L) {
                        CircularProgressIndicator(strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    steps.forEachIndexed { i, r ->
                        val on = r == range
                        Text(
                            r.label,
                            color = if (on) MaterialTheme.colorScheme.onPrimary else LocalInk.current.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (on) MaterialTheme.colorScheme.primary else LocalInk.current.copy(alpha = 0.08f))
                                .clickable { vm.updateSettings { it.copy(flightRange = i) } }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            item {
                RadarScope(
                    state = state,
                    rangeNm = range.nm,
                    rangeLabel = range.label,
                    selected = selected,
                    trails = trails,
                    onSelect = { selected = it },
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                )
            }

            if (state.problem == "location") item {
                InfoCard(
                    "Horizon uses your approximate location to find planes near you. You can also type a city in Settings → Weather.",
                    "Allow location",
                ) { permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
            } else if (state.problem == "network" && state.updatedAt == 0L) item {
                InfoCard("Couldn't reach the flight network. Check your connection.", "Try again") { vm.startRadar(range.nm) }
            }

            if (chosen != null) {
                item(key = "detail") {
                    FlightDetail(chosen.first, chosen.second, state.routes[chosen.first.callsign], imperial, center) { selected = null }
                }
            }

            if (sorted.isNotEmpty()) item {
                Text(
                    "Nearest first",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(sorted, key = { it.first.hex }) { (a, d) ->
                FlightRow(a, d, state.routes[a.callsign], imperial, a.hex == selected) {
                    selected = if (selected == a.hex) null else a.hex
                }
            }

            item {
                Text(
                    "Live data from the adsb.lol and airplanes.live community networks. Some planes, like many military and private flights, don't appear.",
                    color = LocalInk.current.copy(alpha = 0.4f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** The last few positions of each plane, for the fading trails on the radar. */
@Composable
private fun rememberTrails(state: LauncherViewModel.FlightsState): Map<String, List<Pair<Double, Double>>> {
    val trails = remember { HashMap<String, ArrayDeque<Pair<Double, Double>>>() }
    var version by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.updatedAt) {
        val live = state.planes.associateBy { it.hex }
        trails.keys.retainAll(live.keys)
        live.forEach { (hex, a) ->
            val q = trails.getOrPut(hex) { ArrayDeque() }
            if (q.lastOrNull() != (a.lat to a.lon)) q.addLast(a.lat to a.lon)
            while (q.size > 8) q.removeFirst()
        }
        version++
    }
    return remember(version) { trails.mapValues { it.value.toList() } }
}

/** The round radar: range rings, compass, sweep, and every plane pointing where it's heading. */
@Composable
fun RadarScope(
    state: LauncherViewModel.FlightsState,
    rangeNm: Int,
    rangeLabel: String?,
    selected: String?,
    trails: Map<String, List<Pair<Double, Double>>>,
    onSelect: ((String?) -> Unit)?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val primary = MaterialTheme.colorScheme.primary
    val ink = LocalInk.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val sweep by rememberInfiniteTransition(label = "sweep").animateFloat(
        0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart), label = "sweep",
    )
    // Frame clock so planes glide between 5-second updates.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) withFrameMillis { now = System.currentTimeMillis() } }

    val center = state.center
    val tapRadius = with(density) { 28.dp.toPx() }

    Canvas(
        modifier.then(
            if (onSelect == null || center == null) Modifier
            else Modifier.pointerInputTap(state.updatedAt to rangeNm) { pos, size ->
                val r = size / 2f
                val c = Offset(r, r)
                val hit = state.planes.map { a ->
                    val (la, lo) = FlightRepository.project(a, System.currentTimeMillis())
                    a.hex to toScreen(center, la, lo, rangeNm, c, r * 0.94f)
                }.minByOrNull { (_, p) -> hypot(p.x - pos.x, p.y - pos.y) }
                onSelect(hit?.takeIf { (_, p) -> hypot(p.x - pos.x, p.y - pos.y) < tapRadius }?.first)
            },
        ),
    ) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        val scopeR = r * 0.94f

        // Scope
        drawCircle(Brush.radialGradient(listOf(primary.copy(alpha = 0.14f), ink.copy(alpha = 0.04f)), c, r), r, c)
        drawCircle(primary.copy(alpha = 0.45f), scopeR, c, style = Stroke(width = 1.5.dp.toPx()))
        for (i in 1..3) drawCircle(primary.copy(alpha = 0.18f), scopeR * i / 4f, c, style = Stroke(width = 1.dp.toPx()))
        drawLine(primary.copy(alpha = 0.12f), Offset(c.x - scopeR, c.y), Offset(c.x + scopeR, c.y), 1.dp.toPx())
        drawLine(primary.copy(alpha = 0.12f), Offset(c.x, c.y - scopeR), Offset(c.x, c.y + scopeR), 1.dp.toPx())

        // Sweep
        rotate(sweep - 90f, c) {
            drawCircle(
                Brush.sweepGradient(0f to Color.Transparent, 0.82f to Color.Transparent, 1f to primary.copy(alpha = 0.32f), center = c),
                scopeR, c,
            )
            drawLine(primary.copy(alpha = 0.7f), c, Offset(c.x + scopeR, c.y), 1.5.dp.toPx())
        }

        if (!compact) {
            // Compass letters and the ring distances
            val style = TextStyle(color = ink.copy(alpha = 0.55f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f).forEach { (t, deg) ->
                val l = measurer.measure(t, style)
                val rad = Math.toRadians(deg.toDouble())
                val p = Offset(c.x + (scopeR - 14.dp.toPx()) * sin(rad).toFloat(), c.y - (scopeR - 14.dp.toPx()) * cos(rad).toFloat())
                drawText(l, topLeft = Offset(p.x - l.size.width / 2f, p.y - l.size.height / 2f))
            }
            if (rangeLabel != null) {
                val small = TextStyle(color = primary.copy(alpha = 0.7f), fontSize = 10.sp)
                val value = rangeLabel.substringBefore(' ').toFloatOrNull()
                val unit = rangeLabel.substringAfter(' ')
                if (value != null) for (i in 1..4) {
                    val v = value * i / 4f
                    val txt = (if (v % 1f == 0f) v.toInt().toString() else "%.1f".format(Locale.getDefault(), v)) + if (i == 4) " $unit" else ""
                    val l = measurer.measure(txt, small)
                    val d = scopeR * i / 4f * 0.7071f
                    drawText(l, topLeft = Offset(c.x + d + 3.dp.toPx(), c.y - d - l.size.height))
                }
            }
        }

        // You
        drawCircle(primary.copy(alpha = 0.25f), (if (compact) 5 else 9).dp.toPx(), c)
        drawCircle(primary, (if (compact) 2.5 else 4.0).dp.toPx(), c)

        if (center == null) return@Canvas
        val labelStyle = TextStyle(color = ink.copy(alpha = 0.85f), fontSize = 10.sp, fontWeight = FontWeight.Medium)
        val showLabels = !compact && state.planes.size <= 30
        state.planes.forEach { a ->
            val color = altitudeColor(a)
            val isSel = a.hex == selected
            // Trail
            if (!compact) trails[a.hex]?.let { pts ->
                pts.forEachIndexed { i, (la, lo) ->
                    val p = toScreen(center, la, lo, rangeNm, c, scopeR)
                    if (hypot(p.x - c.x, p.y - c.y) <= scopeR) {
                        drawCircle(color.copy(alpha = 0.12f + 0.4f * i / pts.size), 1.6.dp.toPx(), p)
                    }
                }
            }
            val (la, lo) = FlightRepository.project(a, now)
            val p = toScreen(center, la, lo, rangeNm, c, scopeR)
            if (hypot(p.x - c.x, p.y - c.y) > scopeR) return@forEach
            val sizePx = (if (compact) 9 else if (isSel) 22 else 17).dp.toPx()
            if (isSel) drawCircle(primary, sizePx * 0.95f, p, style = Stroke(width = 2.dp.toPx()))
            translate(p.x, p.y) {
                rotate((a.track ?: 0.0).toFloat(), Offset.Zero) { drawPlane(sizePx, color) }
            }
            if (showLabels || isSel) {
                val l = measurer.measure(a.displayName, labelStyle)
                drawText(l, topLeft = Offset(p.x + sizePx * 0.65f, p.y - l.size.height / 2f))
            }
        }
    }
}

private fun toScreen(center: Pair<Double, Double>, lat: Double, lon: Double, rangeNm: Int, c: Offset, r: Float): Offset {
    val d = FlightRepository.distanceNm(center.first, center.second, lat, lon)
    val b = Math.toRadians(FlightRepository.bearing(center.first, center.second, lat, lon))
    val k = (d / rangeNm).toFloat() * r
    return Offset(c.x + k * sin(b).toFloat(), c.y - k * cos(b).toFloat())
}

/** A small airliner pointing up, centred on the origin. */
private fun DrawScope.drawPlane(s: Float, color: Color) {
    val h = s / 2f
    val path = Path().apply {
        moveTo(0f, -h)                    // nose
        lineTo(h * 0.14f, -h * 0.55f)
        lineTo(h * 0.14f, -h * 0.18f)
        lineTo(h, h * 0.18f)              // right wing
        lineTo(h, h * 0.34f)
        lineTo(h * 0.14f, h * 0.12f)
        lineTo(h * 0.12f, h * 0.68f)
        lineTo(h * 0.42f, h * 0.9f)       // right tail
        lineTo(h * 0.42f, h)
        lineTo(0f, h * 0.88f)
        lineTo(-h * 0.42f, h)
        lineTo(-h * 0.42f, h * 0.9f)      // left tail
        lineTo(-h * 0.12f, h * 0.68f)
        lineTo(-h * 0.14f, h * 0.12f)
        lineTo(-h, h * 0.34f)
        lineTo(-h, h * 0.18f)             // left wing
        lineTo(-h * 0.14f, -h * 0.18f)
        lineTo(-h * 0.14f, -h * 0.55f)
        close()
    }
    drawPath(path, Color.Black.copy(alpha = 0.35f), style = Stroke(width = s * 0.12f))
    drawPath(path, color)
}

private fun Modifier.pointerInputTap(key: Any?, onTap: (Offset, Float) -> Unit): Modifier =
    pointerInput(key) {
        detectTapGestures { pos -> onTap(pos, minOf(size.width, size.height).toFloat()) }
    }

@Composable
private fun InfoCard(text: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(LocalInk.current.copy(alpha = 0.06f)).padding(18.dp),
    ) {
        Text(text, color = LocalInk.current.copy(alpha = 0.75f), fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            action,
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable(onClick = onAction)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun FlightRow(a: Aircraft, distNm: Double, route: FlightRoute?, imperial: Boolean, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else LocalInk.current.copy(alpha = 0.05f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(altitudeColor(a).copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Flight, null, tint = altitudeColor(a),
                modifier = Modifier.size(20.dp).rotate((a.track ?: 0.0).toFloat()),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                FlightRepository.flightNumber(a.callsign) ?: a.displayName,
                color = LocalInk.current, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            )
            val sub = listOfNotNull(
                route?.takeIf { it.plausible }?.let { "${it.fromCode} → ${it.toCode}" },
                FlightRepository.airlineOf(a.callsign) ?: a.operator ?: a.type,
            ).joinToString("  ·  ")
            if (sub.isNotEmpty()) {
                Text(sub, color = LocalInk.current.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(fmtAltitude(a), color = LocalInk.current, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(fmtDistance(distNm, imperial), color = LocalInk.current.copy(alpha = 0.6f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun FlightDetail(
    a: Aircraft,
    distNm: Double,
    route: FlightRoute?,
    imperial: Boolean,
    center: Pair<Double, Double>?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val accent = altitudeColor(a)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(LocalInk.current.copy(alpha = 0.07f))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    FlightRepository.flightNumber(a.callsign) ?: a.displayName,
                    color = LocalInk.current, fontSize = 22.sp, fontWeight = FontWeight.Medium,
                )
                val who = FlightRepository.airlineOf(a.callsign) ?: a.operator
                Text(
                    listOfNotNull(who, a.callsign.takeIf { FlightRepository.flightNumber(it) != null }).joinToString("  ·  ").ifEmpty { "Flight" },
                    color = LocalInk.current.copy(alpha = 0.6f), fontSize = 13.sp,
                )
            }
            Icon(
                Icons.Outlined.Close, "Close", tint = LocalInk.current.copy(alpha = 0.6f),
                modifier = Modifier.clip(CircleShape).clickable(onClick = onClose).padding(6.dp).size(20.dp),
            )
        }

        if (a.emergency) {
            Spacer(Modifier.height(10.dp))
            Text(
                when (a.squawk) { "7500" -> "Squawking 7500 (hijack code)"; "7600" -> "Squawking 7600 (radio failure)"; else -> "Squawking 7700 (emergency)" },
                color = Color(0xFFFF5A5F), fontSize = 14.sp, fontWeight = FontWeight.Medium,
            )
        }

        if (route != null && route.plausible) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(route.fromCode, color = LocalInk.current, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    route.fromCity?.let { Text(it, color = LocalInk.current.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Icon(Icons.Outlined.Flight, null, tint = accent, modifier = Modifier.size(22.dp).rotate(90f))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(route.toCode, color = LocalInk.current, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    route.toCity?.let { Text(it, color = LocalInk.current.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        val vr = a.verticalRate
        val climb = when {
            a.onGround || vr == null -> null
            vr > 300 -> "Climbing %,d ft/min".format(Locale.getDefault(), vr)
            vr < -300 -> "Descending %,d ft/min".format(Locale.getDefault(), -vr)
            else -> "Level"
        }
        val bearing = center?.let { FlightRepository.compass(FlightRepository.bearing(it.first, it.second, a.lat, a.lon)) }
        val facts = listOfNotNull(
            "Altitude" to fmtAltitude(a),
            "Speed" to fmtSpeed(a.speed, imperial),
            a.track?.let { "Heading" to "${it.roundToInt()}° ${FlightRepository.compass(it)}" },
            "From you" to (fmtDistance(distNm, imperial) + (bearing?.let { " $it" } ?: "")),
            climb?.let { "Vertical" to it },
            (a.description ?: a.type)?.let { "Aircraft" to it.split(' ').joinToString(" ") { w -> w.lowercase().replaceFirstChar(Char::titlecase) }.let { t -> if (a.type != null && a.description != null) "$t (${a.type})" else t } },
            a.registration?.let { "Registration" to it },
            a.squawk?.let { "Squawk" to it },
        )
        facts.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                pair.forEach { (k, v) ->
                    Column(Modifier.weight(1f)) {
                        Text(k, color = LocalInk.current.copy(alpha = 0.5f), fontSize = 12.sp)
                        Text(v, color = LocalInk.current, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://globe.adsb.lol/?icao=${a.hex}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Track on the map", color = MaterialTheme.colorScheme.onPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** "Flights overhead" card for the Today page, with a small live radar. */
@Composable
fun FlightsTodayCard(vm: LauncherViewModel, onOpen: () -> Unit) {
    val state by vm.flights.collectAsState()
    val imperial = remember { FlightRepository.imperial() }
    val range = ranges(imperial)[1]
    LaunchedEffect(Unit) { vm.refreshFlights(range.nm) }
    val center = state.center
    val inRange = remember(state.planes, center) {
        if (center == null) emptyList()
        else state.planes.map { it to FlightRepository.distanceNm(center.first, center.second, it.lat, it.lon) }
            .filter { it.second <= range.nm }
            .sortedBy { it.second }
    }
    val closest = inRange.firstOrNull { !it.first.onGround } ?: inRange.firstOrNull()

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(LocalInk.current.copy(alpha = 0.06f))
            .clickable(onClick = onOpen)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Flight, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Flights overhead", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(10.dp))
            when {
                state.problem == "location" -> {
                    Text("Tap to allow location and see planes near you.", color = LocalInk.current.copy(alpha = 0.6f), fontSize = 14.sp)
                }
                state.updatedAt == 0L -> {
                    Text(if (state.problem == "network") "Can't reach the flight network right now." else "Looking for planes…",
                        color = LocalInk.current.copy(alpha = 0.6f), fontSize = 14.sp)
                }
                else -> {
                    Text(
                        if (inRange.isEmpty()) "No planes within ${range.label}"
                        else "${inRange.size} " + (if (inRange.size == 1) "plane" else "planes") + " within ${range.label}",
                        color = LocalInk.current, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                    )
                    closest?.let { (a, d) ->
                        val route = state.routes[a.callsign]?.takeIf { it.plausible }
                        Text(
                            listOfNotNull(
                                "Closest: " + (FlightRepository.flightNumber(a.callsign) ?: a.displayName),
                                route?.let { "${it.fromCode} → ${it.toCode}" },
                                fmtAltitude(a),
                                fmtDistance(d, imperial),
                            ).joinToString("  ·  "),
                            color = LocalInk.current.copy(alpha = 0.6f), fontSize = 13.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        if (state.problem == "location") {
            Icon(Icons.Outlined.MyLocation, null, tint = LocalInk.current.copy(alpha = 0.5f), modifier = Modifier.size(28.dp))
        } else {
            RadarScope(
                state = state, rangeNm = range.nm, rangeLabel = null, selected = null, trails = emptyMap(),
                onSelect = null, modifier = Modifier.size(92.dp), compact = true,
            )
        }
    }
}
