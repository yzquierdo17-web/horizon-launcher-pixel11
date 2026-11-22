package com.horizon.launcher

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.AlarmClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.SportsScore
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Umbrella
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One thing worth knowing right now, shown under the clock. */
private data class GlanceItem(val icon: ImageVector, val text: String, val onClick: (() -> Unit)?)

/**
 * Pixel-style At a Glance: the most useful one or two things right now — a running timer,
 * a flight you follow, your team's live game, rain coming, charging, tonight's alarm.
 */
@Composable
fun AtAGlance(vm: LauncherViewModel, shadow: androidx.compose.ui.graphics.Shadow?) {
    val context = LocalContext.current
    val timer by IslandHub.timer.collectAsState()
    val followed by vm.followed.collectAsState()
    val games by vm.games.collectAsState()
    val weather by vm.weather.collectAsState()
    var tick by remember { mutableLongStateOf(0L) }
    // Every second while a timer runs, otherwise every 30 seconds.
    LaunchedEffect(timer != null) {
        while (true) {
            delay(if (timer?.time != null) 1000 else 30_000)
            tick++
        }
    }
    val is24h = android.text.format.DateFormat.is24HourFormat(context)
    @Suppress("UNUSED_VARIABLE") val now = tick
    val items = buildList {
        timer?.let { t ->
            val text = t.time?.let { at ->
                val ms = if (t.countDown) at - System.currentTimeMillis() else System.currentTimeMillis() - at
                (if (t.countDown) "Timer · " else "Stopwatch · ") + clock(ms.coerceAtLeast(0)) + if (t.countDown) " left" else ""
            } ?: t.text.ifBlank { "Timer paused" }
            add(GlanceItem(Icons.Outlined.Timer, text) { IslandHub.send(context, t.open) })
        }
        followed?.let { f ->
            val name = FlightRepository.flightNumber(f.callsign) ?: f.callsign
            val a = f.aircraft
            val text = when {
                a == null || f.notFlying -> "$name · not flying now"
                else -> listOfNotNull(
                    name,
                    a.altitude?.let { "%,d ft".format(Locale.getDefault(), it) },
                    f.route?.takeIf { it.plausible }?.let { "to ${it.toCode}" },
                ).joinToString(" · ")
            }
            add(GlanceItem(Icons.Outlined.Flight, text) { vm.showOverlay(LauncherViewModel.Overlay.Radar) })
        }
        games.firstOrNull { it.live }?.let { g ->
            add(GlanceItem(Icons.Outlined.SportsScore, "${g.us.abbr} ${g.us.score ?: 0}–${g.them.score ?: 0} ${g.them.abbr} · ${g.detail}") { vm.openToday() })
        }
        rainSoon(weather, is24h)?.let { add(GlanceItem(Icons.Outlined.Umbrella, it) { vm.openToday() }) }
        battery(context)?.let { (icon, text) ->
            add(GlanceItem(icon, text) { context.startSafely(Intent(Intent.ACTION_POWER_USAGE_SUMMARY)) })
        }
        alarm(context, is24h)?.let { add(GlanceItem(Icons.Outlined.Alarm, it) { context.startSafely(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }) }
    }.take(2)
    if (items.isEmpty()) return
    Column {
        items.forEach { item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .then(if (item.onClick != null) Modifier.clickable(onClick = item.onClick) else Modifier)
                    .padding(vertical = 3.dp),
            ) {
                Icon(item.icon, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(7.dp))
                Text(
                    item.text,
                    color = Color.White.copy(alpha = 0.9f),
                    style = TextStyle(fontFamily = LocalAppFont.current, fontSize = 14.sp, fontWeight = FontWeight.Medium, shadow = shadow),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** "Rain likely around 3 PM (70%)" when the next few hours look wet. */
private fun rainSoon(w: Weather?, is24h: Boolean): String? {
    val hours = w?.hourly?.take(4) ?: return null
    val wetNow = w.code in 51..67 || w.code in 80..82 || w.code in 95..99
    if (wetNow) return null // you can see it already
    val h = hours.firstOrNull { it.rain >= 60 || (it.code in 61..67 || it.code in 80..82 || it.code in 95..99) } ?: return null
    val snow = h.code in 71..77 || h.code in 85..86
    val at = if (is24h) "%02d:00".format(h.hour) else {
        val hr = if (h.hour % 12 == 0) 12 else h.hour % 12
        "$hr ${if (h.hour < 12) "AM" else "PM"}"
    }
    return (if (snow) "Snow" else "Rain") + " likely around $at" + if (h.rain > 0) " (${h.rain}%)" else ""
}

/** Charging with time to full, or a low battery warning. */
private fun battery(context: Context): Pair<ImageVector, String>? = runCatching {
    val b = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
    val status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
    val pct = if (level >= 0 && scale > 0) level * 100 / scale else return null
    when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> {
            val left = context.getSystemService(BatteryManager::class.java)?.computeChargeTimeRemaining() ?: -1
            val mins = if (left > 0) (left / 60_000).toInt() else -1
            val full = when {
                mins <= 0 -> ""
                mins < 60 -> " · full in $mins min"
                else -> " · full in ${mins / 60} h ${mins % 60} min"
            }
            Icons.Outlined.BatteryChargingFull to "Charging · $pct%$full"
        }
        BatteryManager.BATTERY_STATUS_FULL -> Icons.Outlined.BatteryChargingFull to "Fully charged"
        else -> if (pct <= 15) Icons.Outlined.BatteryAlert to "Battery $pct% · plug in soon" else null
    }
}.getOrNull()

/** Your next alarm, if it's within 12 hours. */
private fun alarm(context: Context, is24h: Boolean): String? = runCatching {
    val next = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock ?: return null
    val ms = next.triggerTime - System.currentTimeMillis()
    if (ms !in 0..(12L * 3600_000)) return null
    val t = Instant.ofEpochMilli(next.triggerTime).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a", Locale.getDefault()))
    "Alarm $t"
}.getOrNull()
