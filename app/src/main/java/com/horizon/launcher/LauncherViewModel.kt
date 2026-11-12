package com.horizon.launcher

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle
import android.os.UserManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppEntry(
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val icon: ImageBitmap,
) {
    val packageName: String get() = component.packageName
    val key: String get() = component.flattenToString() + "#" + user.hashCode()
}

class LauncherViewModel(app: Application) : AndroidViewModel(app) {

    private val launcherApps = app.getSystemService(LauncherApps::class.java)
    private val userManager = app.getSystemService(UserManager::class.java)
    private val prefs = app.getSharedPreferences("horizon", Context.MODE_PRIVATE)

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private val _dock = MutableStateFlow(loadDock())
    val dock: StateFlow<List<String>> = _dock.asStateFlow()

    private val _drawerOpen = MutableStateFlow(false)
    val drawerOpen: StateFlow<Boolean> = _drawerOpen.asStateFlow()

    private val _searchFocus = MutableStateFlow(false)
    val searchFocus: StateFlow<Boolean> = _searchFocus.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { loadApps() }.getOrDefault(emptyList()) }
            _apps.value = list
        }
    }

    private fun loadApps(): List<AppEntry> {
        val ctx = getApplication<Application>()
        val px = (64 * ctx.resources.displayMetrics.density).toInt()
        return userManager.userProfiles
            .flatMap { user -> launcherApps.getActivityList(null, user) }
            .filter { it.componentName.packageName != ctx.packageName }
            .map { info ->
                AppEntry(
                    label = info.label.toString(),
                    component = info.componentName,
                    user = info.user,
                    icon = info.getBadgedIcon(0).toBitmap(px, px).asImageBitmap(),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    fun openDrawer(focusSearch: Boolean = false) {
        _searchFocus.value = focusSearch
        _drawerOpen.value = true
    }

    fun closeDrawer() {
        _drawerOpen.value = false
        _searchFocus.value = false
    }

    fun launch(app: AppEntry) {
        runCatching { launcherApps.startMainActivity(app.component, app.user, null, null) }
    }

    fun openAppInfo(app: AppEntry) {
        runCatching { launcherApps.startAppDetailsActivity(app.component, app.user, null, null) }
    }

    fun isInDock(app: AppEntry) = app.packageName in _dock.value

    fun toggleDock(app: AppEntry) {
        val current = _dock.value
        val next = if (app.packageName in current) {
            current - app.packageName
        } else {
            (current + app.packageName).take(MAX_DOCK)
        }
        _dock.value = next
        prefs.edit().putString(KEY_DOCK, next.joinToString(",")).apply()
    }

    private fun loadDock(): List<String> {
        val saved = prefs.getString(KEY_DOCK, null) ?: return DEFAULT_DOCK
        return saved.split(",").filter { it.isNotBlank() }
    }

    companion object {
        private const val KEY_DOCK = "dock"
        const val MAX_DOCK = 5
        private val DEFAULT_DOCK = listOf(
            "com.google.android.dialer",
            "com.google.android.apps.messaging",
            "com.android.chrome",
            "com.google.android.GoogleCamera",
        )
    }
}
