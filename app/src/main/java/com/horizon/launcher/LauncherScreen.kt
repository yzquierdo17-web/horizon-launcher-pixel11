package com.horizon.launcher

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.AlarmManager
import android.appwidget.AppWidgetProviderInfo
import android.content.IntentFilter
import android.os.BatteryManager
import android.view.HapticFeedbackConstants
import android.view.View
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import android.text.format.DateFormat
import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material.icons.outlined.WorkOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.time.Instant
import java.time.ZoneId
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val ClockShadow = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 2f), 10f)

@Composable
fun LauncherScreen(
    vm: LauncherViewModel,
    onAddWidget: (AppWidgetProviderInfo) -> Unit,
    onBlur: (Float) -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
) {
    val settings by vm.settings.collectAsState()
    CompositionLocalProvider(LocalSettings provides settings) {
        LauncherContent(vm, settings, onAddWidget, onBlur, onBackup, onRestore)
    }
}

@Composable
private fun LauncherContent(
    vm: LauncherViewModel,
    settings: LauncherSettings,
    onAddWidget: (AppWidgetProviderInfo) -> Unit,
    onBlur: (Float) -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
) {
    val apps by vm.apps.collectAsState()
    val index by vm.appIndex.collectAsState()
    val items by vm.items.collectAsState()
    val dockKeys by vm.dock.collectAsState()
    val drawerOpen by vm.drawerOpen.collectAsState()
    val searchFocus by vm.searchFocus.collectAsState()
    val openFolderId by vm.openFolder.collectAsState()
    val pickerOpen by vm.widgetPicker.collectAsState()
    val homeSignal by vm.homeSignal.collectAsState()
    val settingsOpen by vm.settingsOpen.collectAsState()

    val drag = remember { DragController() }
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val visibleApps = remember(apps, settings.hidden) { apps.filter { it.key !in settings.hidden } }

    val pageCount = (items.maxOfOrNull { it.page } ?: 0) + 1 + (if (drag.active) 1 else 0)
    val pageCountState = rememberUpdatedState(pageCount)
    val pager = rememberPagerState { pageCountState.value }

    var lastPage by remember { mutableStateOf(-1) }
    LaunchedEffect(pager.currentPage) {
        vm.currentPage = pager.currentPage
        if (lastPage != -1 && lastPage != pager.currentPage) view.tick()
        lastPage = pager.currentPage
    }
    LaunchedEffect(homeSignal) { if (homeSignal > 0) pager.animateScrollToPage(0) }

    // The drawer position: follows your finger while swiping, springs open or closed after.
    val drawer = remember { Animatable(0f) }
    LaunchedEffect(drawerOpen) {
        drawer.animateTo(if (drawerOpen) 1f else 0f, spring(dampingRatio = 0.85f, stiffness = 420f))
    }
    val progress = drawer.value
    val screenHeightPx = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val onSwipe: (Float) -> Unit = { dy ->
        if (!drawerOpen) scope.launch { drawer.snapTo((drawer.value - dy / (screenHeightPx * 0.6f)).coerceIn(0f, 1f)) }
    }
    val onSwipeEnd: (Float, Float) -> Unit = { velocityY, totalDy ->
        when {
            drawer.value > 0.3f || (velocityY < -1200f && drawer.value > 0.02f) -> {
                if (!drawerOpen) view.tick()
                vm.openDrawer()
                scope.launch { drawer.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 420f, visibilityThreshold = 0.001f)) }
            }
            else -> {
                scope.launch { drawer.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 500f)) }
                if (totalDy > 120f && drawer.value < 0.05f) expandNotifications(view)
            }
        }
    }
    val folderAlpha by animateFloatAsState(
        targetValue = if (openFolderId != null) 1f else 0f,
        animationSpec = tween(160),
        label = "folder",
    )

    LaunchedEffect(settings.wallpaperBlur) {
        if (!settings.wallpaperBlur) {
            onBlur(0f)
            return@LaunchedEffect
        }
        snapshotFlow { maxOf(drawer.value, folderAlpha) }
            .distinctUntilChanged()
            .collect { onBlur(it) }
    }

    // A light tick each time the drop spot moves to a new cell while dragging
    LaunchedEffect(drag.active) {
        if (!drag.active) return@LaunchedEffect
        snapshotFlow { if (drag.overGrid()) drag.cellAt() else null }
            .distinctUntilChanged()
            .collect { if (it != null) view.tick() }
    }

    BackHandler(enabled = drawerOpen || openFolderId != null || pickerOpen || settingsOpen) {
        when {
            settingsOpen -> vm.openSettings(false)
            pickerOpen -> vm.showWidgetPicker(false)
            openFolderId != null -> vm.closeFolder()
            else -> vm.closeDrawer()
        }
    }

    val launch: (AppEntry) -> Unit = { app ->
        val b = drag.lastTapBounds
        val rect = if (b.width > 0f) {
            android.graphics.Rect(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt())
        } else {
            null
        }
        val opts = rect?.let {
            runCatching {
                ActivityOptions.makeScaleUpAnimation(view, it.left, it.top, it.width(), it.height()).toBundle()
            }.getOrNull()
        }
        vm.launch(app, rect, opts)
        vm.closeDrawer()
        vm.closeFolder()
    }

    // Work out where the dragged thing landed and hand it to the view model.
    val finishDrag: () -> Unit = {
        val dockCount = dockKeys.size
        drag.end()?.let { (payload, pos) ->
            val target = when {
                payload !is DragPayload.FromDrawer && drag.removeBounds.contains(pos) -> DropTarget.Remove
                drag.dockBounds.contains(pos) -> {
                    val b = drag.dockBounds
                    val i = ((pos.x - b.left) / b.width * dockCount).roundToInt().coerceIn(0, dockCount)
                    DropTarget.Dock(i)
                }
                drag.gridBounds.contains(pos) -> {
                    val cell = drag.cellAt(pos)
                    DropTarget.Home(pager.currentPage, cell.x, cell.y)
                }
                else -> DropTarget.None
            }
            view.confirm()
            vm.drop(payload, target)
        }
    }

    // Hold an item at the left or right edge to move between pages.
    val edgePx = with(LocalDensity.current) { 28.dp.toPx() }
    LaunchedEffect(drag.active) {
        if (!drag.active) return@LaunchedEffect
        var held = 0L
        var lastEdge = 0
        while (true) {
            delay(100)
            val p = drag.position
            val b = drag.gridBounds
            val edge = when {
                p.y !in b.top..b.bottom -> 0
                p.x < b.left + edgePx -> -1
                p.x > b.right - edgePx -> 1
                else -> 0
            }
            if (edge != 0 && edge == lastEdge) held += 100 else held = 0
            lastEdge = edge
            if (edge != 0 && held >= 500) {
                val targetPage = pager.currentPage + edge
                if (targetPage in 0 until pager.pageCount) pager.animateScrollToPage(targetPage)
                held = 0
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        HomeScreen(
            vm = vm,
            drag = drag,
            pager = pager,
            pageCount = pageCount,
            items = items,
            index = index,
            dockKeys = dockKeys,
            onLaunch = launch,
            onDrop = finishDrag,
            onSwipe = onSwipe,
            onSwipeEnd = onSwipeEnd,
            modifier = Modifier.graphicsLayer {
                alpha = 1f - progress
                scaleX = 1f - 0.06f * progress
                scaleY = 1f - 0.06f * progress
            },
        )

        if (progress > 0.001f || drag.payload is DragPayload.FromDrawer) {
            AppDrawer(
                apps = visibleApps,
                vm = vm,
                drag = drag,
                autoFocus = searchFocus,
                onLaunch = launch,
                onDrop = finishDrag,
                blurOn = settings.wallpaperBlur,
                modifier = Modifier.graphicsLayer {
                    translationY = (1f - progress) * size.height * 0.4f
                    alpha = (progress * 1.6f).coerceAtMost(1f)
                },
            )
        }

        val folderId = openFolderId ?: (drag.payload as? DragPayload.FromFolder)?.folderId
        val folder = folderId?.let { id -> items.firstOrNull { it.id == id && it.type == TYPE_FOLDER } }
        if (folder != null) {
            FolderOverlay(
                folder = folder,
                index = index,
                vm = vm,
                drag = drag,
                onLaunch = launch,
                onDrop = finishDrag,
                modifier = Modifier.graphicsLayer { alpha = folderAlpha },
            )
        }

        if (pickerOpen) {
            WidgetPicker(
                vm = vm,
                apps = apps,
                onPick = { info ->
                    vm.showWidgetPicker(false)
                    onAddWidget(info)
                },
                onClose = { vm.showWidgetPicker(false) },
            )
        }

        if (settingsOpen) {
            SettingsScreen(
                vm = vm,
                apps = apps,
                onBackup = onBackup,
                onRestore = onRestore,
                onClose = { vm.openSettings(false) },
            )
        }

        // "Remove" target at the top while dragging something off the home screen or dock
        if (drag.active && drag.payload !is DragPayload.FromDrawer) {
            val over = drag.overRemove()
            Box(
                Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { drag.removeBounds = it.boundsInRoot() }
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = if (over) 0.55f else 0.35f), Color.Transparent),
                        ),
                    )
                    .statusBarsPadding()
                    .height(88.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(if (over) Color(0xFFE5484D) else Color.White.copy(alpha = 0.18f))
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Remove", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // The item following your finger
        if (drag.active) {
            val size = drag.previewSize
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (drag.position.x - size.width.toPx() / 2).roundToInt(),
                            (drag.position.y - size.height.toPx() / 2).roundToInt(),
                        )
                    }
                    .size(size)
                    .graphicsLayer {
                        scaleX = 1.1f
                        scaleY = 1.1f
                        alpha = 0.95f
                    },
                contentAlignment = Alignment.Center,
            ) {
                drag.preview?.invoke()
            }
        }
    }
}

@Composable
private fun HomeScreen(
    vm: LauncherViewModel,
    drag: DragController,
    pager: PagerState,
    pageCount: Int,
    items: List<HomeItem>,
    index: Map<String, AppEntry>,
    dockKeys: List<String>,
    onLaunch: (AppEntry) -> Unit,
    onDrop: () -> Unit,
    onSwipe: (Float) -> Unit,
    onSwipeEnd: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val settings = LocalSettings.current
    var menu by remember { mutableStateOf(false) }
    var dragTotal by remember { mutableStateOf(0f) }
    val tracker = remember { VelocityTracker() }
    val swipe by rememberUpdatedState(onSwipe)
    val swipeEnd by rememberUpdatedState(onSwipeEnd)

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = {
                        dragTotal = 0f
                        tracker.resetTracking()
                    },
                    onDragEnd = {
                        swipeEnd(tracker.calculateVelocity().y, dragTotal)
                        dragTotal = 0f
                    },
                    onDragCancel = {
                        swipeEnd(0f, 0f)
                        dragTotal = 0f
                    },
                ) { change, dy ->
                    tracker.addPosition(change.uptimeMillis, change.position)
                    dragTotal += dy
                    swipe(dy)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = {
                    if (!drag.itemPressed) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    }
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
                .navigationBarsPadding(),
        ) {
            Spacer(Modifier.height(if (settings.compactClock) 20.dp else 32.dp))
            ClockHeader(vm = vm, compact = settings.compactClock, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(12.dp))

            HorizontalPager(
                state = pager,
                userScrollEnabled = !drag.active,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .onGloballyPositioned { drag.gridBounds = it.boundsInRoot() },
            ) { page ->
                HomePage(
                    page = page,
                    isCurrent = pager.currentPage == page,
                    items = items,
                    index = index,
                    vm = vm,
                    drag = drag,
                    onLaunch = onLaunch,
                    onDrop = onDrop,
                )
            }

            PageDots(count = pageCount, current = pager.currentPage)
            Spacer(Modifier.height(8.dp))
            Dock(
                keys = dockKeys,
                index = index,
                vm = vm,
                drag = drag,
                onLaunch = onLaunch,
                onDrop = onDrop,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (settings.showSearch) {
                Spacer(Modifier.height(12.dp))
                SearchPill(Modifier.padding(horizontal = 16.dp)) { vm.openDrawer(focusSearch = true) }
            }
            Spacer(Modifier.height(14.dp))
        }

        Box(Modifier.align(Alignment.Center)) {
            ItemMenu(
                expanded = menu,
                onDismiss = { menu = false },
                actions = listOf(
                    MenuAction("Widgets") { vm.showWidgetPicker(true) },
                    MenuAction("Wallpaper & style") {
                        context.startSafely(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper"))
                    },
                    MenuAction("Horizon settings") { vm.openSettings(true) },
                ),
            )
        }
    }
}

@Composable
private fun HomePage(
    page: Int,
    isCurrent: Boolean,
    items: List<HomeItem>,
    index: Map<String, AppEntry>,
    vm: LauncherViewModel,
    drag: DragController,
    onLaunch: (AppEntry) -> Unit,
    onDrop: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cellW: Dp = maxWidth / GRID_COLS
        val cellH: Dp = maxHeight / GRID_ROWS
        SideEffect {
            vm.cellWidthDp = cellW.value
            vm.cellHeightDp = cellH.value
        }

        val pageItems = items.filter { it.page == page }

        if (page == 0 && items.isEmpty() && !drag.active) {
            Text(
                "Long-press any app in the drawer to add it here,\nor long-press the home screen for widgets.",
                color = Color.White.copy(alpha = 0.75f),
                style = TextStyle(fontSize = 14.sp, textAlign = TextAlign.Center, shadow = LabelShadow),
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }

        // Where the dragged item will land
        if (isCurrent && drag.overGrid()) {
            val cell = drag.cellAt()
            Box(
                Modifier
                    .offset(cellW * cell.x, cellH * cell.y)
                    .size(cellW * drag.spanX, cellH * drag.spanY)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.White.copy(alpha = 0.16f)),
            )
        }

        pageItems.forEach { item ->
            androidx.compose.runtime.key(item.id) {
                Box(
                    Modifier
                        .offset(cellW * item.x, cellH * item.y)
                        .size(cellW * item.spanX, cellH * item.spanY),
                    contentAlignment = Alignment.Center,
                ) {
                    when (item.type) {
                        TYPE_APP -> {
                            val app = item.apps.firstOrNull()?.let { index[it] }
                            if (app != null) {
                                LauncherIcon(
                                    label = if (LocalSettings.current.homeLabels) app.label else null,
                                    drag = drag,
                                    payload = DragPayload.FromHome(item.id),
                                    onTap = { onLaunch(app) },
                                    menu = listOf(
                                        MenuAction("Remove") { vm.removeItem(item.id) },
                                        MenuAction("App info") { vm.openAppInfo(app) },
                                    ),
                                    onDrop = onDrop,
                                    labelShadow = true,
                                ) { AppImage(app) }
                            }
                        }
                        TYPE_FOLDER -> {
                            val folderApps = item.apps.mapNotNull { index[it] }
                            LauncherIcon(
                                label = if (LocalSettings.current.homeLabels) item.name.ifBlank { "Folder" } else null,
                                drag = drag,
                                payload = DragPayload.FromHome(item.id),
                                onTap = { vm.openFolder(item.id) },
                                menu = listOf(
                                    MenuAction("Open") { vm.openFolder(item.id) },
                                    MenuAction("Remove folder") { vm.removeItem(item.id) },
                                ),
                                onDrop = onDrop,
                                labelShadow = true,
                            ) { FolderPreview(folderApps) }
                        }
                        TYPE_WIDGET -> WidgetTile(
                            item = item,
                            vm = vm,
                            drag = drag,
                            cellWidth = cellW,
                            cellHeight = cellH,
                            onDrop = onDrop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (count > 1) {
            repeat(count) { i ->
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (i == current) 7.dp else 6.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = if (i == current) 0.95f else 0.4f)),
                )
            }
        }
    }
}

@Composable
private fun ClockHeader(vm: LauncherViewModel, compact: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = LocalSettings.current
    val meeting by vm.meeting.collectAsState()
    val workTime by vm.workTime.collectAsState()
    val work by vm.workProfile.collectAsState()
    var calendarGranted by remember { mutableStateOf(vm.calendar.hasPermission()) }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        calendarGranted = granted
        vm.updateSettings { it.copy(showMeetings = granted, meetingPromptDone = true) }
        vm.refreshMeetings()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { calendarGranted = vm.calendar.hasPermission() }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(60_000L - (System.currentTimeMillis() % 60_000L))
        }
    }
    val timePattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
    val time = now.format(DateTimeFormatter.ofPattern(timePattern))
    val date = now.format(DateTimeFormatter.ofPattern(if (compact) "EEE, MMM d" else "EEEE, MMMM d"))
    val glance = remember(now) { atAGlance(context, timePattern) }
    val openClock = { context.startSafely(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
    val openCalendar = {
        context.startSafely(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR))
    }

    Column(modifier) {
        if (compact) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = time,
                    color = Color.White,
                    style = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Normal, letterSpacing = (-1).sp, shadow = ClockShadow),
                    modifier = Modifier.clickable(onClick = openClock),
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    text = date,
                    color = Color.White.copy(alpha = 0.92f),
                    style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, shadow = ClockShadow),
                    modifier = Modifier.padding(bottom = 8.dp).clickable(onClick = openCalendar),
                )
            }
        } else {
            Text(
                text = time,
                color = Color.White,
                style = TextStyle(fontSize = 80.sp, fontWeight = FontWeight.Light, letterSpacing = (-2.5).sp, shadow = ClockShadow),
                modifier = Modifier.clickable(onClick = openClock),
            )
            Text(
                text = date,
                color = Color.White.copy(alpha = 0.92f),
                style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, shadow = ClockShadow),
                modifier = Modifier.clickable(onClick = openCalendar),
            )
        }
        if (glance.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = glance.joinToString("   ·   "),
                color = Color.White.copy(alpha = 0.8f),
                style = TextStyle(fontSize = 14.sp, shadow = ClockShadow),
                modifier = Modifier.clickable(onClick = openClock),
            )
        }

        // Next meeting, then work status.
        val m = meeting
        if (m != null && settings.showMeetings) {
            Spacer(Modifier.height(8.dp))
            MeetingLine(m, now, onOpen = { vm.calendar.openEvent(m) }, onJoin = { vm.calendar.join(m) })
        } else if (settings.showMeetings && !calendarGranted && !settings.meetingPromptDone) {
            Spacer(Modifier.height(8.dp))
            GlanceChip(
                "Show your next meeting",
                Icons.Outlined.Event,
                onClick = { calendarPermission.launch(Manifest.permission.READ_CALENDAR) },
                onDismiss = { vm.updateSettings { it.copy(meetingPromptDone = true) } },
            )
        }
        val w = work
        if (w != null && w.paused) {
            Spacer(Modifier.height(8.dp))
            GlanceChip("Work apps paused \u00B7 Turn on", Icons.Outlined.WorkOff, onClick = { vm.setWorkPaused(false) })
        } else if (workTime) {
            Spacer(Modifier.height(8.dp))
            GlanceChip(
                "Work hours \u00B7 until ${formatMinute(settings.workEnd, DateFormat.is24HourFormat(context))}",
                Icons.Outlined.Work,
            )
        }
    }
}

/** [now] is passed so the "in N min" text recomposes every minute with the clock. */
@Suppress("UNUSED_PARAMETER")
@Composable
private fun MeetingLine(meeting: Meeting, now: LocalDateTime, onOpen: () -> Unit, onJoin: () -> Unit) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val clock = DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a")
    fun fmt(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).format(clock)
    val minutes = ((meeting.begin - System.currentTimeMillis()) / 60_000L).toInt()
    val whenText = when {
        meeting.begin <= System.currentTimeMillis() -> "now \u00B7 until ${fmt(meeting.end)}"
        minutes < 1 -> "starting now"
        minutes < 60 -> "in $minutes min"
        else -> "at ${fmt(meeting.begin)}"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false).clickable(onClick = onOpen),
        ) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(meeting.color?.let { Color(it) } ?: Color(0xFF8AB4F8)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${meeting.title} \u00B7 $whenText",
                color = Color.White,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, shadow = ClockShadow),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Join shows from 15 minutes before until the meeting ends, when the event has a link.
        if (meeting.joinUrl != null && minutes <= 15) {
            Spacer(Modifier.width(10.dp))
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable(onClick = onJoin)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Videocam, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Join", color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun GlanceChip(text: String, icon: ImageVector, onClick: (() -> Unit)? = null, onDismiss: (() -> Unit)? = null) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.28f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 12.dp, end = if (onDismiss != null) 4.dp else 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        if (onDismiss != null) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Not now",
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.padding(start = 6.dp).size(18.dp).clip(CircleShape).clickable(onClick = onDismiss),
            )
        }
    }
}

@Composable
private fun Dock(
    keys: List<String>,
    index: Map<String, AppEntry>,
    vm: LauncherViewModel,
    drag: DragController,
    onLaunch: (AppEntry) -> Unit,
    onDrop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlight = drag.active && drag.dockBounds.contains(drag.position)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .onGloballyPositioned { drag.dockBounds = it.boundsInRoot() }
            .clip(RoundedCornerShape(32.dp))
            .background(Color.White.copy(alpha = if (highlight) 0.26f else 0.14f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (keys.isEmpty()) {
            Text(
                "Drag apps here",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        }
        keys.forEachIndexed { i, key ->
            val app = index[key] ?: return@forEachIndexed
            LauncherIcon(
                label = null,
                drag = drag,
                payload = DragPayload.FromDock(i),
                onTap = { onLaunch(app) },
                menu = listOf(
                    MenuAction("Remove from dock") { vm.removeFromDock(i) },
                    MenuAction("App info") { vm.openAppInfo(app) },
                ),
                onDrop = onDrop,
            ) { AppImage(app) }
        }
    }
}

@Composable
private fun SearchPill(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
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
    drag: DragController,
    autoFocus: Boolean,
    onLaunch: (AppEntry) -> Unit,
    onDrop: () -> Unit,
    blurOn: Boolean,
    modifier: Modifier = Modifier,
) {
    val settings = LocalSettings.current
    val dockKeys by vm.dock.collectAsState()
    val work by vm.workProfile.collectAsState()
    val workTime by vm.workTime.collectAsState()
    val usage by vm.usage.collectAsState()
    var query by remember { mutableStateOf("") }

    // Work and Personal tabs when the phone has a work profile. Opens on Work during work hours.
    var workTab by remember(workTime) { mutableStateOf(workTime) }
    val showTabs = work != null && query.isBlank()
    val paused = work?.paused == true
    val tabApps = remember(apps, showTabs, workTab) {
        if (!showTabs) apps else apps.filter { it.isWork == workTab }
    }
    val filtered = remember(tabApps, query) { searchApps(tabApps, query) }

    // Most-opened apps on top.
    val byKey = remember(apps) { apps.associateBy { it.key } }
    val suggested = remember(byKey, usage, showTabs, workTab, settings.suggestions) {
        if (!settings.suggestions) emptyList()
        else usage.entries.sortedByDescending { it.value }
            .mapNotNull { byKey[it.key] }
            .filter { !it.isPaused && (!showTabs || it.isWork == workTab) }
            .take(5)
    }
    val showSuggestions = query.isBlank() && suggested.isNotEmpty()
    val headerCount = if (showSuggestions) 2 else 0
    val letterIndex = remember(filtered, query, headerCount) {
        if (query.isNotBlank()) emptyMap()
        else buildMap { filtered.forEachIndexed { i, app -> putIfAbsent(app.sectionLetter(), i + headerCount) } }
    }

    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
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
    LaunchedEffect(query) { if (grid.firstVisibleItemIndex > 0) grid.scrollToItem(0) }
    LaunchedEffect(grid.isScrollInProgress) { if (grid.isScrollInProgress) keyboard?.hide() }

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
            .swallowTouches()
            .background(if (blurOn) Color(0xB80D1016) else Color(0xF00D1016))
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
            trailingIcon = {
                if (query.isNotEmpty()) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Clear search",
                        modifier = Modifier.clip(CircleShape).clickable { query = "" }.padding(8.dp),
                    )
                }
            },
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

        if (showTabs) {
            ProfileTabs(workTab = workTab, onSelect = { workTab = it })
            if (workTab && paused) PausedBanner(onTurnOn = { vm.setWorkPaused(false) })
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val showScroller = letterIndex.size > 5
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                state = grid,
                contentPadding = PaddingValues(start = 12.dp, end = if (showScroller) 30.dp else 12.dp, bottom = 32.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(pullToClose)
                    .navigationBarsPadding(),
            ) {
                if (showSuggestions) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "suggested") {
                        Row(Modifier.fillMaxWidth()) {
                            suggested.forEach { app ->
                                Box(Modifier.weight(1f)) {
                                    DrawerAppIcon(app, vm, drag, dockKeys, onLaunch, close, onDrop)
                                }
                            }
                            repeat(5 - suggested.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }, key = "divider") {
                        Box(
                            Modifier
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Color.White.copy(alpha = 0.12f)),
                        )
                    }
                }
                items(filtered, key = { it.key }) { app ->
                    DrawerAppIcon(app, vm, drag, dockKeys, onLaunch, close, onDrop)
                }
            }

            if (showTabs && workTab && !paused) {
                Row(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 36.dp, bottom = 16.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable { vm.setWorkPaused(true) }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.WorkOff, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Pause work apps", color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }

            if (showScroller) {
                FastScroller(
                    letters = letterIndex.keys.toList(),
                    onSelect = { letter -> letterIndex[letter]?.let { scope.launch { grid.scrollToItem(it) } } },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun DrawerAppIcon(
    app: AppEntry,
    vm: LauncherViewModel,
    drag: DragController,
    dockKeys: List<String>,
    onLaunch: (AppEntry) -> Unit,
    close: () -> Unit,
    onDrop: () -> Unit,
) {
    val context = LocalContext.current
    val inDock = app.key in dockKeys
    LauncherIcon(
        label = app.label,
        drag = drag,
        payload = DragPayload.FromDrawer(app.key),
        onTap = { onLaunch(app) },
        menu = listOf(
            MenuAction("Add to home screen") { vm.addToHome(app) },
            MenuAction(
                if (inDock) "Remove from dock" else "Add to dock",
                enabled = inDock || dockKeys.size < MAX_DOCK,
            ) { vm.toggleDock(app.key) },
            MenuAction("App info") { vm.openAppInfo(app) },
            MenuAction("Uninstall") {
                context.startSafely(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")))
            },
        ),
        onDragStarted = close,
        onDrop = onDrop,
        modifier = Modifier.fillMaxWidth(),
    ) { AppImage(app) }
}

@Composable
private fun ProfileTabs(workTab: Boolean, onSelect: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 10.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .padding(4.dp),
    ) {
        TabPill("Personal", Icons.Outlined.Person, selected = !workTab, Modifier.weight(1f)) { onSelect(false) }
        TabPill("Work", Icons.Outlined.Work, selected = workTab, Modifier.weight(1f)) { onSelect(true) }
    }
}

@Composable
private fun TabPill(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else Color.White.copy(alpha = 0.75f)
    Row(
        modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PausedBanner(onTurnOn: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.WorkOff, null, tint = Color.White.copy(alpha = 0.8f))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Work apps are paused", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("You won't get work notifications", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        }
        Text(
            "Turn on",
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable(onClick = onTurnOn)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** A–Z rail: touch or slide to jump, with a letter bubble and a haptic tick per letter. */
@Composable
private fun FastScroller(letters: List<String>, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val view = LocalView.current
    var active by remember { mutableStateOf<String?>(null) }
    var heightPx by remember { mutableIntStateOf(1) }
    val currentLetters by rememberUpdatedState(letters)
    val currentOnSelect by rememberUpdatedState(onSelect)

    fun pick(y: Float) {
        val list = currentLetters
        if (list.isEmpty()) return
        val letter = list[((y / heightPx) * list.size).toInt().coerceIn(0, list.lastIndex)]
        if (letter != active) {
            active = letter
            view.tick()
            currentOnSelect(letter)
        }
    }

    Box(modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(24.dp)
                .clip(CircleShape)
                .background(if (active != null) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                .padding(vertical = 6.dp)
                .onSizeChanged { heightPx = it.height.coerceAtLeast(1) }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        pick(down.position.y)
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            change.consume()
                            pick(change.position.y)
                        }
                        active = null
                    }
                },
        ) {
            letters.forEach { letter ->
                Text(
                    letter,
                    color = if (letter == active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
        active?.let { letter ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (-72).dp)
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            ) {
                Text(letter, color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 28.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

private fun AppEntry.sectionLetter(): String {
    val c = label.trim().firstOrNull()?.uppercaseChar() ?: return "#"
    return if (c.isLetter()) c.toString() else "#"
}

/**
 * Ranked search: name starts with it > a word starts with it > initials ("gm" → Google Maps) > anywhere.
 * Accents are ignored ("cafe" finds "Café"). Alphabetical order is kept within each rank.
 */
internal fun searchApps(apps: List<AppEntry>, query: String): List<AppEntry> {
    val q = query.trim().normalizeForSearch()
    if (q.isEmpty()) return apps
    return apps.mapNotNull { app ->
        val label = app.label.normalizeForSearch()
        val words = label.split(' ', '-', '_', '.', ':').filter { it.isNotEmpty() }
        val rank = when {
            label.startsWith(q) -> 0
            words.any { it.startsWith(q) } -> 1
            q.length >= 2 && words.joinToString("") { it.take(1) }.startsWith(q) -> 2
            label.contains(q) -> 3
            else -> return@mapNotNull null
        }
        rank to app
    }.sortedBy { it.first }.map { it.second }
}

private fun String.normalizeForSearch(): String =
    java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()

fun Context.startSafely(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Next alarm (if within a day) and charging status, for the line under the date. */
private fun atAGlance(context: Context, timePattern: String): List<String> {
    val parts = mutableListOf<String>()
    runCatching {
        val am = context.getSystemService(AlarmManager::class.java)
        val next = am.nextAlarmClock
        if (next != null) {
            val millis = next.triggerTime - System.currentTimeMillis()
            if (millis in 0..(24L * 60 * 60 * 1000)) {
                val t = java.time.Instant.ofEpochMilli(next.triggerTime)
                    .atZone(java.time.ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("$timePattern a".replace("HH:mm a", "HH:mm")))
                parts += "Alarm $t"
            }
        }
    }
    runCatching {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (battery != null) {
            val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            when {
                status == BatteryManager.BATTERY_STATUS_CHARGING && pct >= 0 -> parts += "Charging · $pct%"
                status == BatteryManager.BATTERY_STATUS_FULL -> parts += "Fully charged"
                pct in 0..15 -> parts += "Battery $pct%"
            }
        }
    }
    return parts
}

private fun View.tick() {
    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}

private fun View.confirm() {
    performHapticFeedback(
        if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY,
    )
}

/** Swipe down on the home screen opens the notification shade. */
@SuppressLint("WrongConstant")
private fun expandNotifications(view: View) {
    runCatching {
        val service = view.context.getSystemService("statusbar")
        Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(service)
    }
}
