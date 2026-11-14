package com.slate.launcher.data

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.UserManager
import android.util.SizeF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.text.Collator
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

@Immutable
data class WidgetOption(val info: AppWidgetProviderInfo, val label: String, val appLabel: String)

/** Hosts other apps' widgets on Slate's Home screen. */
class WidgetRepository(private val context: Context) {

    private val manager = AppWidgetManager.getInstance(context)
    private val userManager = context.getSystemService(UserManager::class.java)
    val host = SlateWidgetHost(context)

    /** Widget waiting on its configuration screen (set between configure start and result). */
    var pendingConfigureId: Int = INVALID
        private set

    fun startListening() { runCatching { host.startListening() } }
    fun stopListening() { runCatching { host.stopListening() } }

    fun info(id: Int): AppWidgetProviderInfo? = runCatching { manager.getAppWidgetInfo(id) }.getOrNull()

    /** Every widget on the phone (personal and work), grouped and sorted by app. */
    fun options(): List<WidgetOption> {
        val pm = context.packageManager
        val collator = Collator.getInstance()
        return userManager.userProfiles.flatMap { user ->
            runCatching { manager.getInstalledProvidersForProfile(user) }.getOrNull().orEmpty()
        }.map { info ->
            val appLabel = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString()
            }.getOrDefault(info.provider.packageName)
            WidgetOption(info, info.loadLabel(pm) ?: appLabel, appLabel)
        }.sortedWith(compareBy<WidgetOption, String>(collator) { it.appLabel }.thenBy(collator) { it.label })
    }

    fun preview(option: WidgetOption, maxPx: Int): ImageBitmap? = runCatching {
        val dpi = context.resources.displayMetrics.densityDpi
        val drawable = option.info.loadPreviewImage(context, dpi) ?: option.info.loadIcon(context, dpi)
        val w = drawable.intrinsicWidth.coerceAtLeast(1)
        val h = drawable.intrinsicHeight.coerceAtLeast(1)
        val scale = min(1f, maxPx.toFloat() / maxOf(w, h))
        drawable.toBitmap((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
            .asImageBitmap()
    }.getOrNull()

    fun allocate(): Int = host.allocateAppWidgetId()

    /** True if Slate may bind without asking; otherwise launch [bindIntent] for the user's OK. */
    fun bindIfAllowed(id: Int, info: AppWidgetProviderInfo): Boolean =
        runCatching { manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null) }.getOrDefault(false)

    fun bindIntent(id: Int, info: AppWidgetProviderInfo): Intent =
        Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)

    /**
     * Opens the widget's own setup screen if it has a required one.
     * Returns false when there's nothing to configure (add the widget right away).
     */
    fun startConfigureIfNeeded(activity: Activity, id: Int): Boolean {
        val info = info(id) ?: return false
        val optional = (info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0
        if (info.configure == null || optional) return false
        pendingConfigureId = id
        return runCatching {
            host.startAppWidgetConfigureActivityForResult(activity, id, 0, REQUEST_CONFIGURE, null)
        }.isSuccess.also { if (!it) pendingConfigureId = INVALID }
    }

    fun consumePendingConfigure(): Int = pendingConfigureId.also { pendingConfigureId = INVALID }

    fun delete(id: Int) { runCatching { host.deleteAppWidgetId(id) } }

    /** Starting height in rows (1–4) from the widget's declared minimum size. */
    fun defaultRows(id: Int): Int {
        val minHeightPx = info(id)?.minHeight ?: return 2
        val dp = minHeightPx / context.resources.displayMetrics.density
        return ((dp + 8f) / (ROW_HEIGHT_DP + 8f)).let { kotlin.math.ceil(it).toInt() }.coerceIn(1, 4)
    }

    companion object {
        const val INVALID = AppWidgetManager.INVALID_APPWIDGET_ID
        const val REQUEST_CONFIGURE = 4201
        const val ROW_HEIGHT_DP = 96f
    }
}

class SlateWidgetHost(context: Context) : AppWidgetHost(context.applicationContext, HOST_ID) {
    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
        SlateWidgetHostView(context)

    private companion object { const val HOST_ID = 0x51A7E }
}

/**
 * Widget view that still lets you long-press it for Slate's menu (resize / remove),
 * while taps and scrolls keep going to the widget itself.
 */
class SlateWidgetHostView(context: Context) : AppWidgetHostView(context) {
    var onLongPress: (() -> Unit)? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false
    private var lastSize: SizeF? = null

    private val longPressRunnable = Runnable {
        longPressed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongPress?.invoke()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) longPressed = false
        if (longPressed) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE ->
                if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) removeCallbacks(longPressRunnable)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPressRunnable)
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (longPressed) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) longPressed = false
            return true
        }
        return super.onTouchEvent(ev)
    }

    /** Tell the widget how big it is so it can pick the right layout (Android 12+ responsive widgets). */
    fun updateSize(widthDp: Float, heightDp: Float) {
        val size = SizeF(widthDp, heightDp)
        if (size == lastSize) return
        lastSize = size
        runCatching { updateAppWidgetSize(Bundle(), listOf(size)) }
    }
}
