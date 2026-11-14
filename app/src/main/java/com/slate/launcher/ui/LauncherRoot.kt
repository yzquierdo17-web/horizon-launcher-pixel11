package com.slate.launcher.ui

import android.Manifest
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import com.slate.launcher.data.WidgetRepository
import android.app.ActivityOptions
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slate.launcher.LauncherViewModel
import com.slate.launcher.util.SystemActions
import com.slate.launcher.util.rememberWallpaperSupportsDarkText

@Composable
fun LauncherRoot(vm: LauncherViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val drawer = rememberAppDrawerState()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var focusSearch by remember { mutableStateOf(false) }
    val darkText = rememberWallpaperSupportsDarkText()
    val systemDark = isSystemInDarkTheme()

    val drawerVisible by remember { derivedStateOf { drawer.isVisible } }
    val drawerMostlyOpen by remember { derivedStateOf { drawer.progress > 0.5f } }

    val actions = remember(vm, view) {
        fun options(r: android.graphics.Rect?) = r?.let {
            ActivityOptions.makeScaleUpAnimation(view, it.left, it.top, it.width(), it.height()).toBundle()
        }
        AppActions(
            launch = { app, r ->
                vm.repository.launch(app, r, options(r))
                vm.recordLaunch(app)
            },
            loadShortcuts = { vm.repository.shortcutsFor(it) },
            launchShortcut = { s, r -> vm.repository.launchShortcut(s, r, options(r)) },
            appInfo = { vm.repository.openAppInfo(it) },
            uninstall = { vm.repository.uninstall(it) },
            addToHome = { vm.addToHome(it) },
            removeFromHome = { vm.removeFromHome(it) },
            addToDock = { vm.addToDock(it) },
            removeFromDock = { vm.removeFromDock(it) },
            hide = { vm.hide(it) },
        )
    }
    val expandNotifications = remember(context) { { SystemActions.expandNotifications(context) } }

    // ---- Next meeting: calendar permission ----
    val meeting by vm.meeting.collectAsStateWithLifecycle()
    var calendarGranted by remember { mutableStateOf(vm.calendar.hasPermission()) }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        calendarGranted = granted
        vm.setShowMeetings(granted)
    }

    // ---- Widgets: pick → bind (system "allow widgets" prompt) → optional setup screen → place ----
    var showWidgetPicker by rememberSaveable { mutableStateOf(false) }
    var bindingId by rememberSaveable { mutableIntStateOf(WidgetRepository.INVALID) }
    fun finishAdding(id: Int) {
        val activity = view.context as? Activity
        if (activity == null || !vm.widgetRepo.startConfigureIfNeeded(activity, id)) vm.addWidget(id)
    }
    val bindPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = bindingId
        bindingId = WidgetRepository.INVALID
        if (id == WidgetRepository.INVALID) return@rememberLauncherForActivityResult
        if (result.resultCode == Activity.RESULT_OK) finishAdding(id) else vm.cancelWidget(id)
    }
    val widgetActions = remember(vm) {
        WidgetActions(
            resize = { id, rows -> vm.resizeWidget(id, rows) },
            move = { id, up -> vm.moveWidget(id, up) },
            remove = { id -> vm.removeWidget(id) },
        )
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.widgetRepo.startListening() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.widgetRepo.stopListening() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        calendarGranted = vm.calendar.hasPermission()
        vm.onResume()
    }

    // Home button: always return to a clean Home screen.
    LaunchedEffect(Unit) {
        vm.homePressed.collect {
            showSettings = false
            drawer.close()
        }
    }
    // Coming back from an app lands on Home, like Pixel.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { drawer.snapClosed() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.repository.refresh() }

    // Real-time wallpaper blur behind the drawer (Android 12+ cross-window blur), tied to the swipe.
    val window = (view.context as? Activity)?.window
    var blurAvailable by remember { mutableStateOf(window?.windowManager?.isCrossWindowBlurEnabled == true) }
    DisposableEffect(window) {
        val wm = window?.windowManager
        val listener = java.util.function.Consumer<Boolean> { blurAvailable = it }
        wm?.addCrossWindowBlurEnabledListener(listener)
        onDispose { wm?.removeCrossWindowBlurEnabledListener(listener) }
    }
    LaunchedEffect(window, blurAvailable) {
        if (window == null) return@LaunchedEffect
        if (!blurAvailable) { window.setBackgroundBlurRadius(0); return@LaunchedEffect }
        snapshotFlow { (drawer.progress * MAX_BLUR_PX).roundToInt() }
            .distinctUntilChanged()
            .collect { window.setBackgroundBlurRadius(it) }
    }

    // A soft "click" when the drawer commits to opening.
    LaunchedEffect(drawer.target) {
        if (drawer.target == 1f) {
            view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                else HapticFeedbackConstants.CONTEXT_CLICK,
            )
        }
    }

    // Status/nav bar icons: follow the wallpaper on Home, the theme over drawer/settings.
    val lightBars = if (showSettings || drawerMostlyOpen) !systemDark else darkText
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
    }

    BackHandler(enabled = true) { /* Home is the root: back does nothing. */ }
    BackHandler(enabled = drawerVisible && !showSettings) { drawer.close() }
    BackHandler(enabled = showSettings) { showSettings = false }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .drawerDragGesture(
                state = drawer,
                enabled = !showSettings,
                onPullDown = if (drawer.target > 0f) null else expandNotifications,
            ),
    ) {
        val heightPx = constraints.maxHeight.toFloat()
        SideEffect { drawer.distancePx = heightPx }

        HomeScreen(
            state = state,
            darkText = darkText,
            actions = actions,
            onSearchClick = {
                focusSearch = true
                drawer.open()
            },
            onOpenSettings = { showSettings = true },
            meeting = meeting,
            calendarGranted = calendarGranted,
            onOpenMeeting = { vm.calendar.openEvent(it) },
            onJoinMeeting = { vm.calendar.join(it) },
            onRequestCalendar = { calendarPermission.launch(Manifest.permission.READ_CALENDAR) },
            onDismissMeetingPrompt = { vm.dismissMeetingPrompt() },
            onSetWorkPaused = { vm.setWorkPaused(it) },
            widgetRepo = vm.widgetRepo,
            widgetActions = widgetActions,
            onAddWidget = { showWidgetPicker = true },
            modifier = Modifier.graphicsLayer {
                val p = drawer.progress
                alpha = 1f - p
                val scale = 1f - 0.06f * p
                scaleX = scale
                scaleY = scale
            },
        )

        if (drawerVisible) {
            AppDrawer(
                state = state,
                drawer = drawer,
                actions = actions,
                requestFocus = focusSearch,
                translucent = blurAvailable,
                onFocusConsumed = { focusSearch = false },
                onOpenSettings = { showSettings = true },
                onSetWorkPaused = { vm.setWorkPaused(it) },
                modifier = Modifier.graphicsLayer {
                    val p = drawer.progress
                    translationY = (1f - p) * heightPx
                    alpha = (p * 1.6f).coerceAtMost(1f)
                },
            )
        }

        AnimatedVisibility(
            visible = showSettings,
            enter = slideInVertically { it / 10 } + fadeIn(),
            exit = slideOutVertically { it / 10 } + fadeOut(),
        ) {
            SettingsScreen(
                state = state,
                vm = vm,
                calendarGranted = calendarGranted,
                onRequestCalendar = { calendarPermission.launch(Manifest.permission.READ_CALENDAR) },
                onBack = { showSettings = false },
            )
        }

        if (showWidgetPicker) {
            WidgetPickerSheet(
                repo = vm.widgetRepo,
                onDismiss = { showWidgetPicker = false },
                onPick = { option ->
                    showWidgetPicker = false
                    val id = vm.widgetRepo.allocate()
                    if (vm.widgetRepo.bindIfAllowed(id, option.info)) finishAdding(id)
                    else {
                        bindingId = id
                        bindPermission.launch(vm.widgetRepo.bindIntent(id, option.info))
                    }
                },
            )
        }
    }
}

private const val MAX_BLUR_PX = 72f
