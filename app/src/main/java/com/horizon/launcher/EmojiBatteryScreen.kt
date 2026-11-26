package com.horizon.launcher

import android.content.Intent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddToHomeScreen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val FillColors: List<Pair<String, Color?>> = listOf<Pair<String, Color?>>("level" to null) +
    listOf(0xFF4CD964, 0xFF2196F3, 0xFFFF4081, 0xFF9C27B0, 0xFFFF9800, 0xFFFFFFFF).map { it.toString() to Color(it) }

private val BarColors = listOf(
    0xFF000000, 0xFF202124, 0xFFFFFFFF, 0xFFFFD6E8, 0xFFFF4F8B, 0xFFFFA3C4, 0xFFAD1457, 0xFFD81B60, 0xFFFF6F00,
    0xFFFFC107, 0xFF1B5E20, 0xFF66BB6A, 0xFF004D40, 0xFF00BCD4, 0xFF0D47A1, 0xFF1A237E, 0xFF3A1C71, 0xFF4A148C,
)

/** Pick the emoji, the look, and where your emoji battery shows. */
@Composable
fun EmojiBatteryScreen(vm: LauncherViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val style by EmojiBatteryPrefs.style.collectAsState()
    val real by BatteryHub.state.collectAsState()
    var tryLevel by remember { mutableFloatStateOf(-1f) }
    var tryCharging by remember { mutableStateOf(false) }
    val preview = if (tryLevel < 0 && !tryCharging) real
    else BatteryState(level = if (tryLevel < 0) real.level else tryLevel.roundToInt(), charging = tryCharging)
    fun set(change: (EmojiBatteryStyle) -> EmojiBatteryStyle) = EmojiBatteryPrefs.update(context, change)
    val helperOn = IslandOverlayService.isEnabled(context)
    val primary = MaterialTheme.colorScheme.primary

    Box(Modifier.fillMaxSize().swallowTouches().background(LocalThemeSurface.current.copy(alpha = 0.98f))) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = LocalInk.current,
                        modifier = Modifier.clip(CircleShape).clickable(onClick = onClose).padding(8.dp).size(24.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Emoji battery", color = LocalInk.current, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                }
            }

            // Preview, drawn like a status bar
            item {
                val full = BarMode.of(style.bar) == BarMode.Full
                val bg = if (full) Color(style.background) else Color(0xFF111111)
                val brush = if (full && style.bgMode == "gradient") androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(bg, Color(style.background2)))
                else androidx.compose.ui.graphics.Brush.linearGradient(listOf(bg, bg))
                val autoInk = if (bg.luminance() > 0.5f) Color.Black else Color.White
                val ink = if (full && style.iconColor != 0L) Color(style.iconColor) else autoInk
                val timeInk = if (full && style.timeColor != 0L) Color(style.timeColor) else autoInk
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(LocalInk.current.copy(alpha = 0.06f)).padding(16.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(brush).padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "9:41", color = timeInk, fontSize = (15 * style.timeScale).sp,
                            fontWeight = if (style.timeBold) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.weight(1f),
                        )
                        Box(Modifier.size(12.dp).clip(CircleShape).background(Color.Black.copy(alpha = if (bg.luminance() > 0.5f) 0.9f else 1f)))
                        Spacer(Modifier.weight(1f))
                        if (full) {
                            val sz = (16 * style.iconScale).dp
                            if (style.showWifi) { WifiIcon(4, ink, sz, style.wifiStyle); Spacer(Modifier.width(5.dp)) }
                            if (style.showNetType) { Text("5G", color = ink, fontSize = (11 * style.iconScale).sp, fontWeight = FontWeight.Bold); Spacer(Modifier.width(3.dp)) }
                            if (style.showCell) { CellIcon(3, ink, sz, style.cellStyle); Spacer(Modifier.width(7.dp)) }
                        }
                        EmojiBatteryGlyph(style, preview, ink, height = 18.dp)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Try a level", color = LocalInk.current.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.width(80.dp))
                        Slider(
                            value = if (tryLevel < 0) real.level.toFloat() else tryLevel,
                            onValueChange = { tryLevel = it },
                            valueRange = 0f..100f,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Charging", color = LocalInk.current.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Switch(checked = tryCharging || (tryLevel < 0 && real.charging), onCheckedChange = { tryCharging = it; if (tryLevel < 0) tryLevel = real.level.toFloat() })
                    }
                }
            }

            item { Label("Ready-made looks") }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BarPreset.entries.forEach { p ->
                        val look = p.apply(style)
                        Column(
                            Modifier.width(150.dp).clip(RoundedCornerShape(18.dp)).background(LocalInk.current.copy(alpha = 0.06f))
                                .clickable { set { p.apply(it) } }.padding(10.dp),
                        ) {
                            MiniBar(look, preview)
                            Spacer(Modifier.height(8.dp))
                            Text(p.label, color = LocalInk.current, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            item { Label("At the top of the screen") }
            item {
                Chips(BarMode.entries.map { it.id to it.label }, style.bar) { id -> set { it.copy(bar = id) } }
            }
            if (BarMode.of(style.bar) != BarMode.Off && !helperOn) item {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(primary.copy(alpha = 0.12f))
                        .clickable { context.startSafely(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        .padding(14.dp),
                ) {
                    Text("Turn on Horizon in Accessibility", color = LocalInk.current, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("It's the same helper as the Dynamic Island. Tap to open the setting.", color = LocalInk.current.copy(alpha = 0.7f), fontSize = 13.sp)
                }
            }
            if (BarMode.of(style.bar) == BarMode.Full) {
                item {
                    Text(
                        "Horizon draws its own status bar over your phone's: time, notification icons, Wi-Fi, signal and your emoji battery. It hides when an app goes full screen.",
                        color = LocalInk.current.copy(alpha = 0.6f), fontSize = 13.sp,
                    )
                }
                item { Label("Background") }
                item { Chips(listOf("solid" to "One colour", "gradient" to "Gradient"), style.bgMode) { v -> set { it.copy(bgMode = v) } } }
                item { ColorRow(BarColors, style.background) { c -> set { it.copy(background = c) } } }
                if (style.bgMode == "gradient") item { ColorRow(BarColors, style.background2) { c -> set { it.copy(background2 = c) } } }

                item { Label("Icons") }
                item { SwitchRow("Time", style.showTime) { v -> set { it.copy(showTime = v) } } }
                if (style.showTime) item { SwitchRow("Bold time", style.timeBold) { v -> set { it.copy(timeBold = v) } } }
                item { SwitchRow("Notification icons", style.showNotifs) { v -> set { it.copy(showNotifs = v) } } }
                item { SwitchRow("Wi-Fi", style.showWifi) { v -> set { it.copy(showWifi = v) } } }
                if (style.showWifi) item {
                    ShapeChips(style.wifiStyle, listOf("arcs" to "Fan", "bars" to "Bars", "dots" to "Dots")) { v -> set { it.copy(wifiStyle = v) } }
                }
                item { SwitchRow("Mobile signal", style.showCell) { v -> set { it.copy(showCell = v) } } }
                if (style.showCell) item {
                    ShapeChips(style.cellStyle, listOf("bars" to "Bars", "triangle" to "Triangle", "dots" to "Dots"), cell = true) { v -> set { it.copy(cellStyle = v) } }
                }
                item {
                    val phone = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                    ) { ok -> set { it.copy(showNetType = ok) } }
                    SwitchRow("4G / 5G label", style.showNetType) { v ->
                        val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (v && !granted) phone.launch(android.Manifest.permission.READ_PHONE_STATE) else set { it.copy(showNetType = v) }
                    }
                }
                item { SwitchRow("Airplane mode", style.showAirplane) { v -> set { it.copy(showAirplane = v) } } }
                item { Label("Icon size") }
                item { ScaleChips(style.iconScale) { v -> set { it.copy(iconScale = v) } } }
                item { Label("Time size") }
                item { ScaleChips(style.timeScale) { v -> set { it.copy(timeScale = v) } } }
                item { Label("Icon colour") }
                item { ColorRow(listOf(0L) + IconColors, style.iconColor, auto = true) { c -> set { it.copy(iconColor = c) } } }
                item { Label("Time colour") }
                item { ColorRow(listOf(0L) + IconColors, style.timeColor, auto = true) { c -> set { it.copy(timeColor = c) } } }
            }
            if (BarMode.of(style.bar) != BarMode.Off) {
                item { Label("Camera") }
                item {
                    Chips(listOf("none" to "As is", "ring" to "Ring", "pill" to "Pill", "notch" to "Notch"), style.notch) { v -> set { it.copy(notch = v) } }
                }
                if (style.notch != "none") {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Size", color = LocalInk.current.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.width(60.dp))
                            Slider(value = style.notchSize, onValueChange = { v -> set { it.copy(notchSize = v) } }, valueRange = 0.8f..1.6f, modifier = Modifier.weight(1f))
                        }
                    }
                    item { ColorRow(listOf(0xFF000000) + IconColors, style.notchColor) { c -> set { it.copy(notchColor = c) } } }
                }
            }

            item { Label("Emoji") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BatteryTheme.entries.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { t ->
                                val on = t.id == style.theme
                                Column(
                                    Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                                        .background(if (on) primary.copy(alpha = 0.16f) else LocalInk.current.copy(alpha = 0.05f))
                                        .border(if (on) 2.dp else 0.dp, if (on) primary else Color.Transparent, RoundedCornerShape(18.dp))
                                        .clickable { set { it.copy(theme = t.id) } }
                                        .padding(12.dp),
                                ) {
                                    Text(
                                        if (t == BatteryTheme.Custom) (style.custom.ifBlank { "✏️" })
                                        else listOf(95, 50, 10).joinToString("  ") { t.emojiFor(it, false, "") } + "  " + t.charging,
                                        fontSize = 22.sp,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(t.label, color = LocalInk.current, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            if (style.theme == BatteryTheme.Custom.id) item {
                var text by remember { mutableStateOf(style.custom) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { v ->
                        text = v
                        // Keep it to a single emoji (or two at most).
                        val trimmed = v.trim().let { s -> if (s.codePointCount(0, s.length) > 4) s.substring(0, s.offsetByCodePoints(0, 4)) else s }
                        set { it.copy(custom = trimmed) }
                    },
                    singleLine = true,
                    label = { Text("Type or paste any emoji") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = LocalInk.current, unfocusedTextColor = LocalInk.current),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item { Label("Style") }
            item { Chips(BatteryLayout.entries.map { it.id to it.label }, style.layout) { id -> set { it.copy(layout = id) } } }

            item { Label("Battery colour") }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    FillColors.forEach { (id, c) ->
                        val on = style.fill == id
                        if (c == null) {
                            Text(
                                "By level",
                                color = if (on) primary else LocalInk.current.copy(alpha = 0.8f), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.clip(CircleShape)
                                    .border(1.dp, if (on) primary else LocalInk.current.copy(alpha = 0.25f), CircleShape)
                                    .clickable { set { it.copy(fill = id) } }.padding(horizontal = 14.dp, vertical = 9.dp),
                            )
                        } else {
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).background(c)
                                    .border(if (on) 3.dp else 1.dp, if (on) primary else LocalInk.current.copy(alpha = 0.25f), CircleShape)
                                    .clickable { set { it.copy(fill = id) } },
                            )
                        }
                    }
                }
            }

            item { Label("Size") }
            item {
                val size = when {
                    style.scale < 0.95f -> "0.85"
                    style.scale > 1.1f -> "1.2"
                    else -> "1.0"
                }
                Chips(listOf("0.85" to "Small", "1.0" to "Medium", "1.2" to "Large"), size) { v -> set { it.copy(scale = v.toFloat()) } }
            }

            item { SwitchRow("Show percentage", style.showPercent) { v -> set { it.copy(showPercent = v) } } }
            item { SwitchRow("Bounce while charging", style.animate) { v -> set { it.copy(animate = v) } } }

            if (BarMode.of(style.bar) != BarMode.Off) {
                item { Label("Status bar gestures") }
                item {
                    SwitchRow("Gestures on the status bar", style.barGestures) { v -> set { it.copy(barGestures = v) } }
                }
                if (style.barGestures) {
                    item { Text("Swipe down still opens your notifications unless you change it.", color = LocalInk.current.copy(alpha = 0.6f), fontSize = 13.sp) }
                    item { BarGestureRow(vm, "Tap", style.barTap) { v -> set { it.copy(barTap = v) } } }
                    item { BarGestureRow(vm, "Long-press", style.barLongPress) { v -> set { it.copy(barLongPress = v) } } }
                    item { BarGestureRow(vm, "Swipe down", style.barSwipeDown) { v -> set { it.copy(barSwipeDown = v) } } }
                    item { BarGestureRow(vm, "Swipe left", style.barSwipeLeft) { v -> set { it.copy(barSwipeLeft = v) } } }
                    item { BarGestureRow(vm, "Swipe right", style.barSwipeRight) { v -> set { it.copy(barSwipeRight = v) } } }
                }
            }

            item { Label("Charging animation") }
            item {
                Chips(listOf("off" to "Off", "pulse" to "Glow", "ring" to "Ring", "bubbles" to "Bubbles"), style.chargeAnim) { v -> set { it.copy(chargeAnim = v) } }
            }
            if (style.chargeAnim != "off") item {
                Text("Plays for a few seconds when you plug in (needs Horizon on in Accessibility).", color = LocalInk.current.copy(alpha = 0.6f), fontSize = 13.sp)
            }

            item { Label("Home screen") }
            item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(LocalInk.current.copy(alpha = 0.06f))
                        .clickable { vm.addEmojiBatteryWidget() }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.AddToHomeScreen, null, tint = primary)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Add the widget to your home screen", color = LocalInk.current, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text("Same emoji and colours, any size you like", color = LocalInk.current.copy(alpha = 0.6f), fontSize = 13.sp)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun Chips(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (id, label) ->
            val on = id == selected
            Text(
                label,
                color = if (on) MaterialTheme.colorScheme.onPrimary else LocalInk.current.copy(alpha = 0.85f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(CircleShape)
                    .background(if (on) primary else LocalInk.current.copy(alpha = 0.08f))
                    .clickable { onPick(id) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = LocalInk.current, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}


private val IconColors = listOf(0xFFFFFFFF, 0xFF000000, 0xFFFF4F8B, 0xFFFFD54F, 0xFF39FF14, 0xFF40C4FF, 0xFFB388FF, 0xFFFF6E40)

@Composable
private fun ColorRow(colors: List<Long>, selected: Long, auto: Boolean = false, onPick: (Long) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        colors.forEach { c ->
            val on = selected == c
            if (auto && c == 0L) {
                Text(
                    "Auto",
                    color = if (on) primary else LocalInk.current.copy(alpha = 0.8f), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(CircleShape).border(1.dp, if (on) primary else LocalInk.current.copy(alpha = 0.25f), CircleShape)
                        .clickable { onPick(c) }.padding(horizontal = 14.dp, vertical = 9.dp),
                )
            } else {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(Color(c))
                        .border(if (on) 3.dp else 1.dp, if (on) primary else LocalInk.current.copy(alpha = 0.25f), CircleShape)
                        .clickable { onPick(c) },
                )
            }
        }
    }
}

@Composable
private fun ScaleChips(value: Float, onPick: (Float) -> Unit) {
    val sel = when { value < 0.92f -> "0.85"; value > 1.1f -> "1.2"; else -> "1.0" }
    Chips(listOf("0.85" to "Small", "1.0" to "Medium", "1.2" to "Large"), sel) { onPick(it.toFloat()) }
}

/** Shape choices with a small drawing of each. */
@Composable
private fun ShapeChips(selected: String, options: List<Pair<String, String>>, cell: Boolean = false, onPick: (String) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (id, label) ->
            val on = id == selected
            Row(
                Modifier.clip(CircleShape).background(if (on) primary.copy(alpha = 0.16f) else LocalInk.current.copy(alpha = 0.06f))
                    .clickable { onPick(id) }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (cell) CellIcon(3, LocalInk.current, 16.dp, id) else WifiIcon(3, LocalInk.current, 16.dp, id)
                Spacer(Modifier.width(6.dp))
                Text(label, color = if (on) primary else LocalInk.current, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** A tiny status bar, for the ready-made looks. */
@Composable
private fun MiniBar(look: EmojiBatteryStyle, b: BatteryState) {
    val bg1 = Color(look.background)
    val bg2 = Color(look.background2)
    val full = BarMode.of(look.bar) == BarMode.Full
    val brush = if (!full) androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF222222), Color(0xFF222222)))
    else if (look.bgMode == "gradient") androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(bg1, bg2))
    else androidx.compose.ui.graphics.Brush.linearGradient(listOf(bg1, bg1))
    val autoInk = if (full && bg1.luminance() > 0.5f) Color.Black else Color.White
    val ink = if (look.iconColor == 0L || !full) autoInk else Color(look.iconColor)
    Row(
        Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(8.dp)).background(brush).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (full) "9:41" else "", color = if (look.timeColor == 0L || !full) autoInk else Color(look.timeColor), fontSize = 10.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Box(Modifier.size(6.dp).clip(CircleShape).background(Color.Black))
        Spacer(Modifier.weight(1f))
        EmojiBatteryGlyph(look.copy(animate = false), b, ink, height = 10.dp)
    }
}

@Composable
private fun BarGestureRow(vm: LauncherViewModel, title: String, current: String, onPick: (String) -> Unit) {
    val apps by vm.apps.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { menu = true }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = LocalInk.current, fontSize = 16.sp)
                Text(barActionLabel(current, apps), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
            }
        }
        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.height(420.dp)) {
            BarActions.forEach { (id, label) ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; onPick(id) })
            }
            androidx.compose.material3.DropdownMenuItem(text = { Text("Open an app…") }, onClick = { menu = false; picking = true })
        }
    }
    if (picking) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { picking = false },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { picking = false }) { Text("Cancel") } },
            title = { Text("$title opens…") },
            text = {
                val sorted = remember(apps) { apps.sortedBy { it.label.lowercase() } }
                LazyColumn(Modifier.height(420.dp)) {
                    items(sorted.size) { i ->
                        val app = sorted[i]
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { picking = false; onPick("app:" + app.key) }.padding(vertical = 6.dp),
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
