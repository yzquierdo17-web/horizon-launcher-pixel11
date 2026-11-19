package com.horizon.launcher

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlin.math.abs

/**
 * Horizon's own lock screen: just the dial, covering Pixel's clock. Swipe up to unlock
 * (Android then asks for your fingerprint, face or PIN as usual). Shown when the screen turns off,
 * by [IslandOverlayService], and closed as soon as the phone is unlocked any other way.
 */
class LockDialActivity : Activity() {

    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }

    private val events = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_USER_PRESENT -> finish()
                Intent.ACTION_SCREEN_ON -> if (!keyguard.isKeyguardLocked) finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        showing = true
        setContentView(DialLockView(this) { unlock() })
        ContextCompat.registerReceiver(
            this, events,
            IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    override fun onResume() {
        super.onResume()
        // Screen is on but the phone isn't locked: get out of the way
        val interactive = getSystemService(android.os.PowerManager::class.java).isInteractive
        if (interactive && !keyguard.isKeyguardLocked) finish()
    }

    override fun onDestroy() {
        showing = false
        runCatching { unregisterReceiver(events) }
        super.onDestroy()
    }

    @Deprecated("Back does nothing on the lock screen")
    override fun onBackPressed() = Unit

    private fun unlock() {
        if (!keyguard.isKeyguardLocked) {
            finish()
            return
        }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = finish()
        })
    }

    companion object {
        @Volatile private var showing = false

        /** Called when the screen turns off, and again when it turns on still locked. */
        fun showIfWanted(context: Context, screenOn: Boolean = false) {
            if (!LockDialPrefs.enabled(context) || showing) return
            if (screenOn && !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
            // Never cover a call (the screen also turns off against your ear)
            val audio = context.getSystemService(AudioManager::class.java)
            if (IslandHub.call.value != null || audio.mode == AudioManager.MODE_IN_CALL ||
                audio.mode == AudioManager.MODE_IN_COMMUNICATION || audio.mode == AudioManager.MODE_RINGTONE
            ) return
            runCatching {
                context.startActivity(
                    Intent(context, LockDialActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                    ),
                )
            }
        }
    }
}

object LockDialPrefs {
    fun enabled(context: Context) =
        context.getSharedPreferences("island", Context.MODE_PRIVATE).getBoolean("lockDial", false)

    fun set(context: Context, on: Boolean) =
        context.getSharedPreferences("island", Context.MODE_PRIVATE).edit().putBoolean("lockDial", on).apply()
}

/** The dial, drawn every frame while visible, with a swipe-up hint at the bottom. */
private class DialLockView(context: Context, private val onUnlock: () -> Unit) : View(context) {
    private val renderer = DialRenderer(context)
    private val density = resources.displayMetrics.density
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 14f * density * resources.configuration.fontScale
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 4f * density
    }
    private var downY = 0f
    private var dragY = 0f

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            renderer.battery = if (level >= 0 && scale > 0) level * 100 / scale else -1
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ContextCompat.registerReceiver(context, battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onDetachedFromWindow() {
        runCatching { context.unregisterReceiver(battery) }
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(0f, dragY.coerceAtMost(0f) * 0.5f)
        renderer.draw(canvas, width, height, System.currentTimeMillis(), DateFormat.is24HourFormat(context))
        canvas.restore()
        val y = height - 56f * density
        canvas.drawLine(width / 2f - 22f * density, y + 24f * density, width / 2f + 22f * density, y + 24f * density, bar)
        canvas.drawText("Swipe up to unlock", width / 2f, y, hint)
        if (isShown) postInvalidateOnAnimation()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.y
                dragY = 0f
            }
            MotionEvent.ACTION_MOVE -> dragY = e.y - downY
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val up = -(e.y - downY)
                dragY = 0f
                if (e.actionMasked == MotionEvent.ACTION_UP && up > 120f * density) onUnlock()
                else if (e.actionMasked == MotionEvent.ACTION_UP && abs(up) < 10f * density) performClick()
            }
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
