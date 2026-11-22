package com.horizon.launcher

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Settings for gestures, the drawer and search, Today cards, Discover topics and flight alerts. */
fun LazyListScope.moreSettings(vm: LauncherViewModel, s: LauncherSettings, apps: List<AppEntry>) {
    item { Header("Gestures") }
    HomeGesture.entries.forEach { g ->
        item(key = "gesture-${g.name}") { GestureRow(vm, g, g.current(s), apps) }
    }
    item {
        Note("Lock screen, notifications, quick settings, recent apps and screenshots work best with Horizon turned on in Accessibility.")
    }

    item { Header("App drawer & search") }
    item {
        Toggle("Group apps", "Chips for Social, Media, Games, Productivity and more", s.drawerGroups) { v ->
            vm.updateSettings { it.copy(drawerGroups = v) }
        }
    }
    item {
        Toggle("Web suggestions", "Show what people search for as you type", s.webSuggestions) { v ->
            vm.updateSettings { it.copy(webSuggestions = v) }
        }
    }
    item {
        val context = LocalContext.current
        val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
            vm.updateSettings { it.copy(searchMedia = r.values.any { ok -> ok } || hasMediaPermission(context)) }
        }
        Toggle("Search photos, videos and music", "Finds files on your phone by name, title or artist", s.searchMedia) { v ->
            if (v && !hasMediaPermission(context)) perm.launch(mediaPermissions())
            else vm.updateSettings { it.copy(searchMedia = v) }
        }
    }

    item { Header("Today page cards") }
    item {
        val context = LocalContext.current
        Toggle(
            "Now playing",
            if (IslandHub.hasAccess(context)) "Controls for whatever music or video is playing"
            else "Needs notification access (the same as the Dynamic Island)",
            s.showNowPlaying,
        ) { v ->
            vm.updateSettings { it.copy(showNowPlaying = v) }
            if (v && !IslandHub.hasAccess(context)) {
                context.startSafely(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
    }
    item { SubHeader("Your teams") }
    s.sportsTeams.mapNotNull { FollowedTeam.decode(it) }.forEach { t ->
        item(key = "team-${t.encode()}") {
            RemovableRow(t.name, League.of(t.league)?.label.orEmpty()) {
                vm.updateSettings { it.copy(sportsTeams = it.sportsTeams - t.encode()) }
            }
        }
    }
    item(key = "team-add") { AddTeam(vm, s) }

    item { SubHeader("Stocks watchlist") }
    s.stocks.forEach { sym ->
        item(key = "stock-$sym") {
            RemovableRow(sym, "Tap × to remove") { vm.updateSettings { it.copy(stocks = it.stocks - sym) } }
        }
    }
    item(key = "stock-add") {
        AddField("Add a ticker or company, like AAPL or Tesla", KeyboardCapitalization.Characters) { vm.addStock(it) }
    }

    item { Header("Discover topics") }
    item { Note("Follow anything: a team, a phone, a person, a place. Stories about it show up in Discover with their own chip.") }
    s.newsKeywords.forEach { k ->
        item(key = "kw-$k") { RemovableRow(k, "Followed topic") { vm.updateSettings { it.copy(newsKeywords = it.newsKeywords - k) } } }
    }
    item(key = "kw-add") {
        AddField("Follow a topic", KeyboardCapitalization.Words) { k ->
            vm.updateSettings { if (it.newsKeywords.any { x -> x.equals(k, true) }) it else it.copy(newsKeywords = it.newsKeywords + k) }
        }
    }
    if (s.newsSaved.isNotEmpty()) item {
        Action("Clear saved stories", "${s.newsSaved.size} saved") { vm.updateSettings { it.copy(newsSaved = emptyList()) } }
    }

    item { Header("Flight alerts") }
    item {
        val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            vm.updateSettings { it.copy(overheadAlerts = ok) }
        }
        Toggle(
            "Planes flying over you",
            "A notification when a plane passes low right overhead. Checks once a minute while your screen is on.",
            s.overheadAlerts,
        ) { v ->
            if (v && android.os.Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
            else vm.updateSettings { it.copy(overheadAlerts = v) }
        }
    }
    item {
        val name = s.followFlight.takeIf { it.isNotBlank() }?.let { FlightRepository.flightNumber(it) ?: it }
        Action(
            if (name != null) "Following $name" else "Follow a flight",
            if (name != null) "Open the radar to see it, or stop following" else "Type a flight number in the radar; you'll hear when it lands",
        ) { vm.openSettings(false); vm.showOverlay(LauncherViewModel.Overlay.Radar) }
    }
}

// ---------- Gestures ----------

@Composable
private fun GestureRow(vm: LauncherViewModel, g: HomeGesture, current: String, apps: List<AppEntry>) {
    var menu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { menu = true }.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(g.label, color = LocalInk.current, fontSize = 16.sp)
                Text(gestureLabel(current, apps), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
            }
            Icon(Icons.Outlined.ExpandMore, null, tint = LocalInk.current.copy(alpha = 0.5f))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.heightIn(max = 420.dp)) {
            GestureAction.entries.forEach { a ->
                DropdownMenuItem(
                    text = { Text(a.label, fontWeight = if (a.id == current) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = { menu = false; vm.updateSettings { g.set(it, a.id) } },
                )
            }
            DropdownMenuItem(
                text = { Text("Open an app…", fontWeight = if (current.startsWith("app:")) FontWeight.SemiBold else FontWeight.Normal) },
                onClick = { menu = false; picking = true },
            )
        }
    }
    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
            title = { Text("${g.label} opens…") },
            text = {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(apps.sortedBy { it.label.lowercase() }, key = { it.key }) { app ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable { picking = false; vm.updateSettings { g.set(it, "app:" + app.key) } }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppImage(app, 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(app.label, fontSize = 15.sp)
                        }
                    }
                }
            },
        )
    }
}

// ---------- Teams ----------

@Composable
private fun AddTeam(vm: LauncherViewModel, s: LauncherSettings) {
    var league by remember { mutableStateOf<League?>(null) }
    var filter by remember { mutableStateOf("") }
    if (league == null) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text("Add a team", color = LocalInk.current, fontSize = 16.sp)
            Spacer(Modifier.size(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                League.entries.forEach { l ->
                    Text(
                        l.label,
                        color = LocalInk.current, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clip(CircleShape).background(LocalInk.current.copy(alpha = 0.08f))
                            .clickable { league = l }.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
        return
    }
    val l = league!!
    val teams by produceState<List<TeamInfo>?>(null, l) { value = vm.sportsRepo.teams(l) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(LocalInk.current.copy(alpha = 0.05f)).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${l.label} teams", color = LocalInk.current, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Icon(
                Icons.Outlined.Close, "Close", tint = LocalInk.current.copy(alpha = 0.6f),
                modifier = Modifier.clip(CircleShape).clickable { league = null; filter = "" }.padding(6.dp).size(20.dp),
            )
        }
        OutlinedTextField(
            value = filter, onValueChange = { filter = it }, singleLine = true,
            placeholder = { Text("Find a team") },
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = LocalInk.current, unfocusedTextColor = LocalInk.current),
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
        val list = teams
        when {
            list == null -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(12.dp).size(22.dp))
            list.isEmpty() -> Note("Couldn't load teams. Check your connection.")
            else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(list.filter { filter.isBlank() || it.name.contains(filter, true) || it.abbr.equals(filter, true) }, key = { it.id }) { t ->
                    val code = FollowedTeam(l.path, t.id, t.abbr, t.name).encode()
                    val on = code in s.sportsTeams
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                            vm.updateSettings { if (on) it.copy(sportsTeams = it.sportsTeams - code) else it.copy(sportsTeams = it.sportsTeams + code) }
                        }.padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        t.logo?.let { WebPicture(it, Modifier.size(26.dp)) }
                        Spacer(Modifier.width(12.dp))
                        Text(t.name, color = LocalInk.current, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Switch(checked = on, onCheckedChange = null)
                    }
                    HorizontalDivider(color = LocalInk.current.copy(alpha = 0.06f))
                }
            }
        }
    }
}

// ---------- Small building blocks ----------

@Composable
private fun Header(title: String) {
    Text(
        title,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 8.dp, top = 22.dp, bottom = 6.dp),
    )
}

@Composable
private fun SubHeader(title: String) {
    Text(title, color = LocalInk.current.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 2.dp))
}

@Composable
private fun Note(text: String) {
    Text(text, color = LocalInk.current.copy(alpha = 0.55f), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onChange(!checked) }.padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = LocalInk.current, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, color = LocalInk.current.copy(alpha = 0.6f), fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Action(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp)) {
        Text(title, color = LocalInk.current, fontSize = 16.sp)
        Text(subtitle, color = LocalInk.current.copy(alpha = 0.6f), fontSize = 13.sp)
    }
}

@Composable
private fun RemovableRow(title: String, subtitle: String, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = LocalInk.current, fontSize = 16.sp)
            if (subtitle.isNotBlank()) Text(subtitle, color = LocalInk.current.copy(alpha = 0.55f), fontSize = 12.sp)
        }
        Icon(
            Icons.Outlined.Close, "Remove", tint = LocalInk.current.copy(alpha = 0.6f),
            modifier = Modifier.clip(CircleShape).clickable(onClick = onRemove).padding(8.dp).size(20.dp),
        )
    }
}

@Composable
private fun AddField(label: String, caps: KeyboardCapitalization, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = caps),
        keyboardActions = KeyboardActions(onDone = {
            val t = text.trim()
            if (t.isNotEmpty()) { onAdd(t); text = "" }
        }),
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = LocalInk.current, unfocusedTextColor = LocalInk.current),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
