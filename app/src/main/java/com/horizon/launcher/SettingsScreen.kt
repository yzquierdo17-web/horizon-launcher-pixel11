package com.horizon.launcher

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(
    vm: LauncherViewModel,
    apps: List<AppEntry>,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val s by vm.settings.collectAsState()
    val work by vm.workProfile.collectAsState()
    val accent = MaterialTheme.colorScheme.primary
    val is24h = android.text.format.DateFormat.is24HourFormat(context)
    var calendarGranted by remember { mutableStateOf(vm.calendar.hasPermission()) }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        calendarGranted = granted
        vm.updateSettings { it.copy(showMeetings = granted, meetingPromptDone = true) }
        vm.refreshMeetings()
    }

    Column(
        Modifier
            .fillMaxSize()
            .swallowTouches()
            .background(Color(0xFA0D1016))
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Horizon settings", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.navigationBarsPadding(),
        ) {
            item { Section("Look") }
            item {
                ToggleRow("Themed icons", "Tint icons to match your wallpaper, like Pixel", s.themedIcons) { v ->
                    vm.updateSettings { it.copy(themedIcons = v) }
                }
            }
            item {
                ChoiceRow(
                    title = "Icon size",
                    options = listOf("Small" to 0.85f, "Medium" to 1f, "Large" to 1.15f),
                    selected = s.iconScale,
                    accent = accent,
                ) { v -> vm.updateSettings { it.copy(iconScale = v) } }
            }
            item {
                ToggleRow("App names on home screen", null, s.homeLabels) { v ->
                    vm.updateSettings { it.copy(homeLabels = v) }
                }
            }
            item {
                ChoiceRow(
                    title = "Clock",
                    options = listOf("Large" to false, "Compact" to true),
                    selected = s.compactClock,
                    accent = accent,
                ) { v -> vm.updateSettings { it.copy(compactClock = v) } }
            }
            item {
                ToggleRow("Search bar", "Show the search bar under the dock", s.showSearch) { v ->
                    vm.updateSettings { it.copy(showSearch = v) }
                }
            }
            item {
                ToggleRow("Wallpaper blur", "Blur the wallpaper behind the app drawer and folders", s.wallpaperBlur) { v ->
                    vm.updateSettings { it.copy(wallpaperBlur = v) }
                }
            }

            item { Section("Work") }
            item {
                ToggleRow(
                    "Work hours",
                    "During these hours the drawer opens on Work and the clock shows when your day ends",
                    s.workHours,
                ) { v -> vm.updateSettings { it.copy(workHours = v) } }
            }
            if (s.workHours) {
                item {
                    ActionRow("Starts", formatMinute(s.workStart, is24h)) {
                        TimePickerDialog(context, { _, h, m -> vm.updateSettings { it.copy(workStart = h * 60 + m) } },
                            s.workStart / 60, s.workStart % 60, is24h).show()
                    }
                }
                item {
                    ActionRow("Ends", formatMinute(s.workEnd, is24h)) {
                        TimePickerDialog(context, { _, h, m -> vm.updateSettings { it.copy(workEnd = h * 60 + m) } },
                            s.workEnd / 60, s.workEnd % 60, is24h).show()
                    }
                }
                item {
                    val names = listOf("M", "T", "W", "T", "F", "S", "S")
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        (1..7).forEach { day ->
                            val on = day in s.workDays
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(40.dp)
                                    .clip(CircleShape)
                                    .background(if (on) accent else Color.White.copy(alpha = 0.08f))
                                    .clickable {
                                        vm.updateSettings { st -> st.copy(workDays = if (on) st.workDays - day else st.workDays + day) }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(names[day - 1], color = if (on) MaterialTheme.colorScheme.onPrimary else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
                if (work != null) item {
                    ToggleRow(
                        "Pause work apps after hours",
                        "Turns work apps off when work hours end and back on when they start",
                        s.pauseWorkAfterHours,
                    ) { v -> vm.updateSettings { it.copy(pauseWorkAfterHours = v) } }
                }
            }
            work?.let { w ->
                item {
                    ToggleRow(
                        "Work apps",
                        if (w.paused) "Paused. You won't get work notifications" else "On",
                        !w.paused,
                    ) { v -> vm.setWorkPaused(!v) }
                }
            }
            item {
                ToggleRow(
                    "Next meeting on home screen",
                    if (calendarGranted) "Shows your current or next meeting under the clock, with a Join button"
                    else "Needs access to your calendar",
                    s.showMeetings && calendarGranted,
                ) { v ->
                    if (v && !calendarGranted) calendarPermission.launch(Manifest.permission.READ_CALENDAR)
                    else vm.updateSettings { it.copy(showMeetings = v, meetingPromptDone = true) }
                }
            }
            item {
                ToggleRow("Suggested apps", "Your most-opened apps at the top of the drawer. Counts stay on this phone", s.suggestions) { v ->
                    vm.updateSettings { it.copy(suggestions = v) }
                    if (!v) vm.clearSuggestions()
                }
            }

            item { Section("Your layout") }
            item { ActionRow("Back up layout", "Save your pages, folders, dock and settings to a file", onBackup) }
            item { ActionRow("Restore layout", "Load a backup file", onRestore) }
            item {
                ActionRow("Default home app", "Make Horizon your home screen") {
                    context.startSafely(Intent(Settings.ACTION_HOME_SETTINGS))
                }
            }

            item { Section("Hidden apps") }
            item {
                Text(
                    "Hidden apps don't show in the app drawer or search.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            items(apps, key = { "hide-" + it.key }) { app ->
                val hidden = app.key in s.hidden
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            vm.updateSettings { st ->
                                st.copy(hidden = if (hidden) st.hidden - app.key else st.hidden + app.key)
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppImage(app, 36.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(app.label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Checkbox(checked = hidden, onCheckedChange = null)
                }
            }

            item {
                Text(
                    "Horizon ${BuildConfigVersion.NAME}",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 24.dp, start = 8.dp),
                )
            }
        }
    }
}

object BuildConfigVersion {
    const val NAME = "0.4"
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 8.dp, top = 22.dp, bottom = 6.dp),
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onChange(!checked) }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Text(title, color = Color.White, fontSize = 16.sp)
        Spacer(Modifier.height(2.dp))
        Text(subtitle, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
    }
}

@Composable
private fun <T> ChoiceRow(
    title: String,
    options: List<Pair<String, T>>,
    selected: T,
    accent: Color,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
        Text(title, color = Color.White, fontSize = 16.sp)
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f))
                .padding(4.dp),
        ) {
            options.forEach { (label, value) ->
                val on = value == selected
                Text(
                    label,
                    color = if (on) Color.Black else Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .background(if (on) accent else Color.Transparent)
                        .clickable { onSelect(value) }
                        .padding(vertical = 9.dp),
                )
            }
        }
    }
}
