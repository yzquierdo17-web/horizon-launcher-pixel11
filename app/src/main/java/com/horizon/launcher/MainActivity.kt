package com.horizon.launcher

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {

    private val vm: LauncherViewModel by viewModels()
    private var pendingWidgetId = -1
    private var lastBlur = -1

    private val backupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        val ok = runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(vm.exportLayout().toByteArray()) } != null
        }.getOrDefault(false)
        toast(if (ok) "Layout backed up" else "Couldn't save the backup")
    }

    private val restoreLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val text = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        val ok = text != null && vm.importLayout(text)
        toast(if (ok) "Layout restored" else "That file isn't a Horizon backup")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingWidgetId = savedInstanceState?.getInt(KEY_PENDING, -1) ?: -1
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            HorizonTheme {
                LauncherScreen(
                    vm = vm,
                    onAddWidget = ::addWidget,
                    onBlur = ::setWallpaperBlur,
                    onBackup = { backupLauncher.launch("horizon-layout.json") },
                    onRestore = { restoreLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_PENDING, pendingWidgetId)
    }

    override fun onStart() {
        super.onStart()
        runCatching { vm.widgetHost.startListening() }
    }

    override fun onStop() {
        super.onStop()
        runCatching { vm.widgetHost.stopListening() }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume() // refreshes apps, applies work hours, refreshes the meeting line
    }

    // Pressing Home while Horizon is showing closes overlays, then goes to the first page.
    override fun onNewIntent(intent: Intent) {
        val alreadyOnTop = hasWindowFocus()
        super.onNewIntent(intent)
        vm.onHomePressed(alreadyOnTop)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    /** Blurs the wallpaper behind Horizon (Android 12+ when the device allows cross-window blur). */
    private fun setWallpaperBlur(fraction: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            if (!windowManager.isCrossWindowBlurEnabled) return
            val radius = (fraction.coerceIn(0f, 1f) * 40f * resources.displayMetrics.density).toInt()
            if (radius == lastBlur) return
            lastBlur = radius
            if (radius > 0) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            }
            window.attributes = window.attributes.apply { blurBehindRadius = radius }
        }
    }

    // ---------- Adding widgets ----------

    private fun addWidget(info: AppWidgetProviderInfo) {
        val id = vm.widgetHost.allocateAppWidgetId()
        pendingWidgetId = id
        val bound = runCatching {
            vm.widgetManager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)
        }.getOrDefault(false)
        if (bound) {
            configureOrPlace(id)
        } else {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_BIND)
        }
    }

    private fun configureOrPlace(id: Int) {
        val info = vm.widgetManager.getAppWidgetInfo(id)
        if (info == null) {
            discardPending(id)
            return
        }
        if (info.configure != null) {
            val started = runCatching {
                vm.widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIGURE, null)
            }.isSuccess
            if (started) return
        }
        vm.placeWidget(id, info)
        pendingWidgetId = -1
    }

    private fun discardPending(id: Int) {
        runCatching { vm.widgetHost.deleteAppWidgetId(id) }
        pendingWidgetId = -1
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId) ?: pendingWidgetId
        if (id == -1) return
        when (requestCode) {
            REQ_BIND -> if (resultCode == RESULT_OK) configureOrPlace(id) else discardPending(id)
            REQ_CONFIGURE -> if (resultCode == RESULT_OK) {
                val info = vm.widgetManager.getAppWidgetInfo(id)
                if (info != null) vm.placeWidget(id, info) else discardPending(id)
                pendingWidgetId = -1
            } else {
                discardPending(id)
            }
        }
    }

    companion object {
        private const val KEY_PENDING = "pending_widget"
        private const val REQ_BIND = 11
        private const val REQ_CONFIGURE = 12
    }
}

@Composable
fun HorizonTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        darkColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
