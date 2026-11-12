package com.horizon.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val TextShadow = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 2f), 10f)

@Composable
fun LauncherScreen(vm: LauncherViewModel) {
    val apps by vm.apps.collectAsState()
    val dock by vm.dock.collectAsState()
    val drawerOpen by vm.drawerOpen.collectAsState()
    val searchFocus by vm.searchFocus.collectAsState()

    val progress by animateFloatAsState(
        targetValue = if (drawerOpen) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f),
        label = "drawer",
    )

    BackHandler(enabled = drawerOpen) { vm.closeDrawer() }

    val launch: (AppEntry) -> Unit = { app ->
        vm.launch(app)
        vm.closeDrawer()
    }

    Box(Modifier.fillMaxSize()) {
        HomePage(
            dockApps = dock.mapNotNull { pkg -> apps.firstOrNull { it.packageName == pkg } },
            vm = vm,
            onLaunch = launch,
            modifier = Modifier.graphicsLayer {
                alpha = 1f - progress
                scaleX = 1f - 0.06f * progress
                scaleY = 1f - 0.06f * progress
            },
        )
        if (progress > 0.001f) {
            AppDrawer(
                apps = apps,
                vm = vm,
                autoFocus = searchFocus,
                onLaunch = launch,
                modifier = Modifier.graphicsLayer {
                    translationY = (1f - progress) * size.height * 0.35f
                    alpha = progress
                },
            )
        }
    }
}

@Composable
private fun HomePage(
    dockApps: List<AppEntry>,
    vm: LauncherViewModel,
    onLaunch: (AppEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    var dragTotal by remember { mutableStateOf(0f) }

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragTotal = 0f },
                    onDragEnd = {
                        if (dragTotal < -60f) vm.openDrawer()
                        dragTotal = 0f
                    },
                ) { _, dy -> dragTotal += dy }
            }
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    menu = true
                })
            },
    ) {
        // Soft scrim at the bottom so the dock stays readable on bright wallpapers
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(260.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)))),
        )

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(48.dp))
            ClockHeader()
            Spacer(Modifier.weight(1f))
            SwipeHint()
            Spacer(Modifier.height(10.dp))
            Dock(dockApps, vm, onLaunch)
            Spacer(Modifier.height(14.dp))
            SearchPill { vm.openDrawer(focusSearch = true) }
            Spacer(Modifier.height(16.dp))
        }

        Box(Modifier.align(Alignment.Center)) {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Wallpaper & style") },
                    onClick = {
                        menu = false
                        context.startSafely(
                            Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper")
                        )
                    },
                )
                DropdownMenuItem(
                    text = { Text("Default home app") },
                    onClick = {
                        menu = false
                        context.startSafely(Intent(Settings.ACTION_HOME_SETTINGS))
                    },
                )
                DropdownMenuItem(
                    text = { Text("All apps") },
                    onClick = {
                        menu = false
                        vm.openDrawer()
                    },
                )
            }
        }
    }
}

@Composable
private fun ClockHeader() {
    val context = LocalContext.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(60_000L - (System.currentTimeMillis() % 60_000L))
        }
    }
    val timePattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
    val time = now.format(DateTimeFormatter.ofPattern(timePattern))
    val date = now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d"))

    Column {
        Text(
            text = time,
            color = Color.White,
            style = TextStyle(fontSize = 84.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp, shadow = TextShadow),
            modifier = Modifier.clickable { context.startSafely(Intent(AlarmClock.ACTION_SHOW_ALARMS)) },
        )
        Text(
            text = date,
            color = Color.White.copy(alpha = 0.9f),
            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, shadow = TextShadow),
            modifier = Modifier.clickable {
                context.startSafely(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)
                )
            },
        )
    }
}

@Composable
private fun SwipeHint() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.45f)),
        )
    }
}

@Composable
private fun Dock(apps: List<AppEntry>, vm: LauncherViewModel, onLaunch: (AppEntry) -> Unit) {
    if (apps.isEmpty()) return
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        apps.forEach { app ->
            AppIcon(app = app, vm = vm, showLabel = false, onLaunch = { onLaunch(app) })
        }
    }
}

@Composable
private fun SearchPill(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.18f))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = Color.White)
        Spacer(Modifier.width(12.dp))
        Text("Search apps", color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp)
    }
}

@Composable
private fun AppDrawer(
    apps: List<AppEntry>,
    vm: LauncherViewModel,
    autoFocus: Boolean,
    onLaunch: (AppEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        val q = query.trim()
        if (q.isEmpty()) apps else apps.filter { it.label.contains(q, ignoreCase = true) }
    }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val close by rememberUpdatedState {
        keyboard?.hide()
        vm.closeDrawer()
    }

    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            delay(120)
            runCatching { focus.requestFocus() }
        }
    }

    // Pulling down when the list is already at the top closes the drawer.
    val pullToClose = remember {
        object : NestedScrollConnection {
            var pulled = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f) {
                    pulled += available.y
                    if (pulled > 180f) {
                        pulled = 0f
                        close()
                    }
                } else if (available.y < 0f) {
                    pulled = 0f
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                pulled = 0f
                return Velocity.Zero
            }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(Color(0xF00D1016))
            .statusBarsPadding()
            .imePadding(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dy -> if (dy > 20f) close() }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.35f)),
            )
        }

        TextField(
            value = query,
            onValueChange = { query = it },
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
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { filtered.firstOrNull()?.let(onLaunch) }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .focusRequester(focus),
        )

        Spacer(Modifier.height(12.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp),
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(pullToClose)
                .navigationBarsPadding(),
        ) {
            items(filtered, key = { it.key }) { app ->
                AppIcon(
                    app = app,
                    vm = vm,
                    showLabel = true,
                    onLaunch = { onLaunch(app) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppIcon(
    app: AppEntry,
    vm: LauncherViewModel,
    showLabel: Boolean,
    onLaunch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val dock by vm.dock.collectAsState()
    val inDock = app.packageName in dock

    Box(modifier) {
        Column(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .combinedClickable(
                    onClick = onLaunch,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                )
                .padding(vertical = 8.dp, horizontal = 4.dp)
                .then(if (showLabel) Modifier.fillMaxWidth() else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(bitmap = app.icon, contentDescription = app.label, modifier = Modifier.size(56.dp))
            if (showLabel) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = app.label,
                    color = Color.White,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (inDock) "Remove from dock" else "Add to dock") },
                enabled = inDock || dock.size < LauncherViewModel.MAX_DOCK,
                onClick = {
                    menu = false
                    vm.toggleDock(app)
                },
            )
            DropdownMenuItem(
                text = { Text("App info") },
                onClick = {
                    menu = false
                    vm.openAppInfo(app)
                },
            )
            DropdownMenuItem(
                text = { Text("Uninstall") },
                onClick = {
                    menu = false
                    context.startSafely(
                        Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))
                    )
                },
            )
        }
    }
}

private fun Context.startSafely(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
