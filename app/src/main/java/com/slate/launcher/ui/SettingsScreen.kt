package com.slate.launcher.ui

import android.app.TimePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.text.style.TextAlign
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.slate.launcher.LauncherUiState
import com.slate.launcher.LauncherViewModel
import com.slate.launcher.util.SystemActions
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    state: LauncherUiState,
    vm: LauncherViewModel,
    calendarGranted: Boolean,
    onRequestCalendar: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val s = state.settings
    var isDefault by remember { mutableStateOf(SystemActions.isDefaultLauncher(context)) }
    val roleRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDefault = SystemActions.isDefaultLauncher(context)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { isDefault = SystemActions.isDefaultLauncher(context) }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = WindowInsets.systemBars.asPaddingValues()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                    Spacer(Modifier.width(4.dp))
                    Text("Home settings", style = MaterialTheme.typography.headlineSmall)
                }
            }

            if (!isDefault) item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Icon(Icons.Outlined.Home, null)
                        Spacer(Modifier.size(8.dp))
                        Text("Make Slate your Home app", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Required for the Home button, app shortcuts and smooth app-open animations.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.size(12.dp))
                        Button(onClick = { roleRequest.launch(SystemActions.defaultLauncherIntent(context)) }) {
                            Text("Set as default")
                        }
                    }
                }
            }

            item { Section("Appearance") }
            item {
                LinkRow(Icons.Outlined.Wallpaper, "Wallpaper & style", "Wallpaper, colors and icon shape") {
                    SystemActions.openWallpaperPicker(context)
                }
            }
            item {
                SwitchRow("Themed icons", "Tint icons to match your wallpaper (apps that support it)", s.themedIcons) { vm.setThemedIcons(it) }
            }
            item { SwitchRow("Labels on Home", "Show app names under Home screen icons", s.homeLabels) { vm.setHomeLabels(it) } }

            item { Section("Work") }
            item {
                val w = s.work
                SwitchRow(
                    "Work hours",
                    "Show your work Home row and open the drawer on Work during these hours",
                    w.enabled,
                ) { vm.setWorkSchedule(w.copy(enabled = it)) }
            }
            if (s.work.enabled) {
                item {
                    val w = s.work
                    Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TimeButton("Starts", w.startMinute, Modifier.weight(1f)) { vm.setWorkSchedule(w.copy(startMinute = it)) }
                        TimeButton("Ends", w.endMinute, Modifier.weight(1f)) { vm.setWorkSchedule(w.copy(endMinute = it)) }
                    }
                }
                item {
                    val w = s.work
                    val names = listOf("M", "T", "W", "T", "F", "S", "S")
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    ) {
                        (1..7).forEach { day ->
                            val on = day in w.days
                            FilterChip(
                                selected = on,
                                onClick = { vm.setWorkSchedule(w.copy(days = if (on) w.days - day else w.days + day)) },
                                label = { Text(names[day - 1], modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                if (state.hasWorkProfile) item {
                    val w = s.work
                    SwitchRow(
                        "Pause work apps after hours",
                        "Turns work apps off when work hours end and back on when they start",
                        w.pauseAfterHours,
                    ) { vm.setWorkSchedule(w.copy(pauseAfterHours = it)) }
                }
            }
            if (state.hasWorkProfile) item {
                SwitchRow(
                    "Work apps",
                    if (state.workPaused) "Paused. You won't get work notifications" else "On",
                    !state.workPaused,
                ) { vm.setWorkPaused(!it) }
            }
            item {
                SwitchRow(
                    "Next meeting on Home",
                    if (calendarGranted) "Shows your current or next meeting under the clock, with a Join button"
                    else "Needs access to your calendar",
                    s.showMeetings && calendarGranted,
                ) { on -> if (on && !calendarGranted) onRequestCalendar() else vm.setShowMeetings(on) }
            }

            item { Section("Layout") }
            item {
                SliderRow("Grid columns", s.columns.toFloat(), 4f..6f, steps = 1, format = { "${it.roundToInt()}" }) {
                    vm.setColumns(it.roundToInt())
                }
            }
            item {
                SliderRow("Icon size", s.iconScale, 0.8f..1.25f, steps = 8, format = { "${(it * 100).roundToInt()}%" }) {
                    vm.setIconScale(it)
                }
            }

            item { Section("Hidden apps") }
            if (state.hiddenApps.isEmpty()) item {
                Text(
                    "Nothing hidden. Long-press an app in the drawer and choose Hide app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            items(state.hiddenApps, key = { it.key }) { app ->
                ListItem(
                    headlineContent = { Text(app.label) },
                    leadingContent = { AppIconImage(app, 40.dp, s.themedIcons, null) },
                    trailingContent = { TextButton(onClick = { vm.unhide(app) }) { Text("Unhide") } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }

            item { Section("About") }
            item {
                Text(
                    "Slate Launcher 0.3.0",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun LinkRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onChange(!checked) }.padding(horizontal = 8.dp),
    )
}

@Composable
private fun TimeButton(label: String, minuteOfDay: Int, modifier: Modifier, onPick: (Int) -> Unit) {
    val context = LocalContext.current
    val is24h = android.text.format.DateFormat.is24HourFormat(context)
    OutlinedButton(
        onClick = {
            TimePickerDialog(context, { _, h, m -> onPick(h * 60 + m) }, minuteOfDay / 60, minuteOfDay % 60, is24h).show()
        },
        modifier = modifier,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatMinute(minuteOfDay, is24h), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Local value while dragging; only writes to storage when the finger lifts. */
@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(format(local), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local) },
            valueRange = range,
            steps = steps,
        )
    }
}
