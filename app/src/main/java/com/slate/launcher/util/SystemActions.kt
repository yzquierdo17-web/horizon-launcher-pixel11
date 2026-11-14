package com.slate.launcher.util

import android.annotation.SuppressLint
import android.app.SearchManager
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

object SystemActions {

    /** Opens the notification shade. Uses the long-standing StatusBarManager hook other launchers rely on. */
    @SuppressLint("WrongConstant", "PrivateApi")
    fun expandNotifications(context: Context) {
        runCatching {
            val service = context.getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager")
                .getMethod("expandNotificationsPanel")
                .invoke(service)
        }
    }

    fun openWallpaperPicker(context: Context) = start(context, Intent(Intent.ACTION_SET_WALLPAPER))

    fun openAlarms(context: Context) = start(context, Intent(AlarmClock.ACTION_SHOW_ALARMS))

    fun openCalendar(context: Context) {
        val uri = CalendarContract.CONTENT_URI.buildUpon()
            .appendPath("time")
            .appendPath(System.currentTimeMillis().toString())
            .build()
        start(context, Intent(Intent.ACTION_VIEW, uri))
    }

    fun webSearch(context: Context, query: String) =
        start(context, Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query))

    fun playStoreSearch(context: Context, query: String) =
        start(context, Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(query))))

    fun isDefaultLauncher(context: Context): Boolean =
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true

    fun defaultLauncherIntent(context: Context): Intent =
        context.getSystemService(RoleManager::class.java)
            ?.takeIf { it.isRoleAvailable(RoleManager.ROLE_HOME) }
            ?.createRequestRoleIntent(RoleManager.ROLE_HOME)
            ?: Intent(Settings.ACTION_HOME_SETTINGS)

    private fun start(context: Context, intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(context, "No app available for that", Toast.LENGTH_SHORT).show() }
    }
}

/**
 * True when the wallpaper is light enough that Home text/status icons should be dark.
 * Same signal the Pixel Launcher uses (WallpaperColors.HINT_SUPPORTS_DARK_TEXT).
 */
@Composable
fun rememberWallpaperSupportsDarkText(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { WallpaperManager.getInstance(context) }
    var darkText by remember { mutableStateOf(manager.supportsDarkText()) }
    DisposableEffect(manager) {
        val listener = WallpaperManager.OnColorsChangedListener { colors, which ->
            if ((which and WallpaperManager.FLAG_SYSTEM) != 0) darkText = colors?.supportsDarkText() == true
        }
        manager.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
        onDispose { manager.removeOnColorsChangedListener(listener) }
    }
    return darkText
}

private fun WallpaperManager.supportsDarkText(): Boolean =
    runCatching { getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.supportsDarkText() == true }
        .getOrDefault(false)

private fun WallpaperColors.supportsDarkText(): Boolean =
    (colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
