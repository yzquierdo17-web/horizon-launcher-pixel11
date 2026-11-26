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
    0xFF000000, 0xFF202124, 0xFFFFFFFF, 0xFF1A237E, 0xFFAD1457, 0xFF4A148C, 0xFF004D40, 0xFFFFD6E8,
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
                val bg = if (BarMode.of(style.bar) == BarMode.Full) Color(style.background) else Color(0xFF111111)
                val ink = if (bg.luminance() > 0.5f) Color.Black else Color.White
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(LocalInk.current.copy(alpha = 0.06f)).padding(16.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg).padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("9:41", color = ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Box(Modifier.size(12.dp).clip(CircleShape).background(Color.Black.copy(alpha = if (bg.luminance() > 0.5f) 0.9f else 1f)))
                        Spacer(Modifier.weight(1f))
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
                        "Horizon draws its own status bar over your phone's: time, notification icons, Wi-Fi, signal and your emoji battery. It hides when an app goes full screen. Pull down as usual for notifications.",
                        color = LocalInk.current.copy(alpha = 0.6f), fontSize = 13.sp,
                    )
                }
                item { Label("Status bar colour") }
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BarColors.forEach { c ->
                            val on = style.background == c
                            Box(
                                Modifier.size(38.dp).clip(CircleShape).background(Color(c))
                                    .border(if (on) 3.dp else 1.dp, if (on) primary else LocalInk.current.copy(alpha = 0.25f), CircleShape)
                                    .clickable { set { it.copy(background = c) } },
                            )
                        }
                    }
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
