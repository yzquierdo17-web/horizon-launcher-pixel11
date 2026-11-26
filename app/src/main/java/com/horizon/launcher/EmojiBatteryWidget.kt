package com.horizon.launcher

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews

/** Home-screen widget: your battery as an emoji, redrawn when the level or charging changes. */
class EmojiBatteryWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        last = null
        ids.forEach { draw(context, manager, it, BatteryHub.now(context)) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        draw(context, manager, id, BatteryHub.now(context))
    }

    companion object {
        @Volatile private var last: Triple<Int, Boolean, EmojiBatteryStyle>? = null

        /** Redraws every emoji battery widget, if anything they show has changed. */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, EmojiBatteryWidget::class.java)) }.getOrNull()
            if (ids == null || ids.isEmpty()) return
            EmojiBatteryPrefs.load(context)
            val b = BatteryHub.state.value
            val key = Triple(b.level, b.charging, EmojiBatteryPrefs.style.value)
            if (key == last) return
            last = key
            ids.forEach { draw(context, manager, it, b) }
        }

        private fun draw(context: Context, manager: AppWidgetManager, id: Int, b: BatteryState) {
            runCatching {
                EmojiBatteryPrefs.load(context)
                val opts = manager.getAppWidgetOptions(id)
                val d = context.resources.displayMetrics.density
                val wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0).takeIf { it > 0 } ?: 160
                val hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0).takeIf { it > 0 } ?: 60
                // Keep a battery-like shape whatever the cell size.
                val h = (minOf(hDp, (wDp / 2.6f).toInt()).coerceIn(36, 120) * d).toInt()
                val w = (minOf(wDp.toFloat(), hDp * 3.2f).coerceAtLeast(90f) * d).toInt()
                val bmp = renderEmojiBattery(context, EmojiBatteryPrefs.style.value, b, w, h)
                val views = RemoteViews(context.packageName, R.layout.widget_emoji_battery)
                views.setImageViewBitmap(R.id.emoji_battery_image, bmp)
                // Tap opens the phone's battery page.
                val open = android.app.PendingIntent.getActivity(
                    context, 41, Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                )
                views.setOnClickPendingIntent(R.id.emoji_battery_root, open)
                manager.updateAppWidget(id, views)
            }
        }
    }
}
