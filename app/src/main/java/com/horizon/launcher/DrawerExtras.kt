package com.horizon.launcher

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The drawer's group chips: All, Social, Media, Games… */
@Composable
fun GroupChips(groups: List<AppGroup>, selected: String?, onSelect: (String?) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GroupChip("All", selected == null) { onSelect(null) }
        groups.forEach { g -> GroupChip(g.label, selected == g.id) { onSelect(if (selected == g.id) null else g.id) } }
    }
}

@Composable
private fun GroupChip(label: String, on: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(10.dp)
    Text(
        label,
        color = if (on) primary else LocalInk.current.copy(alpha = 0.8f),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(shape)
            .background(if (on) primary.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, if (on) Color.Transparent else LocalInk.current.copy(alpha = 0.2f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** "Today 3:00 PM", "Tue, Dec 2 · 9:30 AM", "Fri, Nov 14 · All day" for a calendar search result. */
fun eventWhen(m: Meeting, context: Context): String {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(m.begin).atZone(zone)
    val day = when (start.toLocalDate()) {
        LocalDate.now(zone) -> "Today"
        LocalDate.now(zone).plusDays(1) -> "Tomorrow"
        else -> start.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))
    }
    if (m.allDay) return "$day · All day"
    val clock = DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a")
    return "$day · ${start.format(clock)}"
}

/** Rename an app, give it your own picture, or hide its name. */
@Composable
fun AppCustomizeScreen(vm: LauncherViewModel, onClose: () -> Unit) {
    val target by vm.customizing.collectAsState()
    val apps by vm.apps.collectAsState()
    val settings by vm.settings.collectAsState()
    val start = target
    if (start == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onClose() }
        return
    }
    // Follow the app through reloads so the new icon shows right away.
    val app = apps.firstOrNull { it.key == start.key } ?: start
    var name by remember(app.key) { mutableStateOf(settings.appNames[app.key] ?: app.originalLabel) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setAppIcon(app.key, uri)
    }
    fun save() {
        val n = name.trim()
        vm.updateSettings {
            it.copy(appNames = if (n.isEmpty() || n == app.originalLabel) it.appNames - app.key else it.appNames + (app.key to n))
        }
    }
    val changed = app.key in settings.appNames || app.key in settings.appIcons || app.key in settings.noLabel

    Box(
        Modifier
            .fillMaxSize()
            .swallowTouches()
            .background(LocalThemeSurface.current.copy(alpha = 0.98f)),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = LocalInk.current,
                    modifier = Modifier.clip(CircleShape).clickable { save(); onClose() }.padding(8.dp).size(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Customize app", color = LocalInk.current, fontSize = 24.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(28.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                AppImage(app, 88.dp)
                Spacer(Modifier.height(10.dp))
                if (app.showLabel) Text(name.ifBlank { app.originalLabel }, color = LocalInk.current, fontSize = 15.sp)
            }
            Spacer(Modifier.height(28.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = LocalInk.current, unfocusedTextColor = LocalInk.current),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .clickable { vm.updateSettings { it.copy(noLabel = if (app.showLabel) it.noLabel + app.key else it.noLabel - app.key) } }
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Show name under icon", color = LocalInk.current, fontSize = 16.sp)
                    Text("On the home screen, dock and drawer", color = LocalInk.current.copy(alpha = 0.6f), fontSize = 13.sp)
                }
                Switch(
                    checked = app.showLabel,
                    onCheckedChange = { on -> vm.updateSettings { it.copy(noLabel = if (on) it.noLabel - app.key else it.noLabel + app.key) } },
                )
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .clickable { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    .padding(horizontal = 8.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Change icon", color = LocalInk.current, fontSize = 16.sp)
                    Text("Pick any picture; it's cut to your icon shape", color = LocalInk.current.copy(alpha = 0.6f), fontSize = 13.sp)
                }
            }
            if (changed) Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .clickable { vm.resetApp(app.key); name = app.originalLabel }
                    .padding(horizontal = 8.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.RestartAlt, null, tint = LocalInk.current.copy(alpha = 0.7f))
                Spacer(Modifier.width(14.dp))
                Text("Reset to ${app.originalLabel}'s own name and icon", color = LocalInk.current, fontSize = 16.sp)
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Done",
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.End)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { save(); onClose() }
                    .padding(horizontal = 22.dp, vertical = 12.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
