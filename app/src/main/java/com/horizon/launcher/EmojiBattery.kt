package com.horizon.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.telephony.TelephonyManager
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

// ---------- Themes ----------

/** A set of emoji that change as the battery goes down, plus one for charging. */
enum class BatteryTheme(val id: String, val label: String, private val steps: List<String>, val charging: String) {
    Faces("faces", "Faces", listOf("😎", "😄", "🙂", "😐", "😟", "😫"), "🤩"),
    Hearts("hearts", "Hearts", listOf("💖", "❤️", "🧡", "💛", "🤍", "💔"), "💘"),
    Cats("cats", "Cats", listOf("😻", "😸", "😺", "😼", "😿", "🙀"), "😽"),
    Animals("animals", "Animals", listOf("🦁", "🐯", "🐶", "🐱", "🐭", "🐌"), "🐆"),
    Moon("moon", "Moon", listOf("🌕", "🌖", "🌗", "🌘", "🌒", "🌑"), "🌟"),
    Weather("weather", "Weather", listOf("☀️", "🌤️", "⛅", "🌥️", "🌧️", "⛈️"), "🌈"),
    Plants("plants", "Plants", listOf("🌳", "🌲", "🌿", "🌱", "🍂", "🥀"), "🌻"),
    Food("food", "Food", listOf("🍕", "🍔", "🌮", "🍟", "🍪", "🫙"), "🍩"),
    Space("space", "Space", listOf("🚀", "🛸", "🪐", "🌍", "☄️", "🕳️"), "⚡"),
    Fire("fire", "Fire", listOf("🔥", "🔥", "🌶️", "🕯️", "💨", "🧊"), "⚡"),
    Custom("custom", "Your emoji", emptyList(), "⚡");

    /** The emoji for this level (0–100). */
    fun emojiFor(level: Int, isCharging: Boolean, custom: String): String {
        if (this == Custom) return if (isCharging && custom.isBlank()) charging else custom.ifBlank { "🔋" }
        if (isCharging) return charging
        val i = when {
            level >= 85 -> 0
            level >= 65 -> 1
            level >= 45 -> 2
            level >= 30 -> 3
            level >= 15 -> 4
            else -> 5
        }
        return steps[i]
    }

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: Faces
    }
}

/** How the battery looks. */
enum class BatteryLayout(val id: String, val label: String) {
    Beside("beside", "Emoji + battery"),
    Inside("inside", "Emoji in battery"),
    EmojiOnly("emoji", "Emoji + %"),
    Big("big", "Big emoji");

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: Beside
    }
}

/** Where it shows at the top of the screen. */
enum class BarMode(val id: String, val label: String) {
    Off("off", "Off"),
    Sticker("sticker", "Next to the camera"),
    Full("full", "Full status bar");

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: Off
    }
}

data class EmojiBatteryStyle(
    val theme: String = BatteryTheme.Faces.id,
    val layout: String = BatteryLayout.Beside.id,
    val bar: String = BarMode.Off.id,
    val showPercent: Boolean = true,
    /** The single emoji for the "Your emoji" theme. */
    val custom: String = "",
    /** Fill colour: "level" (green → red), or an ARGB number. */
    val fill: String = "level",
    /** Full status bar background (ARGB). */
    val background: Long = 0xFF000000,
    /** 0.8 – 1.4 */
    val scale: Float = 1f,
    /** Bounce while charging. */
    val animate: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("theme", theme).put("layout", layout).put("bar", bar).put("pct", showPercent)
        .put("custom", custom).put("fill", fill).put("bg", background).put("scale", scale.toDouble()).put("anim", animate)

    companion object {
        fun fromJson(o: JSONObject) = EmojiBatteryStyle(
            theme = o.optString("theme", BatteryTheme.Faces.id),
            layout = o.optString("layout", BatteryLayout.Beside.id),
            bar = o.optString("bar", BarMode.Off.id),
            showPercent = o.optBoolean("pct", true),
            custom = o.optString("custom", ""),
            fill = o.optString("fill", "level"),
            background = o.optLong("bg", 0xFF000000),
            scale = o.optDouble("scale", 1.0).toFloat().coerceIn(0.8f, 1.4f),
            animate = o.optBoolean("anim", true),
        )
    }
}

/** The emoji battery's settings, shared by the launcher, the status bar overlay and the widget. */
object EmojiBatteryPrefs {
    private const val FILE = "emoji_battery"
    private val _style = MutableStateFlow(EmojiBatteryStyle())
    val style: StateFlow<EmojiBatteryStyle> = _style.asStateFlow()
    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("style", null)
        if (raw != null) runCatching { _style.value = EmojiBatteryStyle.fromJson(JSONObject(raw)) }
    }

    fun update(context: Context, change: (EmojiBatteryStyle) -> EmojiBatteryStyle) {
        load(context)
        val next = change(_style.value)
        _style.value = next
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("style", next.toJson().toString()).apply()
        EmojiBatteryWidget.updateAll(context)
    }
}

// ---------- Live readings ----------

data class BatteryState(val level: Int = 100, val charging: Boolean = false, val full: Boolean = false)

/** Battery level and charging, kept up to date while anything is watching. */
object BatteryHub {
    private val _state = MutableStateFlow(BatteryState())
    val state: StateFlow<BatteryState> = _state.asStateFlow()
    private var users = 0
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            read(i)
            EmojiBatteryWidget.updateAll(c)
        }
    }

    private fun read(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        if (level < 0 || scale <= 0) return
        _state.value = BatteryState(
            level = level * 100 / scale,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            full = status == BatteryManager.BATTERY_STATUS_FULL,
        )
    }

    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        if (users++ == 0) {
            app.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::read)
        }
    }

    @Synchronized
    fun stop(context: Context) {
        if (users > 0 && --users == 0) runCatching { context.applicationContext.unregisterReceiver(receiver) }
    }

    /** One-off reading (for the widget when nothing is watching). */
    fun now(context: Context): BatteryState {
        context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::read)
        return _state.value
    }
}

data class NetState(val wifi: Int = -1, val cell: Int = -1, val airplane: Boolean = false)

/** Wi-Fi and mobile signal bars for the full status bar (0–4, or -1 when off). */
object NetHub {
    private val _state = MutableStateFlow(NetState())
    val state: StateFlow<NetState> = _state.asStateFlow()
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start(context: Context) {
        if (callback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh(context, caps)
            override fun onLost(network: Network) = refresh(context, null)
        }
        callback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
    }

    fun stop(context: Context) {
        val cb = callback ?: return
        callback = null
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
    }

    /** Called from the status bar every few seconds for the mobile signal. */
    fun pollCell(context: Context) {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val level = runCatching { tm?.signalStrength?.level ?: -1 }.getOrDefault(-1)
        _state.value = _state.value.copy(cell = if (tm?.simState == TelephonyManager.SIM_STATE_READY) level.coerceIn(0, 4) else -1)
    }

    private fun refresh(context: Context, caps: NetworkCapabilities?) {
        val wifi = if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            val rssi = caps.signalStrength
            when {
                rssi == Int.MIN_VALUE || rssi == 0 -> 4
                rssi >= -55 -> 4
                rssi >= -66 -> 3
                rssi >= -77 -> 2
                rssi >= -88 -> 1
                else -> 0
            }
        } else -1
        _state.value = _state.value.copy(wifi = wifi)
        pollCell(context)
    }
}

// ---------- Drawing ----------

private fun levelColor(level: Int, charging: Boolean): Color = when {
    charging -> Color(0xFF4CD964)
    level >= 50 -> Color(0xFF4CD964)
    level >= 20 -> Color(0xFFFFCC00)
    else -> Color(0xFFFF3B30)
}

private fun fillColor(style: EmojiBatteryStyle, b: BatteryState): Color =
    if (style.fill == "level") levelColor(b.level, b.charging) else Color(style.fill.toLongOrNull() ?: 0xFF4CD964)

/** The emoji battery, as it appears in the status bar and the settings preview. */
@Composable
fun EmojiBatteryGlyph(style: EmojiBatteryStyle, b: BatteryState, ink: Color, height: Dp = 16.dp) {
    val theme = BatteryTheme.of(style.theme)
    val emoji = theme.emojiFor(b.level, b.charging, style.custom)
    val h = height * style.scale
    val bounce by if (b.charging && style.animate) {
        rememberInfiniteTransition(label = "charge").animateFloat(
            1f, 1.15f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "charge",
        )
    } else {
        animateFloatAsState(1f, label = "still")
    }
    val emojiSize = (h.value * 0.95f).sp
    val pctStyle = TextStyle(color = ink, fontSize = (h.value * 0.72f).sp, fontWeight = FontWeight.SemiBold)
    val emojiView = @Composable {
        Text(emoji, fontSize = emojiSize, modifier = Modifier.graphicsLayer { scaleX = bounce; scaleY = bounce })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (BatteryLayout.of(style.layout)) {
            BatteryLayout.Beside -> {
                emojiView()
                Spacer(Modifier.width(h * 0.25f))
                BatteryBody(b, fillColor(style, b), ink, h, if (style.showPercent) "${b.level}" else null)
            }
            BatteryLayout.Inside -> {
                Box(contentAlignment = Alignment.Center) {
                    BatteryBody(b, fillColor(style, b), ink, h * 1.25f, null, width = h * 2.9f)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.graphicsLayer { translationX = -h.value * 0.1f }) {
                        Text(emoji, fontSize = (h.value * 0.8f).sp, modifier = Modifier.graphicsLayer { scaleX = bounce; scaleY = bounce })
                        if (style.showPercent) Text(" ${b.level}", style = pctStyle.copy(fontSize = (h.value * 0.62f).sp, color = Color.Black))
                    }
                }
            }
            BatteryLayout.EmojiOnly -> {
                emojiView()
                if (style.showPercent) Text(" ${b.level}%", style = pctStyle)
            }
            BatteryLayout.Big -> {
                Text(emoji, fontSize = (h.value * 1.3f).sp, modifier = Modifier.graphicsLayer { scaleX = bounce; scaleY = bounce })
                if (style.showPercent) Text(" ${b.level}%", style = pctStyle)
            }
        }
    }
}

@Composable
private fun BatteryBody(b: BatteryState, fill: Color, ink: Color, h: Dp, label: String?, width: Dp = h * 2.1f) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.width(width).height(h)) {
        Canvas(Modifier.width(width).height(h)) {
            val nub = size.height * 0.16f
            val bodyW = size.width - nub - 1.dp.toPx()
            val r = size.height * 0.3f
            val stroke = (size.height * 0.09f).coerceAtLeast(1.dp.toPx())
            drawRoundRect(ink.copy(alpha = 0.55f), Offset(stroke / 2, stroke / 2), Size(bodyW - stroke, size.height - stroke), CornerRadius(r), style = Stroke(stroke))
            val inset = stroke * 1.6f
            val fw = (bodyW - inset * 2) * (b.level.coerceIn(0, 100) / 100f)
            if (fw > 0) drawRoundRect(fill, Offset(inset, inset), Size(fw, size.height - inset * 2), CornerRadius(r * 0.6f))
            drawRoundRect(
                ink.copy(alpha = 0.55f),
                Offset(bodyW + 0.5.dp.toPx(), size.height * 0.32f),
                Size(nub, size.height * 0.36f),
                CornerRadius(nub / 2),
            )
        }
        if (label != null) {
            Text(
                label,
                style = TextStyle(
                    color = if (b.level > 55) Color.Black else ink,
                    fontSize = (h.value * 0.62f).sp,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = Modifier.graphicsLayer { translationX = -h.value * 0.08f },
            )
        }
    }
}

/** The same battery as a picture, for the home-screen widget. */
fun renderEmojiBattery(context: Context, style: EmojiBatteryStyle, b: BatteryState, widthPx: Int, heightPx: Int): Bitmap {
    val bmp = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val theme = BatteryTheme.of(style.theme)
    val emoji = theme.emojiFor(b.level, b.charging, style.custom)
    val h = heightPx.toFloat()
    val w = widthPx.toFloat()
    // Card background
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6202124.toInt() }
    c.drawRoundRect(RectF(0f, 0f, w, h), h * 0.28f, h * 0.28f, bg)
    // Emoji
    val ep = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = h * 0.52f; textAlign = Paint.Align.CENTER }
    val emojiX = h * 0.5f
    c.drawText(emoji, emojiX, h * 0.5f - (ep.descent() + ep.ascent()) / 2, ep)
    // Battery body
    val left = h * 0.95f
    val right = w - h * 0.22f
    val bh = h * 0.36f
    val top = (h - bh) / 2
    val nub = bh * 0.18f
    val stroke = bh * 0.1f
    val ink = android.graphics.Color.WHITE
    val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; alpha = 150; this.style = Paint.Style.STROKE; strokeWidth = stroke }
    c.drawRoundRect(RectF(left, top, right - nub, top + bh), bh * 0.3f, bh * 0.3f, sp)
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor(style, b).toArgb() }
    val inset = stroke * 1.6f
    val fw = (right - nub - left - inset * 2) * b.level.coerceIn(0, 100) / 100f
    if (fw > 0) c.drawRoundRect(RectF(left + inset, top + inset, left + inset + fw, top + bh - inset), bh * 0.18f, bh * 0.18f, fillPaint)
    val np = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; alpha = 150 }
    c.drawRoundRect(RectF(right - nub + stroke, top + bh * 0.3f, right, top + bh * 0.7f), nub, nub, np)
    if (style.showPercent) {
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (b.level > 55) android.graphics.Color.BLACK else ink
            textSize = bh * 0.62f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val text = if (b.charging) "⚡${b.level}%" else "${b.level}%"
        c.drawText(text, (left + right - nub) / 2, top + bh / 2 - (tp.descent() + tp.ascent()) / 2, tp)
    }
    return bmp
}
