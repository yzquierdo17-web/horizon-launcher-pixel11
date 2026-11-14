package com.slate.launcher.ui

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material.icons.outlined.WorkOff
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import com.slate.launcher.data.Meeting
import com.slate.launcher.data.WidgetRepository
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.slate.launcher.LauncherUiState
import com.slate.launcher.data.AppEntry
import com.slate.launcher.ui.theme.Dimens
import com.slate.launcher.util.SystemActions
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(
    state: LauncherUiState,
    darkText: Boolean,
    actions: AppActions,
    onSearchClick: () -> Unit,
    onOpenSettings: () -> Unit,
    meeting: Meeting?,
    calendarGranted: Boolean,
    onOpenMeeting: (Meeting) -> Unit,
    onJoinMeeting: (Meeting) -> Unit,
    onRequestCalendar: () -> Unit,
    onDismissMeetingPrompt: () -> Unit,
    onSetWorkPaused: (Boolean) -> Unit,
    widgetRepo: WidgetRepository,
    widgetActions: WidgetActions,
    onAddWidget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = state.settings
    val iconSize = Dimens.BaseIcon * s.iconScale
    val homeKeys = remember(state.homeApps) { state.homeApps.mapTo(HashSet()) { it.key } }
    val dockKeys = remember(state.dockApps) { state.dockApps.mapTo(HashSet()) { it.key } }

    Column(
        modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = Dimens.ScreenPadding),
    ) {
        AtAGlance(darkText, Modifier.padding(start = 8.dp, top = 28.dp)) { now, color, shadow ->
            // Under the clock: next meeting, then work status.
            val chips = HomeChipStyle(darkText)
            when {
                meeting != null && s.showMeetings -> MeetingLine(meeting, now, color, shadow, chips, onOpenMeeting, onJoinMeeting)
                s.showMeetings && !calendarGranted && !s.meetingPromptDone ->
                    PromptChip("Show your next meeting", Icons.Outlined.Event, chips, onRequestCalendar, onDismissMeetingPrompt)
            }
            if (state.hasWorkProfile && state.workPaused) {
                StatusChip("Work apps paused \u00B7 Turn on", Icons.Outlined.WorkOff, chips) { onSetWorkPaused(false) }
            } else if (state.workTime) {
                StatusChip("Work hours \u00B7 until ${formatMinute(s.work.endMinute)}", Icons.Outlined.Work, chips, null)
            }
        }

        HomeEmptyArea(
            onOpenSettings = onOpenSettings,
            onAddWidget = onAddWidget,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            if (state.widgets.isNotEmpty()) {
                WidgetStack(
                    widgets = state.widgets,
                    repo = widgetRepo,
                    actions = widgetActions,
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
        }

        IconRows(state.homeApps, s.columns) { app ->
            AppIcon(
                app = app, place = IconPlace.Home, iconSize = iconSize, themed = s.themedIcons,
                showLabel = s.homeLabels, darkText = darkText,
                isOnHome = true, isInDock = app.key in dockKeys, actions = actions,
            )
        }

        Spacer(Modifier.height(20.dp))

        Row(Modifier.fillMaxWidth()) {
            state.dockApps.forEach { app ->
                key(app.key) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        AppIcon(
                            app = app, place = IconPlace.Dock, iconSize = iconSize, themed = s.themedIcons,
                            showLabel = false, darkText = darkText,
                            isOnHome = app.key in homeKeys, isInDock = true, actions = actions,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        SearchPill(onSearchClick)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun IconRows(apps: List<AppEntry>, columns: Int, cell: @Composable (AppEntry) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        apps.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { app -> key(app.key) { Box(Modifier.weight(1f)) { cell(app) } } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Long-press empty space → Wallpaper & style / Home settings, anchored at the finger. */
@Composable
private fun HomeEmptyArea(
    onOpenSettings: () -> Unit,
    onAddWidget: () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    var open by remember { mutableStateOf(false) }
    var at by remember { mutableStateOf(DpOffset.Zero) }

    Box(
        modifier.pointerInputLongPress { p ->
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            at = with(density) { DpOffset(p.x.toDp(), p.y.toDp()) }
            open = true
        },
    ) {
        content()
        Box(Modifier.offset(at.x, at.y)) {
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                shape = RoundedCornerShape(20.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                DropdownMenuItem(
                    text = { Text("Wallpaper & style") },
                    leadingIcon = { Icon(Icons.Outlined.Wallpaper, null) },
                    onClick = { open = false; SystemActions.openWallpaperPicker(context) },
                )
                DropdownMenuItem(
                    text = { Text("Widgets") },
                    leadingIcon = { Icon(Icons.Outlined.Widgets, null) },
                    onClick = { open = false; onAddWidget() },
                )
                DropdownMenuItem(
                    text = { Text("Home settings") },
                    leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                    onClick = { open = false; onOpenSettings() },
                )
            }
        }
    }
}

private fun Modifier.pointerInputLongPress(onLongPress: (Offset) -> Unit) =
    pointerInput(Unit) { detectTapGestures(onLongPress = onLongPress) }

/** Pixel-style "At a Glance": date over a large clock. Tap for Calendar / Clock. */
@Composable
private fun AtAGlance(
    darkText: Boolean,
    modifier: Modifier = Modifier,
    below: @Composable (now: LocalDateTime, color: Color, shadow: Shadow?) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    val now = rememberCurrentMinute()
    val is24h = DateFormat.is24HourFormat(context)
    val timeFormat = remember(is24h) { DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm") }
    val dateFormat = remember { DateTimeFormatter.ofPattern("EEE, MMM d") }
    val amPm = remember { DateTimeFormatter.ofPattern(" a") }
    val nextAlarm = rememberNextAlarm(now)
    val color = if (darkText) Color(0xFF1F1F1F) else Color.White
    val shadow = if (darkText) null else Shadow(Color.Black.copy(alpha = 0.3f), Offset(0f, 2f), 10f)
    val noRipple = remember { MutableInteractionSource() }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val lineStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium, shadow = shadow)
            Text(
                text = now.format(dateFormat),
                color = color,
                style = lineStyle,
                modifier = Modifier.clickable(noRipple, null) { SystemActions.openCalendar(context) },
            )
            nextAlarm?.let { alarm ->
                Text("  \u00B7  ", color = color, style = lineStyle)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(noRipple, null) { SystemActions.openAlarms(context) },
                ) {
                    Icon(Icons.Outlined.Alarm, "Next alarm", tint = color, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(alarm.format(timeFormat) + if (is24h) "" else alarm.format(amPm), color = color, style = lineStyle)
                }
            }
        }
        Text(
            text = now.format(timeFormat),
            color = color,
            style = TextStyle(
                fontSize = 92.sp,
                lineHeight = 96.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = (-2).sp,
                shadow = shadow,
            ),
            modifier = Modifier.clickable(noRipple, null) { SystemActions.openAlarms(context) },
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            below(now, color, shadow)
        }
    }
}

/** Translucent pills that read well on any wallpaper. */
private class HomeChipStyle(darkText: Boolean) {
    val background = if (darkText) Color.White.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.28f)
    val content = if (darkText) Color(0xFF1F1F1F) else Color.White
}

@Composable
private fun MeetingLine(
    meeting: Meeting,
    now: LocalDateTime,
    color: Color,
    shadow: Shadow?,
    chips: HomeChipStyle,
    onOpen: (Meeting) -> Unit,
    onJoin: (Meeting) -> Unit,
) {
    val context = LocalContext.current
    val nowMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val is24h = DateFormat.is24HourFormat(context)
    val clock = remember(is24h) { DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a") }
    fun fmt(ms: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).format(clock)
    val minutes = ((meeting.begin - nowMs) / 60_000L).toInt()
    val whenText = when {
        meeting.begin <= nowMs -> "now \u00B7 until ${fmt(meeting.end)}"
        minutes < 1 -> "starting now"
        minutes < 60 -> "in $minutes min"
        else -> "at ${fmt(meeting.begin)}"
    }
    val noRipple = remember { MutableInteractionSource() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false).clickable(noRipple, null) { onOpen(meeting) },
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(meeting.color?.let { Color(it) } ?: MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${meeting.title} \u00B7 $whenText",
                color = color,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium, shadow = shadow),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Join appears from 15 min before until the meeting ends.
        if (meeting.joinUrl != null && minutes <= 15) {
            Spacer(Modifier.width(10.dp))
            Surface(
                onClick = { onJoin(meeting) },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Icon(Icons.Outlined.Videocam, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Join", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, icon: ImageVector, chips: HomeChipStyle, onClick: (() -> Unit)?) {
    val shape = CircleShape
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(shape)
            .background(chips.background)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(icon, null, tint = chips.content, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = chips.content, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun PromptChip(text: String, icon: ImageVector, chips: HomeChipStyle, onClick: () -> Unit, onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(chips.background)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 4.dp),
    ) {
        Icon(icon, null, tint = chips.content, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = chips.content, style = MaterialTheme.typography.labelLarge)
        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Outlined.Close, "Not now", tint = chips.content, modifier = Modifier.size(16.dp))
        }
    }
}

fun formatMinute(minuteOfDay: Int, is24h: Boolean = false): String {
    val h = minuteOfDay / 60
    val m = minuteOfDay % 60
    return if (is24h) "%02d:%02d".format(h, m)
    else "%d:%02d %s".format(if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM")
}

/** Ticks with the system clock (TIME_TICK) and refreshes on resume after the screen was off. */
@Composable
private fun rememberCurrentMinute(): LocalDateTime {
    val context = LocalContext.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { now = LocalDateTime.now() }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { now = LocalDateTime.now() }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return now
}

/** Next alarm within 24h, like the Pixel's At a Glance. Re-read every minute tick. */
@Composable
private fun rememberNextAlarm(now: LocalDateTime): LocalDateTime? {
    val context = LocalContext.current
    return remember(now) {
        val info = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock ?: return@remember null
        val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(info.triggerTime), ZoneId.systemDefault())
        at.takeIf { it.isBefore(now.plusHours(24)) }
    }
}

@Composable
private fun SearchPill(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp)) {
            Icon(Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Text("Search apps and the web", style = MaterialTheme.typography.bodyLarge)
        }
    }
}
