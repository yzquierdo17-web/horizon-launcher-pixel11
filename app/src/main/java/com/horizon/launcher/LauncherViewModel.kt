package com.horizon.launcher

import android.app.Application
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.widget.Toast
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import kotlin.math.ceil

data class AppEntry(
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val serial: Long,
    val icon: ImageBitmap,
    val isWork: Boolean = false,
    /** Work profile is paused: shown greyed out; tapping offers to turn work apps on. */
    val isPaused: Boolean = false,
) {
    val packageName: String get() = component.packageName
    val key: String get() = component.flattenToString() + "#" + serial
}

class LauncherViewModel(app: Application) : AndroidViewModel(app) {

    private val launcherApps = app.getSystemService(LauncherApps::class.java)
    private val userManager = app.getSystemService(UserManager::class.java)
    private val prefs = app.getSharedPreferences("horizon", Context.MODE_PRIVATE)

    val widgetHost = AppWidgetHost(app, WIDGET_HOST_ID)
    val widgetManager: AppWidgetManager = AppWidgetManager.getInstance(app)

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private val _appIndex = MutableStateFlow<Map<String, AppEntry>>(emptyMap())
    val appIndex: StateFlow<Map<String, AppEntry>> = _appIndex.asStateFlow()

    private val _items = MutableStateFlow(decodeItems(prefs.getString(KEY_ITEMS, null)))
    val items: StateFlow<List<HomeItem>> = _items.asStateFlow()

    private val _dock = MutableStateFlow(decodeKeys(prefs.getString(KEY_DOCK, null)))
    val dock: StateFlow<List<String>> = _dock.asStateFlow()

    private val _settings = MutableStateFlow(
        LauncherSettings.fromJson(runCatching { JSONObject(prefs.getString(KEY_SETTINGS, "{}") ?: "{}") }.getOrNull()),
    )
    val settings: StateFlow<LauncherSettings> = _settings.asStateFlow()

    private val _settingsOpen = MutableStateFlow(false)
    val settingsOpen: StateFlow<Boolean> = _settingsOpen.asStateFlow()

    private val _drawerOpen = MutableStateFlow(false)
    val drawerOpen: StateFlow<Boolean> = _drawerOpen.asStateFlow()

    private val _searchFocus = MutableStateFlow(false)
    val searchFocus: StateFlow<Boolean> = _searchFocus.asStateFlow()

    private val _openFolder = MutableStateFlow<Long?>(null)
    val openFolder: StateFlow<Long?> = _openFolder.asStateFlow()

    private val _widgetPicker = MutableStateFlow(false)
    val widgetPicker: StateFlow<Boolean> = _widgetPicker.asStateFlow()

    /** Bumped when Home is pressed while already on the home screen. */
    private val _homeSignal = MutableStateFlow(0)
    val homeSignal: StateFlow<Int> = _homeSignal.asStateFlow()

    // ---------- Work profile, work hours, meetings, suggestions ----------

    private val _workProfile = MutableStateFlow<WorkProfile?>(null)
    val workProfile: StateFlow<WorkProfile?> = _workProfile.asStateFlow()

    /** Ticks every 30 s so work hours and the meeting line stay current. */
    private val now = flow {
        while (true) {
            emit(LocalDateTime.now())
            delay(30_000)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LocalDateTime.now())

    val workTime: StateFlow<Boolean> = combine(settings, now) { s, t -> s.isWorkTime(t) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val calendar = CalendarRepository(app)
    private val meetingRefresh = MutableStateFlow(0)
    val meeting: StateFlow<Meeting?> =
        combine(now, calendar.changes(), meetingRefresh, settings.map { it.showMeetings }.distinctUntilChanged()) { _, _, _, show ->
            if (show) runCatching { calendar.nextMeeting() }.getOrNull() else null
        }.flowOn(Dispatchers.IO)
            .catch { emit(null) } // a calendar problem must never take the launcher down
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _usage = MutableStateFlow(decodeUsage(prefs.getString(KEY_USAGE, null)))
    /** Launch counts per app, used for the suggestions row in the drawer. Stays on the phone. */
    val usage: StateFlow<Map<String, Int>> = _usage.asStateFlow()

    /** Work profile turned on/off, added or removed. */
    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = refresh()
    }

    /** Last work-hours state we acted on; null until first seen (we don't act on launch). */
    private var lastWorkTime: Boolean? = null

    /** Measured by the home grid; used to size new widgets. */
    var cellWidthDp = 80f
    var cellHeightDp = 110f
    var currentPage = 0

    init {
        refresh()
        ContextCompat.registerReceiver(
            app,
            profileReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
                addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
                addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
                addAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        // Auto pause / resume work apps at the edges of work hours.
        viewModelScope.launch {
            workTime.collect { applyWorkHours(it) }
        }
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().unregisterReceiver(profileReceiver) }
    }

    private fun applyWorkHours(isWork: Boolean) {
        val previous = lastWorkTime
        lastWorkTime = isWork
        if (previous == null || previous == isWork) return
        val s = _settings.value
        if (!s.workHours || !s.pauseWorkAfterHours || _workProfile.value == null) return
        if (!setWorkPaused(paused = !isWork, quiet = true)) lastWorkTime = previous // retry on next resume
    }

    /** Home came to the front: the moment Android lets a launcher pause or resume work apps. */
    fun onResume() {
        refresh()
        val prev = lastWorkTime
        val cur = workTime.value
        if (prev != null && prev != cur) {
            lastWorkTime = !cur
            applyWorkHours(cur)
        }
        meetingRefresh.value++
    }

    /**
     * Pause or resume all work apps. Android only allows this for the default Home app.
     * Returns false if the system refused.
     */
    fun setWorkPaused(paused: Boolean, quiet: Boolean = false): Boolean {
        val work = _workProfile.value ?: return false
        val ok = runCatching { userManager.requestQuietModeEnabled(paused, work.user) }
            .getOrElse {
                if (!quiet) toast("Make Horizon your default home app to pause work apps")
                false
            }
        refresh()
        return ok
    }

    fun refreshMeetings() { meetingRefresh.value++ }

    // ---------- Apps ----------

    fun refresh() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { loadApps() }.getOrNull() } ?: return@launch
            _workProfile.value = withContext(Dispatchers.IO) {
                runCatching {
                    val me = Process.myUserHandle()
                    userManager.userProfiles.firstOrNull { it != me }?.let { WorkProfile(it, userManager.isQuietModeEnabled(it)) }
                }.getOrNull()
            }
            _apps.value = list
            _appIndex.value = list.associateBy { it.key }
            if (list.isNotEmpty()) {
                ensureDefaultDock(list)
                cleanup(list)
            }
        }
    }

    private fun loadApps(): List<AppEntry> {
        val ctx = getApplication<Application>()
        val px = (72 * ctx.resources.displayMetrics.density).toInt()
        val themed = _settings.value.themedIcons
        val me = Process.myUserHandle()
        val quiet = userManager.userProfiles.filter { it != me && userManager.isQuietModeEnabled(it) }.toSet()
        return userManager.userProfiles
            .flatMap { user -> launcherApps.getActivityList(null, user) }
            .filter { it.componentName.packageName != ctx.packageName }
            .map { info ->
                val themedIcon = if (themed && info.user == me) {
                    runCatching { themedBitmap(ctx, info.getIcon(0), px) }.getOrNull()
                } else {
                    null
                }
                AppEntry(
                    label = info.label.toString(),
                    component = info.componentName,
                    user = info.user,
                    serial = userManager.getSerialNumberForUser(info.user),
                    icon = (themedIcon ?: info.getBadgedIcon(0).toBitmap(px, px)).asImageBitmap(),
                    isWork = info.user != me,
                    isPaused = info.user in quiet,
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /** Pixel-style themed icon: the app's monochrome layer tinted with wallpaper colors. */
    private fun themedBitmap(ctx: Context, drawable: Drawable, px: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val mono = (drawable as? AdaptiveIconDrawable)?.monochrome ?: return null
        val bg = ctx.getColor(android.R.color.system_accent1_800)
        val fg = ctx.getColor(android.R.color.system_accent1_100)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val circle = Path().apply { addCircle(px / 2f, px / 2f, px / 2f, Path.Direction.CW) }
        canvas.drawPath(circle, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
        canvas.save()
        canvas.clipPath(circle)
        val layer = mono.mutate()
        layer.setTint(fg)
        // The monochrome layer is 108dp with a 72dp visible area, so draw it 1.5x and centered.
        val inset = (px * 0.25f).toInt()
        layer.setBounds(-inset, -inset, px + inset, px + inset)
        layer.draw(canvas)
        canvas.restore()
        return bmp
    }

    private fun ensureDefaultDock(list: List<AppEntry>) {
        if (prefs.contains(KEY_DOCK)) return
        val mine = userManager.getSerialNumberForUser(Process.myUserHandle())
        val keys = DEFAULT_DOCK.mapNotNull { pkg ->
            list.firstOrNull { it.packageName == pkg && it.serial == mine }?.key
        }
        setDock(keys)
    }

    /** Drop home and dock entries for apps that were uninstalled. */
    private fun cleanup(list: List<AppEntry>) {
        val keys = list.mapTo(HashSet()) { it.key }
        val dock = _dock.value.filter { it in keys }
        if (dock != _dock.value) setDock(dock)

        val items = _items.value.mapNotNull { item ->
            when (item.type) {
                TYPE_APP -> item.takeIf { it.apps.firstOrNull() in keys }
                TYPE_FOLDER -> {
                    val rest = item.apps.filter { it in keys }
                    when (rest.size) {
                        0 -> null
                        1 -> item.copy(type = TYPE_APP, apps = rest, name = "")
                        else -> item.copy(apps = rest)
                    }
                }
                else -> item
            }
        }
        if (items != _items.value) setItems(items)
    }

    fun launch(app: AppEntry, sourceBounds: Rect? = null, options: android.os.Bundle? = null) {
        if (app.isPaused) {
            // Same as Pixel: tapping a paused work app offers to turn work apps back on.
            setWorkPaused(false)
            return
        }
        recordLaunch(app.key)
        runCatching { launcherApps.startMainActivity(app.component, app.user, sourceBounds, options) }
            .onFailure { runCatching { launcherApps.startMainActivity(app.component, app.user, null, null) } }
    }

    private fun recordLaunch(key: String) {
        val counts = _usage.value.toMutableMap()
        counts[key] = (counts[key] ?: 0) + 1
        val trimmed = counts.entries.sortedByDescending { it.value }.take(200).associate { it.key to it.value }
        _usage.value = trimmed
        prefs.edit().putString(KEY_USAGE, JSONObject(trimmed as Map<*, *>).toString()).apply()
    }

    fun clearSuggestions() {
        _usage.value = emptyMap()
        prefs.edit().remove(KEY_USAGE).apply()
    }

    private fun decodeUsage(json: String?): Map<String, Int> = runCatching {
        val o = JSONObject(json ?: return emptyMap())
        o.keys().asSequence().associateWith { o.getInt(it) }
    }.getOrDefault(emptyMap())

    // ---------- Settings ----------

    fun openSettings(open: Boolean) {
        _settingsOpen.value = open
    }

    fun updateSettings(transform: (LauncherSettings) -> LauncherSettings) {
        val old = _settings.value
        val new = transform(old)
        if (new == old) return
        _settings.value = new
        prefs.edit().putString(KEY_SETTINGS, new.toJson().toString()).apply()
        if (old.themedIcons != new.themedIcons) refresh()
    }

    fun exportLayout(): String = JSONObject()
        .put("app", "Horizon")
        .put("version", 1)
        .put("items", JSONArray(encodeItems(_items.value)))
        .put("dock", JSONArray(_dock.value))
        .put("settings", _settings.value.toJson())
        .toString(2)

    fun importLayout(text: String): Boolean = runCatching {
        val o = JSONObject(text)
        val items = decodeItems(o.getJSONArray("items").toString())
            .filter { it.type != TYPE_WIDGET || widgetManager.getAppWidgetInfo(it.widgetId) != null }
        val dock = decodeKeys(o.getJSONArray("dock").toString())
        setItems(items)
        setDock(dock)
        o.optJSONObject("settings")?.let { js -> updateSettings { LauncherSettings.fromJson(js) } }
        val list = _apps.value
        if (list.isNotEmpty()) cleanup(list)
        true
    }.getOrDefault(false)

    fun openAppInfo(app: AppEntry) {
        runCatching { launcherApps.startAppDetailsActivity(app.component, app.user, null, null) }
    }

    // ---------- Overlays ----------

    fun openDrawer(focusSearch: Boolean = false) {
        _searchFocus.value = focusSearch
        _drawerOpen.value = true
    }

    fun closeDrawer() {
        _drawerOpen.value = false
        _searchFocus.value = false
    }

    fun openFolder(id: Long) {
        _openFolder.value = id
    }

    fun closeFolder() {
        _openFolder.value = null
    }

    fun showWidgetPicker(show: Boolean) {
        _widgetPicker.value = show
    }

    fun onHomePressed(alreadyOnTop: Boolean) {
        val wasOverlay = _drawerOpen.value || _openFolder.value != null || _widgetPicker.value || _settingsOpen.value
        closeDrawer()
        closeFolder()
        showWidgetPicker(false)
        openSettings(false)
        if (alreadyOnTop && !wasOverlay) _homeSignal.value += 1
    }

    // ---------- Persistence ----------

    private fun setItems(new: List<HomeItem>) {
        val compact = compactPages(new)
        _items.value = compact
        prefs.edit().putString(KEY_ITEMS, encodeItems(compact)).apply()
        val open = _openFolder.value
        if (open != null && compact.none { it.id == open && it.type == TYPE_FOLDER }) closeFolder()
    }

    private fun setDock(new: List<String>) {
        _dock.value = new
        prefs.edit().putString(KEY_DOCK, encodeKeys(new)).apply()
    }

    private fun nextId(items: List<HomeItem>) = (items.maxOfOrNull { it.id } ?: 0L) + 1L

    private fun toast(text: String) {
        Toast.makeText(getApplication(), text, Toast.LENGTH_SHORT).show()
    }

    // ---------- Home edits ----------

    fun addToHome(app: AppEntry) {
        val items = _items.value
        if (items.any { it.type == TYPE_APP && it.apps.firstOrNull() == app.key }) {
            toast("${app.label} is already on your home screen")
            return
        }
        val (page, x, y) = findFreeAnywhere(items, 0, 1, 1)
        setItems(items + HomeItem(nextId(items), TYPE_APP, page, x, y, apps = listOf(app.key)))
        toast("Added to home screen")
    }

    fun isInDock(key: String) = key in _dock.value

    fun toggleDock(key: String) {
        val dock = _dock.value
        when {
            key in dock -> setDock(dock - key)
            dock.size >= MAX_DOCK -> toast("The dock is full")
            else -> setDock(dock + key)
        }
    }

    fun removeItem(id: Long) = drop(DragPayload.FromHome(id), DropTarget.Remove)

    fun removeFromDock(index: Int) = drop(DragPayload.FromDock(index), DropTarget.Remove)

    fun removeFromFolder(folderId: Long, index: Int) {
        val items = _items.value
        val folder = items.firstOrNull { it.id == folderId } ?: return
        val key = folder.apps.getOrNull(index) ?: return
        val (without, dock) = withoutSource(DragPayload.FromFolder(folderId, index), items, _dock.value)
        val (page, x, y) = findFree(without, folder.page, 1, 1, folder.x, folder.y)
            ?.let { Triple(folder.page, it.first, it.second) }
            ?: findFreeAnywhere(without, folder.page, 1, 1)
        setItems(without + HomeItem(nextId(without), TYPE_APP, page, x, y, apps = listOf(key)))
        setDock(dock)
    }

    fun renameFolder(id: Long, name: String) {
        setItems(_items.value.map { if (it.id == id) it.copy(name = name) else it })
    }

    fun resizeWidget(id: Long, sx: Int, sy: Int) {
        val items = _items.value
        val item = items.firstOrNull { it.id == id } ?: return
        val x = item.x.coerceAtMost(GRID_COLS - sx)
        val y = item.y.coerceAtMost(GRID_ROWS - sy)
        if (fits(items, item.page, x, y, sx, sy, id)) {
            setItems(items.map { if (it.id == id) it.copy(x = x, y = y, spanX = sx, spanY = sy) else it })
        } else {
            toast("Not enough room for that size")
        }
    }

    // ---------- Widgets ----------

    fun spansFor(info: AppWidgetProviderInfo): Pair<Int, Int> {
        val density = getApplication<Application>().resources.displayMetrics.density
        var sx = ceil(info.minWidth / density / cellWidthDp).toInt()
        var sy = ceil(info.minHeight / density / cellHeightDp).toInt()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.targetCellWidth > 0 && info.targetCellHeight > 0) {
            sx = info.targetCellWidth
            sy = info.targetCellHeight
        }
        return sx.coerceIn(1, GRID_COLS) to sy.coerceIn(1, GRID_ROWS)
    }

    fun placeWidget(widgetId: Int, info: AppWidgetProviderInfo) {
        val items = _items.value
        val (sx, sy) = spansFor(info)
        val (page, x, y) = findFreeAnywhere(items, currentPage, sx, sy)
        setItems(items + HomeItem(nextId(items), TYPE_WIDGET, page, x, y, sx, sy, widgetId = widgetId))
        if (page != currentPage) toast("Widget added to page ${page + 1}")
    }

    // ---------- Drag and drop ----------

    private fun withoutSource(
        p: DragPayload,
        items: List<HomeItem>,
        dock: List<String>,
    ): Pair<List<HomeItem>, List<String>> = when (p) {
        is DragPayload.FromDrawer -> items to dock
        is DragPayload.FromDock -> items to dock.filterIndexed { i, _ -> i != p.index }
        is DragPayload.FromHome -> items.filter { it.id != p.itemId } to dock
        is DragPayload.FromFolder -> items.mapNotNull { f ->
            if (f.id != p.folderId) {
                f
            } else {
                val rest = f.apps.filterIndexed { i, _ -> i != p.index }
                when (rest.size) {
                    0 -> null
                    1 -> f.copy(type = TYPE_APP, apps = rest, name = "")
                    else -> f.copy(apps = rest)
                }
            }
        } to dock
    }

    fun drop(payload: DragPayload, target: DropTarget) {
        val items = _items.value
        val dock = _dock.value
        val dragged = (payload as? DragPayload.FromHome)?.let { p -> items.firstOrNull { it.id == p.itemId } }
        val appKey: String? = when (payload) {
            is DragPayload.FromDrawer -> payload.key
            is DragPayload.FromDock -> dock.getOrNull(payload.index)
            is DragPayload.FromFolder -> items.firstOrNull { it.id == payload.folderId }?.apps?.getOrNull(payload.index)
            is DragPayload.FromHome -> dragged?.takeIf { it.type == TYPE_APP }?.apps?.firstOrNull()
        }

        when (target) {
            DropTarget.None -> Unit

            DropTarget.Remove -> {
                if (payload is DragPayload.FromDrawer) return
                val (ni, nd) = withoutSource(payload, items, dock)
                if (dragged?.type == TYPE_WIDGET) runCatching { widgetHost.deleteAppWidgetId(dragged.widgetId) }
                setItems(ni)
                setDock(nd)
            }

            is DropTarget.Dock -> {
                val key = appKey ?: run {
                    toast("Only apps can go in the dock")
                    return
                }
                if (payload is DragPayload.FromDock) {
                    val m = dock.toMutableList()
                    val moved = m.removeAt(payload.index)
                    val idx = (if (target.index > payload.index) target.index - 1 else target.index).coerceIn(0, m.size)
                    m.add(idx, moved)
                    setDock(m)
                    return
                }
                val (ni, nd0) = withoutSource(payload, items, dock)
                val nd = nd0.filter { it != key }.toMutableList()
                if (nd.size >= MAX_DOCK) {
                    toast("The dock is full")
                    return
                }
                nd.add(target.index.coerceIn(0, nd.size), key)
                setItems(ni)
                setDock(nd)
            }

            is DropTarget.Home -> {
                val under = items.firstOrNull {
                    it.id != dragged?.id && it.page == target.page && it.covers(target.x, target.y)
                }

                // Dropping an app onto an app or folder makes or grows a folder.
                if (appKey != null && under != null && (under.type == TYPE_APP || under.type == TYPE_FOLDER)) {
                    if (payload is DragPayload.FromFolder && under.id == payload.folderId) return
                    val (ni, nd) = withoutSource(payload, items, dock)
                    val tgt = ni.firstOrNull { it.id == under.id } ?: return
                    val merged = when {
                        tgt.type == TYPE_FOLDER -> tgt.copy(apps = (tgt.apps - appKey) + appKey)
                        tgt.apps.firstOrNull() == appKey -> tgt
                        else -> tgt.copy(type = TYPE_FOLDER, apps = tgt.apps + appKey, name = "Folder")
                    }
                    setItems(ni.map { if (it.id == merged.id) merged else it })
                    setDock(nd)
                    return
                }

                val sx = dragged?.spanX ?: 1
                val sy = dragged?.spanY ?: 1
                val (ni, nd) = withoutSource(payload, items, dock)
                val candidate: Pair<Int, Int>? =
                    if (fits(ni, target.page, target.x, target.y, sx, sy)) {
                        target.x to target.y
                    } else {
                        findFree(ni, target.page, sx, sy, target.x, target.y)
                    }
                if (candidate == null) {
                    toast("No room on this page")
                    return
                }
                val spot: Pair<Int, Int> = candidate
                val newItem = dragged?.copy(page = target.page, x = spot.first, y = spot.second)
                    ?: HomeItem(
                        nextId(ni), TYPE_APP, target.page, spot.first, spot.second,
                        apps = listOf(appKey ?: return),
                    )
                setItems(ni + newItem)
                setDock(nd)
            }
        }
    }

    companion object {
        const val WIDGET_HOST_ID = 1024
        private const val KEY_ITEMS = "home_items"
        private const val KEY_DOCK = "dock_keys"
        private const val KEY_SETTINGS = "settings"
        private const val KEY_USAGE = "usage"
        private val DEFAULT_DOCK = listOf(
            "com.google.android.dialer",
            "com.google.android.apps.messaging",
            "com.android.chrome",
            "com.google.android.GoogleCamera",
        )
    }
}
