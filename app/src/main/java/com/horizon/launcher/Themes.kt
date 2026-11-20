package com.horizon.launcher

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import org.json.JSONObject

/**
 * Horizon's looks. Each sets the accent colours (buttons, pills, highlights), the base colour
 * behind the drawer, folders and settings, and the lock-screen dial's background.
 */
enum class HorizonLook(
    val id: String,
    val label: String,
    /** Base colour for the drawer, folders, settings and full-screen pages. */
    val surface: Color,
    /** Lock-screen dial background. */
    val dial: Int,
) {
    Wallpaper("wallpaper", "Wallpaper", Color(0xFF0D1016), 0xFF232427.toInt()),
    Graphite("graphite", "Graphite", Color(0xFF17191C), 0xFF1D1F22.toInt()),
    Midnight("midnight", "Midnight", Color(0xFF0A1022), 0xFF0B1427.toInt()),
    Amoled("amoled", "AMOLED", Color(0xFF000000), 0xFF000000.toInt());

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: Wallpaper

        /** Read straight from Horizon's saved settings (used by the lock-screen dial). */
        fun saved(context: Context): HorizonLook = runCatching {
            val json = context.getSharedPreferences("horizon", Context.MODE_PRIVATE).getString("settings", null)
            of(LauncherSettings.fromJson(JSONObject(json ?: "{}")).theme)
        }.getOrDefault(Wallpaper)
    }
}

/** Base surface for full-screen pages and panels in the current look. */
val LocalThemeSurface = staticCompositionLocalOf { HorizonLook.Wallpaper.surface }

/** Colours for a look. Wallpaper and AMOLED keep your wallpaper's accent (Material You). */
fun colorSchemeFor(look: HorizonLook, context: Context, fallback: ColorScheme): ColorScheme {
    val dynamic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicDarkColorScheme(context) else fallback
    return when (look) {
        HorizonLook.Wallpaper -> dynamic
        HorizonLook.Amoled -> dynamic.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceVariant = Color(0xFF121212),
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF080808),
            surfaceContainer = Color(0xFF0E0E0E),
            surfaceContainerHigh = Color(0xFF161616),
            surfaceContainerHighest = Color(0xFF1E1E1E),
        )
        HorizonLook.Graphite -> darkColorScheme(
            primary = Color(0xFFCDD1D7),
            onPrimary = Color(0xFF1B1D20),
            primaryContainer = Color(0xFF3C4046),
            onPrimaryContainer = Color(0xFFE6E8EC),
            secondary = Color(0xFFB4B8BE),
            onSecondary = Color(0xFF1E2023),
            secondaryContainer = Color(0xFF34373C),
            onSecondaryContainer = Color(0xFFE1E3E7),
            tertiary = Color(0xFFD6C9B8),
            background = Color(0xFF17191C),
            surface = Color(0xFF17191C),
            surfaceVariant = Color(0xFF2B2E33),
            onSurfaceVariant = Color(0xFFC3C6CC),
            outline = Color(0xFF8C9097),
            surfaceContainer = Color(0xFF202327),
            surfaceContainerHigh = Color(0xFF292C30),
            surfaceContainerHighest = Color(0xFF33363B),
        )
        HorizonLook.Midnight -> darkColorScheme(
            primary = Color(0xFFA4C6FF),
            onPrimary = Color(0xFF0A1F3D),
            primaryContainer = Color(0xFF1F3D6B),
            onPrimaryContainer = Color(0xFFD8E6FF),
            secondary = Color(0xFFB8C6E6),
            onSecondary = Color(0xFF14213A),
            secondaryContainer = Color(0xFF283756),
            onSecondaryContainer = Color(0xFFDCE6FA),
            tertiary = Color(0xFFD4BBFF),
            background = Color(0xFF0A1022),
            surface = Color(0xFF0A1022),
            surfaceVariant = Color(0xFF1F2A44),
            onSurfaceVariant = Color(0xFFC0C9DD),
            outline = Color(0xFF8590A8),
            surfaceContainer = Color(0xFF111A30),
            surfaceContainerHigh = Color(0xFF18223B),
            surfaceContainerHighest = Color(0xFF202B46),
        )
    }
}
