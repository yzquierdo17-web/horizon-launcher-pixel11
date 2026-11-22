package com.horizon.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dehaze
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Thunderstorm
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.WbCloudy
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Where the Today page is: 0 = hidden, 1 = fully open. Follows your finger, then springs. */
@Stable
class TodayController(private val scope: CoroutineScope) {
    var progress by mutableFloatStateOf(0f)
        private set
    var width = 1f
    private var job: Job? = null

    val visible get() = progress > 0f

    fun dragBy(dx: Float) {
        job?.cancel()
        progress = (progress + dx / width).coerceIn(0f, 1f)
    }

    fun settle(velocityX: Float) {
        val target = when {
            velocityX > 900f -> 1f
            velocityX < -900f -> 0f
            progress > 0.4f -> 1f
            else -> 0f
        }
        animateTo(target)
    }

    fun open() = animateTo(1f)
    fun close() = animateTo(0f)

    private fun animateTo(t: Float) {
        job?.cancel()
        job = scope.launch {
            animate(progress, t, animationSpec = spring(dampingRatio = 0.9f, stiffness = 420f)) { v, _ -> progress = v }
        }
    }

    /**
     * Put on the Home pager: a rightward swipe that the first page can't use opens Today,
     * and while Today is partly open, swipes move it instead of the pages.
     */
    fun pagerConnection(isFirstPage: () -> Boolean) = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || progress <= 0f) return Offset.Zero
            dragBy(available.x)
            return Offset(available.x, 0f)
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.x <= 0f || !isFirstPage()) return Offset.Zero
            dragBy(available.x)
            return Offset(available.x, 0f)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (progress <= 0f) return Velocity.Zero
            settle(available.x)
            return available
        }
    }
}

/**
 * Swipe right on your first Home page: your day at a glance. Today's agenda, the next hours
 * of weather, battery, and which apps have unread notifications. Swipe left (or Back) to return.
 */
@Composable
fun TodayPage(
    vm: LauncherViewModel,
    today: TodayController,
    apps: List<AppEntry>,
    onLaunch: (AppEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val weather by vm.weather.collectAsState()
    val badges by IslandHub.badges.collectAsState()
    val is24h = android.text.format.DateFormat.is24HourFormat(context)
    val now = remember { LocalDateTime.now() }
    val events by produceState(emptyList<Meeting>()) {
        value = withContext(Dispatchers.IO) { vm.calendar.eventsToday() }
    }
    val battery = rememberBattery()
    val settings by vm.settings.collectAsState()
    val news by vm.news.collectAsState()
    val newsLoading by vm.newsLoading.collectAsState()
    val newsUi = remember { NewsUiState() }
    LaunchedEffect(Unit) { vm.refreshNews() }
    val unread = remember(badges, apps) {
        val byBadge = apps.associateBy { IslandHub.badgeKey(it.packageName, it.user) }
        badges.entries.mapNotNull { (k, n) -> byBadge[k]?.let { it to n } }
            .filter { it.first.key !in vm.settings.value.hidden }
            .sortedByDescending { it.second }
            .take(6)
    }
    val greeting = when (now.hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Good night"
    }

    Box(
        modifier
            .fillMaxSize()
            .swallowTouches()
            .background(LocalThemeSurface.current.copy(alpha = 0.96f))
            .pointerInput(today) {
                // Swipe left anywhere to close.
                val tracker = VelocityTracker()
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { tracker.resetTracking(); total = 0f },
                    onDragEnd = { today.settle(tracker.calculateVelocity().x) },
                    onDragCancel = { today.settle(0f) },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        total += dx
                        tracker.addPosition(change.uptimeMillis, Offset(total, 0f))
                        today.dragBy(dx)
                    },
                )
            },
    ) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        ) {
            item {
                Column(Modifier.padding(top = 28.dp, bottom = 6.dp)) {
                    Text(greeting, color = LocalInk.current, fontSize = 32.sp, fontWeight = FontWeight.Medium)
                    Text(
                        now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault())),
                        color = LocalInk.current.copy(alpha = 0.6f),
                        fontSize = 16.sp,
                    )
                }
            }

            // Weather now and the next few hours
            weather?.let { w ->
                item {
                    TodayCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(weatherIcon(w.code, w.isDay), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("${w.temp}° ${w.label}", color = LocalInk.current, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                                val hiLo = if (w.high != null && w.low != null) "High ${w.high}° · Low ${w.low}°" else null
                                Text(listOfNotNull(hiLo, w.place).joinToString("  ·  "), color = LocalInk.current.copy(alpha = 0.6f), fontSize = 13.sp)
                            }
                        }
                        if (w.hourly.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                w.hourly.forEach { h ->
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(hourLabel(h.hour, is24h), color = LocalInk.current.copy(alpha = 0.6f), fontSize = 12.sp)
                                        Spacer(Modifier.height(6.dp))
                                        Icon(weatherIcon(h.code, h.isDay), null, tint = LocalInk.current.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.height(6.dp))
                                        Text("${h.temp}°", color = LocalInk.current, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Agenda
            item {
                TodayCard(title = "Today", icon = Icons.Outlined.Event) {
                    if (!vm.calendar.hasPermission()) {
                        Text("Turn on “Next meeting on Home” in Settings to see your day here.", color = LocalInk.current.copy(alpha = 0.6f), fontSize = 14.sp)
                    } else if (events.isEmpty()) {
                        Text("Nothing else on your calendar today.", color = LocalInk.current.copy(alpha = 0.6f), fontSize = 14.sp)
                    } else {
                        events.forEachIndexed { i, e ->
                            if (i > 0) Spacer(Modifier.height(10.dp))
                            AgendaRow(e, is24h, onOpen = { vm.calendar.openEvent(e) }, onJoin = { vm.calendar.join(e) })
                        }
                    }
                }
            }

            // Battery
            if (battery.level >= 0) item {
                TodayCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (battery.charging) Icons.Outlined.BatteryChargingFull else Icons.Outlined.BatteryStd,
                            null,
                            tint = if (battery.level <= 15 && !battery.charging) Color(0xFFFF6B6B) else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(30.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Phone ${battery.level}%", color = LocalInk.current, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            Text(
                                when {
                                    battery.charging && battery.fullInMinutes != null -> "Charging · full in ${formatDuration(battery.fullInMinutes)}"
                                    battery.charging -> "Charging"
                                    else -> "On battery"
                                },
                                color = LocalInk.current.copy(alpha = 0.6f),
                                fontSize = 13.sp,
                            )
                        }
                        // Simple level bar
                        Box(
                            Modifier
                                .width(64.dp)
                                .height(8.dp)
                                .clip(CircleShape)
                                .background(LocalInk.current.copy(alpha = 0.12f)),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(battery.level / 100f)
                                    .height(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                }
            }

            // Unread
            item {
                TodayCard(title = "Unread", icon = Icons.Outlined.Notifications) {
                    if (!IslandHub.hasAccess(context)) {
                        Text("Turn on notification dots in Settings to see unread apps here.", color = LocalInk.current.copy(alpha = 0.6f), fontSize = 14.sp)
                    } else if (unread.isEmpty()) {
                        Text("You're all caught up.", color = LocalInk.current.copy(alpha = 0.6f), fontSize = 14.sp)
                    } else {
                        unread.forEachIndexed { i, (app, n) ->
                            if (i > 0) Spacer(Modifier.height(6.dp))
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { onLaunch(app) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppImage(app, 32.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(app.label, color = LocalInk.current, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (n == 1) "1 new" else "$n new",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }

            if (settings.showFlights) item(key = "flights") {
                FlightsTodayCard(vm) { vm.showOverlay(LauncherViewModel.Overlay.Radar) }
            }

            newsSection(vm, settings, news, newsLoading, newsUi)

            item {
                Text(
                    "Swipe left to go back",
                    color = LocalInk.current.copy(alpha = 0.35f),
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun TodayCard(title: String? = null, icon: ImageVector? = null, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(LocalInk.current.copy(alpha = 0.06f))
            .padding(18.dp),
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                if (icon != null) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(title, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
        content()
    }
}

@Composable
private fun AgendaRow(e: Meeting, is24h: Boolean, onOpen: () -> Unit, onJoin: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val fmt = DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a")
    val now = System.currentTimeMillis()
    val live = !e.allDay && e.begin <= now
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(4.dp)
                .height(38.dp)
                .clip(CircleShape)
                .background(e.color?.let { Color(it) } ?: MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, color = LocalInk.current, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    e.allDay -> "All day"
                    live -> "Now · until ${Instant.ofEpochMilli(e.end).atZone(zone).format(fmt)}"
                    else -> Instant.ofEpochMilli(e.begin).atZone(zone).format(fmt) + " – " + Instant.ofEpochMilli(e.end).atZone(zone).format(fmt)
                },
                color = if (live) MaterialTheme.colorScheme.primary else LocalInk.current.copy(alpha = 0.6f),
                fontSize = 13.sp,
            )
        }
        val soon = !e.allDay && e.begin - now < 15 * 60_000L
        if (e.joinUrl != null && soon) {
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable(onClick = onJoin)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Videocam, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Join", color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

private data class BatteryInfo(val level: Int, val charging: Boolean, val fullInMinutes: Int?)

@Composable
private fun rememberBattery(): BatteryInfo {
    val context = LocalContext.current
    var info by remember { mutableStateOf(BatteryInfo(-1, false, null)) }
    DisposableEffect(context) {
        val bm = context.getSystemService(BatteryManager::class.java)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                val remaining = runCatching { bm?.computeChargeTimeRemaining() ?: -1L }.getOrDefault(-1L)
                info = BatteryInfo(
                    level = if (level >= 0 && scale > 0) level * 100 / scale else -1,
                    charging = charging,
                    fullInMinutes = if (charging && remaining > 0) (remaining / 60_000L).toInt() else null,
                )
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return info
}

private fun formatDuration(minutes: Int): String =
    if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"

private fun hourLabel(hour: Int, is24h: Boolean): String =
    if (is24h) "%02d".format(hour) else "${if (hour % 12 == 0) 12 else hour % 12}${if (hour < 12) "a" else "p"}"

/** WMO weather code → an icon. */
fun weatherIcon(code: Int, day: Boolean): ImageVector = when (code) {
    0, 1 -> if (day) Icons.Outlined.WbSunny else Icons.Outlined.NightsStay
    2 -> Icons.Outlined.WbCloudy
    3 -> Icons.Outlined.Cloud
    45, 48 -> Icons.Outlined.Dehaze
    in 51..67, in 80..82 -> Icons.Outlined.WaterDrop
    in 71..77, 85, 86 -> Icons.Outlined.AcUnit
    in 95..99 -> Icons.Outlined.Thunderstorm
    else -> Icons.Outlined.Cloud
}
