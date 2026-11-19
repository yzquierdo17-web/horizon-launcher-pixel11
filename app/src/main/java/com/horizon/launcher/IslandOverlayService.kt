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
    private val outsideTaps = MutableStateFlow(0)

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        IslandPrefs.load(this)
        IslandHub._overlay.value = true
        wm = getSystemService(WindowManager::class.java)
        geometry.value = measure()
        addWindow()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        watchPlacement()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        geometry.value = measure()
        applyMode(mode, force = true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        IslandHub._overlay.value = false
        root?.let { v -> runCatching { wm?.removeView(v) } }
        root = null
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
        runCatching { manager.addView(frame, p) }
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
        private const val BASE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

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
