package com.horizon.launcher

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val ClockShadow = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 2f), 10f)

@Composable
fun LauncherScreen(vm: LauncherViewModel, onAddWidget: (AppWidgetProviderInfo) -> Unit) {
    val apps by vm.apps.collectAsState()
    val index by vm.appIndex.collectAsState()
    val items by vm.items.collectAsState()
    val dockKeys by vm.dock.collectAsState()
    val drawerOpen by vm.drawerOpen.collectAsState()
    val searchFocus by vm.searchFocus.collectAsState()
    val openFolderId by vm.openFolder.collectAsState()
    val pickerOpen by vm.widgetPicker.collectAsState()
    val homeSignal by vm.homeSignal.collectAsState()

    val drag = remember { DragController() }
    val haptic = LocalHapticFeedback.current

    val pageCount = (items.maxOfOrNull { it.page } ?: 0) + 1 + (if (drag.active) 1 else 0)
    val pageCountState = rememberUpdatedState(pageCount)
    val pager = rememberPagerState { pageCountState.value }

    LaunchedEffect(pager.currentPage) { vm.currentPage = pager.currentPage }
    LaunchedEffect(homeSignal) { if (homeSignal > 0) pager.animateScrollToPage(0) }

    val progress by animateFloatAsState(
        targetValue = if (drawerOpen) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f),
        label = "drawer",
    )
    val folderAlpha by animateFloatAsState(
        targetValue = if (openFolderId != null) 1f else 0f,
        animationSpec = tween(160),
        label = "folder",
    )

    BackHandler(enabled = drawerOpen || openFolderId != null || pickerOpen) {
        when {
            pickerOpen -> vm.showWidgetPicker(false)
            openFolderId != null -> vm.closeFolder()
            else -> vm.closeDrawer()
        }
    }

    val launch: (AppEntry) -> Unit = { app ->
        vm.launch(app)
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
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
            modifier = Modifier.graphicsLayer {
                alpha = 1f - progress
                scaleX = 1f - 0.06f * progress
                scaleY = 1f - 0.06f * progress
            },
        )

        if (progress > 0.001f || drag.payload is DragPayload.FromDrawer) {
            AppDrawer(
                apps = apps,
                vm = vm,
                drag = drag,
                autoFocus = searchFocus,
                onLaunch = launch,
                onDrop = finishDrag,
                modifier = Modifier.graphicsLayer {
                    translationY = (1f - progress) * size.height * 0.35f
                    alpha = progress
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
            Spacer(Modifier.height(32.dp))
            ClockHeader(Modifier.padding(horizontal = 24.dp))
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
            Spacer(Modifier.height(12.dp))
            SearchPill(Modifier.padding(horizontal = 16.dp)) { vm.openDrawer(focusSearch = true) }
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
                    MenuAction("Default home app") { context.startSafely(Intent(Settings.ACTION_HOME_SETTINGS)) },
                    MenuAction("All apps") { vm.openDrawer() },
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
                                    label = app.label,
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
                                label = item.name.ifBlank { "Folder" },
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
private fun ClockHeader(modifier: Modifier = Modifier) {
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

    Column(modifier) {
        Text(
            text = time,
            color = Color.White,
            style = TextStyle(fontSize = 76.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp, shadow = ClockShadow),
            modifier = Modifier.clickable { context.startSafely(Intent(AlarmClock.ACTION_SHOW_ALARMS)) },
        )
        Text(
            text = date,
            color = Color.White.copy(alpha = 0.9f),
            style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, shadow = ClockShadow),
            modifier = Modifier.clickable {
                context.startSafely(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR))
            },
        )
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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dockKeys by vm.dock.collectAsState()
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
            .swallowTouches()
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
        }
    }
}

fun Context.startSafely(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
