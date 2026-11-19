package com.horizon.launcher

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the island sits and how big it is. Shared by Horizon's settings and the overlay service
 * (same process), and saved so it survives restarts.
 */
data class IslandPlacement(
    val enabled: Boolean = true,
    /** True: wrap the front camera. False: sit at [centerX], [top] (screen px, portrait). */
    val onCamera: Boolean = true,
    val centerX: Float = -1f,
    val top: Float = -1f,
    /** 0.8 – 1.3 */
    val scale: Float = 1f,
    /** Keep a small black pill showing when nothing's happening, like iPhone. */
    val showIdle: Boolean = true,
)

object IslandPrefs {
    private const val FILE = "island"
    private val _placement = MutableStateFlow(IslandPlacement())
    val placement: StateFlow<IslandPlacement> = _placement.asStateFlow()

    /** True while you're dragging the island into place. */
    private val _editing = MutableStateFlow(false)
    val editing: StateFlow<Boolean> = _editing.asStateFlow()

    private var loaded = false

    fun load(context: Context): IslandPlacement {
        if (!loaded) {
            val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            _placement.value = IslandPlacement(
                enabled = p.getBoolean("enabled", true),
                onCamera = p.getBoolean("onCamera", true),
                centerX = p.getFloat("cx", -1f),
                top = p.getFloat("top", -1f),
                scale = p.getFloat("scale", 1f),
                showIdle = p.getBoolean("showIdle", true),
            )
            loaded = true
        }
        return _placement.value
    }

    fun update(context: Context, transform: (IslandPlacement) -> IslandPlacement) {
        load(context)
        val v = transform(_placement.value).let { it.copy(scale = it.scale.coerceIn(0.8f, 1.3f)) }
        _placement.value = v
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", v.enabled)
            .putBoolean("onCamera", v.onCamera)
            .putFloat("cx", v.centerX)
            .putFloat("top", v.top)
            .putFloat("scale", v.scale)
            .putBoolean("showIdle", v.showIdle)
            .apply()
    }

    fun setEditing(on: Boolean) {
        _editing.value = on
    }
}

/** Remembers the last crash in the island so Settings can show it (and you can send it to me). */
object IslandDiagnostics {
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            record(app, e)
            previous?.uncaughtException(t, e)
        }
    }

    fun record(context: Context, e: Throwable) {
        val trace = android.util.Log.getStackTraceString(e).take(2500)
        val stamp = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()).format(java.util.Date())
        context.getSharedPreferences("island", Context.MODE_PRIVATE).edit()
            .putString("lastError", "$stamp\n$trace")
            .commit()
    }

    fun lastError(context: Context): String? =
        context.getSharedPreferences("island", Context.MODE_PRIVATE).getString("lastError", null)

    fun clear(context: Context) =
        context.getSharedPreferences("island", Context.MODE_PRIVATE).edit().remove("lastError").apply()
}
