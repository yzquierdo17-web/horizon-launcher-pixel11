package com.horizon.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.DisplayCutout
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.MutableStateFlow

/** Screen facts the island needs, in px. [hole] is the front camera's exact outline, if there is one. */
data class IslandGeometry(
    val screenW: Int,
    val screenH: Int,
    val hole: RectF?,
    val statusBar: Int,
    val landscape: Boolean,
)

/** What the overlay window should be right now. */
enum class IslandWindow { Hidden, Compact, Wide, Full }

/**
 * Draws the Dynamic Island over every app and the lock screen, like on iPhone. Android only lets
 * accessibility services put a window above the status bar, so that's what this is. It reads nothing
 * from the screen; calls, music and so on come from [IslandListener].
 */
class IslandOverlayService : AccessibilityService(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    private var wm: WindowManager? = null
    private var root: OutsideTouchFrame? = null
    private var params: WindowManager.LayoutParams? = null
    private var mode = IslandWindow.Hidden

    private val geometry = MutableStateFlow<IslandGeometry?>(null)

    // Horizon's dial lock screen goes up as the screen turns off, so it's there when you wake the phone
    private val screenOff = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: android.content.Intent) = LockDialActivity.showIfWanted(
            this@IslandOverlayService,
            screenOn = i.action == android.content.Intent.ACTION_SCREEN_ON,
        )
    }
    private val outsideTaps = MutableStateFlow(0)

    // Emoji battery status bar: its own thin window across the top, under the island.
    private var barView: FrameLayout? = null
    private val statusBarShown = MutableStateFlow(true)

    override fun onCreate() {
        super.onCreate()
        IslandDiagnostics.install(this)
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        IslandPrefs.load(this)
        IslandHub._overlay.value = true
        wm = getSystemService(WindowManager::class.java)
        try {
            geometry.value = measure()
            addWindow()
        } catch (e: Throwable) {
            IslandDiagnostics.record(this, e)
        }
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        watchPlacement()
        watchEmojiBar()
        androidx.core.content.ContextCompat.registerReceiver(
            this, plugged, android.content.IntentFilter(android.content.Intent.ACTION_POWER_CONNECTED),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
        androidx.core.content.ContextCompat.registerReceiver(
            this, screenOff, android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_SCREEN_OFF)
                addAction(android.content.Intent.ACTION_SCREEN_ON)
            },
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        geometry.value = measure()
        applyMode(mode, force = true)
        barView?.let { v ->
            runCatching {
                val lp = v.layoutParams as WindowManager.LayoutParams
                lp.height = geometry.value?.statusBar ?: lp.height
                wm?.updateViewLayout(v, lp)
            }
        }
    }

    // ---------- Emoji battery bar ----------

    private fun watchEmojiBar() {
        EmojiBatteryPrefs.load(this)
        lifecycleScope.launch {
            EmojiBatteryPrefs.style.collect { st ->
                val on = BarMode.of(st.bar) != BarMode.Off
                if (on && barView == null) addBar() else if (!on && barView != null) removeBar()
                // Touches only reach the bar when you set gestures on it.
                barView?.let { v ->
                    runCatching {
                        val lp = v.layoutParams as WindowManager.LayoutParams
                        val want = if (st.barGestures) BASE_FLAGS else BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        if (lp.flags != want) { lp.flags = want; wm?.updateViewLayout(v, lp) }
                    }
                }
                if (BarMode.of(st.bar) == BarMode.Full) NetHub.start(this@IslandOverlayService) else NetHub.stop(this@IslandOverlayService)
            }
        }
    }

    private fun addBar() {
        val manager = wm ?: return
        runCatching {
            BatteryHub.start(this)
            val frame = FrameLayout(this).apply {
                setViewTreeLifecycleOwner(this@IslandOverlayService)
                setViewTreeSavedStateRegistryOwner(this@IslandOverlayService)
                setViewTreeViewModelStoreOwner(this@IslandOverlayService)
                // Hide when an app goes full screen (videos, games) and hides the real status bar.
                setOnApplyWindowInsetsListener { v, insets ->
                    if (Build.VERSION.SDK_INT >= 30) {
                        statusBarShown.value = insets.isVisible(android.view.WindowInsets.Type.statusBars())
                    }
                    v.onApplyWindowInsets(insets)
                }
            }
            val compose = ComposeView(this).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    val geo by geometry.collectAsState()
                    val shown by statusBarShown.collectAsState()
                    geo?.let { EmojiStatusBar(it, shown) }
                }
            }
            frame.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                geometry.value?.statusBar ?: (24 * resources.displayMetrics.density).toInt(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                if (EmojiBatteryPrefs.style.value.barGestures) BASE_FLAGS else BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
                title = "Horizon emoji battery"
            }
            manager.addView(frame, p)
            barView = frame
            // Put the island back on top of the new bar.
            val island = root
            val ip = params
            if (island != null && ip != null) {
                runCatching { manager.removeView(island) }
                runCatching { manager.addView(island, ip) }
            }
        }.onFailure { IslandDiagnostics.record(this, it) }
    }

    // ---------- Charging animation ----------

    private var chargeView: FrameLayout? = null
    private val plugged = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: android.content.Intent) {
            EmojiBatteryPrefs.load(c)
            val st = EmojiBatteryPrefs.style.value
            val power = getSystemService(android.os.PowerManager::class.java)
            if (st.chargeAnim == "off" || power?.isInteractive == false) return
            showChargeAnimation()
        }
    }

    private fun showChargeAnimation() {
        val manager = wm ?: return
        if (chargeView != null) return
        runCatching {
            BatteryHub.start(this)
            val frame = FrameLayout(this).apply {
                setViewTreeLifecycleOwner(this@IslandOverlayService)
                setViewTreeSavedStateRegistryOwner(this@IslandOverlayService)
                setViewTreeViewModelStoreOwner(this@IslandOverlayService)
            }
            val compose = ComposeView(this).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    val st by EmojiBatteryPrefs.style.collectAsState()
                    val b by BatteryHub.state.collectAsState()
                    ChargingAnimation(st, b.copy(charging = true)) { hideChargeAnimation() }
                }
            }
            frame.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
                title = "Horizon charging"
            }
            manager.addView(frame, p)
            chargeView = frame
        }.onFailure { IslandDiagnostics.record(this, it) }
    }

    private fun hideChargeAnimation() {
        chargeView?.let { v ->
            runCatching { wm?.removeView(v) }
            BatteryHub.stop(this)
        }
        chargeView = null
    }

    private fun removeBar() {
        barView?.let { v -> runCatching { wm?.removeView(v) } }
        if (barView != null) BatteryHub.stop(this)
        barView = null
        NetHub.stop(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        runCatching { unregisterReceiver(screenOff) }
        runCatching { unregisterReceiver(plugged) }
        hideChargeAnimation()
        IslandHub._overlay.value = false
        root?.let { v -> runCatching { wm?.removeView(v) } }
        root = null
        removeBar()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        super.onDestroy()
    }

    // ---------- Window ----------

    private fun addWindow() {
        val manager = wm ?: return
        val frame = OutsideTouchFrame(this).apply {
            onOutside = { outsideTaps.value++ }
            setViewTreeLifecycleOwner(this@IslandOverlayService)
            setViewTreeSavedStateRegistryOwner(this@IslandOverlayService)
            setViewTreeViewModelStoreOwner(this@IslandOverlayService)
        }
        val compose = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val geo by geometry.collectAsState()
                val placement by IslandPrefs.placement.collectAsState()
                val editing by IslandPrefs.editing.collectAsState()
                val outside by outsideTaps.collectAsState()
                val g = geo
                if (g != null) {
                    DynamicIsland(
                        geometry = g,
                        placement = placement,
                        editing = editing,
                        outsideTaps = outside,
                        onWindow = { applyMode(it) },
                        onPlacement = { t -> IslandPrefs.update(this@IslandOverlayService, t) },
                        onDoneEditing = { IslandPrefs.setEditing(false) },
                    )
                }
            }
        }
        frame.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
            title = "Horizon Dynamic Island"
        }
        params = p
        root = frame
        manager.addView(frame, p)
    }

    /** The island's own position: centre x and top y in screen px. */
    private fun anchor(): Pair<Float, Float> {
        val g = geometry.value ?: return 0f to 0f
        return islandAnchor(g, IslandPrefs.placement.value, resources.displayMetrics.density)
    }

    private fun applyMode(m: IslandWindow, force: Boolean = false) {
        if (m == mode && !force) return
        mode = m
        val manager = wm ?: return
        val view = root ?: return
        val p = params ?: return
        val g = geometry.value ?: return
        val (cx, top) = anchor()
        val compose = view.getChildAt(0)
        when (m) {
            IslandWindow.Hidden, IslandWindow.Compact -> {
                p.width = WindowManager.LayoutParams.WRAP_CONTENT
                p.height = WindowManager.LayoutParams.WRAP_CONTENT
                p.x = (cx - g.screenW / 2f).toInt()
                p.y = top.toInt()
                compose?.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            }
            IslandWindow.Wide -> {
                p.width = WindowManager.LayoutParams.MATCH_PARENT
                p.height = WindowManager.LayoutParams.WRAP_CONTENT
                p.x = 0
                p.y = top.toInt()
                compose?.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            }
            IslandWindow.Full -> {
                p.width = WindowManager.LayoutParams.MATCH_PARENT
                p.height = WindowManager.LayoutParams.MATCH_PARENT
                p.x = 0
                p.y = 0
                compose?.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            }
        }
        p.flags = if (m == IslandWindow.Hidden) BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else BASE_FLAGS
        runCatching { manager.updateViewLayout(view, p) }
    }

    private fun watchPlacement() {
        lifecycleScope.launch {
            IslandPrefs.placement.collect { applyMode(mode, force = true) }
        }
    }

    // ---------- Measuring the camera ----------

    private fun measure(): IslandGeometry {
        val manager = wm ?: getSystemService(WindowManager::class.java)
        val w: Int
        val h: Int
        val cutout: DisplayCutout?
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = manager.currentWindowMetrics
            w = metrics.bounds.width()
            h = metrics.bounds.height()
            cutout = metrics.windowInsets.displayCutout
        } else {
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            w = size.x
            h = size.y
            cutout = display.cutout
        }
        val hole: RectF? = cutout?.let { c ->
            val path = if (Build.VERSION.SDK_INT >= 31) c.cutoutPath else null
            val r = RectF()
            if (path != null && !path.isEmpty) {
                path.computeBounds(r, true)
            } else {
                val b = c.boundingRectTop.takeUnless { it.isEmpty } ?: return@let null
                r.set(b)
                // Some phones report a box down to the status bar; keep a square at the bottom of it
                if (r.height() > r.width() * 1.3f) r.top = r.bottom - r.width()
            }
            r.takeIf { it.width() > 0 && it.top < h * 0.15f && it.width() < w * 0.5f }
        }
        val sbId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBar = if (sbId > 0) resources.getDimensionPixelSize(sbId) else (24 * resources.displayMetrics.density).toInt()
        return IslandGeometry(w, h, hole, statusBar, w > h)
    }

    companion object {
        @Volatile private var instance: IslandOverlayService? = null

        /** Turns the screen off and locks, like the power button. False if the helper isn't on. */
        fun lockScreen(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) == true

        /** Notifications, quick settings, recents, screenshot… False if the helper isn't on. */
        fun globalAction(action: Int): Boolean = instance?.performGlobalAction(action) == true

        private const val BASE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        /** Opens Horizon's own switch in Accessibility settings (or the list, on phones that can't). */
        fun openSwitch(context: Context) {
            val comp = android.content.ComponentName(context, IslandOverlayService::class.java).flattenToString()
            val direct = android.content.Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(android.content.Intent.EXTRA_COMPONENT_NAME, comp)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(direct) }.isSuccess) return
            val list = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                // Pixel settings scroll to and highlight the item with these.
                .putExtra(":settings:fragment_args_key", comp)
                .putExtra(":settings:show_fragment_args", android.os.Bundle().apply { putString(":settings:fragment_args_key", comp) })
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(list) }
        }

        /** Horizon's app page, where "Allow restricted settings" lives (⋮ menu). */
        fun openAppPage(context: Context) {
            runCatching {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + context.packageName))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        fun isEnabled(context: Context): Boolean {
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = android.content.ComponentName(context, IslandOverlayService::class.java)
            return enabled.split(':').any {
                android.content.ComponentName.unflattenFromString(it) == me
            }
        }
    }
}

/** Tells us about taps outside the island (to shrink it), while letting them through to the app. */
class OutsideTouchFrame(context: Context) : FrameLayout(context) {
    var onOutside: (() -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            onOutside?.invoke()
            return false
        }
        return super.dispatchTouchEvent(ev)
    }
}

/** Centre x and top y of the island (px). Shared by the window and the drawing so they agree. */
fun islandAnchor(g: IslandGeometry, p: IslandPlacement, density: Float): Pair<Float, Float> {
    val hole = g.hole
    if (p.onCamera && hole != null) {
        val pad = 5f * density * p.scale
        val pillH = maxOf(hole.height() + pad * 2, 30f * density * p.scale)
        return hole.centerX() to maxOf(hole.centerY() - pillH / 2, 2f * density)
    }
    val cx = if (p.centerX >= 0) p.centerX.coerceIn(0f, g.screenW.toFloat()) else g.screenW / 2f
    val top = if (p.top >= 0) p.top.coerceIn(0f, g.screenH - 60f * density) else maxOf(g.statusBar * 0.15f, 4f * density)
    return cx to top
}
