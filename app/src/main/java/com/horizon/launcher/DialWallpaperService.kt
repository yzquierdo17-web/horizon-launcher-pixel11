package com.horizon.launcher

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.text.format.DateFormat
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * "Horizon Dial" live wallpaper: the rotating dial clock, for the lock screen (or Home).
 * Animates ~30 times a second only while visible; stops completely when the screen is off.
 */
class DialWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = DialEngine()

    private inner class DialEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val renderer = DialRenderer(this@DialWallpaperService)
        private var visible = false
        private var width = 0
        private var height = 0

        private val frame = object : Runnable {
            override fun run() {
                draw()
                if (visible) handler.postDelayed(this, FRAME_MS)
            }
        }

        // Battery percentage for the small line at the bottom.
        private val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                renderer.battery = if (level >= 0 && scale > 0) level * 100 / scale else -1
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            ContextCompat.registerReceiver(
                this@DialWallpaperService,
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }

        override fun onDestroy() {
            handler.removeCallbacks(frame)
            runCatching { unregisterReceiver(batteryReceiver) }
            super.onDestroy()
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (isVisible) renderer.reloadTheme(this@DialWallpaperService)
            handler.removeCallbacks(frame)
            if (isVisible) handler.post(frame)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            width = w
            height = h
            draw()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(frame)
            super.onSurfaceDestroyed(holder)
        }

        private fun draw() {
            if (width == 0 || height == 0) return
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockHardwareCanvas()
                if (canvas != null) {
                    renderer.draw(
                        canvas, width, height, System.currentTimeMillis(),
                        DateFormat.is24HourFormat(this@DialWallpaperService),
                    )
                }
            } catch (_: Exception) {
                // Surface went away mid-frame; the next frame will retry.
            } finally {
                if (canvas != null) runCatching { holder.unlockCanvasAndPost(canvas) }
            }
        }
    }

    companion object {
        private const val FRAME_MS = 33L

        /** Opens Android's preview screen for this wallpaper, where you choose lock screen, Home or both. */
        fun previewIntent(context: Context): Intent =
            Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(
                    android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(context, DialWallpaperService::class.java),
                )
    }
}

/** Draws the dial with plain Android graphics (same look as the Home screen dial clock). */
class DialRenderer(context: Context) {
    private val density = context.resources.displayMetrics.density
    private val fontScale = context.resources.configuration.fontScale
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = v * density * fontScale

    var battery = -1
    var background = HorizonLook.saved(context).dial

    /** Re-read the chosen look (it may have changed in Settings). */
    fun reloadTheme(context: Context) { background = HorizonLook.saved(context).dial }

    // Inter, matching the Home screen dial (falls back to the phone's font).
    private val inter: Typeface? = runCatching { androidx.core.content.res.ResourcesCompat.getFont(context, R.font.inter) }.getOrNull()
    private fun face(weight: Int, fallback: String, style: Int = Typeface.NORMAL): Typeface =
        inter?.let { Typeface.create(it, weight, false) } ?: Typeface.create(fallback, style)
    private val light = face(300, "sans-serif-light")
    private val regular = face(400, "sans-serif")
    private val medium = face(500, "sans-serif-medium")
    private val bold = face(700, "sans-serif", Typeface.BOLD)

    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; typeface = regular }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); strokeCap = Paint.Cap.ROUND }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEBFFFFFF.toInt(); style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
    private val dayFmt = DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())
    private val rect = RectF()

    fun draw(c: Canvas, w: Int, h: Int, nowMs: Long, is24h: Boolean) {
        c.drawColor(background)
        val t = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault())
        val sec = t.second + (nowMs % 1000) / 1000f
        val min = t.minute + sec / 60f

        val rMin = dp(84f)
        val rSec = dp(126f)
        val cx = dp(52f)
        val cy = h * 0.44f // a little above centre, clear of notifications and Pixel's own clock

        drawScale(c, cx, cy, rMin, min, sp(15f), dp(14f), dp(6f), dp(3f), 0.35f)
        drawScale(c, cx, cy, rSec, sec, sp(12f), dp(13f), dp(9f), dp(5f), 0.6f)

        // Pill around the current minute and second.
        val pillH = dp(34f)
        val pillLeft = cx + rMin - dp(19f)
        val pillRight = cx + rSec + dp(17f)
        pill.strokeWidth = dp(1.5f)
        rect.set(pillLeft, cy - pillH / 2f, pillRight, cy + pillH / 2f)
        c.drawRoundRect(rect, pillH / 2f, pillH / 2f, pill)

        text.textAlign = Paint.Align.CENTER
        drawCentered(c, pad(t.minute), cx + rMin, cy, text.apply { typeface = medium; textSize = sp(20f); alpha = 255 })
        drawCentered(c, pad(t.second), cx + rSec, cy, text.apply { typeface = regular; textSize = sp(13f) })

        // Big hour, right-aligned against the pill.
        val hour = if (is24h) t.hour else (t.hour % 12).let { if (it == 0) 12 else it }
        text.textAlign = Paint.Align.RIGHT
        text.typeface = light
        text.textSize = sp(58f)
        text.letterSpacing = -0.03f
        drawCentered(c, pad(hour), pillLeft - dp(10f), cy, text)
        text.letterSpacing = 0f

        // Date in small spaced capitals.
        val dateX = pillRight + dp(22f)
        text.textAlign = Paint.Align.LEFT
        text.typeface = medium
        text.textSize = sp(10f)
        text.letterSpacing = 0.2f
        text.alpha = 217
        val dateStr = t.format(dateFmt).uppercase()
        val dayStr = t.format(dayFmt).uppercase()
        if (dateX + maxOf(text.measureText(dateStr), text.measureText(dayStr) * 1.15f) <= w) {
            c.drawText(dateStr, dateX, cy - dp(5f), text)
            text.typeface = bold
            text.textSize = sp(11f)
            text.letterSpacing = 0.22f
            text.alpha = 255
            c.drawText(dayStr, dateX, cy + dp(12f), text)
        }
        text.letterSpacing = 0f
        text.alpha = 255

        // Small battery line near the bottom.
        if (battery >= 0) {
            text.textAlign = Paint.Align.CENTER
            text.typeface = medium
            text.textSize = sp(10f)
            text.alpha = 160
            c.drawText("$battery%", w / 2f, h - dp(120f), text)
            text.alpha = 255
        }
    }

    private fun drawScale(
        c: Canvas, cx: Float, cy: Float, radius: Float, value: Float, textSize: Float,
        tickOffset: Float, majorTick: Float, minorTick: Float, tickAlpha: Float,
    ) {
        val span = 108f
        label.textSize = textSize
        for (v in 0 until 60) {
            var delta = (v - value) * 6f
            delta = ((delta % 360f) + 540f) % 360f - 180f
            val d = abs(delta)
            if (d > span) continue
            val fade = (1f - (d / span).pow(2.2f)).coerceIn(0f, 1f)
            val rad = Math.toRadians(-delta.toDouble())
            val cs = cos(rad).toFloat()
            val sn = sin(rad).toFloat()

            val r1 = radius + tickOffset
            val r2 = r1 + if (v % 5 == 0) majorTick else minorTick
            tick.strokeWidth = if (v % 5 == 0) dp(1.4f) else dp(1f)
            tick.alpha = (255 * tickAlpha * fade).toInt()
            c.drawLine(cx + cs * r1, cy + sn * r1, cx + cs * r2, cy + sn * r2, tick)

            if (v % 5 == 0 && d > 8f) {
                label.alpha = (255 * 0.9f * fade).toInt()
                drawCentered(c, pad(v), cx + cs * radius, cy + sn * radius, label)
            }
        }
    }

    private fun drawCentered(c: Canvas, s: String, x: Float, y: Float, p: Paint) {
        val fm = p.fontMetrics
        c.drawText(s, x, y - (fm.ascent + fm.descent) / 2f, p)
    }

    private fun pad(v: Int) = v.toString().padStart(2, '0')
}
