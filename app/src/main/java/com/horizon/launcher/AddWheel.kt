package com.horizon.launcher

import android.content.Intent
import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.twotone.Apps
import androidx.compose.material.icons.twotone.Palette
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.material.icons.twotone.Wallpaper
import androidx.compose.material.icons.twotone.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private data class WheelOption(val label: String, val title: String, val icon: ImageVector)

private val WheelOptions = listOf(
    WheelOption("Widget", "Add a widget", Icons.TwoTone.Widgets),
    WheelOption("App", "Add an app", Icons.TwoTone.Apps),
    WheelOption("Icons", "Icon style", Icons.TwoTone.Palette),
    WheelOption("Wallpaper", "Change wallpaper", Icons.TwoTone.Wallpaper),
    WheelOption("Settings", "Horizon settings", Icons.TwoTone.Settings),
)

/**
 * Long-press Home → a dial you spin with your finger. It coasts with momentum, snaps to an item,
 * ticks as each one passes the top, and the centre button adds the selected thing.
 */
@Composable
fun AddWheel(vm: LauncherViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val n = WheelOptions.size
    val step = 360f / n
    var rotation by remember { mutableFloatStateOf(0f) }
    var spin: Job? by remember { mutableStateOf(null) }
    val selected by remember { derivedStateOf { ((-(rotation / step)).roundToInt() % n + n) % n } }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(selected) { if (first) first = false else view.tick() }

    // Pops open with a little spring.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val appear by animateFloatAsState(if (shown) 1f else 0f, spring(stiffness = 500f), label = "scrim")
    val scale by animateFloatAsState(if (shown) 1f else 0.6f, spring(dampingRatio = 0.65f, stiffness = 420f), label = "scale")

    fun snapTo(target: Float) {
        spin?.cancel()
        spin = scope.launch {
            animate(rotation, target, animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f)) { v, _ -> rotation = v }
        }
    }

    fun pick(i: Int) {
        onDismiss()
        when (i) {
            0 -> vm.showWidgetPicker(true)
            1 -> vm.showOverlay(LauncherViewModel.Overlay.AddApp)
            2 -> vm.showOverlay(LauncherViewModel.Overlay.IconStyle)
            3 -> context.startSafely(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper"))
            4 -> vm.openSettings(true)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f * appear))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(WheelOptions[selected].title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text("Spin the wheel, then tap the middle", color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
            Spacer(Modifier.height(20.dp))
            // Pointer showing which item is selected
            Box(
                Modifier
                    .size(width = 14.dp, height = 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.height(8.dp))

            BoxWithConstraints(
                Modifier
                    .size(300.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale; alpha = appear }
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.10f))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                    .pointerInput(Unit) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        fun angleOf(p: Offset) = Math.toDegrees(atan2((p.y - center.y).toDouble(), (p.x - center.x).toDouble())).toFloat()
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            spin?.cancel()
                            val tracker = VelocityTracker()
                            var last = angleOf(down.position)
                            var travelled = 0f
                            tracker.addPosition(down.uptimeMillis, Offset(0f, 0f))
                            var moved = false
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull() ?: break
                                if (!change.pressed) break
                                val a = angleOf(change.position)
                                var d = a - last
                                if (d > 180f) d -= 360f
                                if (d < -180f) d += 360f
                                last = a
                                travelled += d
                                if (kotlin.math.abs(travelled) > 2f) moved = true
                                rotation += d
                                tracker.addPosition(change.uptimeMillis, Offset(travelled, 0f))
                                change.consume()
                            }
                            if (!moved) {
                                // A tap on an item spins it to the top.
                                val tapAngle = angleOf(down.position)
                                val i = WheelOptions.indices.minByOrNull { idx ->
                                    val itemAngle = idx * step + rotation - 90f
                                    val diff = ((tapAngle - itemAngle) % 360f + 540f) % 360f - 180f
                                    kotlin.math.abs(diff)
                                } ?: 0
                                val dist = kotlin.math.hypot(down.position.x - center.x, down.position.y - center.y)
                                if (dist > size.width * 0.22f) {
                                    val delta = ((-i * step - rotation) % 360f + 540f) % 360f - 180f
                                    snapTo(rotation + delta)
                                }
                                return@awaitEachGesture
                            }
                            val v = tracker.calculateVelocity().x // degrees per second
                            spin = scope.launch {
                                animateDecay(rotation, v, FloatExponentialDecaySpec(frictionMultiplier = 1.6f)) { value, _ -> rotation = value }
                                val target = (rotation / step).roundToInt() * step
                                animate(rotation, target, animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f)) { value, _ -> rotation = value }
                            }
                        }
                    },
            ) {
                val radius = with(LocalDensity.current) { (maxWidth * 0.36f).toPx() }
                WheelOptions.forEachIndexed { i, opt ->
                    val isSel = i == selected
                    val bg by animateFloatAsState(if (isSel) 1f else 0f, label = "sel$i")
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset {
                                val a = Math.toRadians((i * step + rotation - 90f).toDouble())
                                IntOffset((cos(a) * radius).roundToInt(), (sin(a) * radius).roundToInt())
                            }
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(lerpColor(Color.White.copy(alpha = 0.14f), MaterialTheme.colorScheme.primaryContainer, bg)),
                    ) {
                        Icon(
                            opt.icon,
                            contentDescription = opt.label,
                            tint = lerpColor(Color.White, MaterialTheme.colorScheme.onPrimaryContainer, bg),
                            modifier = Modifier.size(28.dp),
                        )
                        Text(
                            opt.label,
                            color = lerpColor(Color.White.copy(alpha = 0.85f), MaterialTheme.colorScheme.onPrimaryContainer, bg),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                // Centre button: adds whatever is at the top.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(104.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { pick(selected) },
                ) {
                    Icon(WheelOptions[selected].icon, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (selected <= 1) "Add" else "Open",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t.coerceIn(0f, 1f))

/** Pick an app to drop on the Home screen (first free spot). */
@Composable
fun AddAppScreen(vm: LauncherViewModel, apps: List<AppEntry>, onDone: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = remember(apps, query) { searchApps(apps, query) }
    OverlayColumn {
        OverlayHeader("Add an app", "Tap an app to put it on your Home screen", onDone)
        SearchBox(query) { query = it }
        LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp),
            modifier = Modifier.weight(1f).navigationBarsPadding(),
        ) {
            items(shown, key = { "add-" + it.key }) { app ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            vm.addToHome(app)
                            onDone()
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppImage(app, 40.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(app.label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Choose an icon shape and an icon pack; previews use your real apps. */
@Composable
fun IconStyleScreen(vm: LauncherViewModel, apps: List<AppEntry>, onDone: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val packs by produceState<List<IconPackInfo>?>(null) { value = withContext(Dispatchers.IO) { vm.iconPacks() } }
    val sample = remember(apps) { apps.take(5) }
    val previewPx = with(LocalDensity.current) { 44.dp.roundToPx() }
    val shapeColor = Color.White.copy(alpha = 0.9f).toArgb()

    OverlayColumn {
        OverlayHeader("Icon style", "Changes apply to every app", onDone)
        // Live preview of how your icons look now
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            sample.forEach { app -> AppImage(app, 52.dp) }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.weight(1f).navigationBarsPadding()) {
            item { SectionLabel("Shape") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(IconShape.entries.toList(), key = { it.id }) { shape ->
                        val on = settings.iconShape == shape.id
                        val bmp = remember(shape) { IconRenderer.shapePreview(shape, previewPx, shapeColor).asImageBitmap() }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .width(92.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.White.copy(alpha = 0.06f))
                                .clickable { vm.updateSettings { it.copy(iconShape = shape.id) } }
                                .padding(vertical = 14.dp),
                        ) {
                            Image(bmp, contentDescription = null, modifier = Modifier.size(44.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(
                                shape.label,
                                color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else Color.White,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            item { SectionLabel("Icon pack") }
            item {
                PackRow("None", "Your apps' own icons", settings.iconPack.isEmpty()) {
                    vm.updateSettings { it.copy(iconPack = "") }
                }
            }
            val list = packs
            if (list == null) {
                item { Hint("Looking for icon packs…") }
            } else if (list.isEmpty()) {
                item { Hint("No icon packs installed. Get one from the Play Store, for example by searching \"icon pack\". Most packs made for Nova or Lawnchair work here.") }
            } else {
                items(list, key = { it.packageName }) { p ->
                    PackRow(p.label, "Icon pack", settings.iconPack == p.packageName) {
                        vm.updateSettings { it.copy(iconPack = p.packageName) }
                    }
                }
            }
            item {
                Hint("Themed icons (Settings) tint icons with your wallpaper colors and use this shape too. An icon pack's icons take priority.")
            }
        }
    }
}

@Composable
private fun PackRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            Text(subtitle, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        }
        if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 10.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
}

@Composable
private fun OverlayColumn(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .swallowTouches()
            .background(Color(0xFA0D1016))
            .statusBarsPadding()
            .imePadding(),
        content = content,
    )
}

@Composable
private fun OverlayHeader(title: String, subtitle: String, onDone: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        }
        Text(
            "Done",
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable(onClick = onDone)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun SearchBox(query: String, onChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("Search apps") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.White.copy(alpha = 0.10f),
            unfocusedContainerColor = Color.White.copy(alpha = 0.10f),
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    )
}
